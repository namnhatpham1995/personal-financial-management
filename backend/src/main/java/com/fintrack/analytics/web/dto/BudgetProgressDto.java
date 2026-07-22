package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record BudgetProgressDto(
        Long budgetId,
        String budgetName,
        Long categoryId,
        String categoryName,
        String currency,
        BigDecimal limitAmount,
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal percentUsed,
        boolean overBudget
) {}
