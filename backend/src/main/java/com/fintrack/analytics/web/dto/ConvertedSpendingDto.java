package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record ConvertedSpendingDto(
        Long categoryId,
        String categoryName,
        BigDecimal totalAmount,
        long transactionCount
) {}
