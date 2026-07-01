package com.fintrack.transaction.repository;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

public class TransactionSpecification {

    private TransactionSpecification() {}

    public static Specification<Transaction> byUserId(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<Transaction> byAccountId(Long accountId) {
        if (accountId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("account").get("id"), accountId);
    }

    /**
     * Matches transfers whose destination (counterparty) is the given account.
     * Used by the account detail view's separate incoming-transfers list.
     */
    public static Specification<Transaction> byTransferAccountId(Long transferAccountId) {
        if (transferAccountId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("transferAccount").get("id"), transferAccountId);
    }

    public static Specification<Transaction> byStartDate(LocalDate startDate) {
        if (startDate == null) return null;
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("transactionDate"), startDate);
    }

    public static Specification<Transaction> byEndDate(LocalDate endDate) {
        if (endDate == null) return null;
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("transactionDate"), endDate);
    }

    public static Specification<Transaction> byCategoryId(Long categoryId) {
        if (categoryId == null) return null;
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    public static Specification<Transaction> byType(TransactionType type) {
        if (type == null) return null;
        return (root, query, cb) -> cb.equal(root.get("transactionType"), type);
    }

    public static Specification<Transaction> byNoteContaining(String note) {
        if (note == null || note.isBlank()) return null;
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("note")), "%" + note.toLowerCase() + "%");
    }
}
