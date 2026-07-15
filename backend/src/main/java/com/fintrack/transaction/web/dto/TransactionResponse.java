package com.fintrack.transaction.web.dto;

import com.fintrack.common.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TransactionResponse(
        Long id,
        TransactionType transactionType,
        BigDecimal amount,
        String currency,
        /** Amount received in the destination account currency, for cross-currency TRANSFER only. */
        BigDecimal destinationAmount,
        /** Destination account's currency, set for TRANSFER transactions. */
        String destinationCurrency,
        LocalDate transactionDate,
        Long accountId,
        String accountName,
        Long transferAccountId,
        String transferAccountName,
        Long categoryId,
        String categoryName,
        String note,
        Long recurringId,
        /** Set when this transaction was imported from a statement file. */
        String sourceDocumentId,
        Instant createdAt,
        Instant updatedAt,
        List<MutationWarning> warnings
) {
    public TransactionResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static TransactionResponse withWarnings(TransactionResponse source, List<MutationWarning> mutationWarnings) {
        return new TransactionResponse(source.id, source.transactionType, source.amount, source.currency,
                source.destinationAmount, source.destinationCurrency,
                source.transactionDate, source.accountId, source.accountName, source.transferAccountId,
                source.transferAccountName, source.categoryId, source.categoryName, source.note,
                source.recurringId, source.sourceDocumentId, source.createdAt, source.updatedAt, mutationWarnings);
    }
}
