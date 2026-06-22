package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record ExcludedCurrencyDto(String currency, BigDecimal nativeAmount) {}
