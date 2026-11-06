package com.fintrack.category.controller.dto;

import com.fintrack.common.domain.TransactionType;

import java.time.Instant;

public record CategoryResponse(
        Long id,
        String name,
        TransactionType transactionType,
        boolean system,
        Instant createdAt
) {}
