package com.fintrack.transaction.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTransactionRequest(
        @DecimalMin(value = "0.01", message = "Amount must be positive")
        BigDecimal amount,

        /**
         * For a cross-currency TRANSFER, MUST be supplied whenever amount is supplied (and vice
         * versa) so both sides of the transfer stay consistent. Ignored for other transaction types.
         */
        @DecimalMin(value = "0.01", message = "destinationAmount must be positive")
        BigDecimal destinationAmount,

        LocalDate transactionDate,

        Long categoryId,

        @Size(max = 2000) String note
) {}
