package com.fintrack.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.auth.domain.Role;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.RefreshTokenRepository;
import com.fintrack.auth.repository.RoleRepository;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.auth.web.dto.TokenResponse;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.recurring.domain.Frequency;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.recurring.service.RecurringOccurrenceProcessor;
import com.fintrack.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backend correctness and concurrency tests (tasks 3.1–3.4).
 * Uses a real PostgreSQL container for accurate transaction/locking semantics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BackendCorrectnessTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_correctness")
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
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired RecurringTransactionRepository recurringRepository;
    @Autowired RecurringOccurrenceProcessor occurrenceProcessor;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private Role userRole;

    @BeforeEach
    void ensureRole() {
        userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> {
                    Role r = new Role("ROLE_USER");
                    return roleRepository.save(r);
                });
    }

    private User createUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("pass1234"))
                .fullName("Test User")
                .roles(Set.of(userRole))
                .build());
    }

    private Account createAccount(User user, BigDecimal initial) {
        return accountRepository.save(Account.builder()
                .user(user)
                .name("Test Account")
                .accountType(AccountType.CASH)
                .currency("USD")
                .initialBalance(initial)
                .currentBalance(initial)
                .build());
    }

    private String registerAndLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234",
                                "firstName", "Test", "lastName", "User"))))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    // ── Task 3.1: TRANSFER atomicity ──────────────────────────────────────────

    @Test
    void transfer_movesBalanceAtomically() throws Exception {
        String tokenJson = registerAndLogin("correctness3.1@test.com");
        String token = objectMapper.readValue(tokenJson, TokenResponse.class).accessToken();

        // Create source (100) and destination (50) accounts via API
        MvcResult srcResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Source", "accountType", "CASH",
                                "currency", "USD", "initialBalance", "100.00"))))
                .andExpect(status().isCreated()).andReturn();
        MvcResult dstResult = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Dest", "accountType", "BANK",
                                "currency", "USD", "initialBalance", "50.00"))))
                .andExpect(status().isCreated()).andReturn();

        Long srcId = objectMapper.readTree(srcResult.getResponse().getContentAsString()).get("id").asLong();
        Long dstId = objectMapper.readTree(dstResult.getResponse().getContentAsString()).get("id").asLong();

        // Execute TRANSFER of 30
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", srcId,
                                "transferAccountId", dstId,
                                "transactionType", "TRANSFER",
                                "amount", "30.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isCreated());

        Account src = accountRepository.findById(srcId).orElseThrow();
        Account dst = accountRepository.findById(dstId).orElseThrow();
        assertThat(src.getCurrentBalance()).isEqualByComparingTo("70.00");
        assertThat(dst.getCurrentBalance()).isEqualByComparingTo("80.00");
    }

    // ── Task 3.2: Concurrent balance updates ──────────────────────────────────

    @Test
    void concurrentTransactions_finalBalanceEqualsExactSumOfDeltas() throws Exception {
        User user = createUser("correctness3.2@test.com");
        Account account = createAccount(user, BigDecimal.ZERO);
        Long accountId = account.getId();
        Long userId = user.getId();

        String tokenJson = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "correctness3.2@test.com", "password", "pass1234"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readValue(tokenJson, TokenResponse.class).accessToken();

        int threads = 5;
        BigDecimal incomeEach = new BigDecimal("10.00");
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    mockMvc.perform(post("/api/v1/transactions")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of(
                                            "accountId", accountId,
                                            "transactionType", "INCOME",
                                            "amount", "10.00",
                                            "transactionDate", "2026-06-01"))))
                            .andExpect(status().isCreated());
                    successes.incrementAndGet();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        Account updated = accountRepository.findById(accountId).orElseThrow();
        BigDecimal expected = incomeEach.multiply(BigDecimal.valueOf(successes.get()));
        assertThat(updated.getCurrentBalance()).isEqualByComparingTo(expected);
    }

    // ── Task 5.1-5.2: Refresh-token reuse detection ───────────────────────────

    @Test
    void refreshToken_reuseInsideGraceWindow_isRefreshAlreadyRotated_withNoFamilyRevocation() throws Exception {
        String tokenJson = registerAndLogin("correctness5.1@test.com");
        var tokenNode = objectMapper.readTree(tokenJson);
        String refreshToken = tokenNode.get("refreshToken").asText();
        Long userId = tokenNode.get("user").get("id").asLong();

        // First use: valid rotation
        String firstRefreshResponse = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(firstRefreshResponse).contains("accessToken");

        // Second use of the SAME token immediately after: benign concurrent-rotation replay
        // (lost-response retry / racing tab), inside the ten-second grace window — a typed 409,
        // not a theft-detection lockout, and the freshly minted successor stays valid.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("refresh_already_rotated"));

        // The successor minted by the winning rotation remains active — no family revocation.
        long activeTokens = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUser().getId().equals(userId) && !rt.isRevoked())
                .count();
        assertThat(activeTokens).isEqualTo(1);
    }

    @Test
    void refreshToken_replayOutsideGraceWindow_revokesFamilyAndRejects() throws Exception {
        String tokenJson = registerAndLogin("correctness5.2@test.com");
        var tokenNode = objectMapper.readTree(tokenJson);
        String refreshToken = tokenNode.get("refreshToken").asText();
        Long userId = tokenNode.get("user").get("id").asLong();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        // Simulate the grace window having elapsed without a real sleep.
        var rotated = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUser().getId().equals(userId) && rt.isRevoked() && rt.getRotatedAt() != null)
                .findFirst().orElseThrow();
        rotated.setRotatedAt(java.time.Instant.now().minusSeconds(30));
        refreshTokenRepository.save(rotated);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());

        // All tokens for this user, including the successor, are now revoked (family lineage
        // invalidation — theft-detection posture for an out-of-window replay).
        long activeTokens = refreshTokenRepository.findAll().stream()
                .filter(rt -> rt.getUser().getId().equals(userId) && !rt.isRevoked())
                .count();
        assertThat(activeTokens).isZero();
    }

    // ── Task 3.4: Scheduler retry idempotency ─────────────────────────────────

    @Test
    void scheduler_processSameOccurrenceTwice_yieldsExactlyOneTransaction() {
        User user = createUser("correctness3.4@test.com");
        Account account = createAccount(user, new BigDecimal("200.00"));
        LocalDate today = LocalDate.of(2026, 6, 1);

        RecurringTransaction rt = recurringRepository.save(RecurringTransaction.builder()
                .user(user)
                .account(account)
                .transactionType(TransactionType.EXPENSE)
                .amount(new BigDecimal("20.00"))
                .frequency(Frequency.MONTHLY)
                .intervalValue(1)
                .startDate(today)
                .nextRunDate(today)
                .active(true)
                .occurrencesCount(0)
                .build());

        long txsBefore = transactionRepository.count();

        // First processing — should create a transaction
        occurrenceProcessor.process(rt, today);

        // Reload rt (nextRunDate advanced by first processing)
        RecurringTransaction reloaded = recurringRepository.findById(rt.getId()).orElseThrow();
        // Manually reset nextRunDate to today to simulate a retry/duplicate trigger
        reloaded.setNextRunDate(today);
        recurringRepository.save(reloaded);

        // Second processing — duplicate constraint must silently skip; no new transaction
        occurrenceProcessor.process(reloaded, today);

        long txsCreated = transactionRepository.count() - txsBefore;
        assertThat(txsCreated).isEqualTo(1);

        // Balance should reflect exactly one application of the expense
        Account updated = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.getCurrentBalance()).isEqualByComparingTo("180.00");
    }

    // ── Loan/credit default categories (V9 migration) ─────────────────────────

    @Test
    void listCategories_includesLoanAndCreditDefaultCategories() throws Exception {
        String tokenJson = registerAndLogin("correctness-loan-categories@test.com");
        String token = objectMapper.readValue(tokenJson, TokenResponse.class).accessToken();

        MvcResult result = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        var categories = objectMapper.readTree(result.getResponse().getContentAsString());
        boolean hasLoanIncome = false;
        boolean hasLoanExpense = false;
        for (var category : categories) {
            String name = category.get("name").asText();
            String type = category.get("transactionType").asText();
            boolean system = category.get("system").asBoolean();
            if ("Loans & Credit".equals(name) && "INCOME".equals(type) && system) {
                hasLoanIncome = true;
            }
            if ("Loan & Mortgage Payment".equals(name) && "EXPENSE".equals(type) && system) {
                hasLoanExpense = true;
            }
        }
        assertThat(hasLoanIncome).isTrue();
        assertThat(hasLoanExpense).isTrue();
    }
}
