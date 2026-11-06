package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record ExcludedCurrencyResponse(String currency, BigDecimal nativeAmount) {}
