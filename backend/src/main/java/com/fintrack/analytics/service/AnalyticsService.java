package com.fintrack.analytics.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.repository.AnalyticsRepository;
import com.fintrack.analytics.web.dto.BudgetProgressDto;
import com.fintrack.analytics.web.dto.ConvertedOverviewDto;
import com.fintrack.analytics.web.dto.ConvertedSpendingDto;
import com.fintrack.analytics.web.dto.ConvertedTrendDto;
import com.fintrack.analytics.web.dto.CurrencyNetWorthDto;
import com.fintrack.analytics.web.dto.ExcludedCurrencyDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.RateUsedDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.repository.BudgetRepository;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.config.AppProperties;
import com.fintrack.common.config.CacheConfig;
import com.fintrack.exchangerate.exception.ExchangeRateUnavailableException;
import com.fintrack.exchangerate.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;
    private final ExchangeRateService exchangeRateService;
    private final AppProperties appProperties;
    private final CacheVersionService cacheVersionService;

    /**
     * Returns all per-currency analytics (net worth, spending, trend) converted into
     * {@code targetCurrency}. The overview ALWAYS returns successfully — if rates are
     * unavailable for a given source currency, that currency is added to
     * {@code excludedCurrencies} and {@code ratesUnavailable} is set to {@code true}.
     *
     * <p>Math rule: intermediate BigDecimal scale is kept ≥ 10 during conversion;
     * final totals are rounded to scale 4 HALF_UP to prevent VND rows from truncating
     * to 0.0000 before summing.
     */
    @Cacheable(
            value = CacheConfig.ANALYTICS,
            key = "#userId + ':v' + @cacheVersionService.current(#userId) + ':overview:' + #targetCurrency + ':' + #from + ':' + #to"
    )
    @Transactional(readOnly = true)
    public ConvertedOverviewDto getOverview(Long userId, String targetCurrency, LocalDate from, LocalDate to) {
        String base = appProperties.getExchangeRate().getBase();

        // ── 1. Fetch per-currency raw data ────────────────────────────────────
        List<CurrencyNetWorthDto> netWorthBuckets = getNetWorth(userId);
        List<SpendingByCategoryDto> spendingRows  = analyticsRepository.spendingByCategory(userId, from, to, null);
        List<IncomeExpenseTrendDto> trendRows      = analyticsRepository.incomeExpenseTrend(userId, from, to);

        // ── 2. Accumulators ───────────────────────────────────────────────────
        BigDecimal totalAssets      = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        // (currency, categoryId) → [convertedAmount, transactionCount, categoryName]
        Map<String, BigDecimal[]> spendingAccum = new HashMap<>();
        // "year-month" → [convertedIncome, convertedExpense]
        Map<String, BigDecimal[]> trendAccum    = new HashMap<>();

        boolean ratesUnavailable = false;
        List<ExcludedCurrencyDto> excludedCurrencies = new ArrayList<>();
        // Track currencies that were successfully converted (and are not the target)
        java.util.Set<String> convertedSourceCurrencies = new java.util.LinkedHashSet<>();
        // Track currencies that failed — used to skip rate-entry building later
        java.util.Set<String> failedCurrencies = new java.util.HashSet<>();

        // ── 3. Convert net worth ──────────────────────────────────────────────
        for (CurrencyNetWorthDto bucket : netWorthBuckets) {
            String currency = bucket.currency();
            if (currency.equals(targetCurrency)) {
                // Identity: pass through unconverted
                totalAssets      = totalAssets.add(bucket.totalAssets());
                totalLiabilities = totalLiabilities.add(bucket.totalLiabilities());
            } else {
                try {
                    // Sum-then-convert: bucket already has summed assets/liabilities per currency
                    BigDecimal convertedAssets      = exchangeRateService.convert(bucket.totalAssets(), currency, targetCurrency);
                    BigDecimal convertedLiabilities = exchangeRateService.convert(bucket.totalLiabilities(), currency, targetCurrency);
                    totalAssets      = totalAssets.add(convertedAssets);
                    totalLiabilities = totalLiabilities.add(convertedLiabilities);
                    convertedSourceCurrencies.add(currency);
                } catch (ExchangeRateUnavailableException ex) {
                    log.warn("Rate unavailable for net worth conversion {} → {}: {}", currency, targetCurrency, ex.getMessage());
                    ratesUnavailable = true;
                    failedCurrencies.add(currency);
                    BigDecimal nativeNetWorth = bucket.netWorth();
                    excludedCurrencies.add(new ExcludedCurrencyDto(currency, nativeNetWorth));
                }
            }
        }

        // ── 4. Convert spending — sum native amounts per (currency, categoryId) first ──
        // Group: currency → categoryId → [nativeTotal, count, name]
        Map<String, Map<Long, Object[]>> spendingNative = new HashMap<>();
        for (SpendingByCategoryDto row : spendingRows) {
            spendingNative
                .computeIfAbsent(row.currency(), k -> new HashMap<>())
                .merge(row.categoryId(), new Object[]{row.total(), row.transactionCount(), row.categoryName()},
                    (existing, incoming) -> new Object[]{
                        ((BigDecimal) existing[0]).add((BigDecimal) incoming[0]),
                        (long) existing[1] + (long) incoming[1],
                        existing[2]
                    });
        }

        for (Map.Entry<String, Map<Long, Object[]>> currencyEntry : spendingNative.entrySet()) {
            String currency = currencyEntry.getKey();
            if (failedCurrencies.contains(currency)) continue; // already excluded

            for (Map.Entry<Long, Object[]> catEntry : currencyEntry.getValue().entrySet()) {
                Long      categoryId   = catEntry.getKey();
                Object[]  agg         = catEntry.getValue();
                BigDecimal nativeTotal = (BigDecimal) agg[0];
                long       count       = (long) agg[1];
                // agg[2] holds categoryName — resolved via originalRows in buildSpendingList
                String     mapKey      = categoryId.toString();

                BigDecimal convertedAmount;
                if (currency.equals(targetCurrency)) {
                    convertedAmount = nativeTotal;
                } else {
                    try {
                        convertedAmount = exchangeRateService.convert(nativeTotal, currency, targetCurrency);
                        convertedSourceCurrencies.add(currency);
                    } catch (ExchangeRateUnavailableException ex) {
                        log.warn("Rate unavailable for spending conversion {} → {}: {}", currency, targetCurrency, ex.getMessage());
                        ratesUnavailable = true;
                        failedCurrencies.add(currency);
                        // Add to excluded only if not already present from net-worth pass
                        boolean alreadyExcluded = excludedCurrencies.stream()
                            .anyMatch(e -> e.currency().equals(currency));
                        if (!alreadyExcluded) {
                            excludedCurrencies.add(new ExcludedCurrencyDto(currency, nativeTotal));
                        }
                        continue;
                    }
                }

                spendingAccum.merge(mapKey,
                    new BigDecimal[]{convertedAmount, BigDecimal.valueOf(count)},
                    (existing, incoming) -> new BigDecimal[]{
                        existing[0].add(incoming[0]),
                        existing[1].add(incoming[1])
                    });
                // categoryName is resolved in buildSpendingList via the original rows lookup
            }
        }

        // ── 5. Convert trend — sum native income/expense per (currency, year, month) first ──
        Map<String, Map<String, BigDecimal[]>> trendNative = new HashMap<>();
        for (IncomeExpenseTrendDto row : trendRows) {
            String monthKey = row.year() + "-" + row.month();
            trendNative
                .computeIfAbsent(row.currency(), k -> new HashMap<>())
                .merge(monthKey, new BigDecimal[]{row.totalIncome(), row.totalExpense()},
                    (existing, incoming) -> new BigDecimal[]{
                        existing[0].add(incoming[0]),
                        existing[1].add(incoming[1])
                    });
        }

        for (Map.Entry<String, Map<String, BigDecimal[]>> currencyEntry : trendNative.entrySet()) {
            String currency = currencyEntry.getKey();
            if (failedCurrencies.contains(currency)) continue;

            for (Map.Entry<String, BigDecimal[]> monthEntry : currencyEntry.getValue().entrySet()) {
                String     monthKey      = monthEntry.getKey();
                BigDecimal nativeIncome  = monthEntry.getValue()[0];
                BigDecimal nativeExpense = monthEntry.getValue()[1];

                BigDecimal convertedIncome;
                BigDecimal convertedExpense;
                if (currency.equals(targetCurrency)) {
                    convertedIncome  = nativeIncome;
                    convertedExpense = nativeExpense;
                } else {
                    try {
                        convertedIncome  = exchangeRateService.convert(nativeIncome,  currency, targetCurrency);
                        convertedExpense = exchangeRateService.convert(nativeExpense, currency, targetCurrency);
                        convertedSourceCurrencies.add(currency);
                    } catch (ExchangeRateUnavailableException ex) {
                        log.warn("Rate unavailable for trend conversion {} → {}: {}", currency, targetCurrency, ex.getMessage());
                        ratesUnavailable = true;
                        failedCurrencies.add(currency);
                        boolean alreadyExcluded = excludedCurrencies.stream()
                            .anyMatch(e -> e.currency().equals(currency));
                        if (!alreadyExcluded) {
                            excludedCurrencies.add(new ExcludedCurrencyDto(currency, nativeIncome.add(nativeExpense)));
                        }
                        continue;
                    }
                }

                trendAccum.merge(monthKey,
                    new BigDecimal[]{convertedIncome, convertedExpense},
                    (existing, incoming) -> new BigDecimal[]{
                        existing[0].add(incoming[0]),
                        existing[1].add(incoming[1])
                    });
            }
        }

        // ── 6. Assemble final totals ──────────────────────────────────────────
        BigDecimal finalAssets      = totalAssets.setScale(4, RoundingMode.HALF_UP);
        BigDecimal finalLiabilities = totalLiabilities.setScale(4, RoundingMode.HALF_UP);
        BigDecimal finalNetWorth    = finalAssets.subtract(finalLiabilities);

        // ── 7. Build spending list ────────────────────────────────────────────
        List<ConvertedSpendingDto> spendingList = buildSpendingList(spendingAccum, spendingRows);

        // ── 8. Build trend list ───────────────────────────────────────────────
        List<ConvertedTrendDto> trendList = trendAccum.entrySet().stream()
            .map(e -> {
                String[] parts   = e.getKey().split("-");
                int year         = Integer.parseInt(parts[0]);
                int month        = Integer.parseInt(parts[1]);
                BigDecimal inc   = e.getValue()[0].setScale(4, RoundingMode.HALF_UP);
                BigDecimal exp   = e.getValue()[1].setScale(4, RoundingMode.HALF_UP);
                BigDecimal net   = inc.subtract(exp);
                return new ConvertedTrendDto(year, month, inc, exp, net);
            })
            .sorted(Comparator.comparingInt(ConvertedTrendDto::year)
                .thenComparingInt(ConvertedTrendDto::month))
            .toList();

        // ── 9. Build rates list ───────────────────────────────────────────────
        List<RateUsedDto> rates = new ArrayList<>();
        for (String currency : convertedSourceCurrencies) {
            if (!currency.equals(targetCurrency)) {
                try {
                    BigDecimal displayRate = exchangeRateService.convert(BigDecimal.ONE, currency, targetCurrency);
                    rates.add(new RateUsedDto(currency, targetCurrency, displayRate));
                } catch (ExchangeRateUnavailableException ex) {
                    log.warn("Could not compute display rate {} → {}: {}", currency, targetCurrency, ex.getMessage());
                }
            }
        }

        // ── 10. Metadata ──────────────────────────────────────────────────────
        Instant asOf  = exchangeRateService.getAsOf(base).orElse(null);
        boolean stale = exchangeRateService.isStale(base);

        return new ConvertedOverviewDto(
            targetCurrency,
            finalNetWorth,
            finalAssets,
            finalLiabilities,
            spendingList,
            trendList,
            rates,
            asOf,
            ratesUnavailable,
            stale,
            excludedCurrencies
        );
    }

    /**
     * Builds the merged spending list from the accumulated converted amounts.
     * Category names are resolved from the original spending rows by categoryId.
     */
    private List<ConvertedSpendingDto> buildSpendingList(
            Map<String, BigDecimal[]> spendingAccum,
            List<SpendingByCategoryDto> originalRows) {

        // Build categoryId → name lookup from original rows
        Map<Long, String> categoryNames = originalRows.stream()
            .collect(Collectors.toMap(
                SpendingByCategoryDto::categoryId,
                SpendingByCategoryDto::categoryName,
                (a, b) -> a // keep first
            ));

        return spendingAccum.entrySet().stream()
            .map(e -> {
                Long       categoryId   = Long.parseLong(e.getKey());
                BigDecimal amount       = e.getValue()[0].setScale(4, RoundingMode.HALF_UP);
                long       count        = e.getValue()[1].longValue();
                String     categoryName = categoryNames.getOrDefault(categoryId, "Unknown");
                return new ConvertedSpendingDto(categoryId, categoryName, amount, count);
            })
            .sorted(Comparator.comparing(ConvertedSpendingDto::totalAmount).reversed())
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SpendingByCategoryDto> getSpendingByCategory(
            Long userId, LocalDate from, LocalDate to, Long accountId) {
        // Scope to a single account only when the caller owns it; otherwise return
        // an empty breakdown rather than leaking another user's data.
        if (accountId != null && !accountRepository.existsByIdAndUserId(accountId, userId)) {
            return List.of();
        }
        return analyticsRepository.spendingByCategory(userId, from, to, accountId);
    }

    /**
     * Total incoming transfers (this account as counterparty) in the range.
     * Returns zero when the account is not owned or has no incoming transfers.
     */
    @Transactional(readOnly = true)
    public BigDecimal getIncomingTransferTotal(
            Long userId, Long accountId, LocalDate from, LocalDate to) {
        if (!accountRepository.existsByIdAndUserId(accountId, userId)) {
            return BigDecimal.ZERO;
        }
        return analyticsRepository.incomingTransferTotal(userId, accountId, from, to);
    }

    @Transactional(readOnly = true)
    public List<IncomeExpenseTrendDto> getIncomeExpenseTrend(Long userId, LocalDate from, LocalDate to) {
        return analyticsRepository.incomeExpenseTrend(userId, from, to);
    }

    @Transactional(readOnly = true)
    public List<BudgetProgressDto> getBudgetProgress(Long userId) {
        LocalDate today = LocalDate.now();
        List<Budget> budgets = budgetRepository.findAllByUserId(userId);
        return budgets.stream().map(b -> buildProgress(b, today)).toList();
    }

    @Transactional(readOnly = true)
    public List<CurrencyNetWorthDto> getNetWorth(Long userId) {
        List<Account> accounts = accountRepository.findAllByUserId(userId);
        Map<String, List<Account>> byCurrency = accounts.stream()
                .collect(Collectors.groupingBy(Account::getCurrency));

        return byCurrency.entrySet().stream().map(entry -> {
            String currency = entry.getKey();
            List<Account> group = entry.getValue();
            BigDecimal assets = BigDecimal.ZERO;
            BigDecimal liabilities = BigDecimal.ZERO;
            for (Account a : group) {
                if ("CREDIT_CARD".equals(a.getAccountType().name())) {
                    liabilities = liabilities.add(a.getCurrentBalance());
                } else {
                    assets = assets.add(a.getCurrentBalance());
                }
            }
            List<CurrencyNetWorthDto.AccountBalanceDto> dtos = group.stream()
                    .map(a -> new CurrencyNetWorthDto.AccountBalanceDto(
                            a.getId(), a.getName(), a.getAccountType().name(), a.getCurrentBalance()))
                    .toList();
            return new CurrencyNetWorthDto(currency, assets, liabilities, assets.subtract(liabilities), dtos);
        }).toList();
    }

    private BudgetProgressDto buildProgress(Budget budget, LocalDate today) {
        LocalDate[] bounds = periodBounds(budget, today);
        BigDecimal spent = budget.getCategory() != null
                ? budgetRepository.sumSpentInPeriod(
                        budget.getUser().getId(), budget.getCategory().getId(),
                        bounds[0], bounds[1], budget.getCurrency())
                : BigDecimal.ZERO;
        if (spent == null) spent = BigDecimal.ZERO;

        BigDecimal limit = budget.getAmountLimit();
        BigDecimal remaining = limit.subtract(spent);
        BigDecimal percent = limit.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : spent.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP);

        return new BudgetProgressDto(
                budget.getId(),
                budget.getCategory() != null ? budget.getCategory().getName() : null,
                budget.getCategory() != null ? budget.getCategory().getName() : null,
                budget.getCurrency(),
                limit,
                spent,
                remaining,
                percent,
                spent.compareTo(limit) > 0
        );
    }

    private LocalDate[] periodBounds(Budget budget, LocalDate today) {
        return switch (budget.getPeriod()) {
            case MONTHLY -> {
                YearMonth ym = YearMonth.from(today);
                yield new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
            }
            case YEARLY -> new LocalDate[]{
                    LocalDate.of(today.getYear(), 1, 1),
                    LocalDate.of(today.getYear(), 12, 31)
            };
        };
    }
}
