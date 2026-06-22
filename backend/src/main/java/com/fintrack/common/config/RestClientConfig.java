package com.fintrack.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures a {@link RestClient} bean for outbound HTTP calls (e.g. exchange rate provider).
 * Uses {@link SimpleClientHttpRequestFactory} with explicit connect and read timeouts
 * to prevent unbounded blocking on slow or unresponsive external services.
 */
@Configuration
public class RestClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 10_000;

    @Bean
    public RestClient exchangeRateRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
