package com.fintrack.vault.web.dto;

import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;

import java.time.Instant;
import java.util.Map;

public record VaultDocumentResponse(
        String id,
        VaultDocumentType type,
        VaultDocumentStatus status,
        String source,
        Instant capturedAt,
        Map<String, Object> payload,
        boolean hasBinary,
        String originalFilename,
        Long transactionId
) {}
