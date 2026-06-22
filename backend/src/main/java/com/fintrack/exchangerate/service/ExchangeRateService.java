package com.fintrack.exchangerate.service;

import com.fintrack.common.config.AppProperties;
import com.fintrack.exchangerate.domain.ExchangeRate;
import com.fintrack.exchangerate.exception.ExchangeRateUnavailableException;
import com.fintrack.exchangerate.provider.ExchangeRateProvider;
import com.fintrack.exchangerate.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Core exchange-rate business logic.
 *
 * <p>Rates are cached in PostgreSQL and refreshed at most every {@code ttlHours}.
 * A per-base {@link ReentrantLock} prevents duplicate provider calls when multiple
 * threads simultaneously encounter a cold or expired cache (single-flight guard).
 *
 * <p>All cross-rate conversions go through the configured {@code base} currency (default USD)
 * so only O(n) pairs need to be stored instead of O(n²).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    /** Fallback currencies returned by {@link #supportedCurrencies()} when the cache is cold. */
    private static final Set<String> SEED_CURRENCIES = Set.of("USD", "EUR", "VND", "GBP", "JPY", "SGD");

    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateProvider   exchangeRateProvider;
    private final AppProperties          appProperties;

    /**
     * One lock per base-currency code; created on first access.
     * Non-final so Lombok's @RequiredArgsConstructor does not include it in the generated constructor.
     */
    @SuppressWarnings("squid:S3077") // ConcurrentHashMap is thread-safe; field assigned once at init
    private ConcurrentHashMap<String, ReentrantLock> perBaseLocks = new ConcurrentHashMap<>();

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Converts {@code amount} from {@code from} to {@code to} using cross-rates through the base currency.
     *
     * <p>Formula: {@code amount * rate(base→to) / rate(base→from)}
     *
     * @throws ExchangeRateUnavailableException if a required pair is missing from cache after refresh
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return amount.setScale(4, RoundingMode.HALF_UP);
        }

        String base = appProperties.getExchangeRate().getBase();
        List<ExchangeRate> cached = getRates(base);

        BigDecimal rateFrom = findRate(cached, base, from);
        BigDecimal rateTo   = findRate(cached, base, to);

        return amount
                .multiply(rateTo)
                .divide(rateFrom, 10, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Returns cached rates for {@code base}, triggering a refresh if the cache is cold or stale.
     */
    public List<ExchangeRate> getRates(String base) {
        List<ExchangeRate> cached = exchangeRateRepository.findByBaseCode(base);
        if (cached.isEmpty() || isCacheExpired(cached)) {
            refresh(base);
            cached = exchangeRateRepository.findByBaseCode(base);
        }
        return cached;
    }

    /**
     * Returns the distinct set of supported currency codes from the cache,
     * falling back to {@link #SEED_CURRENCIES} when the cache is empty so that
     * account creation never blocks on a cold start.
     */
    public Set<String> supportedCurrencies() {
        String base = appProperties.getExchangeRate().getBase();
        List<ExchangeRate> cached = exchangeRateRepository.findByBaseCode(base);
        if (cached.isEmpty()) {
            return SEED_CURRENCIES;
        }
        return cached.stream()
                .map(ExchangeRate::getQuoteCode)
                .collect(Collectors.toSet());
    }

    /**
     * Returns the provider's reported {@code asOf} timestamp for the most recently fetched row.
     */
    public Optional<Instant> getAsOf(String base) {
        return exchangeRateRepository.findByBaseCode(base).stream()
                .map(ExchangeRate::getAsOf)
                .max(Instant::compareTo);
    }

    /**
     * Returns true if the cached rates for {@code base} are older than {@code staleHours}.
     */
    public boolean isStale(String base) {
        List<ExchangeRate> cached = exchangeRateRepository.findByBaseCode(base);
        if (cached.isEmpty()) return true;
        Instant threshold = Instant.now().minus(appProperties.getExchangeRate().getStaleHours(), ChronoUnit.HOURS);
        return cached.stream().allMatch(r -> r.getFetchedAt().isBefore(threshold));
    }

    /**
     * Fetches fresh rates from the provider and upserts them into the cache.
     *
     * <p>A per-base lock prevents concurrent refreshes. If the cache was already refreshed
     * within {@code ttlHours} by another thread that held the lock, we skip the provider call.
     *
     * <p>On provider failure: if rows already exist, we log WARN and keep the stale cache;
     * if the cache is empty we re-throw so callers know data is unavailable.
     */
    @Transactional
    public void refresh(String base) {
        ReentrantLock lock = perBaseLocks.computeIfAbsent(base, k -> new ReentrantLock());
        lock.lock();
        try {
            // Double-check after acquiring the lock — another thread may have already refreshed.
            List<ExchangeRate> existing = exchangeRateRepository.findByBaseCode(base);
            if (!existing.isEmpty() && !isCacheExpired(existing)) {
                log.debug("Exchange rate cache is still fresh for base={}, skipping provider call", base);
                return;
            }

            ExchangeRateProvider.Result result;
            try {
                result = exchangeRateProvider.fetchLatest(base);
            } catch (ExchangeRateUnavailableException ex) {
                if (!existing.isEmpty()) {
                    log.warn("Provider fetch failed for base={}; keeping stale cache. Reason: {}", base, ex.getMessage());
                    return;
                }
                throw ex; // cold cache — propagate so callers can surface the error
            }

            Instant fetchedAt = Instant.now();

            // Always persist the identity pair (base→base = 1.0)
            upsert(base, base, BigDecimal.ONE, result.asOf(), fetchedAt);

            for (Map.Entry<String, BigDecimal> entry : result.rates().entrySet()) {
                upsert(base, entry.getKey(), entry.getValue(), result.asOf(), fetchedAt);
            }

            log.info("Exchange rate cache refreshed for base={}: {} pairs stored", base, result.rates().size() + 1);
        } finally {
            lock.unlock();
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void upsert(String base, String quote, BigDecimal rate, Instant asOf, Instant fetchedAt) {
        exchangeRateRepository.upsertRate(base, quote, rate, asOf, fetchedAt);
    }

    /**
     * Returns true if every cached row is older than {@code ttlHours}.
     */
    private boolean isCacheExpired(List<ExchangeRate> cached) {
        Instant threshold = Instant.now().minus(appProperties.getExchangeRate().getTtlHours(), ChronoUnit.HOURS);
        return cached.stream().allMatch(r -> r.getFetchedAt().isBefore(threshold));
    }

    /**
     * Looks up the rate for (base → currency) in the cached list.
     *
     * @throws ExchangeRateUnavailableException if no row exists for the requested pair
     */
    private BigDecimal findRate(List<ExchangeRate> cached, String base, String currency) {
        // If currency equals base, rate is 1.0 by definition
        if (currency.equals(base)) return BigDecimal.ONE;

        return cached.stream()
                .filter(r -> r.getQuoteCode().equals(currency))
                .map(ExchangeRate::getRate)
                .findFirst()
                .orElseThrow(() -> new ExchangeRateUnavailableException(
                        "No cached exchange rate for pair " + base + "/" + currency));
    }
}
