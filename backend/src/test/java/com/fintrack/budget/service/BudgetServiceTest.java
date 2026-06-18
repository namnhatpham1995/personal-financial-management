package com.fintrack.budget.service;

import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.domain.BudgetPeriod;
import com.fintrack.budget.repository.BudgetRepository;
import com.fintrack.budget.web.dto.BudgetResponse;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock BudgetRepository budgetRepository;
    @Mock UserRepository userRepository;
    @Mock CategoryService categoryService;

    @InjectMocks BudgetService budgetService;

    private Budget buildBudget(BigDecimal limit) {
        Category category = new Category();
        category.setId(5L);
        category.setName("Groceries");

        return Budget.builder()
                .id(1L)
                .category(category)
                .amountLimit(limit)
                .period(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.now().withDayOfMonth(1))
                .build();
    }

    @Test
    void getById_normalSpending_computesSpentRemainingPercent() {
        Budget budget = buildBudget(new BigDecimal("1000.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any()))
                .thenReturn(new BigDecimal("400.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.spent()).isEqualByComparingTo("400.00");
        assertThat(response.remaining()).isEqualByComparingTo("600.00");
        assertThat(response.percentUsed()).isEqualByComparingTo("40.00");
        assertThat(response.overBudget()).isFalse();
    }

    @Test
    void getById_overBudget_flagsOverBudget() {
        Budget budget = buildBudget(new BigDecimal("500.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any()))
                .thenReturn(new BigDecimal("600.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.overBudget()).isTrue();
        assertThat(response.remaining()).isEqualByComparingTo("-100.00");
        assertThat(response.percentUsed()).isEqualByComparingTo("120.00");
    }

    @Test
    void getById_zeroLimit_returnsZeroPercent() {
        Budget budget = buildBudget(BigDecimal.ZERO);
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any()))
                .thenReturn(new BigDecimal("50.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.percentUsed()).isEqualByComparingTo("0");
    }

    @Test
    void getById_exactlyAtLimit_notOverBudget() {
        Budget budget = buildBudget(new BigDecimal("300.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any()))
                .thenReturn(new BigDecimal("300.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.overBudget()).isFalse();
        assertThat(response.remaining()).isEqualByComparingTo("0.00");
        assertThat(response.percentUsed()).isEqualByComparingTo("100.00");
    }
}
