package com.fintrack.apitoken.web;

import com.fintrack.apitoken.service.ApiTokenService;
import com.fintrack.apitoken.web.dto.ApiTokenResponse;
import com.fintrack.apitoken.web.dto.CreateApiTokenRequest;
import com.fintrack.apitoken.web.dto.CreatedApiTokenResponse;
import com.fintrack.common.security.UserPrincipal;
import com.fintrack.idempotency.service.IdempotencyEnforcementGuard;
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

/**
 * JWT-session-only: a PAT must never be able to mint or manage other PATs.
 * Enforced independently in PatEndpointPolicy's deny-by-default allowlist, which excludes
 * this whole path regardless of token scope.
 */
@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
@Tag(name = "API Tokens")
@SecurityRequirement(name = "bearerAuth")
public class ApiTokenController {

    private final ApiTokenService apiTokenService;
    private final IdempotencyEnforcementGuard idempotencyEnforcementGuard;

    @PostMapping
    @Operation(summary = "Create a personal access token (plaintext returned once)")
    public ResponseEntity<CreatedApiTokenResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Optional client-generated key (16-128 URL-safe characters) "
                    + "that makes a retried create safe to resend; because the plaintext token can "
                    + "never be persisted or replayed, a retry with this key always gets a typed 409 "
                    + "with the original token's metadata instead of creating a second token.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateApiTokenRequest request) {
        idempotencyEnforcementGuard.requireKeyOrThrow(idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiTokenService.create(principal.getUserId(), request, idempotencyKey));
    }

    @GetMapping
    @Operation(summary = "List your API tokens")
    public List<ApiTokenResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return apiTokenService.listByUser(principal.getUserId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an API token")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        apiTokenService.revoke(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
