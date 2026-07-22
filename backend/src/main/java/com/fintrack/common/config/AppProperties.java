package com.fintrack.common.config;

import com.fintrack.idempotency.domain.IdempotencyMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private RateLimit rateLimit = new RateLimit();
    private ExchangeRate exchangeRate = new ExchangeRate();
    private Pat pat = new Pat();
    private Agent agent = new Agent();
    private Idempotency idempotency = new Idempotency();

    @PostConstruct
    void validate() {
        jwt.validateSessionTimeouts();
    }

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long accessTokenExpiryMs = 900_000L;    // 15 min
        private long refreshTokenExpiryMs = 604_800_000L; // 7 days
        private long sessionIdleTimeoutMs = 86_400_000L; // 24 hours
        private long sessionAbsoluteTimeoutMs = 2_592_000_000L; // 30 days

        void validateSessionTimeouts() {
            if (sessionIdleTimeoutMs <= 0 || sessionAbsoluteTimeoutMs <= 0) {
                throw new IllegalStateException("JWT session timeouts must be positive");
            }
            if (sessionAbsoluteTimeoutMs <= sessionIdleTimeoutMs) {
                throw new IllegalStateException("JWT absolute session timeout must exceed idle timeout");
            }
        }
    }

    @Getter
    @Setter
    public static class Cors {
        private String allowedOrigins = "http://localhost:3000";
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int authRequestsPerMinute = 10;
    }

    @Getter
    @Setter
    public static class ExchangeRate {
        /** ISO 4217 base currency all pairs are stored relative to (default: USD). */
        private String base = "USD";
        /** Hours before a cached rate set is refreshed on next access. */
        private int ttlHours = 24;
        /** Hours after which a cached rate set is considered stale and flagged in health checks. */
        private int staleHours = 48;
        /** Base URL of the exchange rate provider (path segment per currency appended at call time). */
        private String providerUrl = "https://open.er-api.com/v6/latest";
    }

    @Getter
    @Setter
    public static class Pat {
        /** Kill switch for PAT authentication — rollback path without a schema change. */
        private boolean enabled = true;
        private int requestsPerMinute = 60;
    }

    @Getter
    @Setter
    public static class Agent {
        /**
         * Base URL of the agent-service (e.g. http://agent-service:8081). Empty/blank means
         * the receipt ingestion feature is unconfigured — run-related endpoints report the
         * feature unavailable rather than affecting any other capability (dark by default).
         */
        private String serviceUrl = "";
        /** TTL of the per-run scoped token minted for the agent service (bounds a run). */
        private long tokenExpiryMs = 900_000L; // 15 min
    }

    @Getter
    @Setter
    public static class Idempotency {
        /**
         * Rollout mode for the {@code Idempotency-Key} header requirement on protected create
         * endpoints (see design.md Migration Plan step 5). Defaults to OBSERVE: missing keys are
         * allowed through — identical behavior to the pre-rollout baseline — but recorded via
         * metrics/logs so an operator can confirm official clients are sending keys before
         * deliberately opting into ENFORCE. Flipping this to ENFORCE for third-party API/MCP
         * callers outside this repo is an operator/product decision this default must not make
         * unilaterally.
         */
        private IdempotencyMode mode = IdempotencyMode.OBSERVE;
    }
}
