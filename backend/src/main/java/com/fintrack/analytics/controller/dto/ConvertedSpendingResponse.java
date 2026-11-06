package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record ConvertedSpendingResponse(
        Long categoryId,
        String categoryName,
        BigDecimal totalAmount,
        long transactionCount
) {}
