package com.fintrack.idempotency.service;

import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Owns the single database transaction that spans the atomic claim insert, the joined business
 * mutation, and the completion write.
 *
 * <p>This lives in its own Spring bean (rather than a private method on
 * {@link IdempotentMutationExecutor}) so {@code @Transactional} is honored: Spring's
 * proxy-based transaction interception does not apply to self-invoked calls within the same
 * class, so the claim+business-logic+completion sequence must be reached through a real
 * inter-bean call to get transactional semantics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyClaimRunner {

    private final IdempotencyOperationRepository repository;
    private final IdempotencyResponseCodec responseCodec;
    private final AuditReplaySignal auditReplaySignal;
    private final IdempotencyMetrics metrics;

    /**
     * Attempts to claim {@code (userId, operation, keyHash)}. If this call wins the claim, runs
     * {@code businessLogic} and persists the completion snapshot in the SAME transaction as the
     * claim insert.
     *
     * <p><b>Rollback guarantee (core correctness property):</b> if {@code businessLogic} throws
     * any exception, it propagates uncaught out of this {@code @Transactional} method, so Spring
     * rolls back both the claim row insert and whatever the business mutation wrote — a failed
     * business operation never leaves a claimed/completed idempotency row behind. Do not wrap the
     * {@code businessLogic.get()} call below in a try/catch that swallows the exception.
     *
     * @return the response to return to the caller if this call won the claim; empty if a row for
     *         this tuple already existed (the caller must look up the existing row to decide how
     *         to respond — replay, conflict, or in-progress).
     */
    @Transactional
    public <T> Optional<ResponseEntity<T>> tryClaimAndExecute(Long userId,
                                                         String operation,
                                                         String keyHash,
                                                         String requestHash,
                                                         Instant expiresAt,
                                                         Supplier<ResponseEntity<T>> businessLogic) {
        int claimed = repository.claim(userId, operation, keyHash, requestHash, expiresAt);
        if (claimed == 0) {
            return Optional.empty();
        }
        metrics.claimed(operation);
        log.debug("Idempotency claim won: operation={}", operation);

        // Not caught on purpose: propagating rolls back the claim row together with any partial
        // business-mutation writes made in this same transaction.
        ResponseEntity<T> response = businessLogic.get();

        IdempotencyOperation op = repository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim row for operation=" + operation + " vanished within its own transaction"));

        responseCodec.applyCompletion(op, response);
        op.setState(IdempotencyOperationState.COMPLETED);
        op.setCompletedAt(Instant.now());
        op.setExpiresAt(expiresAt);
        // Explicit save (rather than relying solely on JPA dirty-checking) documents intent: this
        // is the completion write, not an incidental flush.
        repository.save(op);

        // This is the ORIGINAL, freshly-claimed request — attach a non-secret correlation
        // reference (the operation row's own id) so the resulting audit event can be tied back to
        // this idempotency record without ever storing the raw key or request/response body.
        auditReplaySignal.setOperationReference(String.valueOf(op.getId()));

        return Optional.of(response);
    }
}
