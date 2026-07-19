package com.fintrack.transaction.web.dto;

public record BatchTransactionRowResult(
        int rowIndex,
        Status status,
        String clientRequestId,
        TransactionResponse transaction,
        String error
) {
    /**
     * {@code REPLAYED}: same {@code clientRequestId} + same payload as a previously completed row
     * (returns the existing transaction, no new balance effect). {@code CONFLICT}: same
     * {@code clientRequestId} reused with a different payload (neither transaction is altered).
     */
    public enum Status { CREATED, REPLAYED, CONFLICT, FAILED }
}
