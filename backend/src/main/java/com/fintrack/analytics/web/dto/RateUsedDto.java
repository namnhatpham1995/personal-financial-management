package com.fintrack.analytics.web.dto;

import java.math.BigDecimal;

public record RateUsedDto(String from, String to, BigDecimal rate) {}
