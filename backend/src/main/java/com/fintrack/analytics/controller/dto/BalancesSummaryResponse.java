package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Balances summary enriched with a converted grand total, returned only when a target currency is requested. */
public record BalancesSummaryResponse(
        List<CurrencyBalanceResponse> buckets,
        String targetCurrency,
        BigDecimal convertedTotal,
        List<RateUsedResponse> rates,
        Instant asOf,
        boolean stale,
        boolean ratesUnavailable,
        List<ExcludedCurrencyResponse> excludedCurrencies
) {}
