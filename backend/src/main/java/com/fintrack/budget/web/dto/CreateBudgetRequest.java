package com.fintrack.budget.web.dto;

import com.fintrack.budget.domain.BudgetPeriod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateBudgetRequest(
        @NotNull Long categoryId,
        @NotNull BudgetPeriod period,
        @NotNull @DecimalMin(value = "0.01", message = "Budget limit must be positive") BigDecimal amountLimit,
        @NotNull LocalDate startDate,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO 4217 code") String currency
) {}
