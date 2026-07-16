package com.fintrack.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.agent.service.AgentTokenService;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.dto.ApiError;
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
import java.util.Optional;

/**
 * Authenticates requests bearing an agent-scoped token (design.md decision D3). Runs before
 * {@link JwtAuthenticationFilter} and — critically — owns every token carrying a
 * {@code scope=agent} claim outright: on success it sets the authentication and continues the
 * chain; on any failure (wrong run, wrong user, endpoint not allowlisted) it writes the error
 * response itself and stops. Either way the request never falls through to normal JWT auth,
 * so a scoped agent token can never be reused as a full-session credential.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    private final AgentTokenService agentTokenService;
    private final AgentEndpointPolicy agentEndpointPolicy;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AgentTokenService.AgentTokenClaims> claimsOpt = agentTokenService.tryParse(token);
        if (claimsOpt.isEmpty()) {
            // Not an agent-scoped token (or invalid) — let JwtAuthenticationFilter evaluate it.
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

        AgentTokenService.AgentTokenClaims claims = claimsOpt.get();

        if (!agentEndpointPolicy.isAllowed(request.getMethod(), request.getRequestURI(), claims.userId(), claims.runId())) {
            writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                    "Agent token scope does not permit this operation", request.getRequestURI());
            return;
        }

        Optional<User> userOpt = userRepository.findByEmail(claims.subject());
        if (userOpt.isEmpty() || !userOpt.get().getId().equals(claims.userId())) {
            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                    "Agent token subject not found", request.getRequestURI());
            return;
        }

        UserPrincipal principal = new UserPrincipal(userOpt.get(), AuthMethod.AGENT, null, claims.runId());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void writeJsonError(HttpServletResponse response, int status, String error,
                                 String message, String path) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                objectMapper.writeValueAsString(ApiError.of(status, error, message, path)));
    }
}
