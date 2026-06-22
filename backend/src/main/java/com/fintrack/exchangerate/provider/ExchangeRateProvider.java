package com.fintrack.exchangerate.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Contract for fetching live exchange rates from an external data source.
 * Implementations must be thread-safe.
 */
public interface ExchangeRateProvider {

    /**
     * Fetches the latest rates for all currencies relative to {@code base}.
     *
     * @param base ISO 4217 base currency code, e.g. "USD"
     * @return result containing rates and the provider's last-update timestamp
     * @throws com.fintrack.exchangerate.exception.ExchangeRateUnavailableException if the fetch fails
     */
    Result fetchLatest(String base);

    /**
     * Immutable result from a successful provider fetch.
     *
     * @param baseCode the base currency used in the request
     * @param asOf     when the provider last updated these rates
     * @param rates    map from quote currency code to rate (units of quote per 1 base)
     */
    record Result(String baseCode, Instant asOf, Map<String, BigDecimal> rates) {}
}
