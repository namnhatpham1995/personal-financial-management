package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record SpendingByCategoryDto(
        Long categoryId,
        String categoryName,
        BigDecimal total,
        long transactionCount
) {}
