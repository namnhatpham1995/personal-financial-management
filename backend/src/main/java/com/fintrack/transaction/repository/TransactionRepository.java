package com.fintrack.transaction.repository;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdAndUserId(Long id, Long userId);

    @Query("""
            SELECT t FROM Transaction t
            LEFT JOIN t.category cat
            WHERE t.user.id = :userId
            AND (:accountId IS NULL OR t.account.id = :accountId)
            AND (:startDate IS NULL OR t.transactionDate >= :startDate)
            AND (:endDate IS NULL OR t.transactionDate <= :endDate)
            AND (:categoryId IS NULL OR cat.id = :categoryId)
            AND (:type IS NULL OR t.transactionType = :type)
            AND (:note IS NULL OR LOWER(t.note) LIKE LOWER(CONCAT('%', :note, '%')))
            """)
    Page<Transaction> findByFilters(
            @Param("userId") Long userId,
            @Param("accountId") Long accountId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("categoryId") Long categoryId,
            @Param("type") TransactionType type,
            @Param("note") String note,
            Pageable pageable);

    boolean existsByIdAndUserId(Long id, Long userId);

    long countByAccountId(Long accountId);
}
