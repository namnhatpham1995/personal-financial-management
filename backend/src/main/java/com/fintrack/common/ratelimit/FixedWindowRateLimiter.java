package com.fintrack.common.ratelimit;

import com.fintrack.common.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed one-minute-window request counter shared by the auth (per client IP) and PAT (per token)
 * rate-limit filters.
 *
 * <p>With {@code app.rate-limit.redis-enabled} the counter lives in Redis ({@code INCR} + TTL), so
 * the limit holds across replicas and old keys expire on their own. Otherwise (local dev, tests),
 * or whenever Redis errors, a per-instance map is used; the whole map is replaced when the window
 * rolls over, so it holds at most one window's worth of keys.
 *
 * <p>ponytail: fixed window lets a client burst up to 2x the limit across a window boundary;
 * acceptable for brute-force throttling, switch to a sliding window if exact limits matter.
 */
@Slf4j
@Component
public class FixedWindowRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long WINDOW_MS = WINDOW.toMillis();

    private final StringRedisTemplate redis;   // null when Redis-backed limiting is disabled
    private final Clock clock;

    private volatile long localWindow = -1;
    private volatile ConcurrentHashMap<String, AtomicInteger> localCounts = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(AppProperties appProperties,
                                  ObjectProvider<StringRedisTemplate> redis,
                                  Clock clock) {
        this.redis = appProperties.getRateLimit().isRedisEnabled() ? redis.getIfAvailable() : null;
        this.clock = clock;
    }

    /**
     * Counts one request against {@code key}.
     *
     * @return 0 when the request is allowed, otherwise the seconds until the window resets
     *         (suitable for a {@code Retry-After} header)
     */
    public long tryAcquire(String key, int limit) {
        long nowMs = clock.millis();
        long window = nowMs / WINDOW_MS;
        if (increment(key, window) <= limit) {
            return 0;
        }
        return Math.max(1, (WINDOW_MS - nowMs % WINDOW_MS + 999) / 1000);
    }

    private long increment(String key, long window) {
        if (redis != null) {
            try {
                String redisKey = "ratelimit:" + key + ":" + window;
                Long count = redis.opsForValue().increment(redisKey);
                if (count != null) {
                    if (count == 1) {
                        redis.expire(redisKey, WINDOW);
                    }
                    return count;
                }
            } catch (DataAccessException e) {
                log.warn("Rate-limit store unavailable, falling back to per-instance counter: {}", e.getMessage());
            }
        }
        return localIncrement(key, window);
    }

    private long localIncrement(String key, long window) {
        if (window != localWindow) {
            synchronized (this) {
                if (window != localWindow) {
                    localCounts = new ConcurrentHashMap<>();
                    localWindow = window;
                }
            }
        }
        return localCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }
}
