package com.fintrack.auth.exception;

/**
 * Thrown when a refresh token is presented after it was already rotated by a concurrent request
 * within the ten-second concurrency grace window (lost-response retry or two racing tabs). This
 * is expected benign concurrency, not a theft signal — it deliberately does NOT trigger
 * family-wide reuse revocation the way an out-of-window replay does.
 */
public class RefreshAlreadyRotatedException extends RuntimeException {

    public RefreshAlreadyRotatedException(String message) {
        super(message);
    }
}
