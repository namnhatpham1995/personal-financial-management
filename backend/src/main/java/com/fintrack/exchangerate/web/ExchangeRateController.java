package com.fintrack.exchangerate.web;

import com.fintrack.common.config.AppProperties;
import com.fintrack.exchangerate.service.ExchangeRateService;
import com.fintrack.exchangerate.web.dto.ConvertResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;

@RestController
@RequestMapping("/api/v1/exchange-rates")
@RequiredArgsConstructor
@Tag(name = "Exchange Rates")
@SecurityRequirement(name = "bearerAuth")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;
    private final AppProperties appProperties;

    @GetMapping("/convert")
    @Operation(summary = "Convert an amount between two currencies using cached exchange rates")
    public ConvertResponse convert(
            @RequestParam @NotBlank String from,
            @RequestParam @NotBlank String to,
            @RequestParam @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive") BigDecimal amount) {
        var supported = exchangeRateService.supportedCurrencies();
        if (!supported.contains(from) || !supported.contains(to)) {
            throw new IllegalArgumentException(
                    "Unsupported currency pair: " + from + "/" + to + ". Must be supported ISO 4217 codes.");
        }
        BigDecimal converted = exchangeRateService.convert(amount, from, to);
        BigDecimal rate = converted.divide(amount, 10, RoundingMode.HALF_UP);
        String base = appProperties.getExchangeRate().getBase();
        return new ConvertResponse(from, to, amount, converted, rate,
                exchangeRateService.getAsOf(base).orElse(null));
    }
}
