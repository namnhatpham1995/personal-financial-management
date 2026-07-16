package com.fintrack.agent.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Posted by the agent service (agent-scoped token) once extraction, categorization, and its
 * own client-side validation are complete. The backend re-runs deterministic validation
 * authoritatively — the agent process is never trusted more than the LLM it wraps.
 */
public record SubmitProposalsRequest(
        @NotNull Map<String, Object> extraction,
        @NotEmpty @Valid List<ProposalDto> proposals
) {}
