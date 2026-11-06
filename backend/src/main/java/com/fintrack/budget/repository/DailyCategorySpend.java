package com.fintrack.budget.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Total EXPENSE amount for one category, account currency and day. */
public record DailyCategorySpend(Long categoryId, String currency, LocalDate date, BigDecimal amount) {}
