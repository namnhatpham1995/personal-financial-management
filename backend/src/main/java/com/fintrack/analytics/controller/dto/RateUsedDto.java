package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record RateUsedDto(String from, String to, BigDecimal rate) {}
