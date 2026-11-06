package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record BudgetProgressResponse(
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
