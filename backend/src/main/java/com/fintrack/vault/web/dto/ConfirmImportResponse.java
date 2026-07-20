package com.fintrack.vault.web.dto;

import java.util.List;

/**
 * Durable result of a statement confirmation attempt (fresh, resumed, or replayed). {@code rows}
 * reports one entry per presented {@code selectedDedupKeys} entry, in the order presented.
 */
public record ConfirmImportResponse(
        int created,
        int duplicate,
        int failed,
        List<RowResult> rows
) {
    /**
     * @param status one of {@code CREATED}, {@code DUPLICATE}, {@code FAILED}.
     * @param transactionId set only when {@code status == CREATED}.
     * @param error set only when {@code status == FAILED}.
     */
    public record RowResult(String dedupKey, String status, Long transactionId, String error) {}
}
