package com.fintrack.common.security;

import com.fintrack.agent.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deny-by-default allowlist for agent-token-authenticated requests, mirroring
 * {@link PatEndpointPolicy}'s design (D3-style): an agent token grants access to exactly the
 * endpoints its run needs, and nothing else — never another user's data, never another run's
 * proposals/commit, never a vault document outside the one it was minted for.
 */
@Component
@RequiredArgsConstructor
public class AgentEndpointPolicy {

    private static final Pattern PROPOSALS = Pattern.compile("/api/v1/agent-runs/(\\d+)/proposals");
    private static final Pattern COMMIT = Pattern.compile("/api/v1/agent-runs/(\\d+)/commit");
    private static final Pattern FAIL = Pattern.compile("/api/v1/agent-runs/(\\d+)/fail");
    private static final Pattern VAULT_DOWNLOAD = Pattern.compile("/api/vault/([0-9a-fA-F]{24})/download");
    private static final Pattern CATEGORIES = Pattern.compile("/api/v1/categories.*");
    private static final Pattern ACCOUNTS = Pattern.compile("/api/v1/accounts.*");

    private final AgentRunRepository agentRunRepository;

    public boolean isAllowed(String method, String uri, Long tokenUserId, Long tokenRunId) {
        Matcher proposals = PROPOSALS.matcher(uri);
        if ("POST".equalsIgnoreCase(method) && proposals.matches()) {
            return Long.parseLong(proposals.group(1)) == tokenRunId;
        }

        Matcher commit = COMMIT.matcher(uri);
        if ("POST".equalsIgnoreCase(method) && commit.matches()) {
            return Long.parseLong(commit.group(1)) == tokenRunId;
        }

        Matcher fail = FAIL.matcher(uri);
        if ("POST".equalsIgnoreCase(method) && fail.matches()) {
            return Long.parseLong(fail.group(1)) == tokenRunId;
        }

        Matcher download = VAULT_DOWNLOAD.matcher(uri);
        if ("GET".equalsIgnoreCase(method) && download.matches()) {
            return agentRunRepository.findByIdAndUser_Id(tokenRunId, tokenUserId)
                    .map(run -> run.getVaultDocumentId().equals(download.group(1)))
                    .orElse(false);
        }

        if ("GET".equalsIgnoreCase(method) && (CATEGORIES.matcher(uri).matches() || ACCOUNTS.matcher(uri).matches())) {
            return true;
        }

        return false;
    }
}
