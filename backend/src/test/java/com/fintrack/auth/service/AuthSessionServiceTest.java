package com.fintrack.auth.service;

import com.fintrack.auth.domain.AuthSession;
import com.fintrack.auth.repository.AuthSessionRepository;
import com.fintrack.common.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private AuthSessionRepository repository;
    private AuthSessionService service;
    private AuthSession session;

    @BeforeEach
    void setUp() {
        repository = mock(AuthSessionRepository.class);
        AppProperties properties = new AppProperties();
        properties.getJwt().setSessionIdleTimeoutMs(24 * 60 * 60 * 1000L);
        properties.getJwt().setSessionAbsoluteTimeoutMs(30 * 24 * 60 * 60 * 1000L);
        service = new AuthSessionService(repository, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        session = AuthSession.builder()
                .id(7L)
                .startedAt(NOW.minusSeconds(60))
                .lastActivityAt(NOW.minusSeconds(60))
                .absoluteExpiresAt(NOW.plusSeconds(60))
                .build();
    }

    @Test
    void requireActive_acceptsSessionBeforeBothDeadlines() {
        when(repository.findByIdAndUserId(7L, 9L)).thenReturn(Optional.of(session));

        assertThat(service.requireActive(7L, 9L)).isSameAs(session);
    }

    @Test
    void requireActive_rejectsAtIdleBoundary() {
        session.setLastActivityAt(NOW.minusMillis(24 * 60 * 60 * 1000L));
        when(repository.findByIdAndUserId(7L, 9L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.requireActive(7L, 9L))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void requireActive_rejectsAtAbsoluteBoundaryEvenWhenRecentlyActive() {
        session.setLastActivityAt(NOW.minusSeconds(1));
        session.setAbsoluteExpiresAt(NOW);
        when(repository.findByIdAndUserId(7L, 9L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.requireActive(7L, 9L))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateAndTouch_requiresActiveSessionAndUsesConditionalUpdate() {
        when(repository.findByIdAndUserId(7L, 9L)).thenReturn(Optional.of(session));
        when(repository.touchIfActive(eq(7L), eq(NOW), any(Instant.class))).thenReturn(1);

        assertThat(service.authenticateAndTouch(7L, 9L)).isTrue();
        verify(repository).touchIfActive(eq(7L), eq(NOW), any(Instant.class));

        session.setRevokedAt(NOW);
        assertThat(service.authenticateAndTouch(7L, 9L)).isFalse();
    }
}
