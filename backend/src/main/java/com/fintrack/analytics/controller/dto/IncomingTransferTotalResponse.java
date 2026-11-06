package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

/** Total incoming transfers (this account as counterparty) for a date range. */
public record IncomingTransferTotalResponse(
        Long accountId,
        BigDecimal total
) {}
