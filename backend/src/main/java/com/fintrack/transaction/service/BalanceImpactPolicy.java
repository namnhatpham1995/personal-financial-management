package com.fintrack.transaction.service;

import com.fintrack.common.domain.TransactionType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Signed per-account balance effects for a transaction. Shared by every caller that applies or
 * reverses a transaction's balance impact — transaction create/update/delete, recurring
 * occurrence generation, and account-deletion counterparty reversal — so the INCOME/EXPENSE/
 * TRANSFER sign rules live in exactly one place. Stateless: plain static methods, no Spring
 * wiring, so it can be called from constructors that already have a fixed dependency set.
 */
public final class BalanceImpactPolicy {

    public record AccountEffect(Long accountId, BigDecimal delta) {
    }

    private BalanceImpactPolicy() {
    }

    /**
     * Effects of applying a transaction (create, or amount-increase on update). For TRANSFER,
     * {@code destinationAmount} is the destination-side amount and falls back to {@code amount}
     * when null (same-currency transfers).
     */
    public static List<AccountEffect> apply(TransactionType type, Long accountId, Long transferAccountId,
                                             BigDecimal amount, BigDecimal destinationAmount) {
        return switch (type) {
            case INCOME -> List.of(new AccountEffect(accountId, amount));
            case EXPENSE -> List.of(new AccountEffect(accountId, amount.negate()));
            case TRANSFER -> List.of(
                    new AccountEffect(accountId, amount.negate()),
                    new AccountEffect(transferAccountId, destinationAmount != null ? destinationAmount : amount));
        };
    }

    /** Effects of reversing a transaction (delete, or pre-update rollback). */
    public static List<AccountEffect> reverse(TransactionType type, Long accountId, Long transferAccountId,
                                               BigDecimal amount, BigDecimal destinationAmount) {
        return switch (type) {
            case INCOME -> List.of(new AccountEffect(accountId, amount.negate()));
            case EXPENSE -> List.of(new AccountEffect(accountId, amount));
            case TRANSFER -> List.of(
                    new AccountEffect(accountId, amount),
                    new AccountEffect(transferAccountId,
                            (destinationAmount != null ? destinationAmount : amount).negate()));
        };
    }
}
