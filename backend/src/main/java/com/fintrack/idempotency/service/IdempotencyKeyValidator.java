package com.fintrack.idempotency.service;

import com.fintrack.idempotency.exception.InvalidIdempotencyKeyException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Validates the raw {@code Idempotency-Key} header value before it is hashed or used to claim
 * an operation. Never logs the raw key — callers must not pass it to a logger either.
 */
@Component
public class IdempotencyKeyValidator {

    private static final int MIN_LENGTH = 16;
    private static final int MAX_LENGTH = 128;
    private static final Pattern URL_SAFE = Pattern.compile("^[A-Za-z0-9_-]+$");

    /**
     * @throws InvalidIdempotencyKeyException if the key is null/blank, outside the 16-128
     *                                         character range, or contains characters other than
     *                                         letters, digits, {@code -}, or {@code _}.
     */
    public void validate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key header is blank");
        }
        int length = rawKey.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!URL_SAFE.matcher(rawKey).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must contain only letters, digits, '-', or '_'");
        }
    }
}
