package com.fintrack.budget.web.dto;

import com.fintrack.budget.domain.BudgetPeriod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateBudgetRequest(
        @NotNull Long categoryId,
        @NotNull BudgetPeriod period,
        @NotNull @DecimalMin(value = "0.01", message = "Budget limit must be positive") BigDecimal amountLimit,
        @NotNull LocalDate startDate
) {}
