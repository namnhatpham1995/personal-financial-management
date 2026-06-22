package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record ConvertedTrendDto(
        int year,
        int month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net
) {}
