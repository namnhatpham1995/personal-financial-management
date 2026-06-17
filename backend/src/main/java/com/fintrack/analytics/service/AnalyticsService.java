package com.fintrack.analytics.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.repository.AnalyticsRepository;
import com.fintrack.analytics.web.dto.BudgetProgressDto;
import com.fintrack.analytics.web.dto.CurrencyNetWorthDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.repository.BudgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;

    @Transactional(readOnly = true)
    public List<SpendingByCategoryDto> getSpendingByCategory(Long userId, LocalDate from, LocalDate to) {
        return analyticsRepository.spendingByCategory(userId, from, to);
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
                        budget.getUser().getId(), budget.getCategory().getId(), bounds[0], bounds[1])
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
