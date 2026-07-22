package com.fintrack.audit.interceptor;

import com.fintrack.audit.service.AuditLogWriter;
import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityAuditInterceptorTest {

    @Mock AuditLogWriter auditLogWriter;
    @Mock AuditReplaySignal auditReplaySignal;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock UserPrincipal principal;

    @InjectMocks ActivityAuditInterceptor interceptor;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long userId) {
        // lenient: some tests authenticate a principal but the interceptor short-circuits before
        // ever reading it (e.g. a replay signal or a non-2xx status), which would otherwise trip
        // Mockito's strict-stubbing unnecessary-stub check.
        lenient().when(principal.getUserId()).thenReturn(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    @Test
    void afterCompletion_postWith201_writesAuditEntry() throws Exception {
        authenticateAs(7L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        verify(auditLogWriter).write(eq(7L), eq("accounts.created"), any(), any());
    }

    @Test
    void afterCompletion_deleteWith204_writesAuditEntry() throws Exception {
        authenticateAs(3L);
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts/5");
        when(response.getStatus()).thenReturn(204);

        interceptor.afterCompletion(request, response, null, null);

        verify(auditLogWriter).write(eq(3L), eq("accounts.deleted"), any(), any());
    }

    @Test
    void afterCompletion_getRequest_doesNotWrite() throws Exception {
        when(request.getMethod()).thenReturn("GET");

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void afterCompletion_postWith400_doesNotWrite() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(400);

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void afterCompletion_noAuthentication_doesNotWrite() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void afterCompletion_authRegister_doesNotWrite() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/register");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void afterCompletion_vaultUpload_derivesVaultResource() throws Exception {
        authenticateAs(12L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/vault/upload");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        verify(auditLogWriter).write(eq(12L), eq("vault.created"), any(), any());
    }

    @Test
    void afterCompletion_vaultImportStatement_derivesVaultResource() throws Exception {
        authenticateAs(13L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/vault/import/statements");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        verify(auditLogWriter).write(eq(13L), eq("vault.created"), any(), any());
    }

    @Test
    void afterCompletion_updateLanguage_writesAuditEntry() throws Exception {
        authenticateAs(9L);
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/me/language");
        when(response.getStatus()).thenReturn(200);

        interceptor.afterCompletion(request, response, null, null);

        verify(auditLogWriter).write(eq(9L), eq("auth.updated"), any(), any());
    }

    // ── 9.1/9.2: idempotent-replay audit dedup ──────────────────────────────────

    @Test
    void afterCompletion_replaySignaled_doesNotWriteSecondEvent() throws Exception {
        // The URI must be stubbed (a real request always has one) so this test actually reaches
        // and exercises the replay-signal short-circuit, rather than vacuously passing because of
        // the earlier null-URI guard.
        authenticateAs(7L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(response.getStatus()).thenReturn(201);
        when(auditReplaySignal.isReplayed()).thenReturn(true);

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void afterCompletion_operationReferencePresent_includedInMeta() throws Exception {
        authenticateAs(7L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(response.getStatus()).thenReturn(201);
        when(auditReplaySignal.getOperationReference()).thenReturn("42");

        interceptor.afterCompletion(request, response, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogWriter).write(eq(7L), eq("accounts.created"), any(), metaCaptor.capture());
        assertThat(metaCaptor.getValue()).containsEntry("operationRef", "42");
    }

    @Test
    void afterCompletion_noOperationReference_metaOmitsKey() throws Exception {
        authenticateAs(7L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogWriter).write(eq(7L), eq("accounts.created"), any(), metaCaptor.capture());
        assertThat(metaCaptor.getValue()).doesNotContainKey("operationRef");
    }

    /**
     * Same-key-changed-payload retries surface as a 409 from the idempotency layer. The
     * interceptor only fires on 2xx (see the status-range guard in
     * {@code ActivityAuditInterceptor#afterCompletion}), so a 409 must never produce an audit
     * entry — explicit coverage rather than assuming the generic {@code postWith400} case
     * generalizes to every non-2xx status.
     */
    @Test
    void afterCompletion_postWith409Conflict_doesNotWrite() throws Exception {
        authenticateAs(7L);
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(409);

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void afterCompletion_patAuthWithOperationReference_bothPresentInMeta() throws Exception {
        authenticateAs(11L);
        when(principal.getAuthMethod()).thenReturn(com.fintrack.common.security.AuthMethod.PAT);
        when(principal.getApiTokenId()).thenReturn(99L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(response.getStatus()).thenReturn(201);
        when(auditReplaySignal.getOperationReference()).thenReturn("77");

        interceptor.afterCompletion(request, response, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditLogWriter).write(eq(11L), eq("accounts.created"), any(), metaCaptor.capture());
        Map<String, Object> meta = metaCaptor.getValue();
        assertThat(meta).containsEntry("auth", "pat");
        assertThat(meta).containsEntry("tokenId", 99L);
        assertThat(meta).containsEntry("operationRef", "77");
        // No raw idempotency key, request/response body, PAT, or refresh token is ever placed in
        // audit meta — only the non-secret method/uri/auth/tokenId/operationRef fields.
        assertThat(meta.keySet()).containsExactlyInAnyOrder("method", "uri", "auth", "tokenId", "operationRef");
    }
}
