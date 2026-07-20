package com.fintrack.vault.service;

/**
 * Builds a replay response from a previously completed operation's {@code vaultDocumentId},
 * without touching GridFS again.
 */
@FunctionalInterface
public interface VaultUploadReplay<T> {

    T buildReplayResponse(String vaultDocumentId);
}
