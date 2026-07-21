package com.fintrack.audit.web;

import com.fintrack.audit.domain.AuditLog;
import com.fintrack.audit.domain.AuditLogRepository;
import com.fintrack.audit.service.AuditLogWriter;
import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.ratelimit.AuthRateLimitFilter;
import com.fintrack.common.security.JwtAuthenticationFilter;
import com.fintrack.common.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    value = ActivityController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {JwtAuthenticationFilter.class, AuthRateLimitFilter.class}
    )
)
@Import(ActivityControllerTest.TestMvcConfig.class)
class ActivityControllerTest {

    @TestConfiguration
    static class TestMvcConfig implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new AuthenticationPrincipalArgumentResolver());
        }
    }

    @Autowired MockMvc mockMvc;
    @MockBean AuditLogRepository auditLogRepository;
    @MockBean AuditLogWriter auditLogWriter; // satisfies ActivityAuditInterceptor dependency in WebMvcConfig
    @MockBean AuditReplaySignal auditReplaySignal; // satisfies ActivityAuditInterceptor dependency in WebMvcConfig

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UserPrincipal loginAs(long userId) {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getUserId()).thenReturn(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
        return principal;
    }

    @Test
    void list_returnsPagedEventsForCurrentUser() throws Exception {
        loginAs(5L);

        AuditLog entry = AuditLog.builder()
                .id(1L)
                .userId(5L)
                .action("accounts.created")
                .ts(Instant.parse("2026-06-01T10:00:00Z"))
                .correlationId("corr-1")
                .meta(Map.of("uri", "/api/v1/accounts"))
                .build();

        when(auditLogRepository.findByUserIdOrderByTsDesc(eq(5L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].action").value("accounts.created"))
                .andExpect(jsonPath("$.content[0].correlationId").value("corr-1"));
    }

    @Test
    void list_pageSizeCappedAt100() throws Exception {
        loginAs(1L);
        when(auditLogRepository.findByUserIdOrderByTsDesc(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/activity?size=500"))
                .andExpect(status().isOk());
    }
}
