package com.fintrack.transaction.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Deliberately NOT {@code @Valid} on the list: each row is validated individually inside
 * {@code TransactionService.createBatch} (via a programmatic {@code Validator.validate(row)}
 * call) so one malformed row reports a row-level failure instead of rejecting the whole request
 * with a top-level 400 (see {@code TransactionBatchIntegrationTest}'s partial-success coverage).
 */
public record BatchTransactionRequest(
        @NotEmpty @Size(max = 100) List<BatchTransactionRowRequest> transactions
) {}
