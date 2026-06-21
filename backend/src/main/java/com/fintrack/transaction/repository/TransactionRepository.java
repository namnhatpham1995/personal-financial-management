package com.fintrack.transaction.repository;

import com.fintrack.transaction.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long>,
        JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    boolean existsByRecurringIdAndOccurrenceDate(Long recurringId, LocalDate occurrenceDate);

    long countByAccountId(Long accountId);

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId OR t.transferAccount.id = :accountId")
    List<Transaction> findConnectedToAccount(@Param("accountId") Long accountId);
}
