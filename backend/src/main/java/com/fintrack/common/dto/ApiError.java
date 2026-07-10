package com.fintrack.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> fieldErrors,
        Long retryAfterSeconds
) {
    public record FieldError(String field, String message) {}

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(status, error, message, path, Instant.now(), null, null);
    }

    public static ApiError of(int status, String error, String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(status, error, message, path, Instant.now(), fieldErrors, null);
    }

    public static ApiError rateLimited(String message, String path, long retryAfterSeconds) {
        return new ApiError(429, "Too Many Requests", message, path, Instant.now(), null, retryAfterSeconds);
    }
}
