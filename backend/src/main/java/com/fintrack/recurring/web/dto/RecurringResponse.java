package com.fintrack.recurring.web.dto;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.recurring.domain.Frequency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RecurringResponse(
        Long id,
        TransactionType transactionType,
        BigDecimal amount,
        Long accountId,
        String accountName,
        Long categoryId,
        String categoryName,
        String note,
        Frequency frequency,
        int intervalValue,
        LocalDate startDate,
        LocalDate endDate,
        Integer maxOccurrences,
        int occurrencesCount,
        LocalDate nextRunDate,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {}
