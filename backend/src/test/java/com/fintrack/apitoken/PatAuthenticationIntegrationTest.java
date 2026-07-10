package com.fintrack.apitoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.apitoken.domain.ApiToken;
import com.fintrack.apitoken.repository.ApiTokenRepository;
import com.fintrack.auth.web.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers tasks.md 2.6: PAT read/write scope enforcement, expired/revoked/unknown rejection,
 * hard denial of /auth, /tokens, and vault regardless of scope, audit attribution, and that
 * JWT session auth is unaffected by the new filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PatAuthenticationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_pat_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.rate-limit.auth-requests-per-minute", () -> "1000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApiTokenRepository apiTokenRepository;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String registerAndLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234",
                                "firstName", "Test", "lastName", "User"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class)
                .accessToken();
    }

    private String createAccount(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Pat Test Account", "accountType", "CASH",
                                "currency", "USD", "initialBalance", "0"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    private String createPat(String jwt, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Test Token", "scope", scope, "expiryDays", 30))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("plaintextToken").asText();
    }

    // ── Read scope ──────────────────────────────────────────────────────────

    @Test
    void readScopeToken_canListAccounts_scopedToOwner() throws Exception {
        String jwtA = registerAndLogin("pat.read.a@test.com");
        String jwtB = registerAndLogin("pat.read.b@test.com");
        createAccount(jwtA);
        createAccount(jwtB);
        String patA = createPat(jwtA, "READ");

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + patA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void readScopeToken_cannotCreateTransaction_returns403() throws Exception {
        String jwt = registerAndLogin("pat.readwrite.deny@test.com");
        String accountId = createAccount(jwt);
        String pat = createPat(jwt, "READ");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionType", "EXPENSE", "amount", "10.00",
                                "transactionDate", "2026-01-01", "accountId", accountId))))
                .andExpect(status().isForbidden());
    }

    // ── Write scope ──────────────────────────────────────────────────────────

    @Test
    void writeScopeToken_canCreateTransaction_andAuditAttributesToToken() throws Exception {
        String jwt = registerAndLogin("pat.write.create@test.com");
        String accountId = createAccount(jwt);
        String pat = createPat(jwt, "WRITE");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + pat)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionType", "EXPENSE", "amount", "10.00",
                                "transactionDate", "2026-01-01", "accountId", accountId))))
                .andExpect(status().isCreated());

        Thread.sleep(200); // let REQUIRES_NEW audit tx commit

        mockMvc.perform(get("/api/v1/activity?size=5").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].meta.auth").value("pat"));
    }

    @Test
    void writeScopeToken_cannotDelete_returns403() throws Exception {
        String jwt = registerAndLogin("pat.write.delete@test.com");
        String accountId = createAccount(jwt);
        String pat = createPat(jwt, "WRITE");

        mockMvc.perform(delete("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + pat))
                .andExpect(status().isForbidden());
    }

    // ── Hard denials regardless of scope ────────────────────────────────────

    @Test
    void anyToken_cannotCallAuthEndpoints_returns403() throws Exception {
        String jwt = registerAndLogin("pat.deny.auth@test.com");
        String pat = createPat(jwt, "WRITE");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + pat))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyToken_cannotManageTokens_returns403() throws Exception {
        String jwt = registerAndLogin("pat.deny.tokens@test.com");
        String pat = createPat(jwt, "WRITE");

        mockMvc.perform(get("/api/v1/tokens").header("Authorization", "Bearer " + pat))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyToken_cannotAccessVault_returns403() throws Exception {
        String jwt = registerAndLogin("pat.deny.vault@test.com");
        String pat = createPat(jwt, "WRITE");

        mockMvc.perform(get("/api/vault").header("Authorization", "Bearer " + pat))
                .andExpect(status().isForbidden());
    }

    // ── Invalid tokens ───────────────────────────────────────────────────────

    @Test
    void unknownToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer fintrack_pat_doesnotexist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_returns401() throws Exception {
        String jwt = registerAndLogin("pat.expired@test.com");
        String pat = createPat(jwt, "READ");

        // Force expiry directly — the API never allows creating an already-expired token.
        Optional<ApiToken> stored = apiTokenRepository.findByTokenHashWithUser(hashOf(pat));
        assertThat(stored).isPresent();
        stored.get().setExpiresAt(Instant.now().minusSeconds(1));
        apiTokenRepository.save(stored.get());

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + pat))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedToken_returns401() throws Exception {
        String jwt = registerAndLogin("pat.revoked@test.com");
        String pat = createPat(jwt, "READ");
        MvcResult listResult = mockMvc.perform(get("/api/v1/tokens").header("Authorization", "Bearer " + jwt))
                .andReturn();
        Long tokenId = objectMapper.readTree(listResult.getResponse().getContentAsString()).get(0).get("id").asLong();

        mockMvc.perform(delete("/api/v1/tokens/" + tokenId).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + pat))
                .andExpect(status().isUnauthorized());
    }

    // ── JWT sessions unaffected ──────────────────────────────────────────────

    @Test
    void jwtSession_stillWorksAlongsidePatFilter() throws Exception {
        String jwt = registerAndLogin("pat.jwt.control@test.com");

        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    // Mirrors ApiTokenService's private hashToken() — SHA-256, Base64 standard encoding.
    private static String hashOf(String raw) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(hash);
    }
}
