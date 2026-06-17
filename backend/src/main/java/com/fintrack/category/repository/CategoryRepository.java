package com.fintrack.category.repository;

import com.fintrack.category.domain.Category;
import com.fintrack.common.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Returns system defaults + categories owned by userId, optionally filtered by type. */
    @Query("""
            SELECT c FROM Category c
            WHERE (c.user IS NULL OR c.user.id = :userId)
            AND (:type IS NULL OR c.transactionType = :type)
            ORDER BY c.system DESC, c.name ASC
            """)
    List<Category> findVisibleToUser(Long userId, TransactionType type);

    /** Finds a category accessible to the user (system or owned). */
    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.user IS NULL OR c.user.id = :userId)")
    Optional<Category> findByIdAndVisibleToUser(Long id, Long userId);

    boolean existsByUserIdAndNameIgnoreCaseAndTransactionType(Long userId, String name, TransactionType type);

    @Query("SELECT c FROM Category c WHERE c.user IS NULL AND c.name = :name AND c.transactionType = :type")
    Optional<Category> findSystemCategory(String name, TransactionType type);

    @Modifying
    @Query("UPDATE Transaction t SET t.category.id = :toId WHERE t.category.id = :fromId")
    void reassignTransactionCategory(Long fromId, Long toId);

    /**
     * Drops budgets pointing to `fromId` when the user already has an "Uncategorized"
     * budget for the same period — prevents uq_budget_user_category_period violation.
     */
    @Modifying
    @Query(value = """
            DELETE FROM budgets b
            WHERE b.category_id = :fromId
            AND EXISTS (
                SELECT 1 FROM budgets b2
                WHERE b2.user_id = b.user_id
                  AND b2.category_id = :toId
                  AND b2.period = b.period
            )
            """, nativeQuery = true)
    void dropConflictingBudgets(Long fromId, Long toId);

    @Modifying
    @Query("UPDATE Budget b SET b.category.id = :toId WHERE b.category.id = :fromId")
    void reassignBudgetCategory(Long fromId, Long toId);

    @Modifying
    @Query("UPDATE RecurringTransaction r SET r.category.id = :toId WHERE r.category.id = :fromId")
    void reassignRecurringCategory(Long fromId, Long toId);
}
