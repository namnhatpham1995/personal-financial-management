package com.fintrack.idempotency.service;

import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code REQUIRES_NEW} sibling of {@link IdempotencyClaimRunner}, used for batch row claims.
 *
 * <p>Every batch row MUST commit or fail independently of sibling rows and of the outer
 * batch-processing loop — this is the existing, load-bearing behavior of
 * {@code TransactionService.createBatch} (previously implemented with a manually-constructed
 * {@code TransactionTemplate}, now implemented via this explicit {@code REQUIRES_NEW} claim
 * runner). Spring {@code @Transactional} propagation is static per method, so this is a separate
 * bean rather than a parameterized call into {@link IdempotencyClaimRunner} — the small amount of
 * structural duplication between the two classes is intentional; see the parent class's Javadoc
 * for why a real inter-bean call (not self-invocation) is required for {@code @Transactional} to
 * apply.
 */
@Component
@RequiredArgsConstructor
public class BatchRowClaimRunner {

    private final IdempotencyOperationRepository repository;
    private final IdempotencyResponseCodec responseCodec;

    /**
     * Attempts to claim {@code (userId, operation, keyHash)} in its own {@code REQUIRES_NEW}
     * transaction. If this call wins the claim, runs {@code businessLogic} and persists the
     * completion snapshot in the SAME transaction as the claim insert — a failed business
     * operation rolls back only this row's claim and writes, never a sibling row or the
     * surrounding batch loop.
     *
     * @return the response produced by this call if it won the claim; empty if a row for this
     *         tuple already existed (the caller must look up the existing row to decide how to
     *         respond — replay or conflict).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        // Not caught on purpose: propagating rolls back this row's claim together with any
        // partial business-mutation writes made in this same transaction.
        ResponseEntity<T> response = businessLogic.get();

        IdempotencyOperation op = repository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency claim row for operation=" + operation + " vanished within its own transaction"));

        responseCodec.applyCompletion(op, response);
        op.setState(IdempotencyOperationState.COMPLETED);
        op.setCompletedAt(Instant.now());
        op.setExpiresAt(expiresAt);
        repository.save(op);

        return Optional.of(response);
    }
}
