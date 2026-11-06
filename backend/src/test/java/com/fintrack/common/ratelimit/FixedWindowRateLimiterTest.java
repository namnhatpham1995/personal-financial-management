package com.fintrack.common.ratelimit;

import com.fintrack.common.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:10Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(new AppProperties(), null, clock);

    @Test
    void blocksAfterLimit_perKey_andResetsNextWindow() {
        assertThat(limiter.tryAcquire("a", 2)).isZero();
        assertThat(limiter.tryAcquire("a", 2)).isZero();
        assertThat(limiter.tryAcquire("a", 2)).isEqualTo(50);   // 50 s left in the minute
        assertThat(limiter.tryAcquire("b", 2)).isZero();        // independent key

        now.set(Instant.parse("2026-01-01T00:01:00Z"));
        assertThat(limiter.tryAcquire("a", 2)).isZero();        // new window, old counts dropped
    }

    @Test
    void clientIp_isRightmostForwardedEntry_notClientSuppliedLeftmost() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        assertThat(AuthRateLimitFilter.resolveClientIp(request)).isEqualTo("10.0.0.1");

        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertThat(AuthRateLimitFilter.resolveClientIp(request)).isEqualTo("203.0.113.7");
    }
}
