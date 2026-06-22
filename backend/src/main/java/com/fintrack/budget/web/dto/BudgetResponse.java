package com.fintrack.budget.web.dto;

import com.fintrack.budget.domain.BudgetPeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record BudgetResponse(
        Long id,
        Long categoryId,
        String categoryName,
        BudgetPeriod period,
        BigDecimal amountLimit,
        LocalDate startDate,
        String currency,
        // Progress fields — populated by service
        BigDecimal spent,
        BigDecimal remaining,
        BigDecimal percentUsed,
        boolean overBudget,
        Instant createdAt,
        Instant updatedAt
) {}
