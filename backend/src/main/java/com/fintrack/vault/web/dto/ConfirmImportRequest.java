package com.fintrack.vault.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ConfirmImportRequest(
        @NotNull List<@Valid ConfirmRow> rows
) {
    /** One selected row: its dedup key plus the category chosen during review (nullable = uncategorized). */
    public record ConfirmRow(
            @NotBlank String dedupKey,
            Long categoryId
    ) {}
}
