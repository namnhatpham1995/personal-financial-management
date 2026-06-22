package com.fintrack.exchangerate.provider;

import com.fintrack.exchangerate.exception.ExchangeRateUnavailableException;
import com.fintrack.exchangerate.provider.dto.OpenErApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Fetches rates from open.er-api.com/v6/latest/{base} (free tier, no API key required).
 * Uses {@link RestClient} with explicit connect/read timeouts configured in {@code RestClientConfig}.
 * URL is constructed with {@link UriComponentsBuilder#pathSegment} to prevent SSRF via path traversal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenErApiExchangeRateProvider implements ExchangeRateProvider {

    private final RestClient exchangeRateRestClient;

    @Value("${app.exchange-rate.provider-url}")
    private String providerUrl;

    @Override
    public Result fetchLatest(String base) {
        URI uri = UriComponentsBuilder.fromHttpUrl(providerUrl)
                .pathSegment(base)
                .build()
                .toUri();

        log.debug("Fetching exchange rates from {}", uri);

        OpenErApiResponse response;
        try {
            response = exchangeRateRestClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OpenErApiResponse.class);
        } catch (RestClientException ex) {
            log.warn("HTTP error fetching exchange rates for base={}: {}", base, ex.getMessage());
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider unavailable for base=" + base, ex);
        }

        if (response == null || !"success".equals(response.result())) {
            String resultCode = response == null ? "null response" : response.result();
            log.warn("Exchange rate provider returned non-success result: {}", resultCode);
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider returned failure result for base=" + base);
        }

        Map<String, BigDecimal> rates = response.rates();
        if (rates == null || rates.isEmpty()) {
            throw new ExchangeRateUnavailableException(
                    "Exchange rate provider returned empty rates for base=" + base);
        }

        // Ensure no non-positive rates slip through before we persist them
        rates.entrySet().removeIf(entry -> {
            boolean invalid = entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) <= 0;
            if (invalid) {
                log.warn("Dropping invalid rate entry: {}={}", entry.getKey(), entry.getValue());
            }
            return invalid;
        });

        Instant asOf = Instant.ofEpochSecond(response.timeLastUpdateUnix());
        log.info("Fetched {} rates for base={}, asOf={}", rates.size(), base, asOf);

        return new Result(response.baseCode(), asOf, rates);
    }
}
