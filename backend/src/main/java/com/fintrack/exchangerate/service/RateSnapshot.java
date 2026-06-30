package com.fintrack.exchangerate.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable snapshot of exchange-rate pairs for a single base currency.
 * Cached in Redis / simple cache instead of the raw {@code ExchangeRate} JPA entity
 * to avoid Hibernate-proxy serialisation issues.
 */
public record RateSnapshot(List<RatePair> rates) {

    public record RatePair(String quoteCode, BigDecimal rate) {}
}
