package com.fintrack.audit.interceptor;

import com.fintrack.audit.service.ActivityRecorder;
import com.fintrack.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Set;

/**
 * Fires a best-effort audit event after every successful authenticated mutation
 * (POST/PUT/DELETE that returned 2xx). Existing services are never touched.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBean(ActivityRecorder.class)
public class ActivityAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "DELETE");

    private final ActivityRecorder recorder;

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {

        if (!MUTATION_METHODS.contains(request.getMethod())) return;
        if (response.getStatus() < 200 || response.getStatus() >= 300) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return;

        String action = resolveAction(request.getMethod(), request.getRequestURI());
        String correlationId = MDC.get("correlationId");

        recorder.record(principal.getUserId(), action, correlationId,
                Map.of("method", request.getMethod(),
                       "uri",    request.getRequestURI(),
                       "status", response.getStatus()));
    }

    /** Derives a human-readable action string from method + URI path segments. */
    private String resolveAction(String method, String uri) {
        // e.g. /api/v1/accounts/42 -> "account"
        String[] parts = uri.replaceAll("/api/v\\d+/", "").split("/");
        String resource = parts.length > 0 ? parts[0] : "resource";

        return switch (method) {
            case "POST"   -> resource + ".created";
            case "PUT"    -> resource + ".updated";
            case "DELETE" -> resource + ".deleted";
            default       -> resource + ".mutated";
        };
    }
}
