package com.fintrack.budget.service;

import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.domain.BudgetPeriod;
import com.fintrack.budget.repository.BudgetRepository;
import com.fintrack.budget.web.dto.BudgetResponse;
import com.fintrack.budget.web.dto.CreateBudgetRequest;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.exception.ConflictException;
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
                .currency("USD")
                .build();
    }

    @Test
    void getById_normalSpending_computesSpentRemainingPercent() {
        Budget budget = buildBudget(new BigDecimal("1000.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("USD")))
                .thenReturn(new BigDecimal("400.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.spent()).isEqualByComparingTo("400.00");
        assertThat(response.remaining()).isEqualByComparingTo("600.00");
        assertThat(response.percentUsed()).isEqualByComparingTo("40.00");
        assertThat(response.overBudget()).isFalse();
        assertThat(response.currency()).isEqualTo("USD");
    }

    @Test
    void getById_overBudget_flagsOverBudget() {
        Budget budget = buildBudget(new BigDecimal("500.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("USD")))
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
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("USD")))
                .thenReturn(new BigDecimal("50.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.percentUsed()).isEqualByComparingTo("0");
    }

    @Test
    void getById_exactlyAtLimit_notOverBudget() {
        Budget budget = buildBudget(new BigDecimal("300.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(budget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("USD")))
                .thenReturn(new BigDecimal("300.00"));

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.overBudget()).isFalse();
        assertThat(response.remaining()).isEqualByComparingTo("0.00");
        assertThat(response.percentUsed()).isEqualByComparingTo("100.00");
    }

    // ── Currency isolation tests ──────────────────────────────────────────────

    @Test
    void getById_usdBudget_doesNotCountVndExpenses() {
        // USD budget should return 0 spent when only VND transactions exist in the mock
        Budget usdBudget = buildBudget(new BigDecimal("500.00"));
        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(usdBudget));
        // sumSpentInPeriod with "USD" returns 0 — simulating no USD-account expenses
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("USD")))
                .thenReturn(BigDecimal.ZERO);

        BudgetResponse response = budgetService.getById(1L, 1L);

        assertThat(response.spent()).isEqualByComparingTo("0.00");
        assertThat(response.remaining()).isEqualByComparingTo("500.00");
        assertThat(response.overBudget()).isFalse();
        // Verify currency argument was passed correctly
        verify(budgetRepository).sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("USD"));
    }

    @Test
    void getById_twoSameCategoryDifferentCurrencies_trackIndependently() {
        // USD budget with its own spending
        Category shoppingCategory = new Category();
        shoppingCategory.setId(10L);
        shoppingCategory.setName("Shopping");

        Budget usdBudget = Budget.builder()
                .id(1L).category(shoppingCategory).amountLimit(new BigDecimal("1000.00"))
                .period(BudgetPeriod.MONTHLY).startDate(LocalDate.now().withDayOfMonth(1))
                .currency("USD").build();

        Budget vndBudget = Budget.builder()
                .id(2L).category(shoppingCategory).amountLimit(new BigDecimal("5000000.00"))
                .period(BudgetPeriod.MONTHLY).startDate(LocalDate.now().withDayOfMonth(1))
                .currency("VND").build();

        when(budgetRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(usdBudget));
        when(budgetRepository.findByIdAndUserId(2L, 1L)).thenReturn(Optional.of(vndBudget));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(10L), any(), any(), eq("USD")))
                .thenReturn(new BigDecimal("300.00"));
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(10L), any(), any(), eq("VND")))
                .thenReturn(new BigDecimal("1500000.00"));

        BudgetResponse usdResponse = budgetService.getById(1L, 1L);
        BudgetResponse vndResponse = budgetService.getById(1L, 2L);

        assertThat(usdResponse.currency()).isEqualTo("USD");
        assertThat(usdResponse.spent()).isEqualByComparingTo("300.00");

        assertThat(vndResponse.currency()).isEqualTo("VND");
        assertThat(vndResponse.spent()).isEqualByComparingTo("1500000.00");
    }

    @Test
    void create_sameCategoryPeriodCurrency_throws409() {
        Category category = new Category();
        category.setId(5L);
        category.setName("Groceries");

        when(categoryService.findVisibleOrThrow(1L, 5L)).thenReturn(category);
        when(budgetRepository.existsByUserIdAndCategoryIdAndPeriodAndCurrency(
                1L, 5L, BudgetPeriod.MONTHLY, "USD")).thenReturn(true);

        CreateBudgetRequest req = new CreateBudgetRequest(
                5L, BudgetPeriod.MONTHLY, new BigDecimal("500.00"), LocalDate.now(), "USD");

        assertThatThrownBy(() -> budgetService.create(1L, req))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_sameCategoryPeriodDifferentCurrency_succeeds() {
        Category category = new Category();
        category.setId(5L);
        category.setName("Groceries");

        User user = User.builder().id(1L).email("u@t.com").passwordHash("h").fullName("U").build();
        when(categoryService.findVisibleOrThrow(1L, 5L)).thenReturn(category);
        // USD budget already exists, but VND does not
        when(budgetRepository.existsByUserIdAndCategoryIdAndPeriodAndCurrency(
                1L, 5L, BudgetPeriod.MONTHLY, "VND")).thenReturn(false);
        when(userRepository.getReferenceById(1L)).thenReturn(user);

        Budget savedBudget = Budget.builder()
                .id(3L).category(category).amountLimit(new BigDecimal("2000000.00"))
                .period(BudgetPeriod.MONTHLY).startDate(LocalDate.now().withDayOfMonth(1))
                .currency("VND").user(user).build();
        when(budgetRepository.save(any())).thenReturn(savedBudget);
        when(budgetRepository.sumSpentInPeriod(eq(1L), eq(5L), any(), any(), eq("VND")))
                .thenReturn(BigDecimal.ZERO);

        CreateBudgetRequest req = new CreateBudgetRequest(
                5L, BudgetPeriod.MONTHLY, new BigDecimal("2000000.00"), LocalDate.now(), "VND");

        BudgetResponse response = budgetService.create(1L, req);

        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.id()).isEqualTo(3L);
    }
}
