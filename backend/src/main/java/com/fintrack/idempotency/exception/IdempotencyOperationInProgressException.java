package com.fintrack.idempotency.exception;

/**
 * Thrown when a concurrent request for the same {@code (user, operation, key)} is still
 * {@code PROCESSING} after the executor's bounded poll window. Carries a suggested
 * {@code Retry-After} value so the handler can advise the caller.
 */
public class IdempotencyOperationInProgressException extends RuntimeException {

    private final long retryAfterSeconds;

    public IdempotencyOperationInProgressException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
