package com.fintrack.agent.service;

import com.fintrack.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Mints and validates the short-lived, per-run scoped token the agent service uses to call
 * back into the backend (design.md decision D3). Distinguished from a normal session JWT by
 * the {@code scope=agent} claim — {@link com.fintrack.common.security.AgentAuthenticationFilter}
 * owns every token carrying that claim so it can never fall through to normal JWT auth.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTokenService {

    private static final String SCOPE_CLAIM = "scope";
    private static final String SCOPE_VALUE = "agent";
    private static final String RUN_ID_CLAIM = "runId";

    private final AppProperties appProperties;

    public String generateAgentToken(String userEmail, Long userId, Long runId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(Map.of("userId", userId, RUN_ID_CLAIM, runId, SCOPE_CLAIM, SCOPE_VALUE))
                .subject(userEmail)
                .issuedAt(new Date(now))
                .expiration(new Date(now + appProperties.getAgent().getTokenExpiryMs()))
                .signWith(signingKey())
                .compact();
    }

    /** Returns claims only if the token is a syntactically valid, non-expired, scope=agent token. */
    public Optional<AgentTokenClaims> tryParse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!SCOPE_VALUE.equals(claims.get(SCOPE_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(new AgentTokenClaims(
                    claims.getSubject(),
                    claims.get("userId", Long.class),
                    claims.get(RUN_ID_CLAIM, Long.class)));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Agent token parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private SecretKey signingKey() {
        byte[] keyBytes = appProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public record AgentTokenClaims(String subject, Long userId, Long runId) {}
}
