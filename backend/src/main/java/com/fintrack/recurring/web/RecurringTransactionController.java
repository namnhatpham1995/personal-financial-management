package com.fintrack.recurring.web;

import com.fintrack.common.security.UserPrincipal;
import com.fintrack.idempotency.service.IdempotencyEnforcementGuard;
import com.fintrack.idempotency.service.IdempotentMutationExecutor;
import com.fintrack.recurring.service.RecurringTransactionService;
import com.fintrack.recurring.web.dto.CreateRecurringRequest;
import com.fintrack.recurring.web.dto.RecurringResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/v1/recurring-transactions")
@RequiredArgsConstructor
@Tag(name = "Recurring Transactions")
@SecurityRequirement(name = "bearerAuth")
public class RecurringTransactionController {

    private final RecurringTransactionService recurringService;
    private final IdempotentMutationExecutor idempotentMutationExecutor;
    private final IdempotencyEnforcementGuard idempotencyEnforcementGuard;

    @PostMapping
    @Operation(summary = "Create a recurring transaction definition")
    public ResponseEntity<RecurringResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Optional client-generated key (16-128 URL-safe characters) "
                    + "that makes a retried create safe to resend; a retry with the same key and "
                    + "payload replays the original result instead of creating a duplicate "
                    + "recurring definition.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRecurringRequest request) {
        if (idempotencyKey == null) {
            idempotencyEnforcementGuard.requireKeyOrThrow(idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(recurringService.create(principal.getUserId(), request));
        }
        return idempotentMutationExecutor.execute(principal.getUserId(), "recurring.create", idempotencyKey,
                request, RecurringResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(recurringService.create(principal.getUserId(), request)));
    }

    @GetMapping
    @Operation(summary = "List all recurring transaction definitions")
    public List<RecurringResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return recurringService.listByUser(principal.getUserId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a recurring definition by id")
    public RecurringResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return recurringService.getById(principal.getUserId(), id);
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a recurring definition — stops future generation")
    public RecurringResponse pause(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return recurringService.pause(principal.getUserId(), id);
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a paused recurring definition")
    public RecurringResponse resume(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return recurringService.resume(principal.getUserId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a definition (already-generated transactions are retained)")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        recurringService.delete(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
