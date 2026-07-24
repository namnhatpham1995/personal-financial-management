package com.fintrack.common.exception;

import com.fintrack.vault.service.UnsupportedStatementFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }

    @Test
    void maxUploadSizeExceeded_mapsTo413WithClearMessage() {
        ResponseEntity<com.fintrack.common.dto.ApiError> response = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(10 * 1024 * 1024), request("/api/vault/import/upload"));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().error()).isEqualTo("file_too_large");
        assertThat(response.getBody().message()).contains("10MB");
    }

    @Test
    void unsupportedStatementFormat_mapsTo415() {
        ResponseEntity<com.fintrack.common.dto.ApiError> response = handler.handleUnsupportedStatementFormat(
                new UnsupportedStatementFormatException("Unsupported statement format: only CSV, OFX, and QFX files are accepted"),
                request("/api/vault/import/upload"));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody().error()).isEqualTo("unsupported_statement_format");
    }
}
