package com.fintrack.category.web;

import com.fintrack.category.service.CategoryService;
import com.fintrack.category.web.dto.CategoryResponse;
import com.fintrack.category.web.dto.CreateCategoryRequest;
import com.fintrack.category.web.dto.UpdateCategoryRequest;
import com.fintrack.common.domain.TransactionType;
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
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Categories")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;
    private final IdempotentMutationExecutor idempotentMutationExecutor;
    private final IdempotencyEnforcementGuard idempotencyEnforcementGuard;

    @GetMapping
    @Operation(summary = "List categories (system defaults + user-defined), optionally filtered by type")
    public List<CategoryResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) TransactionType type) {
        return categoryService.list(principal.getUserId(), type);
    }

    @PostMapping
    @Operation(summary = "Create a user-defined category")
    public ResponseEntity<CategoryResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Optional client-generated key (16-128 URL-safe characters) "
                    + "that makes a retried create safe to resend; a retry with the same key and "
                    + "payload replays the original result instead of creating a duplicate category.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCategoryRequest request) {
        if (idempotencyKey == null) {
            idempotencyEnforcementGuard.requireKeyOrThrow("category.create", idempotencyKey);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(categoryService.create(principal.getUserId(), request));
        }
        return idempotentMutationExecutor.execute(principal.getUserId(), "category.create", idempotencyKey,
                request, CategoryResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(categoryService.create(principal.getUserId(), request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a user-defined category (system categories return 403)")
    public CategoryResponse update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return categoryService.update(principal.getUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a user-defined category (transactions reassigned to Uncategorized)")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        categoryService.delete(principal.getUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
