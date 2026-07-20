package com.fintrack.apitoken.exception;

import com.fintrack.apitoken.web.dto.ApiTokenResponse;
import lombok.Getter;

/**
 * Thrown when a PAT-creation {@code Idempotency-Key} was already used by this user, regardless
 * of whether the retried request's settings (name/scope/expiry) match the original. Because the
 * created token's plaintext is never persisted, a same-key retry can never replay the original
 * response body the way other idempotent creates do — it always gets this typed conflict instead,
 * carrying only the existing token's non-secret metadata.
 */
@Getter
public class ApiTokenIdempotencyConflictException extends RuntimeException {

    private final ApiTokenResponse existingToken;

    public ApiTokenIdempotencyConflictException(String message, ApiTokenResponse existingToken) {
        super(message);
        this.existingToken = existingToken;
    }
}
