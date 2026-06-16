package com.fintrack.transaction.web.dto;

import com.fintrack.common.domain.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateTransactionRequest(
        @NotNull TransactionType transactionType,

        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be positive")
        BigDecimal amount,

        @NotNull LocalDate transactionDate,

        @NotNull Long accountId,

        /** Required when transactionType is TRANSFER */
        Long transferAccountId,

        Long categoryId,

        @Size(max = 2000) String note
) {}
