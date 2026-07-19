package com.fintrack.idempotency.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Public entry point for making a whole batch request resumable under one request-level
 * {@code Idempotency-Key}. See design.md Decision #2 and
 * {@code openspec/changes/harden-idempotent-mutations/tasks.md} 3.3 for the full contract.
 *
 * <p>Because every row committed by {@code businessLogic} already claims/replays/conflicts
 * independently and idempotently (see {@link IdempotentBatchRowExecutor}), the aggregate
 * operation record does NOT need to hold a lock across the whole row-processing loop — that would
 * defeat "rows commit independently of the batch". Instead:
 *
 * <ol>
 *   <li>Validate + hash the batch key and the full request body.
 *   <li>If a COMPLETED aggregate row already exists with a matching request hash, return the
 *       stored response immediately (fast full replay, zero row reprocessing). A COMPLETED row
 *       with a different hash is a 409 batch-level conflict.
 *   <li>Otherwise (new, or a stale {@code PROCESSING} row from a prior/crashed attempt) claim the
 *       aggregate row (best-effort; a lost claim race is ignored — see
 *       {@link BatchAggregateOperationStore#claimIfAbsent}) and run {@code businessLogic}, which
 *       processes every row via {@link IdempotentBatchRowExecutor}.
 *   <li>Unconditionally upsert the aggregate row to COMPLETED with the assembled response.
 * </ol>
 *
 * <p>This makes an interrupted batch naturally resumable: resubmitting the exact same payload
 * under the same key re-derives already-done rows as cheap REPLAYED lookups and only does real
 * work for rows that never completed — no special "resume" code path beyond what row-level
 * idempotency already provides.
 */
@Service
@RequiredArgsConstructor
public class IdempotentBatchCoordinator {

    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    private final IdempotencyKeyValidator validator;
    private final IdempotencyHasher hasher;
    private final BatchAggregateOperationStore aggregateStore;

    /**
     * @param userId            owner of the batch; part of the claim scope.
     * @param operation         stable logical operation name for the aggregate record (e.g.
     *                          {@code "transaction.batch"}).
     * @param rawIdempotencyKey the caller-supplied {@code Idempotency-Key} header value.
     * @param requestBody       the full batch request payload (rows + their client request ids),
     *                          canonicalized and hashed for payload-match checks.
     * @param responseBodyType  class used to deserialize a replayed aggregate response.
     * @param businessLogic     processes every row and assembles the aggregate response. Runs
     *                          exactly once per claimed key on a given attempt; may run again on
     *                          a resumed/interrupted batch, in which case already-completed rows
     *                          resolve as cheap replays inside it.
     */
    public <T> ResponseEntity<T> execute(Long userId,
                                          String operation,
                                          String rawIdempotencyKey,
                                          Object requestBody,
                                          Class<T> responseBodyType,
                                          Supplier<ResponseEntity<T>> businessLogic) {
        validator.validate(rawIdempotencyKey);

        String keyHash = hasher.hashKey(rawIdempotencyKey);
        String requestHash = hasher.hashJsonRequest(operation, requestBody);
        Instant expiresAt = Instant.now().plus(RESULT_RETENTION);

        Optional<ResponseEntity<T>> replay =
                aggregateStore.tryReplay(userId, operation, keyHash, requestHash, responseBodyType);
        if (replay.isPresent()) {
            return replay.get();
        }

        aggregateStore.claimIfAbsent(userId, operation, keyHash, requestHash, expiresAt);

        ResponseEntity<T> response = businessLogic.get();

        aggregateStore.complete(userId, operation, keyHash, response, expiresAt);
        return response;
    }
}
