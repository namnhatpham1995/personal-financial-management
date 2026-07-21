package com.fintrack.vault.service;

import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.IdempotencyOperationInProgressException;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotencyKeyValidator;
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared claim/replay/compensation logic for the two Mongo/GridFS-backed multipart uploads
 * (receipt/statement vault upload, statement-import upload). See design.md Decision #4 and
 * {@code openspec/changes/harden-idempotent-mutations/specs/document-vault/spec.md}.
 *
 * <p>Unlike the PostgreSQL {@code IdempotentMutationExecutor}, there is no shared transaction
 * manager spanning the Mongo claim row, the GridFS binary, and the Mongo document save — so this
 * coordinator persists the claim first, tags the binary with the claim's id, and compensates
 * (deletes the binary) immediately if the document save fails. A stale-{@code PROCESSING} sweep
 * ({@code VaultOperationRecoveryScheduler}) recovers from a process death between those steps.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultUploadIdempotencyCoordinator {

    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    /** Mirrors {@code IdempotentMutationExecutor}'s bounded poll: ~150ms steps, 3s total. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);
    private static final Duration POLL_BOUND = Duration.ofSeconds(3);

    private final IdempotencyKeyValidator validator;
    private final IdempotencyHasher hasher;
    private final VaultOperationRepository operationRepository;
    private final GridFsFileStore gridFsFileStore;
    private final MongoTemplate mongoTemplate;
    private final AuditReplaySignal auditReplaySignal;
    private final VaultOperationMetrics metrics;

    /**
     * Guards one-time, on-first-use index creation (see {@link #ensureIndexesOnce()}). Deliberately
     * NOT done via {@code spring.data.mongodb.auto-index-creation} or eager {@code @PostConstruct}:
     * either would force a real Mongo connection during application-context startup for every
     * Spring context in this codebase, including the many unit/slice test contexts that never
     * configure Mongo at all (they leave it pointed at an unreachable default) — that previously
     * broke unrelated test suites with a context-startup timeout. Deferring index creation to the
     * first real upload call means it only ever runs where Mongo is actually being used.
     */
    private final AtomicBoolean indexesEnsured = new AtomicBoolean(false);

    /**
     * Claims (or replays) an upload operation and, on a fresh claim, runs {@code work} to store
     * the binary and save the document. See the scenarios in the document-vault spec for the
     * exact replay/conflict/in-progress contract.
     */
    public <T> VaultUploadOutcome<T> execute(Long userId,
                                              String operation,
                                              String rawIdempotencyKey,
                                              Map<String, String> nonFileParams,
                                              byte[] fileBytes,
                                              VaultUploadBinaryStore binaryStore,
                                              VaultUploadDocumentSave<T> documentSave,
                                              VaultUploadReplay<T> replay) throws IOException {
        ensureIndexesOnce();
        validator.validate(rawIdempotencyKey);
        String keyHash = hasher.hashKey(rawIdempotencyKey);
        String requestHash = hasher.hashMultipartRequest(operation, nonFileParams, fileBytes);

        Instant deadline = Instant.now().plus(POLL_BOUND);

        while (true) {
            ClaimAttempt attempt = tryClaim(userId, operation, keyHash, requestHash);
            if (attempt.claimed()) {
                metrics.claimed(operation);
                log.debug("Vault operation claim won: operation={}", operation);
                T response = runClaimedUpload(attempt.operationRow(), binaryStore, documentSave);
                // Original, freshly-claimed upload — attach a non-secret correlation reference
                // (the vault_operations id) for the resulting audit event.
                auditReplaySignal.setOperationReference(attempt.operationRow().getId());
                return new VaultUploadOutcome<>(response, false);
            }

            VaultOperation existing = attempt.existingRow();
            if (existing.getState() == VaultOperationState.COMPLETED) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    metrics.conflicted(operation);
                    log.warn("Vault operation payload conflict: operation={}", operation);
                    throw new IdempotencyConflictException(
                            "Idempotency-Key was already used to complete an upload with a different file or parameters");
                }
                // Same key, same file/params, already completed: pure replay, no second binary or
                // document was written — the interceptor must not record a second audit event.
                metrics.replayed(operation);
                log.debug("Vault operation replay: operation={}", operation);
                auditReplaySignal.markReplayed();
                return new VaultUploadOutcome<>(replay.buildReplayResponse(existing.getVaultDocumentId()), true);
            }

            // PROCESSING owned by another request, or FAILED that we just lost the reclaim race
            // for: wait/retry within the bounded window rather than tying up the thread forever.
            if (Instant.now().isAfter(deadline)) {
                metrics.inProgress(operation);
                log.warn("Vault operation in-progress (poll bound exceeded): operation={}", operation);
                throw new IdempotencyOperationInProgressException(
                        "Another request with this Idempotency-Key is still being processed; retry shortly",
                        Math.max(1, POLL_INTERVAL.toSeconds()));
            }
            sleepPollInterval();
        }
    }

    private <T> T runClaimedUpload(VaultOperation op, VaultUploadBinaryStore binaryStore,
                                    VaultUploadDocumentSave<T> documentSave) throws IOException {
        String gridFsFileId;
        try {
            gridFsFileId = binaryStore.storeBinary(op.getId());
        } catch (IOException | RuntimeException e) {
            markFailed(op, null);
            throw e;
        }

        // Persist the GridFS id immediately (before the document save) so a stale-operation sweep
        // can find and compensate the binary even if this process dies right after this line.
        op.setGridFsFileId(gridFsFileId);
        operationRepository.save(op);

        VaultUploadResult<T> result;
        try {
            result = documentSave.saveDocument(gridFsFileId);
        } catch (RuntimeException e) {
            log.warn("Vault document save failed after GridFS store (operation={}, gridFsFileId={}); "
                    + "compensating by deleting the orphaned binary", op.getOperation(), gridFsFileId);
            gridFsFileStore.delete(gridFsFileId);
            markFailed(op, gridFsFileId);
            throw e;
        }

        op.setVaultDocumentId(result.vaultDocumentId());
        op.setState(VaultOperationState.COMPLETED);
        op.setCompletedAt(Instant.now());
        operationRepository.save(op);
        return result.response();
    }

    private void markFailed(VaultOperation op, String gridFsFileId) {
        op.setGridFsFileId(gridFsFileId);
        op.setState(VaultOperationState.FAILED);
        operationRepository.save(op);
    }

    /**
     * Attempts to insert a new PROCESSING row. On a unique-index collision, either atomically
     * reclaims an existing FAILED row (CAS) or returns the current row for the caller's
     * replay/conflict/in-progress decision.
     */
    private ClaimAttempt tryClaim(Long userId, String operation, String keyHash, String requestHash) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(RESULT_RETENTION);
        VaultOperation candidate = VaultOperation.builder()
                .userId(userId)
                .operation(operation)
                .keyHash(keyHash)
                .requestHash(requestHash)
                .state(VaultOperationState.PROCESSING)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        try {
            return ClaimAttempt.claimed(operationRepository.insert(candidate));
        } catch (DuplicateKeyException e) {
            VaultOperation existing = fetchExisting(userId, operation, keyHash);
            if (existing.getState() == VaultOperationState.FAILED) {
                VaultOperation reclaimed = reclaimFailed(existing, requestHash, now, expiresAt);
                if (reclaimed != null) {
                    return ClaimAttempt.claimed(reclaimed);
                }
                existing = fetchExisting(userId, operation, keyHash);
            }
            return ClaimAttempt.existing(existing);
        }
    }

    private VaultOperation fetchExisting(Long userId, String operation, String keyHash) {
        return operationRepository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Vault operation for operation=" + operation + " vanished after a failed claim"));
    }

    /**
     * Atomic compare-and-set reclaim of a FAILED row back to PROCESSING, so a retry with the same
     * key can try again without a delete+reinsert race. Returns {@code null} if another request
     * won the reclaim first.
     */
    private VaultOperation reclaimFailed(VaultOperation existing, String requestHash, Instant now, Instant expiresAt) {
        Query query = Query.query(Criteria.where("_id").is(existing.getId())
                .and("state").is(VaultOperationState.FAILED));
        Update update = new Update()
                .set("state", VaultOperationState.PROCESSING)
                .set("requestHash", requestHash)
                .set("createdAt", now)
                .set("expiresAt", expiresAt)
                .set("gridFsFileId", null)
                .set("vaultDocumentId", null)
                .set("completedAt", null);
        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), VaultOperation.class);
    }

    /**
     * Creates the {@link VaultOperation} unique claim index and TTL index the first time this
     * coordinator is actually used, rather than relying on {@code spring.data.mongodb.auto-index-creation}
     * (see the field javadoc on {@link #indexesEnsured} for why). {@code ensureIndex} is a no-op
     * when an equivalent index already exists, so this is safe to race across concurrent callers
     * and safe to run once per process.
     */
    private void ensureIndexesOnce() {
        if (!indexesEnsured.compareAndSet(false, true)) {
            return;
        }
        var indexOps = mongoTemplate.indexOps(VaultOperation.class);
        indexOps.ensureIndex(new CompoundIndexDefinition(
                        new Document("userId", 1).append("operation", 1).append("keyHash", 1))
                .named("uq_vault_operations_user_operation_key")
                .unique());
        indexOps.ensureIndex(new Index()
                .on("expiresAt", Sort.Direction.ASC)
                .named("idx_vault_operations_expires_at")
                .expire(Duration.ZERO));
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent vault upload operation", e);
        }
    }

    private record ClaimAttempt(boolean claimed, VaultOperation operationRow, VaultOperation existingRow) {
        static ClaimAttempt claimed(VaultOperation op) {
            return new ClaimAttempt(true, op, null);
        }

        static ClaimAttempt existing(VaultOperation op) {
            return new ClaimAttempt(false, null, op);
        }
    }
}
