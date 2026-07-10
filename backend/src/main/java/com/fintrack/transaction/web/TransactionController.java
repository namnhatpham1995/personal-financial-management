package com.fintrack.transaction.web;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.dto.PageResponse;
import com.fintrack.common.security.UserPrincipal;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.transaction.web.dto.BatchTransactionRequest;
import com.fintrack.transaction.web.dto.BatchTransactionResponse;
import com.fintrack.transaction.web.dto.TransactionResponse;
import com.fintrack.transaction.web.dto.UpdateTransactionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    @Operation(summary = "Create a transaction (INCOME, EXPENSE, or TRANSFER)")
    public ResponseEntity<TransactionResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.create(principal.getUserId(), request));
    }

    @PostMapping("/batch")
    @Operation(summary = "Create transactions independently and return a result for every row")
    public ResponseEntity<BatchTransactionResponse> createBatch(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BatchTransactionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.createBatch(principal.getUserId(), request.transactions()));
    }

    @GetMapping
    @Operation(summary = "List transactions with optional filters (defaults to all accounts)")
    public PageResponse<TransactionResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) Long transferAccountId,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return transactionService.list(principal.getUserId(), accountId, startDate, endDate,
                categoryId, type, note, transferAccountId, currency, page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single transaction by id")
    public TransactionResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return transactionService.getById(principal.getUserId(), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a transaction (amount, date, category, note)")
    public TransactionResponse update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTransactionRequest request) {
        return transactionService.update(principal.getUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a transaction and reverse its balance effect")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        transactionService.delete(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
