package com.fintrack.category.service;

import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.mapper.CategoryMapper;
import com.fintrack.category.repository.CategoryRepository;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository categoryRepository;
    @Mock UserRepository userRepository;
    @Mock CategoryMapper categoryMapper;

    @InjectMocks CategoryService categoryService;

    private static final Long USER_ID = 1L;
    private static final Long CATEGORY_ID = 10L;
    private static final Long UNCATEGORIZED_ID = 99L;

    private Category userCategory;
    private Category uncategorized;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(USER_ID);

        userCategory = Category.builder()
                .id(CATEGORY_ID)
                .name("Food")
                .transactionType(TransactionType.EXPENSE)
                .system(false)
                .user(user)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        uncategorized = Category.builder()
                .id(UNCATEGORIZED_ID)
                .name("Uncategorized")
                .transactionType(TransactionType.EXPENSE)
                .system(true)
                .user(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ─── delete: transaction reassignment ─────────────────────────────────────

    @Test
    void delete_reassignsTransactionsToUncategorized() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        verify(categoryRepository).reassignTransactionCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

    // ─── delete: budget reassignment (regression for ON DELETE CASCADE bug) ───

    @Test
    void delete_reassignsBudgetsToUncategorized_notDeleted() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        // Conflict-drop must happen before reassign
        var inOrder = inOrder(categoryRepository);
        inOrder.verify(categoryRepository).dropConflictingBudgets(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).reassignBudgetCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

    @Test
    void delete_dropsConflictingBudgetsBeforeReassign() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        // dropConflictingBudgets must be called before reassignBudgetCategory
        var inOrder = inOrder(categoryRepository);
        inOrder.verify(categoryRepository).dropConflictingBudgets(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).reassignBudgetCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

    // ─── delete: recurring transaction reassignment ───────────────────────────

    @Test
    void delete_reassignsRecurringTransactionsToUncategorized() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        verify(categoryRepository).reassignRecurringCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

    // ─── delete: system category guard ────────────────────────────────────────

    @Test
    void delete_systemCategory_throwsForbidden() {
        Category systemCat = Category.builder()
                .id(CATEGORY_ID)
                .name("Salary")
                .transactionType(TransactionType.INCOME)
                .system(true)
                .build();
        when(categoryRepository.findByIdAndVisibleToUser(CATEGORY_ID, USER_ID))
                .thenReturn(Optional.of(systemCat));

        assertThatThrownBy(() -> categoryService.delete(USER_ID, CATEGORY_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(categoryRepository, never()).reassignTransactionCategory(any(), any());
        verify(categoryRepository, never()).dropConflictingBudgets(any(), any());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    // ─── delete: full sequence ────────────────────────────────────────────────

    @Test
    void delete_allReassignmentsHappenBeforeDeletion() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        var inOrder = inOrder(categoryRepository);
        inOrder.verify(categoryRepository).reassignTransactionCategory(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).dropConflictingBudgets(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).reassignBudgetCategory(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).reassignRecurringCategory(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).delete(userCategory);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void givenCategoryVisible() {
        when(categoryRepository.findByIdAndVisibleToUser(CATEGORY_ID, USER_ID))
                .thenReturn(Optional.of(userCategory));
    }

    private void givenUncategorizedFound() {
        when(categoryRepository.findSystemCategory("Uncategorized", TransactionType.EXPENSE))
                .thenReturn(Optional.of(uncategorized));
    }
}
