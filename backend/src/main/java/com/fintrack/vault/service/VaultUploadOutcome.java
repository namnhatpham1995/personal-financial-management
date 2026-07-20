package com.fintrack.vault.service;

/** Result of {@link VaultUploadIdempotencyCoordinator#execute}: the business response plus whether it was a replay. */
public record VaultUploadOutcome<T>(T response, boolean replayed) {
}
