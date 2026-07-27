package com.fintrack.agent.web.dto;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentRunDetailResponse(
        Long id,
        String vaultDocumentId,
        /** Account every non-excluded proposal commits against; null for runs predating this field. */
        Long accountId,
        AgentRunStatus status,
        Map<String, Object> extraction,
        List<ProposalDto> proposals,
        String failureReason,
        boolean retryable,
        List<Long> createdTransactionIds,
        Instant invalidatedAt,
        String invalidationReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentRunDetailResponse from(AgentRun run, List<ProposalDto> proposals) {
        return new AgentRunDetailResponse(
                run.getId(),
                run.getVaultDocumentId(),
                run.getAccount() != null ? run.getAccount().getId() : null,
                run.getStatus(),
                run.getExtraction(),
                proposals,
                run.getFailureReason(),
                run.isRetryable(),
                run.getCreatedTransactionIds(),
                run.getInvalidatedAt(),
                run.getInvalidationReason(),
                run.getCreatedAt(),
                run.getUpdatedAt());
    }
}
