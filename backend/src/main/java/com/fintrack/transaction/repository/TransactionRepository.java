package com.fintrack.transaction.repository;

import com.fintrack.transaction.domain.Transaction;
import com.fintrack.common.domain.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>,
        JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    /**
     * Owned-transaction lookup that acquires {@code PESSIMISTIC_WRITE} on the row for the
     * duration of the caller's transaction. Used by update/delete to serialize concurrent
     * balance-affecting mutations of the same transaction (see openspec/changes/
     * harden-idempotent-mutations/design.md Decision #3). Plain reads (GET-by-id, list) must
     * keep using {@link #findByIdAndUserId} and stay lock-free.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id AND t.user.id = :userId")
    Optional<Transaction> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    boolean existsByImportDedupKey(String importDedupKey);

    /** User-scoped duplicate check backing the composite {@code uq_transactions_user_import_dedup_key} index. */
    boolean existsByUserIdAndImportDedupKey(Long userId, String importDedupKey);

    boolean existsByUserIdAndAccountIdAndTransactionDateAndAmountAndTransactionTypeAndNote(
            Long userId, Long accountId, LocalDate transactionDate, BigDecimal amount,
            TransactionType transactionType, String note);

    boolean existsByIdAndUserId(Long id, Long userId);

    boolean existsByRecurringIdAndOccurrenceDate(Long recurringId, LocalDate occurrenceDate);

    long countByAccountId(Long accountId);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId OR t.transferAccount.id = :accountId")
    List<Transaction> findConnectedToAccount(@Param("accountId") Long accountId);
}
