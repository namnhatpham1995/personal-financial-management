package com.fintrack.category.web.dto;

import com.fintrack.common.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Name is always required; transactionType is optional — null means "keep existing". */
public record UpdateCategoryRequest(
        @NotBlank @Size(max = 100) String name,
        TransactionType transactionType
) {}
