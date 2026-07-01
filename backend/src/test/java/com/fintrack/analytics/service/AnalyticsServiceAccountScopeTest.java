package com.fintrack.analytics.service;

import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.repository.AnalyticsRepository;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.budget.repository.BudgetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Account-scoped analytics used by the account detail view: spending-by-category
 * narrowed to one owned account, and the incoming-transfer total for the pie's
 * incoming slice. Both must refuse to leak data for accounts the user does not own.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceAccountScopeTest {

    @Mock AccountRepository accountRepository;
    @Mock AnalyticsRepository analyticsRepository;
    @Mock BudgetRepository budgetRepository;

    @InjectMocks AnalyticsService analyticsService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 42L;
    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);

    @Test
    void getSpendingByCategory_ownedAccount_scopesToThatAccount() {
        List<SpendingByCategoryDto> rows = List.of(
                new SpendingByCategoryDto("USD", 1L, "Food", new BigDecimal("120.00"), 4));
        when(accountRepository.existsByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(true);
        when(analyticsRepository.spendingByCategory(USER_ID, FROM, TO, ACCOUNT_ID)).thenReturn(rows);

        List<SpendingByCategoryDto> result =
                analyticsService.getSpendingByCategory(USER_ID, FROM, TO, ACCOUNT_ID);

        assertThat(result).isEqualTo(rows);
    }

    @Test
    void getSpendingByCategory_unownedAccount_returnsEmptyWithoutQuerying() {
        when(accountRepository.existsByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(false);

        List<SpendingByCategoryDto> result =
                analyticsService.getSpendingByCategory(USER_ID, FROM, TO, ACCOUNT_ID);

        assertThat(result).isEmpty();
        verify(analyticsRepository, never()).spendingByCategory(USER_ID, FROM, TO, ACCOUNT_ID);
    }

    @Test
    void getSpendingByCategory_nullAccount_skipsOwnershipCheck() {
        List<SpendingByCategoryDto> rows = List.of(
                new SpendingByCategoryDto("USD", 1L, "Food", new BigDecimal("50.00"), 2));
        when(analyticsRepository.spendingByCategory(USER_ID, FROM, TO, null)).thenReturn(rows);

        List<SpendingByCategoryDto> result =
                analyticsService.getSpendingByCategory(USER_ID, FROM, TO, null);

        assertThat(result).isEqualTo(rows);
        verify(accountRepository, never()).existsByIdAndUserId(null, USER_ID);
    }

    @Test
    void getIncomingTransferTotal_ownedAccount_returnsSum() {
        when(accountRepository.existsByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(true);
        when(analyticsRepository.incomingTransferTotal(USER_ID, ACCOUNT_ID, FROM, TO))
                .thenReturn(new BigDecimal("300.00"));

        BigDecimal result =
                analyticsService.getIncomingTransferTotal(USER_ID, ACCOUNT_ID, FROM, TO);

        assertThat(result).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void getIncomingTransferTotal_unownedAccount_returnsZero() {
        when(accountRepository.existsByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(false);

        BigDecimal result =
                analyticsService.getIncomingTransferTotal(USER_ID, ACCOUNT_ID, FROM, TO);

        assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        verify(analyticsRepository, never()).incomingTransferTotal(USER_ID, ACCOUNT_ID, FROM, TO);
    }
}
