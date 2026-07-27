package com.fintrack.vault.web.dto;

import com.fintrack.vault.domain.VaultDocumentType;

public record VaultReassignmentPreviewResponse(
        String documentId,
        VaultDocumentType type,
        String originalFilename,
        Long sourceAccountId,
        String sourceAccountName,
        String sourceCurrency,
        Long targetAccountId,
        String targetAccountName,
        String targetCurrency,
        boolean currencyChanged,
        int importedTransactionCount,
        boolean detachManualLink
) {}
