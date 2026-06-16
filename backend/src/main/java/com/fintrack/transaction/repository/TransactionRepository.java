package com.fintrack.transaction.repository;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.user.id = :userId
            AND (:accountId IS NULL OR t.account.id = :accountId)
            AND (:startDate IS NULL OR t.transactionDate >= :startDate)
            AND (:endDate IS NULL OR t.transactionDate <= :endDate)
            AND (:categoryId IS NULL OR t.category.id = :categoryId)
            AND (:type IS NULL OR t.transactionType = :type)
            AND (:note IS NULL OR LOWER(t.note) LIKE LOWER(CONCAT('%', :note, '%')))
            """)
    Page<Transaction> findByFilters(
            Long userId,
            Long accountId,
            LocalDate startDate,
            LocalDate endDate,
            Long categoryId,
            TransactionType type,
            String note,
            Pageable pageable);

    boolean existsByIdAndUserId(Long id, Long userId);

    long countByAccountId(Long accountId);
}
