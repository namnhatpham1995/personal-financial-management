package com.fintrack.analytics.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.repository.AnalyticsRepository;
import com.fintrack.analytics.web.dto.BudgetProgressDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.NetWorthDto;
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
    public NetWorthDto getNetWorth(Long userId) {
        List<Account> accounts = accountRepository.findAllByUserId(userId);

        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;

        List<NetWorthDto.AccountBalanceDto> accountDtos = accounts.stream().map(a -> {
            return new NetWorthDto.AccountBalanceDto(
                    a.getId(), a.getName(), a.getAccountType().name(), a.getCurrentBalance());
        }).toList();

        for (Account a : accounts) {
            // CREDIT_CARD balances represent liabilities (debt owed)
            if (a.getAccountType().name().equals("CREDIT_CARD")) {
                liabilities = liabilities.add(a.getCurrentBalance());
            } else {
                assets = assets.add(a.getCurrentBalance());
            }
        }

        return new NetWorthDto(assets, liabilities, assets.subtract(liabilities), accountDtos);
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
