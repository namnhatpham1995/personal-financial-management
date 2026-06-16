package com.fintrack.account.web.dto;

import com.fintrack.account.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank @Size(max = 255)
        String name,

        @NotNull
        AccountType accountType,

        @NotBlank @Size(max = 10)
        String currency,

        @DecimalMin(value = "0.0", inclusive = true, message = "Initial balance must be zero or positive")
        BigDecimal initialBalance
) {
    public CreateAccountRequest {
        if (initialBalance == null) initialBalance = BigDecimal.ZERO;
    }
}
