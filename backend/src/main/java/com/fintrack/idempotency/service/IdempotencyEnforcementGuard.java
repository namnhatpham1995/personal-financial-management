package com.fintrack.idempotency.service;

import com.fintrack.common.config.AppProperties;
import com.fintrack.idempotency.domain.IdempotencyMode;
import com.fintrack.idempotency.exception.MissingIdempotencyKeyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mode-aware accept/observe/enforce gate for protected-create endpoints. See
 * {@link IdempotencyMode} and {@code AppProperties.Idempotency} for the rollout contract
 * (design.md Migration Plan step 5, tasks.md 9.5).
 *
 * <ul>
 *   <li>{@code ACCEPT} — a missing key is silently allowed through; no metric or log.
 *   <li>{@code OBSERVE} (default) — a missing key is allowed through, identical to ACCEPT, but
 *       records a metric/log so an operator can confirm official clients are ready before opting
 *       into ENFORCE.
 *   <li>{@code ENFORCE} — a missing key is rejected with a typed 400 before any side effect;
 *       records the same metric/log as OBSERVE for consistency.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyEnforcementGuard {

    private static final String MISSING_KEY_COUNTER = "idempotency.missing_key";

    private final AppProperties appProperties;
    private final MeterRegistry meterRegistry;

    /**
     * Throws {@link MissingIdempotencyKeyException} when the configured mode is {@code ENFORCE}
     * and no key was supplied. A no-op in {@code ACCEPT}/{@code OBSERVE} mode, allowing the
     * endpoint to fall back to calling the service directly without idempotency protection.
     *
     * @param operation stable logical operation name (e.g. {@code "account.create"}) — used only
     *                  as a non-secret metric/log tag, never the raw key or request body.
     */
    public void requireKeyOrThrow(String operation, String rawIdempotencyKey) {
        if (rawIdempotencyKey != null) {
            return;
        }

        IdempotencyMode mode = appProperties.getIdempotency().getMode();
        if (mode == IdempotencyMode.ACCEPT) {
            return;
        }

        recordMissingKey(operation);
        if (mode == IdempotencyMode.ENFORCE) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for this operation");
        }
    }

    private void recordMissingKey(String operation) {
        log.info("Idempotency-Key missing for protected create: operation={}", operation);
        Counter.builder(MISSING_KEY_COUNTER)
                .tag("operation", operation)
                .description("Protected-create requests received without an Idempotency-Key header")
                .register(meterRegistry)
                .increment();
    }
}
