package com.fintrack.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.apitoken.domain.ApiToken;
import com.fintrack.apitoken.service.ApiTokenService;
import com.fintrack.common.config.AppProperties;
import com.fintrack.common.dto.ApiError;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates requests bearing a Personal Access Token (prefix fintrack_pat_), registered
 * before JwtAuthenticationFilter so a PAT never falls through to JWT parsing.
 * Also enforces the deny-by-default endpoint allowlist (design.md decision D3) and a per-token
 * rate limit — both independent of whatever tools an MCP client happens to expose, so a
 * compromised or malicious MCP process is still bound by the token's own scope.
 */
@Slf4j
@RequiredArgsConstructor
public class PatAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_PREFIX = "fintrack_pat_";

    private final ApiTokenService apiTokenService;
    private final PatEndpointPolicy patEndpointPolicy;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private final Map<Long, Bucket> tokenBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!appProperties.getPat().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = extractPatToken(request);
        if (rawToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth != null
                && currentAuth.isAuthenticated()
                && !(currentAuth instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<ApiToken> tokenOpt = apiTokenService.authenticate(rawToken);
        if (tokenOpt.isEmpty()) {
            // Leave unauthenticated — anyRequest().authenticated() + the entry point returns 401,
            // the same path as an invalid JWT, without revealing whether the token ever existed.
            filterChain.doFilter(request, response);
            return;
        }

        ApiToken token = tokenOpt.get();

        RateLimitDecision rateLimit = tryConsume(token.getId());
        if (!rateLimit.allowed()) {
            response.setHeader("Retry-After", Long.toString(rateLimit.retryAfterSeconds()));
            writeRateLimitError(response, "Rate limit exceeded for this token",
                    request.getRequestURI(), rateLimit.retryAfterSeconds());
            return;
        }

        if (!patEndpointPolicy.isAllowed(request.getMethod(), request.getRequestURI(), token.getScope())) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                    "Token scope does not permit this operation", request.getRequestURI());
            return;
        }

        UserPrincipal principal = new UserPrincipal(token.getUser(), AuthMethod.PAT, token.getId());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private String extractPatToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer " + TOKEN_PREFIX)) {
            return header.substring(7);
        }
        return null;
    }

    private RateLimitDecision tryConsume(Long tokenId) {
        Bucket bucket = tokenBuckets.computeIfAbsent(tokenId, id -> createBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long retryAfterSeconds = probe.isConsumed()
                ? 0
                : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        return new RateLimitDecision(probe.isConsumed(), retryAfterSeconds);
    }

    private Bucket createBucket() {
        int limit = appProperties.getPat().getRequestsPerMinute();
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private void writeJsonError(HttpServletResponse response, int status, String error,
                                 String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiError.of(status, error, message, path)));
    }

    private void writeRateLimitError(HttpServletResponse response, String message,
                                     String path, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiError.rateLimited(message, path, retryAfterSeconds)));
    }

    private record RateLimitDecision(boolean allowed, long retryAfterSeconds) {}
}
