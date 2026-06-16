package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record NetWorthDto(
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal netWorth,
        List<AccountBalanceDto> accounts
) {
    public record AccountBalanceDto(
            Long accountId,
            String accountName,
            String accountType,
            BigDecimal balance
    ) {}
}
