package com.fintrack.budget.repository;

import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.domain.BudgetPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findAllByUserId(Long userId);

    Optional<Budget> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndCategoryIdAndPeriod(Long userId, Long categoryId, BudgetPeriod period);

    /** Sum of EXPENSE transactions in a category between two dates (for progress calculation). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM Transaction t
            WHERE t.user.id = :userId
              AND t.category.id = :categoryId
              AND t.transactionType = 'EXPENSE'
              AND t.transactionDate >= :periodStart
              AND t.transactionDate <= :periodEnd
            """)
    BigDecimal sumSpentInPeriod(Long userId, Long categoryId, LocalDate periodStart, LocalDate periodEnd);
}
