package com.fintrack.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long accessTokenExpiryMs = 900_000L;    // 15 min
        private long refreshTokenExpiryMs = 604_800_000L; // 7 days
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
}
