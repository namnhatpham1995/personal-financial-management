package com.fintrack.account.web.dto;

import com.fintrack.account.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateAccountRequest(
        @Size(max = 255) String name,
        AccountType accountType,
        @Size(max = 10) String currency,
        @DecimalMin("0.0") BigDecimal initialBalance
) {}
