package com.fintrack.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.audit.domain.AuditLogRepository;
import com.fintrack.auth.web.dto.TokenResponse;
import com.fintrack.support.HttpTestHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies PUT /api/v1/auth/me/language persists the preferred language and
 * that GET /api/v1/auth/me echoes it back, end-to-end, following the same
 * Testcontainers pattern as AuthLogoutIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuthLanguagePreferenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_language_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_language_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditLogRepository auditLogRepository;

    @Test
    void updateLanguage_writesAuditLogEntry() throws Exception {
        TokenResponse tokens = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "lang.audit@test.com");
        long before = auditLogRepository.count();

        mockMvc.perform(put("/api/v1/auth/me/language")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "vi"))))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(auditLogRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void updateLanguage_persistsAndEchoesInMe() throws Exception {
        TokenResponse tokens = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "lang.persist@test.com");

        mockMvc.perform(put("/api/v1/auth/me/language")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "de"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value("de"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value("de"));
    }

    @Test
    void updateLanguage_invalidCode_returns400AndDoesNotChangeStoredValue() throws Exception {
        TokenResponse tokens = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "lang.invalid@test.com");

        mockMvc.perform(put("/api/v1/auth/me/language")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "xx"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updateLanguage_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/auth/me/language")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("language", "en"))))
                .andExpect(status().isUnauthorized());
    }
}
