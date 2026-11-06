package com.fintrack.auth.service;

import com.fintrack.auth.domain.AuthSession;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.AuthSessionRepository;
import com.fintrack.common.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionService {

    /**
     * Minimum gap between {@code last_activity_at} writes. Without it every authenticated
     * request (even a dashboard read) issues an UPDATE. Against a 24 h idle timeout, recording
     * activity up to a minute late changes nothing a user can notice.
     */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final AuthSessionRepository repository;
    private final AppProperties appProperties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Transactional
    public AuthSession start(User user) {
        Instant now = clock.instant();
        AuthSession session = AuthSession.builder()
                .user(user)
                .startedAt(now)
                .lastActivityAt(now)
                .absoluteExpiresAt(now.plusMillis(appProperties.getJwt().getSessionAbsoluteTimeoutMs()))
                .build();
        return repository.save(session);
    }

    @Transactional(readOnly = true, noRollbackFor = BadCredentialsException.class)
    public AuthSession requireActive(Long sessionId, Long userId) {
        AuthSession session = repository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> expired("unknown"));
        Instant now = clock.instant();
        if (!session.isActive(now, appProperties.getJwt().getSessionIdleTimeoutMs())) {
            meterRegistry.counter("auth.session.expired", "reason", expiryReason(session, now)).increment();
            log.info("Browser authentication session expired: sessionId={}, userId={}, reason={}",
                    sessionId, userId, expiryReason(session, now));
            throw expired(expiryReason(session, now));
        }
        return session;
    }

    /** Validates and records one accepted JWT-authenticated request. */
    @Transactional
    public boolean authenticateAndTouch(Long sessionId, Long userId) {
        AuthSession session = repository.findByIdAndUserId(sessionId, userId).orElse(null);
        if (session == null) return false;
        Instant now = clock.instant();
        if (!session.isActive(now, appProperties.getJwt().getSessionIdleTimeoutMs())) return false;
        if (session.getLastActivityAt().isAfter(now.minus(TOUCH_INTERVAL))) return true;
        return repository.touchIfActive(sessionId, now,
                now.minusMillis(appProperties.getJwt().getSessionIdleTimeoutMs())) == 1;
    }

    @Transactional
    public void recordActivity(AuthSession session) {
        Instant now = clock.instant();
        if (!session.isActive(now, appProperties.getJwt().getSessionIdleTimeoutMs())) {
            throw expired(expiryReason(session, now));
        }
        repository.touchIfActive(session.getId(), now,
                now.minusMillis(appProperties.getJwt().getSessionIdleTimeoutMs()));
    }

    @Transactional
    public void revoke(Long sessionId) {
        repository.revoke(sessionId, clock.instant());
    }

    private BadCredentialsException expired(String reason) {
        return new BadCredentialsException("Authentication session is expired or revoked (" + reason + ")");
    }

    private String expiryReason(AuthSession session, Instant now) {
        if (session.getRevokedAt() != null) return "revoked";
        if (!now.isBefore(session.getAbsoluteExpiresAt())) return "absolute_expiry";
        return "idle_expiry";
    }
}
