package com.fintrack.common.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory version counter for local development and tests.
 * Active when {@code spring.cache.type=simple} (or when the property is absent).
 */
@Service("cacheVersionService")
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "simple", matchIfMissing = true)
public class InMemoryCacheVersionService implements CacheVersionService {

    private final ConcurrentHashMap<Long, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public long current(Long userId) {
        return versions.computeIfAbsent(userId, k -> new AtomicLong(0)).get();
    }

    @Override
    public void bump(Long userId) {
        versions.computeIfAbsent(userId, k -> new AtomicLong(0)).incrementAndGet();
    }
}
