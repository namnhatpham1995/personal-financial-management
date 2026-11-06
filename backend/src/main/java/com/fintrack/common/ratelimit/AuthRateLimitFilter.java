package com.fintrack.common.ratelimit;

import com.fintrack.common.config.AppProperties;
import com.fintrack.common.exception.RateLimitException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-IP rate limiter on auth endpoints only — prevents brute-force attacks.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final AppProperties appProperties;
    private final FixedWindowRateLimiter rateLimiter;

    private static final String[] AUTH_PATHS = {
        "/api/v1/auth/login",
        "/api/v1/auth/register",
        "/api/v1/auth/refresh"
    };

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        if (!isAuthPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        int limit = appProperties.getRateLimit().getAuthRequestsPerMinute();
        if (rateLimiter.tryAcquire("auth:" + resolveClientIp(request), limit) > 0) {
            throw new RateLimitException("Too many requests. Please slow down.");
        }

        chain.doFilter(request, response);
    }

    private boolean isAuthPath(String uri) {
        for (String path : AUTH_PATHS) {
            if (uri.startsWith(path)) return true;
        }
        return false;
    }

    /**
     * The browser calls the API directly, so exactly one trusted proxy (the Railway edge) sits in
     * front of the app and appends the address it saw to {@code X-Forwarded-For}. Only that
     * rightmost entry is trustworthy; everything to its left is whatever the client sent, so
     * taking the leftmost entry would let a client pick its own rate-limit key.
     */
    static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
            if (!last.isEmpty()) return last;
        }
        return request.getRemoteAddr();
    }
}
