package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ConvertedOverviewDto(
        String targetCurrency,
        BigDecimal netWorth,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        List<ConvertedSpendingDto> spending,
        List<ConvertedTrendDto> trend,
        List<RateUsedDto> rates,
        Instant asOf,
        boolean ratesUnavailable,
        boolean stale,
        List<ExcludedCurrencyDto> excludedCurrencies
) {}
