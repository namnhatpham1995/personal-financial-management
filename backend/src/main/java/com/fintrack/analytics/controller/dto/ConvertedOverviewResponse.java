package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ConvertedOverviewResponse(
        String targetCurrency,
        List<ConvertedSpendingResponse> spending,
        List<ConvertedTrendResponse> trend,
        List<RateUsedResponse> rates,
        Instant asOf,
        boolean ratesUnavailable,
        boolean stale,
        List<ExcludedCurrencyResponse> excludedCurrencies
) {}
