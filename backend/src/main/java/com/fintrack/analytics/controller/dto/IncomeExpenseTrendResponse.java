package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record IncomeExpenseTrendResponse(
        String currency,
        int year,
        int month,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal net
) {}
