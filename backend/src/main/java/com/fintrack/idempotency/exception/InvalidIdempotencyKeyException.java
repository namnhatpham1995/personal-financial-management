package com.fintrack.idempotency.exception;

/** Thrown when a caller-supplied {@code Idempotency-Key} header fails format validation. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }
}
