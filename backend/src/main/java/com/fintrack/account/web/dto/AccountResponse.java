package com.fintrack.account.web.dto;

import com.fintrack.account.domain.AccountType;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String name,
        AccountType accountType,
        String currency,
        BigDecimal initialBalance,
        BigDecimal currentBalance,
        Instant createdAt,
        Instant updatedAt
) {}
