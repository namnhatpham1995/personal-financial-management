package com.fintrack.vault.controller.dto;

import com.fintrack.vault.domain.VaultStage;

import java.util.Map;

/** Per-stage document counts across the user's whole matching collection, not one page. */
public record VaultStageCountsResponse(Map<VaultStage, Long> counts) {
}
