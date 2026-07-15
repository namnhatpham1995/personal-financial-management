package com.fintrack.exchangerate.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ConvertResponse(
        String from,
        String to,
        BigDecimal amount,
        BigDecimal convertedAmount,
        BigDecimal rate,
        Instant asOf
) {}
