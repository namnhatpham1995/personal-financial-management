package com.fintrack.idempotency.service;

import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.IdempotencyOperationInProgressException;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Public entry point for protected mutations to become idempotent. See design.md Decision #1 for
 * the full contract; behavior summary:
 *
 * <ol>
 *   <li>Validate the raw key, hash the key and the canonical request.
 *   <li>Try to atomically claim {@code (userId, operation, keyHash)}. If claimed, run
 *       {@code businessLogic} and persist the completion snapshot in the same transaction as the
 *       claim (see {@link IdempotencyClaimRunner}) — a failed business operation rolls back the
 *       claim too.
 *   <li>If not claimed, another request already owns this key. Poll (outside any long-held
 *       transaction) until it completes or a bounded wait expires: same-hash completion replays
 *       the stored response, different-hash completion is a conflict, and a still-processing
 *       operation past the poll bound returns a typed in-progress error with retry guidance.
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class IdempotentMutationExecutor {

    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    /**
     * Poll every 150ms for up to 3s (~20 attempts) while waiting for a concurrent claimant to
     * finish. 150ms is short enough that a caller who eventually gets a replay barely notices the
     * wait; 3s total is short enough to avoid tying up a servlet thread indefinitely while still
     * covering the overwhelming majority of normal request latencies. Each iteration re-reads
     * committed state under READ COMMITTED rather than holding one long-lived transaction open.
     */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);
    private static final Duration POLL_BOUND = Duration.ofSeconds(3);

    private final IdempotencyKeyValidator validator;
    private final IdempotencyHasher hasher;
    private final IdempotencyOperationRepository repository;
    private final IdempotencyClaimRunner claimRunner;
    private final IdempotencyResponseCodec responseCodec;

    /**
     * @param userId              owner of the operation; part of the claim scope.
     * @param operation           stable logical operation name (not a raw URL); part of the claim
     *                            scope.
     * @param rawIdempotencyKey   the caller-supplied {@code Idempotency-Key} header value.
     * @param requestBody         the request payload, canonicalized and hashed for payload-match
     *                            checks. May be {@code null} for bodyless operations.
     * @param responseBodyType    class used to deserialize a replayed response body; must match
     *                            the type {@code businessLogic} produces in its
     *                            {@link ResponseEntity}.
     * @param businessLogic       the actual mutation to run exactly once per claimed key.
     */
    public <T> ResponseEntity<T> execute(Long userId,
                                          String operation,
                                          String rawIdempotencyKey,
                                          Object requestBody,
                                          Class<T> responseBodyType,
                                          Supplier<ResponseEntity<T>> businessLogic) {
        // Validation happens before any claim attempt and is allowed to propagate uncaught.
        validator.validate(rawIdempotencyKey);

        String keyHash = hasher.hashKey(rawIdempotencyKey);
        String requestHash = hasher.hashJsonRequest(operation, requestBody);
        Instant expiresAt = Instant.now().plus(RESULT_RETENTION);

        Optional<ResponseEntity<T>> claimedResult =
                claimRunner.tryClaimAndExecute(userId, operation, keyHash, requestHash, expiresAt, businessLogic);
        if (claimedResult.isPresent()) {
            return claimedResult.get();
        }

        return awaitExistingOperation(userId, operation, keyHash, requestHash, responseBodyType);
    }

    private <T> ResponseEntity<T> awaitExistingOperation(Long userId,
                                                           String operation,
                                                           String keyHash,
                                                           String requestHash,
                                                           Class<T> responseBodyType) {
        Instant deadline = Instant.now().plus(POLL_BOUND);

        while (true) {
            IdempotencyOperation existing = repository.findByUserIdAndOperationAndKeyHash(userId, operation, keyHash)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency operation for operation=" + operation + " not found after a failed claim"));

            if (existing.getState() == IdempotencyOperationState.COMPLETED) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            "Idempotency-Key was already used to complete a request with a different payload");
                }
                return responseCodec.toReplayResponse(existing, responseBodyType);
            }

            if (Instant.now().isAfter(deadline)) {
                throw new IdempotencyOperationInProgressException(
                        "Another request with this Idempotency-Key is still being processed; retry shortly",
                        Math.max(1, POLL_INTERVAL.toSeconds()));
            }

            sleepPollInterval();
        }
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent idempotent operation", e);
        }
    }
}
