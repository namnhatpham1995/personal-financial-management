package com.fintrack.idempotency.service;

/**
 * Result of processing one batch row through {@link IdempotentBatchRowExecutor}. Never carries an
 * HTTP-level exception — the caller (batch loop) translates this into a row result and moves on
 * to the next row regardless of outcome.
 */
public record BatchRowOutcome<T>(Kind kind, T result, String errorMessage) {

    public enum Kind { CREATED, REPLAYED, CONFLICT, FAILED }

    public static <T> BatchRowOutcome<T> created(T result) {
        return new BatchRowOutcome<>(Kind.CREATED, result, null);
    }

    public static <T> BatchRowOutcome<T> replayed(T result) {
        return new BatchRowOutcome<>(Kind.REPLAYED, result, null);
    }

    public static <T> BatchRowOutcome<T> conflict(String message) {
        return new BatchRowOutcome<>(Kind.CONFLICT, null, message);
    }

    public static <T> BatchRowOutcome<T> failed(String message) {
        return new BatchRowOutcome<>(Kind.FAILED, null, message);
    }
}
