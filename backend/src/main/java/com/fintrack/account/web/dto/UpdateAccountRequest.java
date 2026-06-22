package com.fintrack.account.web.dto;

import com.fintrack.account.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateAccountRequest(
        @Size(max = 255) String name,
        AccountType accountType,
        @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO 4217 code (e.g. USD, VND)") String currency,
        @DecimalMin("0.0") BigDecimal initialBalance
) {}
