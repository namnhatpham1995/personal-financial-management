package com.fintrack.category.repository;

import com.fintrack.category.domain.Category;
import com.fintrack.common.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
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
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Transaction t SET t.category.id = :toId WHERE t.category.id = :fromId")
    void reassignTransactionCategory(Long fromId, Long toId);
}
