package com.fintrack.analytics.web.dto;

import com.fintrack.budget.domain.BudgetPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BudgetHistoryDto(
        Long budgetId,
        Long categoryId,
        String categoryName,
        BudgetPeriod period,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        BigDecimal amountLimit,
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal percentUsed,
        boolean overBudget
) {}
