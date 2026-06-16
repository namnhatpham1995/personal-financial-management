package com.fintrack.recurring.web.dto;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.recurring.domain.Frequency;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateRecurringRequest(
        @NotNull TransactionType transactionType,

        @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive")
        BigDecimal amount,

        @NotNull Long accountId,
        Long categoryId,

        @Size(max = 2000) String note,

        @NotNull Frequency frequency,

        @Min(value = 1, message = "Interval must be at least 1")
        int intervalValue,

        @NotNull LocalDate startDate,

        LocalDate endDate,
        Integer maxOccurrences
) {}
