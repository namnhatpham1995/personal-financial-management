package com.fintrack.analytics.web;

import com.fintrack.analytics.service.AnalyticsService;
import com.fintrack.analytics.web.dto.BudgetProgressDto;
import com.fintrack.analytics.web.dto.ConvertedOverviewDto;
import com.fintrack.analytics.web.dto.CurrencyBalanceDto;
import com.fintrack.analytics.web.dto.IncomeExpenseTrendDto;
import com.fintrack.analytics.web.dto.IncomingTransferTotalDto;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/spending-by-category")
    @Operation(summary = "Spending breakdown by category for a date range, optionally scoped to one account")
    public List<SpendingByCategoryDto> spendingByCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId) {
        return analyticsService.getSpendingByCategory(principal.getUserId(), from, to, accountId);
    }

    @GetMapping("/incoming-transfer-total")
    @Operation(summary = "Total incoming transfers into an account for a date range")
    public IncomingTransferTotalDto incomingTransferTotal(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new IncomingTransferTotalDto(
                accountId,
                analyticsService.getIncomingTransferTotal(principal.getUserId(), accountId, from, to));
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

    @GetMapping("/balances")
    @Operation(summary = "Account balances grouped by currency, with a total per currency; " +
            "pass targetCurrency to additionally receive a converted grand total")
    public ResponseEntity<?> balances(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z]{3}$") String targetCurrency) {
        if (targetCurrency == null) {
            return ResponseEntity.ok(analyticsService.getBalances(principal.getUserId()));
        }
        return ResponseEntity.ok(analyticsService.getConvertedBalances(principal.getUserId(), targetCurrency));
    }

    @GetMapping("/overview")
    @Operation(summary = "All analytics converted into a single target currency")
    public ResponseEntity<ConvertedOverviewDto> getOverview(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String targetCurrency,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(
                analyticsService.getOverview(principal.getUserId(), targetCurrency, from, to));
    }
}
