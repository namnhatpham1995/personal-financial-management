package com.fintrack.common.ratelimit;

import com.fintrack.common.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Two limiter instances sharing one Redis behave like two replicas sharing one limit. */
@Testcontainers
class FixedWindowRateLimiterRedisTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Test
    void replicasShareOneCounter_andKeysExpire() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        AppProperties props = new AppProperties();
        props.getRateLimit().setRedisEnabled(true);
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:30Z"), ZoneOffset.UTC);
        var provider = new StaticListableBeanFactory(Map.of("redis", template))
                .getBeanProvider(StringRedisTemplate.class);

        FixedWindowRateLimiter replicaA = new FixedWindowRateLimiter(props, provider, clock);
        FixedWindowRateLimiter replicaB = new FixedWindowRateLimiter(props, provider, clock);

        assertThat(replicaA.tryAcquire("auth:1.2.3.4", 2)).isZero();
        assertThat(replicaB.tryAcquire("auth:1.2.3.4", 2)).isZero();
        assertThat(replicaA.tryAcquire("auth:1.2.3.4", 2)).isEqualTo(30);

        String key = template.keys("ratelimit:auth:1.2.3.4:*").iterator().next();
        assertThat(template.getExpire(key)).isPositive();
        factory.destroy();
    }
}
