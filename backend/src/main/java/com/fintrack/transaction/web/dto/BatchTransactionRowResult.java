package com.fintrack.transaction.web.dto;

public record BatchTransactionRowResult(
        int rowIndex,
        Status status,
        String importDedupKey,
        TransactionResponse transaction,
        String error
) {
    public enum Status { CREATED, SKIPPED_DUPLICATE, FAILED }
}
