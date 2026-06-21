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
        String rawLine
) {}
