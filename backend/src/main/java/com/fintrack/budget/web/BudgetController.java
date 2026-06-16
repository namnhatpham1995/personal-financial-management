package com.fintrack.budget.web;

import com.fintrack.budget.service.BudgetService;
import com.fintrack.budget.web.dto.BudgetResponse;
import com.fintrack.budget.web.dto.CreateBudgetRequest;
import com.fintrack.budget.web.dto.UpdateBudgetRequest;
import com.fintrack.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/budgets")
@RequiredArgsConstructor
@Tag(name = "Budgets")
@SecurityRequirement(name = "bearerAuth")
public class BudgetController {

    private final BudgetService budgetService;

    @PostMapping
    @Operation(summary = "Create a budget for a category and period")
    public ResponseEntity<BudgetResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateBudgetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(budgetService.create(principal.getUserId(), request));
    }

    @GetMapping
    @Operation(summary = "List all budgets with current-period progress")
    public List<BudgetResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return budgetService.listByUser(principal.getUserId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single budget with progress")
    public BudgetResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return budgetService.getById(principal.getUserId(), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update budget limit or period")
    public BudgetResponse update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateBudgetRequest request) {
        return budgetService.update(principal.getUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a budget (transactions unaffected)")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        budgetService.delete(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
