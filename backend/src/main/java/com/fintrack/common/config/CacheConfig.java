package com.fintrack.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintrack.analytics.web.dto.ConvertedOverviewDto;
import com.fintrack.category.web.dto.CategoryResponse;
import com.fintrack.exchangerate.service.RateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String ANALYTICS      = "analytics";
    public static final String EXCHANGE_RATES = "exchangeRates";
    public static final String CATEGORIES     = "categories";

    private static final Duration ANALYTICS_TTL      = Duration.ofMinutes(10);
    private static final Duration EXCHANGE_RATES_TTL = Duration.ofHours(1);
    private static final Duration CATEGORIES_TTL     = Duration.ofHours(1);

    /**
     * Per-cache typed serializers — required because Java records are {@code final} classes.
     * {@code GenericJackson2JsonRedisSerializer} with {@code NON_FINAL} default typing omits
     * {@code @class} for final types, causing deserialization to return {@code LinkedHashMap}.
     * Using {@link Jackson2JsonRedisSerializer} with an explicit target type bypasses that
     * problem: deserialization calls {@code readValue(bytes, declaredType)} which works
     * correctly for records without any polymorphic type markers in the JSON.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        ObjectMapper om = redisObjectMapper();

        return builder -> builder
                .withCacheConfiguration(ANALYTICS,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(ANALYTICS_TTL)
                                .serializeValuesWith(pair(new Jackson2JsonRedisSerializer<>(om, ConvertedOverviewDto.class))))
                .withCacheConfiguration(EXCHANGE_RATES,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(EXCHANGE_RATES_TTL)
                                .serializeValuesWith(pair(new Jackson2JsonRedisSerializer<>(om, RateSnapshot.class))))
                .withCacheConfiguration(CATEGORIES,
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(CATEGORIES_TTL)
                                .serializeValuesWith(pair(new Jackson2JsonRedisSerializer<>(om,
                                        om.getTypeFactory().constructCollectionType(List.class, CategoryResponse.class)))));
    }

    /**
     * Swallows all Redis cache errors so a Redis outage degrades to direct-DB reads (HTTP 200)
     * rather than propagating as 500s.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    // ── ObjectMapper ──────────────────────────────────────────────────────────

    /**
     * Minimal mapper for Redis serialization: JavaTime support only, no polymorphic typing.
     * Typed serializers supply the target class at deserialization time so {@code @class}
     * markers in the JSON are neither needed nor written.
     */
    static ObjectMapper redisObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisSerializationContext.SerializationPair<Object> pair(
            Jackson2JsonRedisSerializer<?> serializer) {
        return RedisSerializationContext.SerializationPair.fromSerializer(
                (RedisSerializer<Object>) (RedisSerializer) serializer);
    }

    // ── Error handler ─────────────────────────────────────────────────────────

    static class LoggingCacheErrorHandler implements CacheErrorHandler {

        private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

        @Override
        public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
            log.warn("Cache GET error on '{}' key='{}': {}", cache.getName(), key, ex.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
            log.warn("Cache PUT error on '{}' key='{}': {}", cache.getName(), key, ex.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
            log.warn("Cache EVICT error on '{}' key='{}': {}", cache.getName(), key, ex.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException ex, Cache cache) {
            log.warn("Cache CLEAR error on '{}': {}", cache.getName(), ex.getMessage());
        }
    }
}
