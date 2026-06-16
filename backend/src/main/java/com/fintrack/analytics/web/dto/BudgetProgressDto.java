package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record BudgetProgressDto(
        Long budgetId,
        String budgetName,
        String categoryName,
        BigDecimal limitAmount,
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal percentUsed,
        boolean overBudget
) {}
