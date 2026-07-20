package com.fintrack.apitoken.web.dto;

import java.time.Instant;

/**
 * 409 response body for a PAT-creation idempotency-key replay. Deliberately never carries
 * plaintext — {@code existingToken} is the same non-secret shape returned by {@code GET
 * /tokens}, plus revoke-and-recreate guidance in {@code message}.
 */
public record ApiTokenConflictError(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        ApiTokenResponse existingToken
) {
    public static ApiTokenConflictError of(String message, String path, ApiTokenResponse existingToken) {
        return new ApiTokenConflictError(409, "api_token_idempotency_conflict", message, path, Instant.now(), existingToken);
    }
}
