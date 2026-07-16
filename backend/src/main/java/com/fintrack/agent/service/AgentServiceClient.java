package com.fintrack.agent.service;

import com.fintrack.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Best-effort HTTP client that kicks off / resumes graph execution in the agent-service.
 * The backend is the system of record for run state (design.md D2) — a failed notification
 * here does not fail the request that triggered it; the run simply stays where it is until
 * retried or recovered.
 */
@Slf4j
@Service
public class AgentServiceClient {

    private final RestClient restClient;
    private final AppProperties appProperties;

    public AgentServiceClient(@Qualifier("agentServiceRestClient") RestClient restClient,
                               AppProperties appProperties) {
        this.restClient = restClient;
        this.appProperties = appProperties;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(appProperties.getAgent().getServiceUrl());
    }

    /** Notifies the agent service that a run is ready to begin extraction. Fire-and-forget. */
    public void notifyRunStarted(Long runId, String vaultDocumentId, String agentToken) {
        if (!isConfigured()) {
            return;
        }
        try {
            restClient.post()
                    .uri(appProperties.getAgent().getServiceUrl() + "/runs")
                    .body(Map.of("runId", runId, "vaultDocumentId", vaultDocumentId, "token", agentToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Failed to notify agent-service of run {} start: {}", runId, e.getMessage());
        }
    }

    /** Forwards the user's approval (with edits) as a resume command. Fire-and-forget. */
    public void notifyRunResumed(Long runId, String agentToken) {
        if (!isConfigured()) {
            return;
        }
        try {
            restClient.post()
                    .uri(appProperties.getAgent().getServiceUrl() + "/runs/" + runId + "/resume")
                    .body(Map.of("token", agentToken))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Failed to notify agent-service of run {} resume: {}", runId, e.getMessage());
        }
    }
}
