package com.fintrack.account.web;

import com.fintrack.account.service.AccountService;
import com.fintrack.account.web.dto.AccountDeletePreviewDto;
import com.fintrack.account.web.dto.AccountResponse;
import com.fintrack.account.web.dto.CreateAccountRequest;
import com.fintrack.account.web.dto.UpdateAccountRequest;
import com.fintrack.common.security.UserPrincipal;
import com.fintrack.idempotency.service.IdempotencyEnforcementGuard;
import com.fintrack.idempotency.service.IdempotentMutationExecutor;
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
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;
    private final IdempotentMutationExecutor idempotentMutationExecutor;
    private final IdempotencyEnforcementGuard idempotencyEnforcementGuard;

    @PostMapping
    @Operation(summary = "Create a new account")
    public ResponseEntity<AccountResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Optional client-generated key (16-128 URL-safe characters) "
                    + "that makes a retried create safe to resend; a retry with the same key and "
                    + "payload replays the original result instead of creating a duplicate account.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAccountRequest request) {
        if (idempotencyKey == null) {
            idempotencyEnforcementGuard.requireKeyOrThrow(idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(accountService.create(principal.getUserId(), request));
        }
        return idempotentMutationExecutor.execute(principal.getUserId(), "account.create", idempotencyKey,
                request, AccountResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(accountService.create(principal.getUserId(), request)));
    }

    @GetMapping
    @Operation(summary = "List all accounts for the current user")
    public List<AccountResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return accountService.listByUser(principal.getUserId());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single account by id")
    public AccountResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return accountService.getById(principal.getUserId(), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an account")
    public AccountResponse update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateAccountRequest request) {
        return accountService.update(principal.getUserId(), id, request);
    }

    @GetMapping("/{id}/delete-preview")
    @Operation(summary = "Preview how many transactions will be removed if account is deleted")
    public AccountDeletePreviewDto deletePreview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return accountService.getDeletePreview(principal.getUserId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an account and all its connected transactions")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        accountService.delete(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/recompute-balance")
    @Operation(summary = "Recompute account balance from transactions (safety-net)")
    public AccountResponse recomputeBalance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        accountService.recomputeBalance(principal.getUserId(), id);
        return accountService.getById(principal.getUserId(), id);
    }
}
