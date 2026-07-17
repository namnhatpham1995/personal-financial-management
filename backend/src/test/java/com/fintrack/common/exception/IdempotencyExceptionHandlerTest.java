package com.fintrack.common.exception;

import com.fintrack.common.dto.ApiError;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.IdempotencyOperationInProgressException;
import com.fintrack.idempotency.exception.InvalidIdempotencyKeyException;
import com.fintrack.idempotency.exception.MissingIdempotencyKeyException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies GlobalExceptionHandler's typed mappings for the idempotency exception family. */
class IdempotencyExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/accounts");
    }

    @Test
    void invalidIdempotencyKey_mapsTo400() {
        ResponseEntity<ApiError> response =
                handler.handleInvalidIdempotencyKey(new InvalidIdempotencyKeyException("too short"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("invalid_idempotency_key");
        assertThat(response.getBody().message()).isEqualTo("too short");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/accounts");
    }

    @Test
    void missingIdempotencyKey_mapsTo400() {
        ResponseEntity<ApiError> response =
                handler.handleMissingIdempotencyKey(new MissingIdempotencyKeyException("header required"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("missing_idempotency_key");
    }

    @Test
    void idempotencyConflict_mapsTo409() {
        ResponseEntity<ApiError> response =
                handler.handleIdempotencyConflict(new IdempotencyConflictException("payload changed"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("idempotency_key_conflict");
    }

    @Test
    void idempotencyOperationInProgress_mapsTo409WithRetryAfterHeader() {
        ResponseEntity<ApiError> response = handler.handleIdempotencyOperationInProgress(
                new IdempotencyOperationInProgressException("still processing", 3), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("operation_in_progress");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("3");
    }
}
