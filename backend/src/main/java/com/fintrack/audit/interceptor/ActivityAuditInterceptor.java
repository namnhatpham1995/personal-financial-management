package com.fintrack.audit.interceptor;

import com.fintrack.audit.service.AuditLogWriter;
import com.fintrack.common.security.AuthMethod;
import com.fintrack.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Records one audit entry per authenticated mutation (POST/PUT/DELETE returning 2xx).
 * Capture happens in afterCompletion so we only write events for requests that actually
 * succeeded; the write goes to PostgreSQL via AuditLogWriter in its own transaction.
 */
@Component
@RequiredArgsConstructor
public class ActivityAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "DELETE");

    private final AuditLogWriter auditLogWriter;

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {

        if (!MUTATION_METHODS.contains(request.getMethod())) return;
        if (response.getStatus() < 200 || response.getStatus() >= 300) return;
        String uri = request.getRequestURI();
        if (uri == null) return;
        // Auth endpoints mostly create/validate credentials, where the principal isn't the
        // acting user (register has none yet; login/refresh/logout act on tokens, not profile
        // data) — excluded from the audit trail. /auth/me/language is a genuine authenticated
        // profile mutation by the acting user, same shape as any other audited write, so it's
        // exempted from the exclusion.
        if (uri.startsWith("/api/v1/auth/") && !uri.equals("/api/v1/auth/me/language")) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return;

        String action = resolveAction(request.getMethod(), uri);
        String correlationId = MDC.get("correlationId");

        // status omitted from meta: capture fires after HTTP response is already sent,
        // and the action name already conveys what happened.
        Map<String, Object> meta = new HashMap<>();
        meta.put("method", request.getMethod());
        meta.put("uri", uri);
        if (principal.getAuthMethod() == AuthMethod.PAT) {
            meta.put("auth", "pat");
            meta.put("tokenId", principal.getApiTokenId());
        }
        auditLogWriter.write(principal.getUserId(), action, correlationId, meta);
    }

    private String resolveAction(String method, String uri) {
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
