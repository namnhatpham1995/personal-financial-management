package com.fintrack.exchangerate.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Maps the JSON response from open.er-api.com/v6/latest/{base}.
 * Only the fields we consume are mapped; other fields are silently ignored.
 */
public record OpenErApiResponse(
        String result,

        @JsonProperty("base_code")
        String baseCode,

        @JsonProperty("time_last_update_unix")
        long timeLastUpdateUnix,

        Map<String, BigDecimal> rates
) {}
