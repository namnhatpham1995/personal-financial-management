package com.fintrack.agent.web.dto;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;

import java.time.Instant;

public record AgentRunSummaryResponse(
        Long id,
        String vaultDocumentId,
        AgentRunStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentRunSummaryResponse from(AgentRun run) {
        return new AgentRunSummaryResponse(
                run.getId(), run.getVaultDocumentId(), run.getStatus(), run.getCreatedAt(), run.getUpdatedAt());
    }
}
