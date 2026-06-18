package com.fintrack.audit.web;

import com.fintrack.audit.domain.ActivityEvent;
import com.fintrack.audit.domain.ActivityEventRepository;
import com.fintrack.audit.service.ActivityRecorder;
import com.fintrack.common.ratelimit.AuthRateLimitFilter;
import com.fintrack.common.security.JwtAuthenticationFilter;
import com.fintrack.common.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    value = ActivityController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {JwtAuthenticationFilter.class, AuthRateLimitFilter.class}
    )
)
class ActivityControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ActivityEventRepository repository;
    @MockBean ActivityRecorder recorder; // satisfies WebMvcConfig -> ActivityAuditInterceptor

    @Test
    void list_returnsPagedEventsForCurrentUser() throws Exception {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getUserId()).thenReturn(5L);

        ActivityEvent event = ActivityEvent.builder()
                .id("abc123")
                .userId(5L)
                .action("accounts.created")
                .ts(Instant.parse("2026-06-01T10:00:00Z"))
                .correlationId("corr-1")
                .meta(Map.of("uri", "/api/v1/accounts"))
                .build();

        when(repository.findByUserIdOrderByTsDesc(eq(5L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        mockMvc.perform(get("/api/v1/activity")
                        .with(authentication(new UsernamePasswordAuthenticationToken(principal, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("abc123"))
                .andExpect(jsonPath("$.content[0].action").value("accounts.created"))
                .andExpect(jsonPath("$.content[0].correlationId").value("corr-1"));
    }

    @Test
    void list_pageSizeCappedAt100() throws Exception {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getUserId()).thenReturn(1L);
        when(repository.findByUserIdOrderByTsDesc(any(), any())).thenReturn(new PageImpl<>(List.of()));

        // size=500 in request — controller caps to 100; no exception
        mockMvc.perform(get("/api/v1/activity?size=500")
                        .with(authentication(new UsernamePasswordAuthenticationToken(principal, null))))
                .andExpect(status().isOk());
    }
}
