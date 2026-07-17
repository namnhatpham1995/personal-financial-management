package com.fintrack.idempotency.exception;

/**
 * Thrown when a previously completed idempotency key is reused with a request body whose
 * canonical hash no longer matches the original — i.e. the same key is being reused for a
 * logically different request.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
