package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record ExcludedCurrencyDto(String currency, BigDecimal nativeAmount) {}
