package com.fintrack.analytics.service;

import com.fintrack.common.cache.InMemoryCacheVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level verification of the per-user version-stamping strategy used as the
 * analytics cache key.  No Spring context required — the cache key logic is
 * exercised through the {@link InMemoryCacheVersionService} directly.
 *
 * <p>End-to-end cache hit/miss behaviour is covered by {@code RedisCacheRoundTripIT}.
 */
class AnalyticsCacheTest {

    private InMemoryCacheVersionService versionService;

    @BeforeEach
    void setUp() {
        versionService = new InMemoryCacheVersionService();
    }

    @Test
    void initialVersion_isZero() {
        assertThat(versionService.current(1L)).isEqualTo(0L);
    }

    @Test
    void bump_incrementsVersion() {
        versionService.bump(1L);
        assertThat(versionService.current(1L)).isEqualTo(1L);
    }

    @Test
    void multipleBumps_incrementCumulatively() {
        versionService.bump(1L);
        versionService.bump(1L);
        versionService.bump(1L);
        assertThat(versionService.current(1L)).isEqualTo(3L);
    }

    @Test
    void bumpUser1_doesNotAffectUser2() {
        versionService.bump(1L);
        versionService.bump(1L);

        assertThat(versionService.current(2L)).isEqualTo(0L);
    }

    @Test
    void differentUsers_maintainIndependentCounters() {
        versionService.bump(1L);
        versionService.bump(2L);
        versionService.bump(2L);

        assertThat(versionService.current(1L)).isEqualTo(1L);
        assertThat(versionService.current(2L)).isEqualTo(2L);
    }

    @Test
    void cacheKeyDiffersAfterBump() {
        long userId = 42L;
        String keyBefore = buildKey(userId, "USD");
        versionService.bump(userId);
        String keyAfter = buildKey(userId, "USD");

        assertThat(keyBefore).isNotEqualTo(keyAfter);
    }

    @Test
    void cacheKeyIsolatedBetweenUsers_forSameParams() {
        // Bump user 1 twice; user 2 never bumped — keys must differ
        versionService.bump(1L);
        versionService.bump(1L);

        assertThat(buildKey(1L, "USD")).isNotEqualTo(buildKey(2L, "USD"));
    }

    /** Mirrors the SpEL expression on AnalyticsService.getOverview. */
    private String buildKey(Long userId, String targetCurrency) {
        return userId + ":v" + versionService.current(userId) + ":overview:" + targetCurrency + ":2024-01-01:2024-12-31";
    }
}
