package com.fintrack.account.repository;

import com.fintrack.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findAllByUserId(Long userId);

    Optional<Account> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    @Query("SELECT COUNT(t) > 0 FROM Transaction t WHERE t.account.id = :accountId")
    boolean hasTransactions(Long accountId);

    @Query("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN t.transactionType = 'INCOME' THEN t.amount
                    WHEN t.transactionType = 'EXPENSE' THEN -t.amount
                    WHEN t.transactionType = 'TRANSFER' AND t.account.id = :accountId THEN -t.amount
                    WHEN t.transactionType = 'TRANSFER' AND t.transferAccount.id = :accountId THEN t.amount
                    ELSE 0
                END
            ), 0) FROM Transaction t WHERE t.account.id = :accountId OR t.transferAccount.id = :accountId
            """)
    java.math.BigDecimal computeBalanceFromTransactions(Long accountId);
}
