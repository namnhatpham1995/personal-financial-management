package com.fintrack.analytics.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.web.dto.BalancesSummaryDto;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AnalyticsService#getConvertedBalances} covering:
 * - multi-currency conversion into a grand total
 * - identity pass-through for buckets already in the target currency
 * - partial rate coverage (one currency excluded, rest still summed)
 * - rates entirely unavailable (all buckets excluded, native buckets still returned)
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceConvertedBalancesTest {

    @Mock AccountRepository   accountRepository;
    @Mock ExchangeRateService exchangeRateService;
    @Mock AppProperties       appProperties;

    @InjectMocks AnalyticsService analyticsService;

    private static final Long USER_ID = 1L;

    private AppProperties.ExchangeRate exchangeRateProps;

    @BeforeEach
    void setUp() {
        exchangeRateProps = new AppProperties.ExchangeRate();
        when(appProperties.getExchangeRate()).thenReturn(exchangeRateProps);
        when(exchangeRateService.getAsOf("USD")).thenReturn(Optional.of(Instant.now()));
        when(exchangeRateService.isStale("USD")).thenReturn(false);
    }

    private Account account(String currency, String balance) {
        return Account.builder()
                .id(1L).name(currency + " account")
                .accountType(AccountType.CASH).currency(currency)
                .currentBalance(new BigDecimal(balance))
                .build();
    }

    @Test
    void getConvertedBalances_multiCurrency_sumsIntoGrandTotal() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                account("USD", "100.00"),
                account("EUR", "50.00")
        ));
        when(exchangeRateService.convert(new BigDecimal("50.00"), "EUR", "USD"))
                .thenReturn(new BigDecimal("55.0000"));
        when(exchangeRateService.convert(BigDecimal.ONE, "EUR", "USD"))
                .thenReturn(new BigDecimal("1.1000"));

        BalancesSummaryDto result = analyticsService.getConvertedBalances(USER_ID, "USD");

        assertThat(result.targetCurrency()).isEqualTo("USD");
        assertThat(result.convertedTotal()).isEqualByComparingTo("155.0000");
        assertThat(result.ratesUnavailable()).isFalse();
        assertThat(result.excludedCurrencies()).isEmpty();
        assertThat(result.rates()).hasSize(1);
        assertThat(result.rates().get(0).from()).isEqualTo("EUR");
        assertThat(result.buckets()).hasSize(2);
    }

    @Test
    void getConvertedBalances_identityCurrency_needsNoConversion() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                account("USD", "200.00")
        ));

        BalancesSummaryDto result = analyticsService.getConvertedBalances(USER_ID, "USD");

        assertThat(result.convertedTotal()).isEqualByComparingTo("200.0000");
        assertThat(result.rates()).isEmpty();
        assertThat(result.ratesUnavailable()).isFalse();
    }

    @Test
    void getConvertedBalances_missingRateForOneCurrency_excludesItButSumsRest() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                account("USD", "100.00"),
                account("VND", "5000000")
        ));
        when(exchangeRateService.convert(new BigDecimal("5000000"), "VND", "USD"))
                .thenThrow(new ExchangeRateUnavailableException("No cached rate"));

        BalancesSummaryDto result = analyticsService.getConvertedBalances(USER_ID, "USD");

        assertThat(result.convertedTotal()).isEqualByComparingTo("100.0000");
        assertThat(result.ratesUnavailable()).isTrue();
        assertThat(result.excludedCurrencies()).hasSize(1);
        assertThat(result.excludedCurrencies().get(0).currency()).isEqualTo("VND");
        assertThat(result.excludedCurrencies().get(0).nativeAmount()).isEqualByComparingTo("5000000");
        // Native buckets are still returned regardless of conversion outcome
        assertThat(result.buckets()).hasSize(2);
    }

    @Test
    void getConvertedBalances_ratesEntirelyUnavailable_flagsUnavailableButKeepsBuckets() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(
                account("EUR", "50.00")
        ));
        when(exchangeRateService.convert(new BigDecimal("50.00"), "EUR", "USD"))
                .thenThrow(new ExchangeRateUnavailableException("Provider unreachable"));

        BalancesSummaryDto result = analyticsService.getConvertedBalances(USER_ID, "USD");

        assertThat(result.ratesUnavailable()).isTrue();
        assertThat(result.convertedTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.excludedCurrencies()).hasSize(1);
        assertThat(result.buckets()).hasSize(1);
        assertThat(result.buckets().get(0).currency()).isEqualTo("EUR");
        assertThat(result.buckets().get(0).totalBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    void getConvertedBalances_staleRatesFlagged() {
        when(accountRepository.findAllByUserId(USER_ID)).thenReturn(List.of(account("USD", "10.00")));
        when(exchangeRateService.isStale("USD")).thenReturn(true);

        BalancesSummaryDto result = analyticsService.getConvertedBalances(USER_ID, "USD");

        assertThat(result.stale()).isTrue();
    }
}
