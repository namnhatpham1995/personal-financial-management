package com.fintrack.vault.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for the Mongo/GridFS-backed idempotency decision points (vault/statement
 * upload via {@link VaultUploadIdempotencyCoordinator}, statement-confirmation state machine in
 * {@code StatementImportService}, and the stale-operation recovery sweep in
 * {@link VaultOperationRecoveryScheduler}). See
 * {@code com.fintrack.idempotency.service.IdempotencyMetrics} for the PostgreSQL-side equivalent
 * this deliberately mirrors under a distinct {@code vault_operation.*} prefix, since these two
 * stores are not the same underlying claim mechanism (no shared transaction manager across
 * Postgres and Mongo — see design.md Decision #4).
 *
 * <p>Every counter is tagged only with the non-secret logical {@code operation} name (e.g.
 * {@code "statement.upload"}, {@code "statement.confirm"}) — never a raw idempotency key, key
 * hash, or file/response content.
 */
@Component
@RequiredArgsConstructor
class VaultOperationMetrics {

    private final MeterRegistry meterRegistry;

    /** A request won the atomic claim (or CAS reclaim) and is about to do real work for the first time. */
    void claimed(String operation) {
        increment("vault_operation.claim", "Vault/statement operation claims won (fresh execution) per operation", operation);
    }

    /** A request with a matching key and payload was served the previously-completed result. */
    void replayed(String operation) {
        increment("vault_operation.replay", "Vault/statement operation replays (matching key and payload) per operation", operation);
    }

    /** A request reused a key already completed (or in progress) with a different payload. */
    void conflicted(String operation) {
        increment("vault_operation.conflict", "Vault/statement operation payload conflicts per operation", operation);
    }

    /** A request's key is still owned by another in-flight claim past the bounded poll window. */
    void inProgress(String operation) {
        increment("vault_operation.in_progress", "Vault/statement operation in-progress responses per operation", operation);
    }

    /** A stale PROCESSING operation was compensated (marked FAILED) by the recovery sweep. */
    void recoveryRecovered(String operation) {
        increment("vault_operation.recovery.recovered", "Stale PROCESSING vault operations recovered per sweep, per operation", operation);
    }

    /** An orphaned GridFS binary was deleted while recovering a stale operation. */
    void recoveryCompensated(String operation) {
        increment("vault_operation.recovery.compensated", "Orphaned GridFS binaries deleted while recovering a stale operation, per operation", operation);
    }

    private void increment(String name, String description, String operation) {
        Counter.builder(name)
                .tag("operation", operation)
                .description(description)
                .register(meterRegistry)
                .increment();
    }
}
