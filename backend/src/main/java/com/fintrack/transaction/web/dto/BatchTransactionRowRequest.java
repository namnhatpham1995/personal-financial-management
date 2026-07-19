package com.fintrack.transaction.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One row of a batch transaction create request. {@code clientRequestId} is the row's public
 * request-identity token — it plays the same role as the {@code Idempotency-Key} header does for
 * a single create, but scoped to this row rather than the whole batch request. Rules mirror
 * {@link com.fintrack.idempotency.service.IdempotencyKeyValidator} exactly (16-128 URL-safe
 * characters) so row claims can reuse the same idempotency infrastructure as the
 * {@code Idempotency-Key} header.
 */
public record BatchTransactionRowRequest(
        @NotBlank
        @Size(min = 16, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "clientRequestId must contain only letters, digits, '-', or '_'")
        String clientRequestId,

        @NotNull @Valid CreateTransactionRequest transaction
) {}
