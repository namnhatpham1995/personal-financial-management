package com.fintrack.vault.repository;

import com.fintrack.vault.domain.VaultDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;

public interface VaultSearchRepository {

    /**
     * Searches vault documents by optional filters. Line-item text matching uses
     * a MongoDB aggregation pipeline {@code $filter} over nested arrays so it works
     * across differently-shaped source documents.
     */
    Page<VaultDocument> search(
            Long userId,
            String merchant,
            Instant from,
            Instant to,
            String lineItemText,
            BigDecimal maxLineItemAmount,
            Pageable pageable
    );
}
