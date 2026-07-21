package com.fintrack.idempotency.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for the PostgreSQL-backed idempotency decision points (single-create claim
 * runner, batch aggregate store, batch row claim runner). See
 * {@code com.fintrack.vault.service.VaultOperationMetrics} for the Mongo/GridFS-side equivalent
 * and {@link IdempotencyEnforcementGuard} for the {@code idempotency.missing_key} counter this
 * intentionally mirrors the naming/tagging convention of.
 *
 * <p>Every counter is tagged only with the non-secret logical {@code operation} name (e.g.
 * {@code "account.create"}, {@code "transaction.batch.row"}) — never a raw idempotency key, key
 * hash, or request/response body.
 */
@Component
@RequiredArgsConstructor
class IdempotencyMetrics {

    private final MeterRegistry meterRegistry;

    /** A request won the atomic claim and is about to run its business logic for the first time. */
    void claimed(String operation) {
        increment("idempotency.claim", "Idempotency claims won (fresh execution) per operation", operation);
    }

    /** A request with a matching key and payload was served the previously-completed response. */
    void replayed(String operation) {
        increment("idempotency.replay", "Idempotency replays (matching key and payload) per operation", operation);
    }

    /** A request reused a key already completed with a different payload. */
    void conflicted(String operation) {
        increment("idempotency.conflict", "Idempotency payload conflicts (matching key, different payload) per operation", operation);
    }

    /** A request's key is still owned by another in-flight claim past the bounded poll window. */
    void inProgress(String operation) {
        increment("idempotency.in_progress", "Idempotency in-progress responses (concurrent claim still running) per operation", operation);
    }

    private void increment(String name, String description, String operation) {
        Counter.builder(name)
                .tag("operation", operation)
                .description(description)
                .register(meterRegistry)
                .increment();
    }
}
