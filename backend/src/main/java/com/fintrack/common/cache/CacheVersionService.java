package com.fintrack.common.cache;

/**
 * Tracks a per-user version counter for the analytics cache.
 *
 * <p>The cache key embeds {@link #current(Long)} so bumping the version on any
 * write makes all old keys stale (orphaned) without touching other users' entries.
 * Stale entries are reclaimed naturally by the TTL.
 *
 * <p>Two implementations are wired by profile:
 * <ul>
 *   <li>{@link InMemoryCacheVersionService} — local / test (no Redis needed)</li>
 *   <li>{@link RedisCacheVersionService} — prod / docker (shared across instances)</li>
 * </ul>
 */
public interface CacheVersionService {

    /** Returns the current version for {@code userId}. Defaults to 0 when unknown. */
    long current(Long userId);

    /** Increments the version for {@code userId}, orphaning all cached analytics keys for that user. */
    void bump(Long userId);
}
