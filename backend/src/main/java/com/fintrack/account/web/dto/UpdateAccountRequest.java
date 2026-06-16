package com.fintrack.account.web.dto;

import com.fintrack.account.domain.AccountType;
import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(
        @Size(max = 255) String name,
        AccountType accountType,
        @Size(max = 10) String currency
) {}
