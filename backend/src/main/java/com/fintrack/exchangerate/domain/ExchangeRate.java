package com.fintrack.exchangerate.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cached exchange rate pair. One row per (base_code, quote_code) combination.
 * The UNIQUE constraint on the pair means upserts use ON CONFLICT DO UPDATE.
 */
@Entity
@Table(name = "exchange_rates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO 4217 base currency code, e.g. "USD". */
    @Column(name = "base_code", nullable = false, length = 10)
    private String baseCode;

    /** ISO 4217 quote currency code, e.g. "VND". */
    @Column(name = "quote_code", nullable = false, length = 10)
    private String quoteCode;

    /** How many quote units equal 1 base unit. Must be positive. */
    @Column(nullable = false, precision = 19, scale = 10)
    private BigDecimal rate;

    /** The timestamp the provider reports the rate was last updated. */
    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    /** When we last fetched and stored this row. */
    @CreationTimestamp
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
}
