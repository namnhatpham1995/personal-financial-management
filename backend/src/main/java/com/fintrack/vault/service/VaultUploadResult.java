package com.fintrack.vault.service;

/** Outcome of {@link VaultUploadWork#saveDocument}: the saved document's id plus the business response. */
public record VaultUploadResult<T>(String vaultDocumentId, T response) {
}
