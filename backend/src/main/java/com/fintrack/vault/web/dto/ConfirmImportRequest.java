package com.fintrack.vault.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ConfirmImportRequest(
        @NotNull List<String> selectedDedupKeys
) {}
