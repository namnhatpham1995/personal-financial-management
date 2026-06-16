package com.fintrack.transaction.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTransactionRequest(
        @DecimalMin(value = "0.01", message = "Amount must be positive")
        BigDecimal amount,

        LocalDate transactionDate,

        Long categoryId,

        @Size(max = 2000) String note
) {}
