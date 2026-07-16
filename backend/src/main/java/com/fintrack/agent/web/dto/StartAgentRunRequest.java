package com.fintrack.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

public record StartAgentRunRequest(
        @NotBlank String vaultDocumentId
) {}
