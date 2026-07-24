package com.fintrack.vault.web.dto;

public record StagedRowResponse(
        String date,
        String amount,
        String type,
        String description,
        String dedupKey,
        /** Category text as parsed from the source file (e.g. a CSV category column), if any. */
        String category,
        /**
         * Existing category matched by name (case-insensitive) to {@link #category}, scoped to the
         * row's transaction type. Null when there was no category text or no match — the row is
         * shown uncategorized and remains user-editable during review.
         */
        Long categoryId
) {}
