package com.fintrack.idempotency.service;

import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Owns the three short, independent transactions that back {@link IdempotentBatchCoordinator}'s
 * aggregate batch-operation record. Deliberately three separate calls rather than one transaction
 * spanning row processing — see {@link IdempotentBatchCoordinator} for why.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BatchAggregateOperationStore {

    private final IdempotencyOperationRepository repository;
    private final IdempotencyResponseCodec responseCodec;
    private final IdempotencyMetrics metrics;

    /**
     * @return the stored replay response if a COMPLETED row already exists for this key with a
     *         matching request hash; empty if no COMPLETED row exists yet (new or still
     *         {@code PROCESSING} from a prior/crashed attempt).
     * @throws IdempotencyConflictException if a COMPLETED row exists with a different request hash.
     */
    @Transactional
    <T> Optional<ResponseEntity<T>> tryReplay(Long userId, String operation, String keyHash,
                                               String requestHash, Class<T> responseBodyType) {
        Optional<IdempotencyOperation> existing =
                repository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash);
        if (existing.isEmpty() || existing.get().getState() != IdempotencyOperationState.COMPLETED) {
            return Optional.empty();
        }

        IdempotencyOperation op = existing.get();
        if (!op.getRequestHash().equals(requestHash)) {
            metrics.conflicted(operation);
            log.warn("Idempotency batch payload conflict: operation={}", operation);
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used to complete a batch with a different payload");
        }
        metrics.replayed(operation);
        log.debug("Idempotency batch replay: operation={}", operation);
        return Optional.of(responseCodec.toReplayResponse(op, responseBodyType));
    }

    /**
     * Best-effort claim insert; a lost race (another concurrent identical request already holds
     * or completed the claim) is intentionally ignored here — row-level claims (see
     * {@link IdempotentBatchRowExecutor}) are what actually make concurrent/repeated row
     * processing safe, not this outer claim.
     */
    @Transactional
    void claimIfAbsent(Long userId, String operation, String keyHash, String requestHash, Instant expiresAt) {
        int claimed = repository.claim(userId, operation, keyHash, requestHash, expiresAt);
        if (claimed == 1) {
            metrics.claimed(operation);
            log.debug("Idempotency batch claim won: operation={}", operation);
        }
    }

    /**
     * Unconditionally upserts the aggregate operation row to COMPLETED with the given response
     * snapshot. Safe to call even when a concurrent identical request is doing the same thing:
     * row processing is itself idempotent/deterministic, so the content converges.
     */
    @Transactional
    <T> Long complete(Long userId, String operation, String keyHash, ResponseEntity<T> response, Instant expiresAt) {
        IdempotencyOperation op = repository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim row for operation=" + operation + " not found during batch completion"));

        responseCodec.applyCompletion(op, response);
        op.setState(IdempotencyOperationState.COMPLETED);
        op.setCompletedAt(Instant.now());
        op.setExpiresAt(expiresAt);
        repository.save(op);
        return op.getId();
    }
}
