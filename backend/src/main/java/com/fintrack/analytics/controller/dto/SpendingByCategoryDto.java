package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record SpendingByCategoryDto(
        String currency,
        Long categoryId,
        String categoryName,
        BigDecimal total,
        long transactionCount
) {}
