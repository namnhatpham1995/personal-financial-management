package com.fintrack.budget.web.dto;

import com.fintrack.budget.domain.BudgetPeriod;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record UpdateBudgetRequest(
        @DecimalMin(value = "0.01", message = "Budget limit must be positive")
        BigDecimal amountLimit,
        BudgetPeriod period
) {}
