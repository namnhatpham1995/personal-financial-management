package com.fintrack.common.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link CacheConfig.LoggingCacheErrorHandler} swallows all cache exceptions
 * without rethrowing them. This is the contract that keeps a Redis outage from surfacing
 * as HTTP 500 — the service degrades to direct-DB reads instead.
 */
class CacheErrorHandlerTest {

    private final CacheConfig.LoggingCacheErrorHandler handler =
            new CacheConfig.LoggingCacheErrorHandler();

    private Cache cache;
    private RuntimeException ex;

    @BeforeEach
    void setUp() {
        cache = mock(Cache.class);
        when(cache.getName()).thenReturn("analytics");
        ex = new RuntimeException("Redis connection refused");
    }

    @Test
    void handleCacheGetError_doesNotThrow() {
        assertThatNoException()
                .isThrownBy(() -> handler.handleCacheGetError(ex, cache, "someKey"));
    }

    @Test
    void handleCachePutError_doesNotThrow() {
        assertThatNoException()
                .isThrownBy(() -> handler.handleCachePutError(ex, cache, "someKey", "someValue"));
    }

    @Test
    void handleCacheEvictError_doesNotThrow() {
        assertThatNoException()
                .isThrownBy(() -> handler.handleCacheEvictError(ex, cache, "someKey"));
    }

    @Test
    void handleCacheClearError_doesNotThrow() {
        assertThatNoException()
                .isThrownBy(() -> handler.handleCacheClearError(ex, cache));
    }
}
