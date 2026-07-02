package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record CurrencyBalanceDto(
        String currency,
        BigDecimal totalBalance,
        List<AccountBalanceDto> accounts
) {
    public record AccountBalanceDto(
            Long accountId,
            String accountName,
            String accountType,
            BigDecimal balance
    ) {}
}
