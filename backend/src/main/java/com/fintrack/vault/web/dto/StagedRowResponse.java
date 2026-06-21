package com.fintrack.vault.web.dto;

public record StagedRowResponse(
        String date,
        String amount,
        String type,
        String description,
        String dedupKey
) {}
