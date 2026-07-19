package com.fintrack.idempotency.service;

import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.InvalidIdempotencyKeyException;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Row-level counterpart to {@link IdempotentMutationExecutor}, for a single row of a
 * {@code REQUIRES_NEW}-committed batch (see {@link BatchRowClaimRunner}).
 *
 * <p>Row claims use a distinct logical operation name ({@link #ROW_OPERATION}) so they never
 * collide with any single-create operation's claims even if a caller reuses the same literal key
 * string in both places — claims are scoped by {@code (user_id, operation, key_hash)}.
 *
 * <p>Unlike {@link IdempotentMutationExecutor}, this class never throws an HTTP-level exception:
 * batch processing must continue to the next row regardless of one row's outcome. Every failure
 * mode — malformed {@code clientRequestId}, changed-payload conflict, still-processing after the
 * poll bound, or a business-logic exception — becomes a {@link BatchRowOutcome} instead.
 */
@Service
@RequiredArgsConstructor
public class IdempotentBatchRowExecutor {

    /** Distinct from any single-create operation name; see class Javadoc. */
    public static final String ROW_OPERATION = "transaction.batch.row";

    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    /** Same bound/interval rationale as {@link IdempotentMutationExecutor}. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);
    private static final Duration POLL_BOUND = Duration.ofSeconds(3);

    private final IdempotencyKeyValidator validator;
    private final IdempotencyHasher hasher;
    private final IdempotencyOperationRepository repository;
    private final BatchRowClaimRunner claimRunner;
    private final IdempotencyResponseCodec responseCodec;

    /**
     * @param userId              owner of the row; part of the claim scope.
     * @param rawClientRequestId  the row's caller-supplied {@code clientRequestId}.
     * @param rowPayload          the row's logical payload, canonicalized and hashed for
     *                            payload-match checks.
     * @param responseBodyType    class used to deserialize a replayed row result.
     * @param businessLogic       the row mutation to run exactly once per claimed
     *                            {@code clientRequestId}. May throw; any exception is caught and
     *                            reported as a {@link BatchRowOutcome.Kind#FAILED} outcome rather
     *                            than propagated.
     */
    public <T> BatchRowOutcome<T> execute(Long userId,
                                           String rawClientRequestId,
                                           Object rowPayload,
                                           Class<T> responseBodyType,
                                           Supplier<ResponseEntity<T>> businessLogic) {
        try {
            validator.validate(rawClientRequestId);
        } catch (InvalidIdempotencyKeyException e) {
            return BatchRowOutcome.failed(e.getMessage());
        }

        String keyHash = hasher.hashKey(rawClientRequestId);
        String requestHash = hasher.hashJsonRequest(ROW_OPERATION, rowPayload);
        Instant expiresAt = Instant.now().plus(RESULT_RETENTION);

        Optional<ResponseEntity<T>> claimedResult;
        try {
            claimedResult = claimRunner.tryClaimAndExecute(
                    userId, ROW_OPERATION, keyHash, requestHash, expiresAt, businessLogic);
        } catch (RuntimeException e) {
            // The claim (if won) and any partial business-mutation writes were already rolled
            // back by BatchRowClaimRunner's REQUIRES_NEW transaction; report this row as failed
            // and let the batch loop continue to the next row.
            return BatchRowOutcome.failed(safeMessage(e));
        }

        if (claimedResult.isPresent()) {
            return BatchRowOutcome.created(claimedResult.get().getBody());
        }

        return awaitExistingOperation(userId, keyHash, requestHash, responseBodyType);
    }

    private <T> BatchRowOutcome<T> awaitExistingOperation(Long userId,
                                                            String keyHash,
                                                            String requestHash,
                                                            Class<T> responseBodyType) {
        Instant deadline = Instant.now().plus(POLL_BOUND);

        while (true) {
            Optional<IdempotencyOperation> existingOpt =
                    repository.findByUserIdAndOperationAndKeyHash(userId, ROW_OPERATION, keyHash);

            if (existingOpt.isPresent()) {
                IdempotencyOperation existing = existingOpt.get();
                if (existing.getState() == IdempotencyOperationState.COMPLETED) {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        return BatchRowOutcome.conflict(
                                "clientRequestId was already used to complete a row with a different payload");
                    }
                    try {
                        ResponseEntity<T> replay = responseCodec.toReplayResponse(existing, responseBodyType);
                        return BatchRowOutcome.replayed(replay.getBody());
                    } catch (IdempotencyConflictException e) {
                        return BatchRowOutcome.failed(e.getMessage());
                    }
                }
            }

            if (Instant.now().isAfter(deadline)) {
                return BatchRowOutcome.failed(
                        "Another request with this clientRequestId is still being processed; retry shortly");
            }

            sleepPollInterval();
        }
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent batch row operation", e);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Batch row could not be created" : message;
    }
}
