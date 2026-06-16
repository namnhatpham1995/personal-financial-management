package com.fintrack.analytics.repository;

import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.transaction.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AnalyticsRepository extends JpaRepository<Transaction, Long> {

    @Query("""
            SELECT new com.fintrack.analytics.web.dto.SpendingByCategoryDto(
                c.id, c.name,
                COALESCE(SUM(t.amount), 0),
                COUNT(t.id)
            )
            FROM Transaction t
            JOIN t.category c
            WHERE t.user.id = :userId
              AND t.transactionType = com.fintrack.common.domain.TransactionType.EXPENSE
              AND t.transactionDate BETWEEN :from AND :to
            GROUP BY c.id, c.name
            ORDER BY SUM(t.amount) DESC
            """)
    List<SpendingByCategoryDto> spendingByCategory(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            SELECT new com.fintrack.analytics.web.dto.IncomeExpenseTrendDto(
                YEAR(t.transactionDate),
                MONTH(t.transactionDate),
                COALESCE(SUM(CASE WHEN t.transactionType = com.fintrack.common.domain.TransactionType.INCOME  THEN t.amount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN t.transactionType = com.fintrack.common.domain.TransactionType.EXPENSE THEN t.amount ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN t.transactionType = com.fintrack.common.domain.TransactionType.INCOME  THEN t.amount ELSE 0 END), 0)
                - COALESCE(SUM(CASE WHEN t.transactionType = com.fintrack.common.domain.TransactionType.EXPENSE THEN t.amount ELSE 0 END), 0)
            )
            FROM Transaction t
            WHERE t.user.id = :userId
              AND t.transactionType IN (
                  com.fintrack.common.domain.TransactionType.INCOME,
                  com.fintrack.common.domain.TransactionType.EXPENSE
              )
              AND t.transactionDate BETWEEN :from AND :to
            GROUP BY YEAR(t.transactionDate), MONTH(t.transactionDate)
            ORDER BY YEAR(t.transactionDate), MONTH(t.transactionDate)
            """)
    List<IncomeExpenseTrendDto> incomeExpenseTrend(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
