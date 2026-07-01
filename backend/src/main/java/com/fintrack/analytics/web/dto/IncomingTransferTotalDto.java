package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

/** Total incoming transfers (this account as counterparty) for a date range. */
public record IncomingTransferTotalDto(
        Long accountId,
        BigDecimal total
) {}
