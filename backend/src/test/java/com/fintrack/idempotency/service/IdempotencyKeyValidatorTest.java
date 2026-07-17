package com.fintrack.idempotency.service;

import com.fintrack.idempotency.exception.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyValidatorTest {

    private final IdempotencyKeyValidator validator = new IdempotencyKeyValidator();

    @Test
    void validate_minLength16_accepted() {
        String key = "a".repeat(16);
        assertThatCode(() -> validator.validate(key)).doesNotThrowAnyException();
    }

    @Test
    void validate_maxLength128_accepted() {
        String key = "a".repeat(128);
        assertThatCode(() -> validator.validate(key)).doesNotThrowAnyException();
    }

    @Test
    void validate_length15_rejected() {
        String key = "a".repeat(15);
        assertThatThrownBy(() -> validator.validate(key))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void validate_length129_rejected() {
        String key = "a".repeat(129);
        assertThatThrownBy(() -> validator.validate(key))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"has spaces here!!", "has/slash-in-key1", "has+plus-char-key", "has.dot.in.key123", "hasáccentedchars1"})
    void validate_invalidCharacters_rejected(String key) {
        assertThatThrownBy(() -> validator.validate(key))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void validate_urlSafeCharacters_accepted() {
        String key = "AZaz09-_AZaz09-_";
        assertThatCode(() -> validator.validate(key)).doesNotThrowAnyException();
    }

    @Test
    void validate_null_rejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void validate_blank_rejected() {
        assertThatThrownBy(() -> validator.validate("   "))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    @Test
    void validate_empty_rejected() {
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }
}
