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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
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
     *
     * <p>Set to {@code true} only once index creation has actually succeeded — never before the
     * attempt, so a transient Mongo failure leaves this {@code false} and the next call retries,
     * instead of silently disabling the unique-claim index (and same-key protection with it) for
     * the remaining life of the process.
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

        VaultOperationClaimCoordinator coordinator = new VaultOperationClaimCoordinator(operationRepository, mongoTemplate);
        VaultOperationClaimCoordinator.ClaimOutcome outcome;
        try {
            outcome = coordinator.claim(userId, operation, keyHash, requestHash, POLL_BOUND,
                    () -> buildCandidate(userId, operation, keyHash, requestHash),
                    (existing, now) -> buildReclaimUpdate(requestHash, now),
                    "Idempotency-Key was already used to complete an upload with a different file or parameters",
                    "Another request with this Idempotency-Key is still being processed; retry shortly",
                    POLL_INTERVAL.toSeconds());
        } catch (IdempotencyConflictException e) {
            metrics.conflicted(operation);
            log.warn("Vault operation payload conflict: operation={}", operation);
            throw e;
        } catch (IdempotencyOperationInProgressException e) {
            metrics.inProgress(operation);
            log.warn("Vault operation in-progress (poll bound exceeded): operation={}", operation);
            throw e;
        }

        if (outcome.owned()) {
            metrics.claimed(operation);
            log.debug("Vault operation claim won: operation={}", operation);
            T response = runClaimedUpload(outcome.operation(), binaryStore, documentSave);
            // Original, freshly-claimed upload — attach a non-secret correlation reference
            // (the vault_operations id) for the resulting audit event.
            auditReplaySignal.setOperationReference(outcome.operation().getId());
            return new VaultUploadOutcome<>(response, false);
        }

        // Same key, same file/params, already completed: pure replay, no second binary or
        // document was written — the interceptor must not record a second audit event.
        VaultOperation existing = outcome.operation();
        metrics.replayed(operation);
        log.debug("Vault operation replay: operation={}", operation);
        auditReplaySignal.markReplayed();
        return new VaultUploadOutcome<>(replay.buildReplayResponse(existing.getVaultDocumentId()), true);
    }

    private VaultOperation buildCandidate(Long userId, String operation, String keyHash, String requestHash) {
        Instant now = Instant.now();
        return VaultOperation.builder()
                .userId(userId)
                .operation(operation)
                .keyHash(keyHash)
                .requestHash(requestHash)
                .state(VaultOperationState.PROCESSING)
                .createdAt(now)
                .expiresAt(now.plus(RESULT_RETENTION))
                .build();
    }

    private Update buildReclaimUpdate(String requestHash, Instant now) {
        return new Update()
                .set("state", VaultOperationState.PROCESSING)
                .set("requestHash", requestHash)
                .set("createdAt", now)
                .set("expiresAt", now.plus(RESULT_RETENTION))
                .set("gridFsFileId", null)
                .set("vaultDocumentId", null)
                .set("completedAt", null);
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
        try {
            var indexOps = mongoTemplate.indexOps(VaultOperation.class);
            indexOps.ensureIndex(new CompoundIndexDefinition(
                            new Document("userId", 1).append("operation", 1).append("keyHash", 1))
                    .named("uq_vault_operations_user_operation_key")
                    .unique());
            indexOps.ensureIndex(new Index()
                    .on("expiresAt", Sort.Direction.ASC)
                    .named("idx_vault_operations_expires_at")
                    .expire(Duration.ZERO));
        } catch (RuntimeException e) {
            // Creation failed (e.g. transient Mongo unavailability) — release the flag so the
            // next call retries instead of silently running unprotected for the rest of the
            // process's life. ensureIndex is a no-op when an equivalent index already exists, so
            // a partial success followed by a retry is safe.
            indexesEnsured.set(false);
            throw e;
        }
    }

}
