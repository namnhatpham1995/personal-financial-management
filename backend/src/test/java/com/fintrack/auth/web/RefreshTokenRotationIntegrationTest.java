package com.fintrack.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.domain.RefreshToken;
import com.fintrack.auth.repository.RefreshTokenRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Covers tasks.md 5.3: atomic refresh consumption under concurrency, refresh racing logout, a
 * lost-response retry inside the ten-second grace window, replay outside the window, and the
 * invariant that no raw successor refresh-token value is ever persisted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class RefreshTokenRotationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_refresh_rotation_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_refresh_rotation_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.rate-limit.auth-requests-per-minute", () -> "1000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    // ── Concurrent refresh: exactly one winner, one successor row, no raw value stored ──────

    @Test
    void concurrentRefresh_exactlyOneWinnerAndOneSuccessorRow_noRawSuccessorStored() throws Exception {
        TokenResponse tokens = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "refresh.race@test.com");
        String refreshToken = tokens.refreshToken();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceRefresh(refreshToken, ready, go)),
                    executor.submit(() -> raceRefresh(refreshToken, ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int okCount = 0;
            int conflictCount = 0;
            String successorRawToken = null;
            List<String> observed = new java.util.ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(15, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                String responseBody = result.getResponse().getContentAsString();
                observed.add(status + ":" + responseBody);
                if (status == 200) {
                    okCount++;
                    TokenResponse parsed = objectMapper.readValue(responseBody, TokenResponse.class);
                    successorRawToken = parsed.refreshToken();
                } else if (status == 409) {
                    conflictCount++;
                    assertThat(objectMapper.readTree(responseBody)
                            .get("error").asText()).isEqualTo("refresh_already_rotated");
                }
            }

            assertThat(okCount).as("statuses: %s", observed).isEqualTo(1);
            assertThat(conflictCount).as("statuses: %s", observed).isEqualTo(1);
            assertThat(successorRawToken).isNotNull();

            List<RefreshToken> all = refreshTokenRepository.findAll();
            RefreshToken presented = all.stream()
                    .filter(rt -> rt.getTokenHash().equals(hashOf(refreshToken)))
                    .findFirst().orElseThrow();
            assertThat(presented.isRevoked()).isTrue();
            assertThat(presented.getSuccessorId()).isNotNull();
            assertThat(presented.getRotatedAt()).isNotNull();

            RefreshToken successor = all.stream()
                    .filter(rt -> rt.getId().equals(presented.getSuccessorId()))
                    .findFirst().orElseThrow();
            assertThat(successor.isRevoked()).isFalse();
            assertThat(successor.getTokenHash()).isEqualTo(hashOf(successorRawToken));

            // No raw refresh-token value (old or new) ever appears in the stored hash column —
            // only its SHA-256 hash does.
            for (RefreshToken rt : all) {
                assertThat(rt.getTokenHash()).isNotEqualTo(refreshToken);
                assertThat(rt.getTokenHash()).isNotEqualTo(successorRawToken);
            }
        } finally {
            executor.shutdown();
        }
    }

    private MvcResult raceRefresh(String refreshToken, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();
    }

    // ── Refresh racing logout: logout is a deliberate action, not a theft signal ────────────

    @Test
    void refreshRacingLogout_neverTriggersFamilyRevocationOfUnrelatedTokens() throws Exception {
        TokenResponse first = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "refresh.vs.logout@test.com");
        String email = "refresh.vs.logout@test.com";
        // Second active session for the same user — the control token that must survive
        // regardless of which side of the race (refresh vs logout) wins on the first session.
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234"))))
                .andReturn();
        TokenResponse second = objectMapper.readValue(loginResult.getResponse().getContentAsString(), TokenResponse.class);

        String racedRefreshToken = first.refreshToken();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<MvcResult> refreshFuture = executor.submit(() -> raceRefresh(racedRefreshToken, ready, go));
            Future<MvcResult> logoutFuture = executor.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/api/v1/auth/logout")
                                .header("Authorization", "Bearer " + first.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("refreshToken", racedRefreshToken))))
                        .andReturn();
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int refreshStatus = refreshFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int logoutStatus = logoutFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

            // Logout always succeeds; refresh either wins (200) or loses to the explicit revoke (401).
            assertThat(logoutStatus).isEqualTo(204);
            assertThat(refreshStatus).isIn(200, 401);
        } finally {
            executor.shutdown();
        }

        // The unrelated second session's refresh token must still be active either way — a
        // logout race on the first session must never cascade into family-wide revocation.
        RefreshToken secondStored = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getTokenHash().equals(hashOf(second.refreshToken())))
                .findFirst().orElseThrow();
        assertThat(secondStored.isRevoked()).isFalse();
    }

    // ── Lost-response retry inside the grace window ─────────────────────────────────────────

    @Test
    void lostResponseRetryInsideGraceWindow_getsAlreadyRotated_notASecondSuccessor() throws Exception {
        TokenResponse tokens = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "refresh.grace@test.com");
        String refreshToken = tokens.refreshToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));

        long successorsBefore = refreshTokenRepository.findAll().stream()
                .filter(rt -> !rt.isRevoked()).count();

        // Client believes the first response was lost and retries immediately with the same
        // (now-rotated) token, well inside the ten-second grace window.
        MvcResult retry = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();

        assertThat(retry.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()).get("error").asText())
                .isEqualTo("refresh_already_rotated");

        long successorsAfter = refreshTokenRepository.findAll().stream()
                .filter(rt -> !rt.isRevoked()).count();
        assertThat(successorsAfter).isEqualTo(successorsBefore);
    }

    // ── Replay outside the grace window: theft-detection posture retained ──────────────────

    @Test
    void replayOutsideGraceWindow_401AndFamilyRevoked() throws Exception {
        TokenResponse tokens = HttpTestHelper.registerAndLoginFull(mockMvc, objectMapper, "refresh.stale@test.com");
        String refreshToken = tokens.refreshToken();
        Long userId = tokens.user().id();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));

        RefreshToken rotated = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getTokenHash().equals(hashOf(refreshToken)))
                .findFirst().orElseThrow();
        // Simulate the grace window having elapsed without a real sleep.
        rotated.setRotatedAt(Instant.now().minusSeconds(30));
        refreshTokenRepository.save(rotated);

        MvcResult replay = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();

        List<RefreshToken> mine = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUser().getId().equals(userId))
                .toList();
        long activeTokens = mine.stream().filter(rt -> !rt.isRevoked()).count();
        String dump = mine.stream()
                .map(rt -> "id=" + rt.getId() + " revoked=" + rt.isRevoked() + " rotatedAt=" + rt.getRotatedAt()
                        + " successorId=" + rt.getSuccessorId())
                .reduce("", (a, b) -> a + " | " + b);
        assertThat(replay.getResponse().getStatus())
                .as("replay body: %s", replay.getResponse().getContentAsString())
                .isEqualTo(401);
        assertThat(activeTokens).as("rows: %s", dump).isZero();
    }

    // Mirrors AuthService's private hashToken() — SHA-256, Base64 standard encoding.
    private static String hashOf(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
