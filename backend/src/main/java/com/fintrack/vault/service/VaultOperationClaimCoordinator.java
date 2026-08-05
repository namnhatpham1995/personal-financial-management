package com.fintrack.vault.service;

import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.IdempotencyOperationInProgressException;
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Shared Mongo {@link VaultOperation} claim algorithm used by both vault upload and vault
 * reassignment idempotency. Owns: insert-or-conflict, atomic FAILED→PROCESSING reclaim (CAS),
 * COMPLETED replay-vs-conflict by payload hash, and PROCESSING poll-with-deadline. Operation-
 * specific work — what a fresh claim does, what a reclaim resets, what a completed replay
 * returns — stays with each caller and is passed in per call, the same way
 * {@code VaultUploadIdempotencyCoordinator} already parameterizes
 * {@code VaultUploadBinaryStore}/{@code VaultUploadDocumentSave}.
 *
 * <p>Deliberately not a Spring bean: {@code VaultUploadIdempotencyCoordinator} and
 * {@code VaultReassignmentService} are constructed directly (without Spring) in their unit tests
 * with an exact, positional constructor-argument list. Adding a new constructor-injected
 * collaborator to either class would break that construction. Each caller instead creates one of
 * these per call from its own already-existing {@code operationRepository}/{@code mongoTemplate}
 * fields — cheap, since this class holds no state beyond those two references.
 */
class VaultOperationClaimCoordinator {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);

    private final VaultOperationRepository operationRepository;
    private final MongoTemplate mongoTemplate;

    VaultOperationClaimCoordinator(VaultOperationRepository operationRepository, MongoTemplate mongoTemplate) {
        this.operationRepository = operationRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Outcome of {@link #claim}. {@code owned=true} means the caller won a fresh or reclaimed
     * PROCESSING row and must perform the operation-specific work. {@code owned=false} means
     * {@code operation} is a COMPLETED row with a matching payload hash, to replay from.
     */
    record ClaimOutcome(boolean owned, VaultOperation operation) {
    }

    /**
     * Claims (or replays) the operation identified by {@code (userId, operation, keyHash)}.
     * FAILED leaves no key binding — a duplicate insert against a FAILED row is atomically
     * reclaimed via {@code buildReclaimUpdate} regardless of {@code requestHash}. COMPLETED binds
     * the key to its {@code requestHash} until TTL: a match replays, a mismatch conflicts.
     * PROCESSING (owned by another caller, or lost in a reclaim race) is polled at
     * {@link #POLL_INTERVAL} until {@code pollBound} elapses, then reported in-progress.
     *
     * @param buildCandidate     builds the initial PROCESSING row for a fresh insert attempt;
     *                           invoked once per poll iteration since a fresh candidate (with a
     *                           current timestamp) is needed on every retry
     * @param buildReclaimUpdate builds the Mongo {@link Update} applied to a FAILED row to
     *                           reclaim it; always sets state=PROCESSING and requestHash, and
     *                           resets whatever operation-specific fields that caller stores
     *                           (upload: gridFsFileId/vaultDocumentId; reassignment: payload)
     */
    ClaimOutcome claim(Long userId, String operation, String keyHash, String requestHash, Duration pollBound,
                        Supplier<VaultOperation> buildCandidate,
                        BiFunction<VaultOperation, Instant, Update> buildReclaimUpdate,
                        String conflictMessage, String inProgressMessage, long retryAfterSeconds) {
        Instant deadline = Instant.now().plus(pollBound);
        while (true) {
            ClaimResult attempt = tryClaimOnce(userId, operation, keyHash, buildCandidate, buildReclaimUpdate);
            if (attempt.claimed()) {
                return new ClaimOutcome(true, attempt.operationRow());
            }
            VaultOperation existing = attempt.existingRow();
            if (existing.getState() == VaultOperationState.COMPLETED) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new IdempotencyConflictException(conflictMessage);
                }
                return new ClaimOutcome(false, existing);
            }
            // PROCESSING owned by another request, or FAILED that we just lost the reclaim race
            // for: wait/retry within the bounded window rather than tying up the thread forever.
            if (Instant.now().isAfter(deadline)) {
                throw new IdempotencyOperationInProgressException(inProgressMessage, Math.max(1, retryAfterSeconds));
            }
            sleepPollInterval();
        }
    }

    private ClaimResult tryClaimOnce(Long userId, String operation, String keyHash,
                                      Supplier<VaultOperation> buildCandidate,
                                      BiFunction<VaultOperation, Instant, Update> buildReclaimUpdate) {
        try {
            return ClaimResult.claimed(operationRepository.insert(buildCandidate.get()));
        } catch (DuplicateKeyException e) {
            VaultOperation existing = fetchExisting(userId, operation, keyHash);
            if (existing.getState() == VaultOperationState.FAILED) {
                Update update = buildReclaimUpdate.apply(existing, Instant.now());
                VaultOperation reclaimed = reclaimFailed(existing.getId(), update);
                if (reclaimed != null) {
                    return ClaimResult.claimed(reclaimed);
                }
                // Lost the reclaim race: re-read and fall through to the COMPLETED-replay /
                // PROCESSING-in-progress handling instead of proceeding as owner.
                existing = fetchExisting(userId, operation, keyHash);
            }
            return ClaimResult.existing(existing);
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
    private VaultOperation reclaimFailed(String operationId, Update update) {
        Query query = Query.query(Criteria.where("_id").is(operationId).and("state").is(VaultOperationState.FAILED));
        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), VaultOperation.class);
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent vault operation claim", e);
        }
    }

    /**
     * Creates the {@link VaultOperation} unique claim index and TTL index the first time a caller
     * actually uses it, guarded by the caller-owned {@code indexesEnsured} flag. Deliberately not
     * done via {@code spring.data.mongodb.auto-index-creation} or eager {@code @PostConstruct} —
     * see the field javadoc each caller keeps on its own {@code indexesEnsured} field for why.
     *
     * <p>{@code indexesEnsured} is owned by the caller (a singleton Spring bean), not by this
     * class, because a coordinator instance is created fresh per call (see the class javadoc) and
     * so cannot hold the guard itself. Set to {@code true} only once creation has actually
     * succeeded; on failure it is released so the next call retries instead of silently disabling
     * the unique-claim index for the remaining life of the process.
     */
    static void ensureIndexesOnce(AtomicBoolean indexesEnsured, MongoTemplate mongoTemplate) {
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

    private record ClaimResult(boolean claimed, VaultOperation operationRow, VaultOperation existingRow) {
        static ClaimResult claimed(VaultOperation op) {
            return new ClaimResult(true, op, null);
        }

        static ClaimResult existing(VaultOperation op) {
            return new ClaimResult(false, null, op);
        }
    }
}
