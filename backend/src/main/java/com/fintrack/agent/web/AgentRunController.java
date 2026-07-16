package com.fintrack.agent.web;

import com.fintrack.agent.service.AgentRunService;
import com.fintrack.agent.web.dto.AgentDecisionRequest;
import com.fintrack.agent.web.dto.AgentRunDetailResponse;
import com.fintrack.agent.web.dto.AgentRunSummaryResponse;
import com.fintrack.agent.web.dto.StartAgentRunRequest;
import com.fintrack.agent.web.dto.SubmitProposalsRequest;
import com.fintrack.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Receipt ingestion run lifecycle. User-facing endpoints (start/list/get/decision) require a
 * normal session; the proposals/commit/fail endpoints are called by the agent service using
 * its short-lived, per-run scoped token (see AgentAuthenticationFilter) — the same
 * {@code @AuthenticationPrincipal} works for both since an agent token resolves to the
 * initiating user's principal, just with a narrower endpoint allowlist.
 */
@Tag(name = "Agent Runs", description = "Receipt ingestion agent run lifecycle")
@RestController
@RequestMapping("/api/v1/agent-runs")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunService agentRunService;

    @Operation(summary = "Start an ingestion run for an owned vault receipt")
    @PostMapping
    public ResponseEntity<AgentRunSummaryResponse> start(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody StartAgentRunRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agentRunService.start(principal.getUserId(), request.vaultDocumentId()));
    }

    @Operation(summary = "List the authenticated user's ingestion runs")
    @GetMapping
    public List<AgentRunSummaryResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return agentRunService.list(principal.getUserId());
    }

    @Operation(summary = "Get an ingestion run's full detail, including proposals")
    @GetMapping("/{id}")
    public AgentRunDetailResponse get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        return agentRunService.get(principal.getUserId(), id);
    }

    @Operation(summary = "Approve (with optional edits) or reject a run awaiting review")
    @PostMapping("/{id}/decision")
    public AgentRunDetailResponse decide(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody AgentDecisionRequest request
    ) {
        return agentRunService.decide(principal.getUserId(), id, request);
    }

    @Operation(summary = "Agent-token: submit extraction + proposals, moving the run to AWAITING_REVIEW")
    @PostMapping("/{id}/proposals")
    public AgentRunDetailResponse submitProposals(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody SubmitProposalsRequest request
    ) {
        return agentRunService.submitProposals(principal.getUserId(), id, request);
    }

    @Operation(summary = "Agent-token or recovery path: commit the run's stored proposals")
    @PostMapping("/{id}/commit")
    public AgentRunDetailResponse commit(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id
    ) {
        return agentRunService.commit(principal.getUserId(), id);
    }

    @Operation(summary = "Agent-token: report that the run could not complete")
    @PostMapping("/{id}/fail")
    public ResponseEntity<Void> fail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody FailAgentRunRequest request
    ) {
        agentRunService.fail(principal.getUserId(), id, request.reason(), request.retryable());
        return ResponseEntity.noContent().build();
    }

    public record FailAgentRunRequest(String reason, boolean retryable) {}
}
