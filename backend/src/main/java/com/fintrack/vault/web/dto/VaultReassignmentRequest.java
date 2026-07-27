package com.fintrack.vault.web.dto;

import jakarta.validation.constraints.NotNull;

public record VaultReassignmentRequest(@NotNull Long targetAccountId) {}
