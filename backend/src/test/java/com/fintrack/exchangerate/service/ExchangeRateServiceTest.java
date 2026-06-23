package com.fintrack.exchangerate.service;

import com.fintrack.common.config.AppProperties;
import com.fintrack.exchangerate.domain.ExchangeRate;
import com.fintrack.exchangerate.exception.ExchangeRateUnavailableException;
import com.fintrack.exchangerate.provider.ExchangeRateProvider;
import com.fintrack.exchangerate.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeRateServiceTest {

    @Mock ExchangeRateRepository exchangeRateRepository;
    @Mock ExchangeRateProvider   exchangeRateProvider;
    @Mock AppProperties          appProperties;

    @InjectMocks ExchangeRateService exchangeRateService;

    private static final String BASE = "USD";
    private AppProperties.ExchangeRate erConfig;

    @BeforeEach
    void setUp() {
        erConfig = new AppProperties.ExchangeRate();
        erConfig.setBase(BASE);
        erConfig.setTtlHours(24);
        erConfig.setStaleHours(48);
        when(appProperties.getExchangeRate()).thenReturn(erConfig);
        // Wire self-reference so convert/supportedCurrencies can call self.getRates() without proxy
        ReflectionTestUtils.setField(exchangeRateService, "self", exchangeRateService);
    }

    // ── Cold cache: triggers one provider call and upserts ───────────────────────

    @Test
    void getRates_coldCache_triggersProviderFetchAndUpsert() {
        Instant asOf = Instant.now().minus(1, ChronoUnit.HOURS);
        Map<String, BigDecimal> providerRates = Map.of(
                "USD", BigDecimal.ONE,
                "VND", new BigDecimal("25000"),
                "EUR", new BigDecimal("0.92")
        );
        ExchangeRateProvider.Result result = new ExchangeRateProvider.Result(BASE, asOf, providerRates);

        // Call 1 (getRates initial check): empty → triggers refresh
        // Call 2 (refresh double-check inside lock): also empty → provider call proceeds
        // Call 3 (getRates re-read after refresh): populated rows
        ExchangeRate usdRow = makeRate(BASE, "USD", BigDecimal.ONE, asOf);
        ExchangeRate vndRow = makeRate(BASE, "VND", new BigDecimal("25000"), asOf);
        ExchangeRate eurRow = makeRate(BASE, "EUR", new BigDecimal("0.92"), asOf);

        when(exchangeRateRepository.findByBaseCode(BASE))
                .thenReturn(Collections.emptyList())           // call 1: getRates initial check
                .thenReturn(Collections.emptyList())           // call 2: refresh double-check
                .thenReturn(List.of(usdRow, vndRow, eurRow));  // call 3: getRates re-read after refresh

        when(exchangeRateProvider.fetchLatest(BASE)).thenReturn(result);

        RateSnapshot snapshot = exchangeRateService.getRates(BASE);

        assertThat(snapshot.rates()).hasSize(3);
        verify(exchangeRateProvider, times(1)).fetchLatest(BASE);
        // Upsert called for identity pair + 3 provider pairs (USD already in provider map, so 3 total + 1 identity = 4 calls)
        verify(exchangeRateRepository, atLeastOnce()).upsertRate(eq(BASE), eq(BASE), eq(BigDecimal.ONE), any(), any());
        verify(exchangeRateRepository, atLeastOnce()).upsertRate(eq(BASE), eq("VND"), eq(new BigDecimal("25000")), any(), any());
    }

    // ── Within TTL: no external calls made ──────────────────────────────────────

    @Test
    void getRates_freshCache_makesNoExternalCalls() {
        // fetchedAt is "just now" — well within the 24h TTL
        Instant asOf      = Instant.now();
        Instant fetchedAt = Instant.now();

        ExchangeRate freshRow = ExchangeRate.builder()
                .baseCode(BASE).quoteCode("VND")
                .rate(new BigDecimal("25000"))
                .asOf(asOf).fetchedAt(fetchedAt)
                .build();

        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(List.of(freshRow));

        RateSnapshot snapshot = exchangeRateService.getRates(BASE);

        assertThat(snapshot.rates()).hasSize(1);
        verifyNoInteractions(exchangeRateProvider);
    }

    // ── convert(USD, VND): direct cross-rate math ────────────────────────────────

    @Test
    void convert_usdToVnd_returnsCorrectResult() {
        Instant now = Instant.now();
        // USD→USD = 1, USD→VND = 25000
        ExchangeRate usdRow = makeRate(BASE, "USD", BigDecimal.ONE, now);
        ExchangeRate vndRow = makeRate(BASE, "VND", new BigDecimal("25000"), now);

        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(List.of(usdRow, vndRow));

        BigDecimal result = exchangeRateService.convert(new BigDecimal("100"), "USD", "VND");

        // 100 * 25000 / 1 = 2500000
        assertThat(result).isEqualByComparingTo(new BigDecimal("2500000.0000"));
        verifyNoInteractions(exchangeRateProvider);
    }

    // ── Cross-rate convert: VND→EUR via USD base ─────────────────────────────────

    @Test
    void convert_vndToEur_crossRateViaBase() {
        Instant now = Instant.now();
        // USD→VND = 25000, USD→EUR = 0.92
        ExchangeRate usdRow = makeRate(BASE, "USD", BigDecimal.ONE, now);
        ExchangeRate vndRow = makeRate(BASE, "VND", new BigDecimal("25000"), now);
        ExchangeRate eurRow = makeRate(BASE, "EUR", new BigDecimal("0.92"), now);

        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(List.of(usdRow, vndRow, eurRow));

        BigDecimal result = exchangeRateService.convert(new BigDecimal("25000"), "VND", "EUR");

        // 25000 * 0.92 / 25000 = 0.92
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.9200"));
    }

    // ── Identity convert: same currency ──────────────────────────────────────────

    @Test
    void convert_sameCurrency_returnsAmountUnchanged() {
        BigDecimal result = exchangeRateService.convert(new BigDecimal("42.5"), "EUR", "EUR");
        assertThat(result).isEqualByComparingTo(new BigDecimal("42.5000"));
        verifyNoInteractions(exchangeRateRepository);
        verifyNoInteractions(exchangeRateProvider);
    }

    // ── Provider failure with existing cache: cache preserved, no exception ──────

    @Test
    void refresh_providerFailsWithExistingCache_keepsStaleCache() {
        Instant staleTime = Instant.now().minus(30, ChronoUnit.HOURS); // older than 24h TTL
        ExchangeRate staleRow = ExchangeRate.builder()
                .baseCode(BASE).quoteCode("VND").rate(new BigDecimal("24000"))
                .asOf(staleTime).fetchedAt(staleTime).build();

        // findByBaseCode returns stale row (non-empty → keeps cache on failure)
        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(List.of(staleRow));
        when(exchangeRateProvider.fetchLatest(BASE))
                .thenThrow(new ExchangeRateUnavailableException("Provider down"));

        // Should NOT throw — just log WARN and return
        exchangeRateService.refresh(BASE);

        // No upsert should have been called
        verify(exchangeRateRepository, never()).upsertRate(any(), any(), any(), any(), any());
    }

    // ── Provider failure with empty cache: exception propagated ─────────────────

    @Test
    void refresh_providerFailsWithEmptyCache_throws() {
        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(Collections.emptyList());
        when(exchangeRateProvider.fetchLatest(BASE))
                .thenThrow(new ExchangeRateUnavailableException("Provider down"));

        assertThatThrownBy(() -> exchangeRateService.refresh(BASE))
                .isInstanceOf(ExchangeRateUnavailableException.class)
                .hasMessageContaining("Provider down");
    }

    // ── supportedCurrencies: returns cached codes ────────────────────────────────

    @Test
    void supportedCurrencies_withCachedRates_returnsCodes() {
        Instant now = Instant.now();
        List<ExchangeRate> cached = List.of(
                makeRate(BASE, "USD", BigDecimal.ONE, now),
                makeRate(BASE, "VND", new BigDecimal("25000"), now),
                makeRate(BASE, "EUR", new BigDecimal("0.92"), now)
        );
        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(cached);

        Set<String> supported = exchangeRateService.supportedCurrencies();

        assertThat(supported).containsExactlyInAnyOrder("USD", "VND", "EUR");
    }

    // ── supportedCurrencies: empty cache → seed fallback ─────────────────────────

    @Test
    void supportedCurrencies_emptyCacheReturnsSeedFallback() {
        // getRates → refresh → provider throws → supportedCurrencies catches and falls back to seed
        when(exchangeRateRepository.findByBaseCode(BASE)).thenReturn(Collections.emptyList());
        when(exchangeRateProvider.fetchLatest(BASE))
                .thenThrow(new ExchangeRateUnavailableException("Provider down"));

        Set<String> supported = exchangeRateService.supportedCurrencies();

        assertThat(supported).contains("USD", "EUR", "VND", "GBP");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ExchangeRate makeRate(String base, String quote, BigDecimal rate, Instant asOf) {
        return ExchangeRate.builder()
                .baseCode(base)
                .quoteCode(quote)
                .rate(rate)
                .asOf(asOf)
                .fetchedAt(Instant.now()) // "just now" — within TTL by default
                .build();
    }
}
