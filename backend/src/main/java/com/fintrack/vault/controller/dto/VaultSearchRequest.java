package com.fintrack.vault.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record VaultSearchRequest(
        String merchant,
        Instant from,
        Instant to,
        String lineItemText,
        BigDecimal maxLineItemAmount
) {}
