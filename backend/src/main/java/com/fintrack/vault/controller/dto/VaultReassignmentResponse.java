package com.fintrack.vault.controller.dto;

public record VaultReassignmentResponse(
        VaultDocumentResponse document,
        int removedImportedTransactions,
        boolean manualLinkDetached,
        boolean replayed
) {}
