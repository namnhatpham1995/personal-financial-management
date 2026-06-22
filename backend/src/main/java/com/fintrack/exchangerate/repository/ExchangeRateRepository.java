package com.fintrack.exchangerate.repository;

import com.fintrack.exchangerate.domain.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    List<ExchangeRate> findByBaseCode(String baseCode);

    Optional<ExchangeRate> findByBaseCodeAndQuoteCode(String baseCode, String quoteCode);

    /**
     * Native upsert: insert or update on the unique (base_code, quote_code) pair.
     * Keeps fetched_at current so staleness checks work correctly.
     */
    @Modifying
    @Query(value = """
            INSERT INTO exchange_rates (base_code, quote_code, rate, as_of, fetched_at)
            VALUES (:base, :quote, :rate, :asOf, :fetchedAt)
            ON CONFLICT (base_code, quote_code)
            DO UPDATE SET rate = EXCLUDED.rate,
                          as_of = EXCLUDED.as_of,
                          fetched_at = EXCLUDED.fetched_at
            """, nativeQuery = true)
    void upsertRate(@Param("base") String base,
                    @Param("quote") String quote,
                    @Param("rate") BigDecimal rate,
                    @Param("asOf") Instant asOf,
                    @Param("fetchedAt") Instant fetchedAt);
}
