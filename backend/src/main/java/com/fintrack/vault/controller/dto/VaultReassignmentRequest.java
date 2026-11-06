package com.fintrack.vault.controller.dto;

import jakarta.validation.constraints.NotNull;

public record VaultReassignmentRequest(@NotNull Long targetAccountId) {}
