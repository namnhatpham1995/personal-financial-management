package com.fintrack.analytics.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.repository.AnalyticsRepository;
import com.fintrack.analytics.web.dto.CurrencyNetWorthDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.budget.repository.BudgetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceCurrencyTest {

    @Mock AccountRepository accountRepository;
    @Mock AnalyticsRepository analyticsRepository;
    @Mock BudgetRepository budgetRepository;

    @InjectMocks AnalyticsService analyticsService;

    private static final Long USER_ID = 1L;

    // ── Task 4.4: net worth grouped by currency ───────────────────────────────

    @Test
    void getNetWorth_singleCurrency_returnsOneBucket() {
        List<Account> accounts = List.of(
                account(1L, "Bank", AccountType.BANK, "USD", new BigDecimal("1000.00")),
                account(2L, "Savings", AccountType.SAVINGS, "USD", new BigDecimal("500.00"))
        );
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(accounts);

        List<CurrencyNetWorthDto> result = analyticsService.getNetWorth(USER_ID);

        assertThat(result).hasSize(1);
        CurrencyNetWorthDto usd = result.get(0);
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.totalAssets()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(usd.netWorth()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(usd.accounts()).hasSize(2);
    }

    @Test
    void getNetWorth_multiCurrency_returnsSeparateBuckets() {
        List<Account> accounts = List.of(
                account(1L, "USD Bank", AccountType.BANK, "USD", new BigDecimal("1000.00")),
                account(2L, "EUR Savings", AccountType.SAVINGS, "EUR", new BigDecimal("800.00"))
        );
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(accounts);

        List<CurrencyNetWorthDto> result = analyticsService.getNetWorth(USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CurrencyNetWorthDto::currency)
                .containsExactlyInAnyOrder("USD", "EUR");

        CurrencyNetWorthDto usd = result.stream().filter(d -> "USD".equals(d.currency())).findFirst().orElseThrow();
        assertThat(usd.totalAssets()).isEqualByComparingTo(new BigDecimal("1000.00"));

        CurrencyNetWorthDto eur = result.stream().filter(d -> "EUR".equals(d.currency())).findFirst().orElseThrow();
        assertThat(eur.totalAssets()).isEqualByComparingTo(new BigDecimal("800.00"));
    }

    @Test
    void getNetWorth_creditCard_countsAsLiability() {
        List<Account> accounts = List.of(
                account(1L, "Bank", AccountType.BANK, "USD", new BigDecimal("2000.00")),
                account(2L, "Card", AccountType.CREDIT_CARD, "USD", new BigDecimal("400.00"))
        );
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(accounts);

        List<CurrencyNetWorthDto> result = analyticsService.getNetWorth(USER_ID);

        assertThat(result).hasSize(1);
        CurrencyNetWorthDto usd = result.get(0);
        assertThat(usd.totalAssets()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(usd.totalLiabilities()).isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(usd.netWorth()).isEqualByComparingTo(new BigDecimal("1600.00"));
    }

    @Test
    void getNetWorth_noAccounts_returnsEmptyList() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        List<CurrencyNetWorthDto> result = analyticsService.getNetWorth(USER_ID);

        assertThat(result).isEmpty();
    }

    // ── Task 4.4: spending-by-category segregated by currency ────────────────

    @Test
    void getSpendingByCategory_returnsCurrencySegregated() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        List<SpendingByCategoryDto> rows = List.of(
                new SpendingByCategoryDto("USD", 1L, "Food", new BigDecimal("300.00"), 5),
                new SpendingByCategoryDto("EUR", 2L, "Transport", new BigDecimal("150.00"), 3)
        );
        when(analyticsRepository.spendingByCategory(USER_ID, from, to)).thenReturn(rows);

        List<SpendingByCategoryDto> result = analyticsService.getSpendingByCategory(USER_ID, from, to);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(SpendingByCategoryDto::currency)
                .containsExactlyInAnyOrder("USD", "EUR");
    }

    // ── Task 4.4: income-vs-expense segregated by currency ───────────────────

    @Test
    void getIncomeExpenseTrend_returnsCurrencySegregated() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        List<IncomeExpenseTrendDto> rows = List.of(
                new IncomeExpenseTrendDto("USD", 2025, 1, new BigDecimal("1000"), new BigDecimal("600"), new BigDecimal("400")),
                new IncomeExpenseTrendDto("EUR", 2025, 1, new BigDecimal("500"), new BigDecimal("200"), new BigDecimal("300"))
        );
        when(analyticsRepository.incomeExpenseTrend(USER_ID, from, to)).thenReturn(rows);

        List<IncomeExpenseTrendDto> result = analyticsService.getIncomeExpenseTrend(USER_ID, from, to);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(IncomeExpenseTrendDto::currency)
                .containsExactlyInAnyOrder("USD", "EUR");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Account account(Long id, String name, AccountType type, String currency, BigDecimal balance) {
        return Account.builder()
                .id(id).name(name).accountType(type).currency(currency)
                .currentBalance(balance).initialBalance(BigDecimal.ZERO)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }
}
