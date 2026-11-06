package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record ConvertedTrendDto(
        int year,
        int month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net
) {}
