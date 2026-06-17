package com.fintrack.transaction.web.dto;

import com.fintrack.common.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(
        Long id,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        LocalDate transactionDate,
        Long accountId,
        String accountName,
        Long transferAccountId,
        String transferAccountName,
        Long categoryId,
        String categoryName,
        String note,
        Long recurringId,
        Instant createdAt,
        Instant updatedAt
) {}
