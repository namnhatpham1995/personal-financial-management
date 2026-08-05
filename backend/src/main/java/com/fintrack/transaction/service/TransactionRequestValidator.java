package com.fintrack.transaction.service;

import com.fintrack.common.domain.TransactionType;
import jakarta.validation.ConstraintViolation;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Request-shape validation for transaction mutations, split out of {@link TransactionService} so
 * validation and orchestration are separate units. Stateless static methods — same
 * {@code @InjectMocks}-safe pattern as {@link BalanceImpactPolicy}.
 */
final class TransactionRequestValidator {

    private TransactionRequestValidator() {
    }

    static void requireTransferAccountId(TransactionType type, Long transferAccountId) {
        if (type == TransactionType.TRANSFER && transferAccountId == null) {
            throw new IllegalArgumentException("transferAccountId is required for TRANSFER transactions");
        }
    }

    static void validateDestinationAmount(TransactionType type, boolean crossCurrency, BigDecimal destinationAmount,
                                          String sourceCurrency, String destCurrency) {
        if (type == TransactionType.TRANSFER) {
            if (crossCurrency && destinationAmount == null) {
                throw new IllegalArgumentException(
                        "destinationAmount is required for a TRANSFER between accounts with different currencies ("
                                + sourceCurrency + " -> " + destCurrency + ")");
            }
            if (!crossCurrency && destinationAmount != null) {
                throw new IllegalArgumentException(
                        "destinationAmount must be omitted for a TRANSFER between accounts with the same currency");
            }
        } else if (destinationAmount != null) {
            throw new IllegalArgumentException("destinationAmount is only valid for TRANSFER transactions");
        }
    }

    static void validateUpdateDestinationAmount(boolean isCrossCurrencyTransfer, BigDecimal amount, BigDecimal destinationAmount) {
        if (isCrossCurrencyTransfer && (amount == null) != (destinationAmount == null)) {
            throw new IllegalArgumentException(
                    "amount and destinationAmount must be supplied together when updating a cross-currency transfer");
        }
        if (!isCrossCurrencyTransfer && destinationAmount != null) {
            throw new IllegalArgumentException("destinationAmount must be omitted for this transaction");
        }
    }

    static String firstViolationMessage(Set<? extends ConstraintViolation<?>> violations) {
        return violations.iterator().next().getMessage();
    }
}
