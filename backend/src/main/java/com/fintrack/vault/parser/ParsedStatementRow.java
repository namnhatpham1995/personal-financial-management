package com.fintrack.vault.parser;

import com.fintrack.common.domain.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Normalized row produced by any statement parser before it is written to MongoDB
 * as a staged VaultDocument payload or confirmed into PostgreSQL as a transaction.
 */
public record ParsedStatementRow(
        LocalDate date,
        BigDecimal amount,
        TransactionType type,
        String description,
        String rawLine,
        /**
         * OFX {@code FITID} — a stable, source-assigned row identity. Null for CSV rows (CSV has
         * no such concept) and for OFX rows whose block omits it. When present, the statement-row
         * fingerprint is built from this alone (see {@code StatementImportService}) instead of the
         * occurrence-ordinal fallback fingerprint.
         */
        String fitId
) {}
