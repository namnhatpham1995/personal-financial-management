package com.fintrack.agent.web.dto;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentRunDetailResponse(
        Long id,
        String vaultDocumentId,
        AgentRunStatus status,
        Map<String, Object> extraction,
        List<ProposalDto> proposals,
        String failureReason,
        boolean retryable,
        List<Long> createdTransactionIds,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentRunDetailResponse from(AgentRun run, List<ProposalDto> proposals) {
        return new AgentRunDetailResponse(
                run.getId(),
                run.getVaultDocumentId(),
                run.getStatus(),
                run.getExtraction(),
                proposals,
                run.getFailureReason(),
                run.isRetryable(),
                run.getCreatedTransactionIds(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
