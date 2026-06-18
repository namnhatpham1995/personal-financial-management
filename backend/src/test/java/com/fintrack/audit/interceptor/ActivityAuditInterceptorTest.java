package com.fintrack.audit.interceptor;

import com.fintrack.audit.service.ActivityRecorder;
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

    @Mock ActivityRecorder recorder;
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
    void afterCompletion_postWith201_recordsEvent() throws Exception {
        authenticateAs(7L);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
        when(response.getStatus()).thenReturn(201);

        interceptor.afterCompletion(request, response, null, null);

        verify(recorder).record(eq(7L), eq("accounts.created"), any(), any());
    }

    @Test
    void afterCompletion_deleteWith204_recordsEvent() throws Exception {
        authenticateAs(3L);
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts/5");
        when(response.getStatus()).thenReturn(204);

        interceptor.afterCompletion(request, response, null, null);

        verify(recorder).record(eq(3L), eq("accounts.deleted"), any(), any());
    }

    @Test
    void afterCompletion_getRequest_doesNotRecord() throws Exception {
        authenticateAs(1L);
        when(request.getMethod()).thenReturn("GET");

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(recorder);
    }

    @Test
    void afterCompletion_postWith400_doesNotRecord() throws Exception {
        authenticateAs(1L);
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(400);

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(recorder);
    }

    @Test
    void afterCompletion_noAuthentication_doesNotRecord() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(response.getStatus()).thenReturn(201);
        // SecurityContext is empty — unauthenticated request

        interceptor.afterCompletion(request, response, null, null);

        verifyNoInteractions(recorder);
    }
}
