package com.fintrack.analytics.controller.dto;

import java.math.BigDecimal;

public record RateUsedResponse(String from, String to, BigDecimal rate) {}
