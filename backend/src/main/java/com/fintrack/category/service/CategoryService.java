package com.fintrack.category.service;

import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.mapper.CategoryMapper;
import com.fintrack.category.repository.CategoryRepository;
import com.fintrack.category.web.dto.CategoryResponse;
import com.fintrack.category.web.dto.CreateCategoryRequest;
import com.fintrack.category.web.dto.UpdateCategoryRequest;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ForbiddenException;
import com.fintrack.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final CategoryMapper categoryMapper;

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(Long userId, TransactionType type) {
        return categoryMapper.toResponseList(categoryRepository.findVisibleToUser(userId, type));
    }

    @Transactional
    public CategoryResponse create(Long userId, CreateCategoryRequest request) {
        rejectTransferType(request.transactionType());
        if (categoryRepository.existsByUserIdAndNameIgnoreCaseAndTransactionType(
                userId, request.name(), request.transactionType())) {
            throw new ConflictException("Category '" + request.name() + "' already exists for this type");
        }
        User user = userRepository.getReferenceById(userId);
        Category category = categoryMapper.toEntity(request);
        category.setUser(user);
        return categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long userId, Long categoryId, UpdateCategoryRequest request) {
        Category category = findVisibleOrThrow(userId, categoryId);
        guardSystemCategory(category);

        TransactionType targetType = request.transactionType() != null
                ? request.transactionType()
                : category.getTransactionType();
        rejectTransferType(targetType);

        boolean nameChanged = !category.getName().equalsIgnoreCase(request.name());
        boolean typeChanged = request.transactionType() != null
                && request.transactionType() != category.getTransactionType();

        if (nameChanged || typeChanged) {
            if (categoryRepository.existsByUserIdAndNameIgnoreCaseAndTransactionType(
                    userId, request.name(), targetType)) {
                throw new ConflictException("Category '" + request.name() + "' already exists for this type");
            }
        }

        category.setName(request.name());
        if (typeChanged) {
            category.setTransactionType(targetType);
        }
        return categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long userId, Long categoryId) {
        Category category = findVisibleOrThrow(userId, categoryId);
        guardSystemCategory(category);

        // Resolve matching-type "Uncategorized" for transaction/recurring reassignment
        Category uncategorized = categoryRepository
                .findSystemCategory("Uncategorized", category.getTransactionType())
                .orElseThrow(() -> new IllegalStateException("System 'Uncategorized' category not found"));
        Long toId = uncategorized.getId();

        // 1. Delete budgets — they belong to this category and are removed with it
        categoryRepository.deleteBudgetsByCategory(categoryId);
        // 2. Reassign transactions and recurring transactions to Uncategorized (history preserved)
        categoryRepository.reassignTransactionCategory(categoryId, toId);
        categoryRepository.reassignRecurringCategory(categoryId, toId);

        categoryRepository.delete(category);
    }

    /** Called by TransactionService to validate category visibility; returns the category. */
    @Transactional(readOnly = true)
    public Category findVisibleOrThrow(Long userId, Long categoryId) {
        return categoryRepository.findByIdAndVisibleToUser(categoryId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Category", categoryId));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void guardSystemCategory(Category category) {
        if (category.isSystem()) {
            throw new ForbiddenException("System-default categories cannot be modified or deleted");
        }
    }

    private void rejectTransferType(TransactionType type) {
        if (type == TransactionType.TRANSFER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category type must be INCOME or EXPENSE");
        }
    }
}
