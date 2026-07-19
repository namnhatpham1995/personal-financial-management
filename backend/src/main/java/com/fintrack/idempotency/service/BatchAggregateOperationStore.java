package com.fintrack.idempotency.service;

import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import lombok.RequiredArgsConstructor;
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
@Component
@RequiredArgsConstructor
class BatchAggregateOperationStore {

    private final IdempotencyOperationRepository repository;
    private final IdempotencyResponseCodec responseCodec;

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
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used to complete a batch with a different payload");
        }
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
        repository.claim(userId, operation, keyHash, requestHash, expiresAt);
    }

    /**
     * Unconditionally upserts the aggregate operation row to COMPLETED with the given response
     * snapshot. Safe to call even when a concurrent identical request is doing the same thing:
     * row processing is itself idempotent/deterministic, so the content converges.
     */
    @Transactional
    <T> void complete(Long userId, String operation, String keyHash, ResponseEntity<T> response, Instant expiresAt) {
        IdempotencyOperation op = repository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim row for operation=" + operation + " not found during batch completion"));

        responseCodec.applyCompletion(op, response);
        op.setState(IdempotencyOperationState.COMPLETED);
        op.setCompletedAt(Instant.now());
        op.setExpiresAt(expiresAt);
        repository.save(op);
    }
}
