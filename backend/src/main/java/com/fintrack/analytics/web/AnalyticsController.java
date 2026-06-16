package com.fintrack.analytics.web;

import com.fintrack.analytics.service.AnalyticsService;
import com.fintrack.analytics.web.dto.BudgetProgressDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.NetWorthDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/spending-by-category")
    @Operation(summary = "Spending breakdown by category for a date range")
    public List<SpendingByCategoryDto> spendingByCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.getSpendingByCategory(principal.getUserId(), from, to);
    }

    @GetMapping("/income-vs-expense")
    @Operation(summary = "Monthly income vs expense trend for a date range")
    public List<IncomeExpenseTrendDto> incomeVsExpense(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.getIncomeExpenseTrend(principal.getUserId(), from, to);
    }

    @GetMapping("/budget-progress")
    @Operation(summary = "Current period progress for all active budgets")
    public List<BudgetProgressDto> budgetProgress(
            @AuthenticationPrincipal UserPrincipal principal) {
        return analyticsService.getBudgetProgress(principal.getUserId());
    }

    @GetMapping("/net-worth")
    @Operation(summary = "Net worth: total assets minus liabilities across all accounts")
    public NetWorthDto netWorth(
            @AuthenticationPrincipal UserPrincipal principal) {
        return analyticsService.getNetWorth(principal.getUserId());
    }
}
