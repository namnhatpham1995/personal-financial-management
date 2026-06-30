package com.fintrack.exchangerate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving that {@link RateSnapshot} (a Java record) survives a full
 * Redis serialization/deserialization round-trip using the same
 * {@link Jackson2JsonRedisSerializer} + {@link ObjectMapper} configuration as production.
 *
 * <p>Java records are {@code final} classes: {@code GenericJackson2JsonRedisSerializer} with
 * {@code NON_FINAL} default typing omits {@code @class} for them, so a typed serializer that
 * knows the target class at deserialization time is required.  This test would fail if the
 * wrong serializer (e.g. the old {@code GenericJackson2JsonRedisSerializer} approach) were used.
 */
@Testcontainers
class RedisCacheRoundTripIT {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private Cache cache;

    @BeforeEach
    void setUp() {
        // Mirror the same ObjectMapper configuration as CacheConfig.redisObjectMapper()
        ObjectMapper om = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .build();

        Jackson2JsonRedisSerializer<RateSnapshot> serializer =
                new Jackson2JsonRedisSerializer<>(om, RateSnapshot.class);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();

        @SuppressWarnings({"unchecked", "rawtypes"})
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        (RedisSerializer<Object>) (RedisSerializer) serializer));

        RedisCacheManager cacheManager = RedisCacheManager.builder(factory)
                .cacheDefaults(config)
                .build();
        cacheManager.afterPropertiesSet();

        cache = cacheManager.getCache("exchangeRates");
    }

    @Test
    void rateSnapshot_survivesRedisRoundTrip_preservingAllFields() {
        RateSnapshot snapshot = new RateSnapshot(List.of(
                new RateSnapshot.RatePair("VND", new BigDecimal("25000")),
                new RateSnapshot.RatePair("EUR", new BigDecimal("0.92"))
        ));

        cache.put("USD", snapshot);
        RateSnapshot result = cache.get("USD", RateSnapshot.class);

        assertThat(result).isNotNull();
        assertThat(result.rates()).hasSize(2);
        assertThat(result.rates().get(0).quoteCode()).isEqualTo("VND");
        assertThat(result.rates().get(0).rate()).isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(result.rates().get(1).quoteCode()).isEqualTo("EUR");
        assertThat(result.rates().get(1).rate()).isEqualByComparingTo(new BigDecimal("0.92"));
    }

    @Test
    void emptyRates_roundTrip() {
        RateSnapshot empty = new RateSnapshot(List.of());

        cache.put("EMPTY", empty);
        RateSnapshot result = cache.get("EMPTY", RateSnapshot.class);

        assertThat(result).isNotNull();
        assertThat(result.rates()).isEmpty();
    }

    @Test
    void cacheMiss_returnsNull() {
        assertThat(cache.get("NONEXISTENT_KEY", RateSnapshot.class)).isNull();
    }
}
