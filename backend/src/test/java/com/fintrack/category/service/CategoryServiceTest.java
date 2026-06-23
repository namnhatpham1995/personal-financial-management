package com.fintrack.category.service;

import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.mapper.CategoryMapper;
import com.fintrack.category.repository.CategoryRepository;
import com.fintrack.category.web.dto.CreateCategoryRequest;
import com.fintrack.category.web.dto.UpdateCategoryRequest;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    CategoryRepository categoryRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    CategoryMapper categoryMapper;
    @Mock
    CacheVersionService cacheVersionService;

    @InjectMocks
    CategoryService categoryService;

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

    @Test
    void delete_reassignsTransactionsToUncategorized() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        verify(categoryRepository).reassignTransactionCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

    @Test
    void delete_reassignsBudgetsToUncategorized_notDeleted() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        var inOrder = inOrder(categoryRepository);
        inOrder.verify(categoryRepository).dropConflictingBudgets(CATEGORY_ID, UNCATEGORIZED_ID);
        inOrder.verify(categoryRepository).reassignBudgetCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

    @Test
    void delete_reassignsRecurringTransactionsToUncategorized() {
        givenCategoryVisible();
        givenUncategorizedFound();

        categoryService.delete(USER_ID, CATEGORY_ID);

        verify(categoryRepository).reassignRecurringCategory(CATEGORY_ID, UNCATEGORIZED_ID);
    }

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
        verify(categoryRepository, never()).reassignBudgetCategory(any(), any());
        verify(categoryRepository, never()).reassignRecurringCategory(any(), any());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

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

    @Test
    void create_transferType_throwsBadRequest() {
        var request = new CreateCategoryRequest("Transfers", TransactionType.TRANSFER);

        assertThatThrownBy(() -> categoryService.create(USER_ID, request))
                .isInstanceOf(ResponseStatusException.class);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    void update_transferType_throwsBadRequest() {
        givenCategoryVisible();
        var request = new UpdateCategoryRequest("Food", TransactionType.TRANSFER);

        assertThatThrownBy(() -> categoryService.update(USER_ID, CATEGORY_ID, request))
                .isInstanceOf(ResponseStatusException.class);

        verify(categoryRepository, never()).save(any());
    }

    @Test
    void update_typeChange_updatesType() {
        givenCategoryVisible();
        when(categoryRepository.existsByUserIdAndNameIgnoreCaseAndTransactionType(
                USER_ID, "Food", TransactionType.INCOME)).thenReturn(false);
        when(categoryRepository.save(any())).thenReturn(userCategory);

        categoryService.update(USER_ID, CATEGORY_ID, new UpdateCategoryRequest("Food", TransactionType.INCOME));

        assertThat(userCategory.getTransactionType()).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void update_typeChange_conflictingName_throws409() {
        givenCategoryVisible();
        when(categoryRepository.existsByUserIdAndNameIgnoreCaseAndTransactionType(
                USER_ID, "Food", TransactionType.INCOME)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.update(USER_ID, CATEGORY_ID,
                new UpdateCategoryRequest("Food", TransactionType.INCOME)))
                .isInstanceOf(ConflictException.class);

        verify(categoryRepository, never()).save(any());
    }

    private void givenCategoryVisible() {
        when(categoryRepository.findByIdAndVisibleToUser(CATEGORY_ID, USER_ID))
                .thenReturn(Optional.of(userCategory));
    }

    private void givenUncategorizedFound() {
        when(categoryRepository.findSystemCategory("Uncategorized", TransactionType.EXPENSE))
                .thenReturn(Optional.of(uncategorized));
    }
}
