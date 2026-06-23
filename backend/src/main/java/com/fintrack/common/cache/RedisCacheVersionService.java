package com.fintrack.common.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed version counter for production — shared across instances.
 * Active when {@code spring.cache.type=redis}.
 *
 * <p>Uses {@code INCR analytics:ver:{userId}} so each bump is atomic.
 * On Redis failure, {@code current()} returns 0 (cache-miss path) and
 * {@code bump()} logs a warning and returns silently — the next read simply
 * bypasses the stale cache and hits the DB, which is the correct degraded behaviour.
 */
@Slf4j
@Service("cacheVersionService")
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
@RequiredArgsConstructor
public class RedisCacheVersionService implements CacheVersionService {

    private static final String KEY_PREFIX = "analytics:ver:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public long current(Long userId) {
        try {
            String val = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            return val != null ? Long.parseLong(val) : 0L;
        } catch (Exception e) {
            log.warn("Failed to read cache version for user {}: {}", userId, e.getMessage());
            return 0L;
        }
    }

    @Override
    public void bump(Long userId) {
        try {
            redisTemplate.opsForValue().increment(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("Failed to bump cache version for user {}: {}", userId, e.getMessage());
        }
    }
}
