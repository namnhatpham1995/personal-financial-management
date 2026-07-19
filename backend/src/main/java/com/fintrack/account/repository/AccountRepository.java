package com.fintrack.account.repository;

import com.fintrack.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByUserId(Long userId);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    /**
     * Owned-account lookup that acquires {@code PESSIMISTIC_WRITE} on the row for the duration
     * of the caller's transaction. Used to serialize transaction update/delete balance effects,
     * account initial-balance changes, and recomputation against each other (see openspec/
     * changes/harden-idempotent-mutations/design.md Decision #3). Independent creates keep using
     * {@link #atomicAdjustBalance}, which Postgres already serializes at the row level without an
     * app-level lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.user.id = :userId")
    Optional<Account> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    @Query("SELECT COUNT(t) > 0 FROM Transaction t WHERE t.account.id = :accountId OR t.transferAccount.id = :accountId")
    boolean hasTransactions(Long accountId);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.account.id = :accountId OR t.transferAccount.id = :accountId")
    long countConnectedTransactions(@Param("accountId") Long accountId);

    @Query("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN t.transactionType = 'INCOME' THEN t.amount
                    WHEN t.transactionType = 'EXPENSE' THEN -t.amount
                    WHEN t.transactionType = 'TRANSFER' AND t.account.id = :accountId THEN -t.amount
                    WHEN t.transactionType = 'TRANSFER' AND t.transferAccount.id = :accountId THEN COALESCE(t.destinationAmount, t.amount)
                    ELSE 0
                END
            ), 0) FROM Transaction t WHERE t.account.id = :accountId OR t.transferAccount.id = :accountId
            """)
    java.math.BigDecimal computeBalanceFromTransactions(Long accountId);

    @Modifying
    @Transactional
    @Query("UPDATE Account a SET a.currentBalance = a.currentBalance + :delta WHERE a.id = :id")
    void atomicAdjustBalance(@Param("id") Long id, @Param("delta") BigDecimal delta);
}
