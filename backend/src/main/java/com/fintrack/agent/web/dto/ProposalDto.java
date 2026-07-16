package com.fintrack.agent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A single proposed transaction derived from one receipt line item (or the receipt as a whole).
 * {@code flags} are reviewer-visible validation/confidence markers, e.g. "low-confidence",
 * "totals-mismatch" — attached by deterministic validation, never silently dropped.
 */
public record ProposalDto(
        @NotBlank String merchant,
        @NotNull LocalDate date,
        @NotNull BigDecimal amount,
        @NotBlank String currency,
        Long categoryId,
        Long accountId,
        String description,
        List<String> flags,
        boolean excluded
) {
    public ProposalDto {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    public ProposalDto withFlags(List<String> newFlags) {
        return new ProposalDto(merchant, date, amount, currency, categoryId, accountId, description, newFlags, excluded);
    }

    public ProposalDto withCategoryId(Long newCategoryId) {
        return new ProposalDto(merchant, date, amount, currency, newCategoryId, accountId, description, flags, excluded);
    }
}
