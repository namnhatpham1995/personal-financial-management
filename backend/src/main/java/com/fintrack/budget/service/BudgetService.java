package com.fintrack.budget.service;

import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.domain.BudgetPeriod;
import com.fintrack.budget.repository.BudgetRepository;
import com.fintrack.budget.web.dto.BudgetResponse;
import com.fintrack.budget.web.dto.CreateBudgetRequest;
import com.fintrack.budget.web.dto.UpdateBudgetRequest;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final UserRepository userRepository;
    private final CategoryService categoryService;
    private final CacheVersionService cacheVersionService;

    @Transactional
    public BudgetResponse create(Long userId, CreateBudgetRequest req) {
        Category category = categoryService.findVisibleOrThrow(userId, req.categoryId());

        if (budgetRepository.existsByUserIdAndCategoryIdAndPeriodAndCurrency(
                userId, req.categoryId(), req.period(), req.currency())) {
            throw new ConflictException("Budget for this category, period, and currency already exists");
        }

        User user = userRepository.getReferenceById(userId);
        Budget budget = Budget.builder()
                .user(user)
                .category(category)
                .amountLimit(req.amountLimit())
                .period(req.period())
                .startDate(req.startDate())
                .currency(req.currency())
                .build();

        BudgetResponse response = toResponse(budgetRepository.save(budget), userId);
        cacheVersionService.bump(userId);
        return response;
    }

    @Transactional(readOnly = true)
    public List<BudgetResponse> listByUser(Long userId) {
        return budgetRepository.findAllByUserId(userId).stream()
                .map(b -> toResponse(b, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public BudgetResponse getById(Long userId, Long budgetId) {
        return toResponse(findOwned(userId, budgetId), userId);
    }

    @Transactional
    public BudgetResponse update(Long userId, Long budgetId, UpdateBudgetRequest req) {
        Budget budget = findOwned(userId, budgetId);
        if (req.amountLimit() != null) budget.setAmountLimit(req.amountLimit());
        if (req.period() != null) budget.setPeriod(req.period());
        BudgetResponse response = toResponse(budgetRepository.save(budget), userId);
        cacheVersionService.bump(userId);
        return response;
    }

    @Transactional
    public void delete(Long userId, Long budgetId) {
        Budget budget = findOwned(userId, budgetId);
        budgetRepository.delete(budget);
        cacheVersionService.bump(userId);
    }

    // ─── Progress calculation ─────────────────────────────────────────────────

    private BudgetResponse toResponse(Budget budget, Long userId) {
        LocalDate[] bounds = periodBounds(budget.getPeriod());
        BigDecimal spent = budgetRepository.sumSpentInPeriod(
                userId, budget.getCategory().getId(), bounds[0], bounds[1], budget.getCurrency());
        BigDecimal limit = budget.getAmountLimit();
        BigDecimal remaining = limit.subtract(spent);
        BigDecimal percent = limit.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : spent.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP);

        return new BudgetResponse(
                budget.getId(),
                budget.getCategory().getId(),
                budget.getCategory().getName(),
                budget.getPeriod(),
                limit,
                budget.getStartDate(),
                budget.getCurrency(),
                spent,
                remaining,
                percent,
                spent.compareTo(limit) > 0,
                budget.getCreatedAt(),
                budget.getUpdatedAt()
        );
    }

    /** Returns [periodStart, periodEnd] for today's date based on the budget period type. */
    private LocalDate[] periodBounds(BudgetPeriod period) {
        LocalDate today = LocalDate.now();
        return switch (period) {
            case MONTHLY -> new LocalDate[]{
                    today.withDayOfMonth(1),
                    today.withDayOfMonth(today.lengthOfMonth())
            };
            case YEARLY -> new LocalDate[]{
                    today.withDayOfYear(1),
                    today.withDayOfYear(today.lengthOfYear())
            };
        };
    }

    private Budget findOwned(Long userId, Long budgetId) {
        return budgetRepository.findByIdAndUserId(budgetId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Budget", budgetId));
    }
}
