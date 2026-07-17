package com.fintrack.idempotency.exception;

/**
 * Thrown when a protected mutation requires an {@code Idempotency-Key} header and none was
 * supplied. Not thrown by any endpoint yet — enforcement wiring lands in a later task — but
 * defined now so the typed-error contract and its handler mapping exist ahead of that wiring.
 */
public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException(String message) {
        super(message);
    }
}
