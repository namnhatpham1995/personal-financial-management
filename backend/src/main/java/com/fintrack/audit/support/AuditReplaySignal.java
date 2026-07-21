package com.fintrack.audit.support;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Request-scoped signal that lets idempotent-mutation code tell {@code ActivityAuditInterceptor}
 * (which runs later, in {@code afterCompletion}) that the current HTTP request's business effect
 * was a replay of a prior operation rather than a fresh mutation, and/or attach a non-secret
 * operation reference for audit correlation.
 *
 * <p>Backed by a servlet request attribute rather than a field on this singleton bean, so it is
 * safe under concurrent requests. Every idempotency/vault code path that calls this class runs
 * synchronously on the request-handling thread all the way down to the interceptor, so
 * {@link RequestContextHolder}'s thread-bound {@link RequestAttributes} is available the whole
 * time without ever crossing a thread boundary.
 *
 * <p>Never store a raw idempotency key, request/response body, PAT, or refresh token through this
 * class — only non-secret operation identifiers (e.g. an {@code idempotency_operations.id} or
 * {@code vault_operations} id) belong here.
 */
@Component
public class AuditReplaySignal {

    private static final String REPLAYED_ATTR = "com.fintrack.audit.replayed";
    private static final String OPERATION_REF_ATTR = "com.fintrack.audit.operationRef";

    /** Marks the current request's business effect as a replay; the interceptor will not audit it. */
    public void markReplayed() {
        setAttribute(REPLAYED_ATTR, Boolean.TRUE);
    }

    /** Attaches a non-secret operation reference to the current request's eventual audit entry. */
    public void setOperationReference(String reference) {
        if (reference != null) {
            setAttribute(OPERATION_REF_ATTR, reference);
        }
    }

    public boolean isReplayed() {
        return Boolean.TRUE.equals(getAttribute(REPLAYED_ATTR));
    }

    public String getOperationReference() {
        Object value = getAttribute(OPERATION_REF_ATTR);
        return value == null ? null : value.toString();
    }

    private void setAttribute(String name, Object value) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private Object getAttribute(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
    }
}
