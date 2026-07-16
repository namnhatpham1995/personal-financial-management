package com.fintrack.agent.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Submitted by the initiating user against a run in AWAITING_REVIEW.
 * When {@code approve} is true, {@code proposals} carries the user's (possibly edited) proposals
 * and is re-validated authoritatively before any transaction is created. When false (reject),
 * proposals are ignored — the run closes with no side effects.
 */
public record AgentDecisionRequest(
        @NotNull Boolean approve,
        @Valid List<ProposalDto> proposals
) {}
