package com.fintrack.support;

import com.fintrack.exchangerate.provider.ExchangeRateProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Deterministic ExchangeRateProvider for integration tests that need currency
 * conversion without live HTTP calls. Rates are relative to the app's configured
 * base currency (USD, per application.yml exchange-rate.base).
 *
 * ExchangeRateService.convert() triggers self.getRates(base) -> refresh(base) on
 * first use per base per test class, which calls this stub and upserts the
 * result into the real exchange_rates table — only the outbound HTTP is faked.
 */
@TestConfiguration
public class ExchangeRateProviderTestStub {

    @Bean
    @Primary
    public ExchangeRateProvider exchangeRateProvider() {
        return base -> new ExchangeRateProvider.Result(
                base,
                Instant.now(),
                Map.of(
                        "USD", new BigDecimal("1"),
                        "VND", new BigDecimal("30000"),
                        "EUR", new BigDecimal("0.85")
                )
        );
    }
}
