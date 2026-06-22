package com.fintrack.analytics.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.repository.AnalyticsRepository;
import com.fintrack.analytics.web.dto.ConvertedOverviewDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.budget.repository.BudgetRepository;
import com.fintrack.common.config.AppProperties;
import com.fintrack.exchangerate.exception.ExchangeRateUnavailableException;
import com.fintrack.exchangerate.service.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsService#getOverview} covering:
 * - multi-currency merge (USD + VND accounts)
 * - identity pass-through (all accounts in target currency)
 * - provider unavailable → ratesUnavailable=true, HTTP 200 (no exception propagated)
 * - sum-then-convert correctness for small-valued currencies (VND)
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceOverviewTest {

    @Mock AccountRepository     accountRepository;
    @Mock AnalyticsRepository   analyticsRepository;
    @Mock BudgetRepository      budgetRepository;
    @Mock ExchangeRateService   exchangeRateService;
    @Mock AppProperties         appProperties;

    @InjectMocks AnalyticsService analyticsService;

    private static final Long      USER_ID = 1L;
    private static final LocalDate FROM    = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO      = LocalDate.of(2026, 6, 30);

    private AppProperties.ExchangeRate exchangeRateProps;

    @BeforeEach
    void setUp() {
        exchangeRateProps = new AppProperties.ExchangeRate();
        when(appProperties.getExchangeRate()).thenReturn(exchangeRateProps);
        // Default: stale check and asOf return safe values
        when(exchangeRateService.getAsOf("USD")).thenReturn(Optional.of(Instant.now()));
        when(exchangeRateService.isStale("USD")).thenReturn(false);
    }

    // ── Merge test: USD + VND accounts ───────────────────────────────────────

    @Test
    void getOverview_multiCurrency_mergesSpendingByCategory() {
        // USD account: 1000 assets
        // VND account: 25_000_000 VND assets → converted at 0.00004 USD/VND = 1000 USD
        Account usdAccount = account(1L, "USD Bank", AccountType.BANK, "USD", new BigDecimal("1000.00"));
        Account vndAccount = account(2L, "VND Bank", AccountType.BANK, "VND", new BigDecimal("25000000"));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(usdAccount, vndAccount));

        // VND → USD rate: 1 VND = 0.00004 USD
        when(exchangeRateService.convert(new BigDecimal("25000000"), "VND", "USD"))
            .thenReturn(new BigDecimal("1000.0000"));
        when(exchangeRateService.convert(BigDecimal.ZERO, "VND", "USD"))
            .thenReturn(BigDecimal.ZERO.setScale(4));

        // Spending: same category from two currencies
        List<SpendingByCategoryDto> spending = List.of(
            new SpendingByCategoryDto("USD", 10L, "Food", new BigDecimal("200.00"), 3),
            new SpendingByCategoryDto("VND", 10L, "Food", new BigDecimal("5000000"), 2)
        );
        when(analyticsRepository.spendingByCategory(USER_ID, FROM, TO)).thenReturn(spending);
        when(analyticsRepository.incomeExpenseTrend(USER_ID, FROM, TO)).thenReturn(List.of());

        // 5_000_000 VND → 200 USD
        when(exchangeRateService.convert(new BigDecimal("5000000"), "VND", "USD"))
            .thenReturn(new BigDecimal("200.0000"));
        // Display rate: 1 VND in USD
        when(exchangeRateService.convert(BigDecimal.ONE, "VND", "USD"))
            .thenReturn(new BigDecimal("0.00004000"));

        ConvertedOverviewDto result = analyticsService.getOverview(USER_ID, "USD", FROM, TO);

        assertThat(result.targetCurrency()).isEqualTo("USD");
        assertThat(result.ratesUnavailable()).isFalse();
        assertThat(result.excludedCurrencies()).isEmpty();

        // Net worth: 1000 USD (direct) + 1000 USD (converted VND) = 2000 USD
        assertThat(result.totalAssets()).isEqualByComparingTo(new BigDecimal("2000.0000"));
        assertThat(result.netWorth()).isEqualByComparingTo(new BigDecimal("2000.0000"));

        // Spending: single "Food" entry with 200 (USD direct) + 200 (VND converted) = 400
        assertThat(result.spending()).hasSize(1);
        assertThat(result.spending().get(0).categoryId()).isEqualTo(10L);
        assertThat(result.spending().get(0).totalAmount()).isEqualByComparingTo(new BigDecimal("400.0000"));
        assertThat(result.spending().get(0).transactionCount()).isEqualTo(5L);

        // Rates list should contain VND entry (source ≠ target)
        assertThat(result.rates()).hasSize(1);
        assertThat(result.rates().get(0).from()).isEqualTo("VND");
        assertThat(result.rates().get(0).to()).isEqualTo("USD");
    }

    // ── Identity test: all accounts in target currency ────────────────────────

    @Test
    void getOverview_identityCurrency_passesThrough_noRatesEntry() {
        Account usd1 = account(1L, "Checking", AccountType.BANK,    "USD", new BigDecimal("500.00"));
        Account usd2 = account(2L, "Savings",  AccountType.SAVINGS, "USD", new BigDecimal("1500.00"));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(usd1, usd2));

        List<SpendingByCategoryDto> spending = List.of(
            new SpendingByCategoryDto("USD", 5L, "Groceries", new BigDecimal("300.00"), 4)
        );
        when(analyticsRepository.spendingByCategory(USER_ID, FROM, TO)).thenReturn(spending);

        List<IncomeExpenseTrendDto> trend = List.of(
            new IncomeExpenseTrendDto("USD", 2026, 1, new BigDecimal("3000"), new BigDecimal("1200"), new BigDecimal("1800"))
        );
        when(analyticsRepository.incomeExpenseTrend(USER_ID, FROM, TO)).thenReturn(trend);

        ConvertedOverviewDto result = analyticsService.getOverview(USER_ID, "USD", FROM, TO);

        assertThat(result.ratesUnavailable()).isFalse();
        assertThat(result.rates()).isEmpty(); // no conversion needed
        assertThat(result.excludedCurrencies()).isEmpty();

        assertThat(result.totalAssets()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(result.netWorth()).isEqualByComparingTo(new BigDecimal("2000.00"));

        assertThat(result.spending()).hasSize(1);
        assertThat(result.spending().get(0).totalAmount()).isEqualByComparingTo(new BigDecimal("300.00"));

        assertThat(result.trend()).hasSize(1);
        assertThat(result.trend().get(0).income()).isEqualByComparingTo(new BigDecimal("3000"));
        assertThat(result.trend().get(0).expense()).isEqualByComparingTo(new BigDecimal("1200"));
        assertThat(result.trend().get(0).net()).isEqualByComparingTo(new BigDecimal("1800"));
    }

    // ── Provider unavailable → ratesUnavailable=true, no exception propagated ─

    @Test
    void getOverview_providerDown_setsRatesUnavailableTrue_doesNotThrow() {
        Account usdAcc = account(1L, "USD",  AccountType.BANK, "USD", new BigDecimal("1000.00"));
        Account vndAcc = account(2L, "VND",  AccountType.BANK, "VND", new BigDecimal("10000000"));
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(usdAcc, vndAcc));

        // Provider is down for VND
        when(exchangeRateService.convert(any(BigDecimal.class), eq("VND"), eq("USD")))
            .thenThrow(new ExchangeRateUnavailableException("Provider unreachable"));
        when(analyticsRepository.spendingByCategory(USER_ID, FROM, TO)).thenReturn(List.of());
        when(analyticsRepository.incomeExpenseTrend(USER_ID, FROM, TO)).thenReturn(List.of());

        // Must NOT throw — overview always returns 200
        ConvertedOverviewDto result = analyticsService.getOverview(USER_ID, "USD", FROM, TO);

        assertThat(result.ratesUnavailable()).isTrue();
        assertThat(result.excludedCurrencies()).hasSize(1);
        assertThat(result.excludedCurrencies().get(0).currency()).isEqualTo("VND");

        // USD portion still included
        assertThat(result.totalAssets()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    // ── Sum-then-convert: two VND rows must not truncate to zero ─────────────

    @Test
    void getOverview_sumThenConvert_vndRowsNotTruncatedToZero() {
        // Two VND spending rows of 1_000_000 VND each.
        // At 0.00004 USD/VND, sum = 2_000_000 VND → 80 USD.
        // If convert-then-sum at scale 4 were used: 1_000_000 * 0.00004 = 40.0000 each → 80 OK at this scale.
        // The real risk is very small amounts. Use 1 VND * 2 rows:
        // sum-then-convert: 2 VND → convert(2, VND, USD) = verifiable
        // convert-then-sum at scale 4: convert(1, VND, USD) = 0.0000 * 2 = 0

        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(analyticsRepository.incomeExpenseTrend(USER_ID, FROM, TO)).thenReturn(List.of());

        // Two rows of 1 VND (same category)
        List<SpendingByCategoryDto> spending = List.of(
            new SpendingByCategoryDto("VND", 99L, "Micro", new BigDecimal("1"), 1),
            new SpendingByCategoryDto("VND", 99L, "Micro", new BigDecimal("1"), 1)
        );
        when(analyticsRepository.spendingByCategory(USER_ID, FROM, TO)).thenReturn(spending);

        // Service sums to 2 VND first, then calls convert(2, VND, USD)
        when(exchangeRateService.convert(new BigDecimal("2"), "VND", "USD"))
            .thenReturn(new BigDecimal("0.0001")); // tiny but non-zero
        when(exchangeRateService.convert(BigDecimal.ONE, "VND", "USD"))
            .thenReturn(new BigDecimal("0.00005000"));

        ConvertedOverviewDto result = analyticsService.getOverview(USER_ID, "USD", FROM, TO);

        assertThat(result.spending()).hasSize(1);
        // Sum-then-convert: result is non-zero (0.0001)
        assertThat(result.spending().get(0).totalAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.spending().get(0).transactionCount()).isEqualTo(2L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Account account(Long id, String name, AccountType type, String currency, BigDecimal balance) {
        return Account.builder()
                .id(id).name(name).accountType(type).currency(currency)
                .currentBalance(balance).initialBalance(BigDecimal.ZERO)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }
}
