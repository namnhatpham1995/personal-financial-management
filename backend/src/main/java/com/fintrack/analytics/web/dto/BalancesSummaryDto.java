package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Balances summary enriched with a converted grand total, returned only when a target currency is requested. */
public record BalancesSummaryDto(
        List<CurrencyBalanceDto> buckets,
        String targetCurrency,
        BigDecimal convertedTotal,
        List<RateUsedDto> rates,
        Instant asOf,
        boolean stale,
        boolean ratesUnavailable,
        List<ExcludedCurrencyDto> excludedCurrencies
) {}
