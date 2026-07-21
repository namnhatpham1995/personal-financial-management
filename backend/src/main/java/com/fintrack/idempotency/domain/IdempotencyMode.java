package com.fintrack.idempotency.domain;

/**
 * Rollout mode for the {@code Idempotency-Key} header requirement on protected create endpoints.
 * See design.md Migration Plan step 5 and {@code openspec/changes/harden-idempotent-mutations/tasks.md}
 * 9.5. Bound as {@code app.idempotency.mode}, consumed by
 * {@link com.fintrack.idempotency.service.IdempotencyEnforcementGuard}.
 */
public enum IdempotencyMode {

    /** A missing key is silently allowed through; no metric or log is recorded. Original baseline behavior. */
    ACCEPT,

    /**
     * A missing key is allowed through (identical behavior to ACCEPT), but the occurrence is
     * recorded via a metric/log so an operator can confirm official clients are sending keys
     * before opting into ENFORCE. Default mode.
     */
    OBSERVE,

    /** A missing key is rejected with a typed 400 before any side effect executes. */
    ENFORCE
}
