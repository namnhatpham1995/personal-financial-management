package com.fintrack.vault.web.dto;

public record VaultReassignmentResponse(
        VaultDocumentResponse document,
        int removedImportedTransactions,
        boolean manualLinkDetached,
        boolean replayed
) {}
