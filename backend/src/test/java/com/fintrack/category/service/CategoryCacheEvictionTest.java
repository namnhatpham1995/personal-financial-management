package com.fintrack.category.service;

import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.mapper.CategoryMapper;
import com.fintrack.category.repository.CategoryRepository;
import com.fintrack.category.web.dto.CategoryResponse;
import com.fintrack.category.web.dto.CreateCategoryRequest;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.cache.InMemoryCacheVersionService;
import com.fintrack.common.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that CategoryService mutations bump the version counter so that the
 * {@link CacheVersionService}-stamped cache key changes for the next read.
 *
 * Uses a real {@link InMemoryCacheVersionService} — no Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class CategoryCacheEvictionTest {

    @Mock CategoryRepository categoryRepository;
    @Mock UserRepository     userRepository;
    @Mock CategoryMapper     categoryMapper;

    private CacheVersionService cacheVersionService;
    private CategoryService     categoryService;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        cacheVersionService = new InMemoryCacheVersionService();
        categoryService = new CategoryService(
                categoryRepository, userRepository, categoryMapper, cacheVersionService);
    }

    @Test
    void create_bumpsVersionCounter() {
        long before = cacheVersionService.current(USER_ID);

        Category category = mock(Category.class);
        when(categoryRepository.existsByUserIdAndNameIgnoreCaseAndTransactionType(any(), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(categoryMapper.toEntity(any())).thenReturn(category);
        when(categoryRepository.save(any())).thenReturn(category);
        when(categoryMapper.toResponse(category)).thenReturn(mock(CategoryResponse.class));

        categoryService.create(USER_ID, new CreateCategoryRequest("Food", TransactionType.EXPENSE));

        assertThat(cacheVersionService.current(USER_ID)).isEqualTo(before + 1);
    }

    @Test
    void delete_bumpsVersionCounter() {
        long before = cacheVersionService.current(USER_ID);

        Category category = mock(Category.class);
        Category uncategorized = mock(Category.class);
        when(category.isSystem()).thenReturn(false);
        when(category.getTransactionType()).thenReturn(TransactionType.EXPENSE);
        when(uncategorized.getId()).thenReturn(99L);
        when(categoryRepository.findByIdAndVisibleToUser(anyLong(), eq(USER_ID)))
                .thenReturn(java.util.Optional.of(category));
        when(categoryRepository.findSystemCategory(eq("Uncategorized"), any()))
                .thenReturn(java.util.Optional.of(uncategorized));

        categoryService.delete(USER_ID, 1L);

        assertThat(cacheVersionService.current(USER_ID)).isEqualTo(before + 1);
    }

    @Test
    void bumpForUser1_doesNotAffectUser2Counter() {
        long user2before = cacheVersionService.current(2L);

        Category category = mock(Category.class);
        when(categoryRepository.existsByUserIdAndNameIgnoreCaseAndTransactionType(any(), any(), any()))
                .thenReturn(false);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(categoryMapper.toEntity(any())).thenReturn(category);
        when(categoryRepository.save(any())).thenReturn(category);
        when(categoryMapper.toResponse(category)).thenReturn(mock(CategoryResponse.class));

        categoryService.create(USER_ID, new CreateCategoryRequest("Food", TransactionType.EXPENSE));

        assertThat(cacheVersionService.current(2L)).isEqualTo(user2before);
    }
}
