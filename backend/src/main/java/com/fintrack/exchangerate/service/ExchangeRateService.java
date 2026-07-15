package com.fintrack.exchangerate.service;

import com.fintrack.common.config.AppProperties;
import com.fintrack.common.config.CacheConfig;
import com.fintrack.exchangerate.domain.ExchangeRate;
import com.fintrack.exchangerate.exception.ExchangeRateUnavailableException;
import com.fintrack.exchangerate.provider.ExchangeRateProvider;
import com.fintrack.exchangerate.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
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
 * <p>Rates are persisted in PostgreSQL (DB-level cache, TTL = {@code ttlHours}) and also
 * cached at the application level via {@link #getRates(String)} (Redis in prod, simple
 * in-process cache locally). The application cache TTL (1 h) is shorter than the DB TTL
 * (24 h) so entries always represent fresh DB-level data.
 *
 * <p>{@link #getRates} is annotated {@code @Cacheable} but is also called internally by
 * {@link #convert}. To avoid the Spring proxy self-invocation bypass, {@code convert} calls
 * {@code self.getRates(base)} where {@code self} is a lazily-injected proxy of this bean.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private static final Set<String> SEED_CURRENCIES = Set.of("USD", "EUR", "VND", "GBP", "JPY", "SGD");

    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateProvider   exchangeRateProvider;
    private final AppProperties          appProperties;

    /** Self-reference so that {@code @Cacheable} on {@link #getRates} fires through the proxy. */
    @Lazy
    @Autowired
    ExchangeRateService self;

    @SuppressWarnings("squid:S3077")
    private ConcurrentHashMap<String, ReentrantLock> perBaseLocks = new ConcurrentHashMap<>();

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Converts {@code amount} from {@code from} to {@code to} using cross-rates.
     * Reads rates via {@code self.getRates(base)} so the {@code @Cacheable} proxy fires.
     */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return amount.setScale(4, RoundingMode.HALF_UP);
        }
        String base = appProperties.getExchangeRate().getBase();
        RateSnapshot snapshot = self.getRates(base);
        BigDecimal rateFrom = findRate(snapshot, base, from);
        BigDecimal rateTo   = findRate(snapshot, base, to);
        return amount
                .multiply(rateTo)
                .divide(rateFrom, 10, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Returns a snapshot of all rates for {@code base}, triggering a DB refresh if stale.
     * Result is cached at the application level (TTL 1 h).
     */
    @Cacheable(value = CacheConfig.EXCHANGE_RATES, key = "#base")
    public RateSnapshot getRates(String base) {
        List<ExchangeRate> cached = exchangeRateRepository.findByBaseCode(base);
        if (cached.isEmpty() || isCacheExpired(cached)) {
            // Self-invocation would bypass refresh()'s own @Transactional proxy interception
            // (same self-invocation pitfall documented on convert()) — go through self so a
            // transaction always backs the @Modifying upsert, even when the caller (e.g. a
            // REST controller) has no outer @Transactional boundary of its own.
            self.refresh(base);
            cached = exchangeRateRepository.findByBaseCode(base);
        }
        return toSnapshot(cached);
    }

    /**
     * Returns the supported currency codes, falling back to {@link #SEED_CURRENCIES} when the
     * cache is cold and the provider is unreachable so that account creation never blocks.
     */
    public Set<String> supportedCurrencies() {
        String base = appProperties.getExchangeRate().getBase();
        try {
            RateSnapshot snapshot = self.getRates(base);
            return snapshot.rates().stream()
                    .map(RateSnapshot.RatePair::quoteCode)
                    .collect(Collectors.toSet());
        } catch (ExchangeRateUnavailableException e) {
            log.warn("supportedCurrencies: rate cache unavailable, returning seed fallback. Reason: {}", e.getMessage());
            return SEED_CURRENCIES;
        }
    }

    public Optional<Instant> getAsOf(String base) {
        return exchangeRateRepository.findByBaseCode(base).stream()
                .map(ExchangeRate::getAsOf)
                .max(Instant::compareTo);
    }

    public boolean isStale(String base) {
        List<ExchangeRate> cached = exchangeRateRepository.findByBaseCode(base);
        if (cached.isEmpty()) return true;
        Instant threshold = Instant.now().minus(appProperties.getExchangeRate().getStaleHours(), ChronoUnit.HOURS);
        return cached.stream().allMatch(r -> r.getFetchedAt().isBefore(threshold));
    }

    /**
     * Fetches fresh rates from the provider and upserts them into the DB.
     * Evicts the application-level cache so the next {@link #getRates} call re-populates it.
     * Called by the daily scheduler (cross-bean → proxy fires the eviction).
     */
    @Transactional
    @CacheEvict(value = CacheConfig.EXCHANGE_RATES, key = "#base")
    public void refresh(String base) {
        ReentrantLock lock = perBaseLocks.computeIfAbsent(base, k -> new ReentrantLock());
        lock.lock();
        try {
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
                throw ex;
            }

            Instant fetchedAt = Instant.now();
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

    private RateSnapshot toSnapshot(List<ExchangeRate> rates) {
        return new RateSnapshot(rates.stream()
                .map(r -> new RateSnapshot.RatePair(r.getQuoteCode(), r.getRate()))
                .toList());
    }

    private void upsert(String base, String quote, BigDecimal rate, Instant asOf, Instant fetchedAt) {
        exchangeRateRepository.upsertRate(base, quote, rate, asOf, fetchedAt);
    }

    private boolean isCacheExpired(List<ExchangeRate> cached) {
        Instant threshold = Instant.now().minus(appProperties.getExchangeRate().getTtlHours(), ChronoUnit.HOURS);
        return cached.stream().allMatch(r -> r.getFetchedAt().isBefore(threshold));
    }

    private BigDecimal findRate(RateSnapshot snapshot, String base, String currency) {
        if (currency.equals(base)) return BigDecimal.ONE;
        return snapshot.rates().stream()
                .filter(p -> p.quoteCode().equals(currency))
                .map(RateSnapshot.RatePair::rate)
                .findFirst()
                .orElseThrow(() -> new ExchangeRateUnavailableException(
                        "No cached exchange rate for pair " + base + "/" + currency));
    }
}
