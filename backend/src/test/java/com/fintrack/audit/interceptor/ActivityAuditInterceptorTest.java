package com.fintrack.audit.interceptor;

import com.fintrack.audit.service.AuditLogWriter;
import com.fintrack.common.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityAuditInterceptorTest {

    @Mock AuditLogWriter auditLogWriter;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock UserPrincipal principal;

    @InjectMocks ActivityAuditInterceptor interceptor;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(long userId) {
        when(principal.getUserId()).thenReturn(userId);
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
    void afterCompletion_updateLanguage_writesAuditEntry() throws Exception {
        authenticateAs(9L);
        when(request.getMethod()).thenReturn("PUT");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/me/language");
        when(response.getStatus()).thenReturn(200);

        interceptor.afterCompletion(request, response, null, null);

        verify(auditLogWriter).write(eq(9L), eq("auth.updated"), any(), any());
    }
}
