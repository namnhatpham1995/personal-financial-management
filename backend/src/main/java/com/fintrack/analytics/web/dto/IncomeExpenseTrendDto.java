package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record IncomeExpenseTrendDto(
        int year,
        int month,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal net
) {}
