package com.fintrack.exchangerate.exception;

/**
 * Thrown when exchange rate data cannot be fetched from the provider
 * and no valid cache exists to fall back on.
 */
public class ExchangeRateUnavailableException extends RuntimeException {

    public ExchangeRateUnavailableException(String message) {
        super(message);
    }

    public ExchangeRateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
