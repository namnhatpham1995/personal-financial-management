package com.fintrack.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record StartAgentRunRequest(
        @NotBlank String vaultDocumentId
) {}
