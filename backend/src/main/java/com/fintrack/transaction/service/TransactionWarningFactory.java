package com.fintrack.transaction.service;

import com.fintrack.account.domain.Account;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.web.dto.MutationWarning;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mutation-warning construction, split out of {@link TransactionService} so warning-building and
 * orchestration are separate units. Stateless static methods — same {@code @InjectMocks}-safe
 * pattern as {@link BalanceImpactPolicy}.
 */
final class TransactionWarningFactory {

    private TransactionWarningFactory() {
    }

    static void addDuplicateWarning(List<MutationWarning> warnings, boolean possibleDuplicate, Long accountId) {
        if (possibleDuplicate) {
            warnings.add(new MutationWarning("possible_duplicate_transaction",
                    "A similar transaction already exists", accountId));
        }
    }

    static void addNegativeBalanceWarning(List<MutationWarning> warnings, Account account,
                                          TransactionType type, BigDecimal amount) {
        if ((type == TransactionType.EXPENSE || type == TransactionType.TRANSFER)
                && amount.signum() > 0
                && account.getCurrentBalance().subtract(amount).signum() < 0) {
            warnings.add(new MutationWarning("account_balance_negative",
                    "This mutation leaves the account balance negative", account.getId()));
        }
    }
}
