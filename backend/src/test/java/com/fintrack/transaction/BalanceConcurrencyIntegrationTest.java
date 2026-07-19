package com.fintrack.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.account.domain.Account;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers tasks.md 4.4/4.5: barrier-based PostgreSQL concurrency tests proving transaction
 * update/delete and account initial-balance-change/recompute are serialized against each other
 * per openspec/changes/harden-idempotent-mutations/specs/transaction-management/spec.md
 * "Requirement: Balance-affecting transaction mutations are serialized" and
 * specs/account-management/spec.md "Requirement: Account balance recomputation is serialized with
 * transaction effects". Every test asserts the invariant
 * {@code current_balance == initial_balance + Σ(signed committed transaction effects)} against
 * the real persisted {@link Account}, not just the HTTP response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BalanceConcurrencyIntegrationTest {

    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_balance_concurrency").withUsername("test").withPassword("test");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        r.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createAccountWithBalance(String jwt, String initialBalance) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Concurrency Account", "accountType", "BANK",
                                "currency", "USD", "initialBalance", initialBalance))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createTransaction(String jwt, String type, String amount, String accountId,
                                      String transferAccountId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "transactionType", type,
                "amount", amount,
                "transactionDate", "2026-06-01",
                "accountId", accountId));
        if (transferAccountId != null) {
            body.put("transferAccountId", transferAccountId);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private MvcResult raceUpdateAmount(String jwt, String txId, String amount,
                                        CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(put("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", amount))))
                .andReturn();
    }

    private MvcResult raceDelete(String jwt, String txId, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(delete("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn();
    }

    private MvcResult raceCreate(String jwt, String type, String amount, String accountId,
                                  String transferAccountId, CountDownLatch ready, CountDownLatch go) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "transactionType", type,
                "amount", amount,
                "transactionDate", "2026-06-01",
                "accountId", accountId));
        if (transferAccountId != null) {
            body.put("transferAccountId", transferAccountId);
        }
        ready.countDown();
        go.await();
        return mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult raceRecompute(String jwt, String accountId, CountDownLatch ready, CountDownLatch go)
            throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(post("/api/v1/accounts/" + accountId + "/recompute-balance")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn();
    }

    private MvcResult raceInitialBalanceChange(String jwt, String accountId, String newInitialBalance,
                                                CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(put("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("initialBalance", newInitialBalance))))
                .andReturn();
    }

    /** Recomputes the expected balance directly from committed rows, independent of production code. */
    private BigDecimal expectedBalance(Long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        BigDecimal net = BigDecimal.ZERO;
        for (Transaction tx : transactionRepository.findConnectedToAccount(accountId)) {
            switch (tx.getTransactionType()) {
                case INCOME -> net = net.add(tx.getAmount());
                case EXPENSE -> net = net.subtract(tx.getAmount());
                case TRANSFER -> {
                    if (tx.getAccount().getId().equals(accountId)) {
                        net = net.subtract(tx.getAmount());
                    } else if (tx.getTransferAccount() != null && tx.getTransferAccount().getId().equals(accountId)) {
                        BigDecimal destEffect = tx.getDestinationAmount() != null
                                ? tx.getDestinationAmount() : tx.getAmount();
                        net = net.add(destEffect);
                    }
                }
            }
        }
        return account.getInitialBalance().add(net);
    }

    private void assertBalanceInvariant(String accountId) {
        Long id = Long.valueOf(accountId);
        Account account = accountRepository.findById(id).orElseThrow();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo(expectedBalance(id));
    }

    // ── Overlapping identical amount updates ────────────────────────────────

    @Test
    void identicalConcurrentUpdates_applyDeltaExactlyOnce() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "balance.identical@test.com");
        String accountId = createAccountWithBalance(jwt, "0.00");
        String txId = createTransaction(jwt, "EXPENSE", "100.00", accountId, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceUpdateAmount(jwt, txId, "150.00", ready, go)),
                    executor.submit(() -> raceUpdateAmount(jwt, txId, "150.00", ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<MvcResult> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
        } finally {
            executor.shutdown();
        }

        Transaction stored = transactionRepository.findById(Long.valueOf(txId)).orElseThrow();
        assertThat(stored.getAmount()).isEqualByComparingTo("150.00");
        // If the second update read a stale pre-update amount instead of observing the first
        // update's committed 150, the expense delta would be applied twice (account = -200
        // instead of -150).
        assertBalanceInvariant(accountId);
        Account account = accountRepository.findById(Long.valueOf(accountId)).orElseThrow();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("-150.00");
    }

    // ── Overlapping different updates ───────────────────────────────────────

    @Test
    void differentConcurrentUpdates_executeSerially_lastCommittedValueWins() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "balance.different@test.com");
        String accountId = createAccountWithBalance(jwt, "0.00");
        String txId = createTransaction(jwt, "EXPENSE", "100.00", accountId, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceUpdateAmount(jwt, txId, "200.00", ready, go)),
                    executor.submit(() -> raceUpdateAmount(jwt, txId, "300.00", ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<MvcResult> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
        } finally {
            executor.shutdown();
        }

        Transaction stored = transactionRepository.findById(Long.valueOf(txId)).orElseThrow();
        // Whichever update committed last "wins" - non-deterministic which one that is, but the
        // stored amount must be exactly one of the two submitted values (not corrupted/averaged),
        // and the account balance must reflect that same value exactly once.
        assertThat(stored.getAmount().compareTo(new BigDecimal("200.00")) == 0
                || stored.getAmount().compareTo(new BigDecimal("300.00")) == 0)
                .as("stored amount %s must be exactly one of the two submitted values", stored.getAmount())
                .isTrue();
        assertBalanceInvariant(accountId);
    }

    // ── Update races delete ──────────────────────────────────────────────────

    @Test
    void updateRacesDelete_oneCompletes_otherObservesResultingState() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "balance.updatedelete@test.com");
        String accountId = createAccountWithBalance(jwt, "0.00");
        String txId = createTransaction(jwt, "EXPENSE", "100.00", accountId, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<MvcResult> updateFuture = executor.submit(() -> raceUpdateAmount(jwt, txId, "150.00", ready, go));
            Future<MvcResult> deleteFuture = executor.submit(() -> raceDelete(jwt, txId, ready, go));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int updateStatus = updateFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int deleteStatus = deleteFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

            // Delete always succeeds (204) whether it wins the lock race or runs after the
            // update. Update either succeeds against the pre-delete row (200) or, if delete won
            // the race first, observes the row is gone and returns 404 - never a corrupted write.
            assertThat(deleteStatus).isEqualTo(204);
            assertThat(updateStatus).isIn(200, 404);
        } finally {
            executor.shutdown();
        }

        // Transaction is gone either way, and the account balance is never adjusted from a stale
        // snapshot - it must land back at exactly the initial balance (no leftover expense effect).
        assertThat(transactionRepository.findById(Long.valueOf(txId))).isEmpty();
        assertBalanceInvariant(accountId);
        Account account = accountRepository.findById(Long.valueOf(accountId)).orElseThrow();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("0.00");
    }

    // ── Reverse-direction transfer mutations ────────────────────────────────

    @Test
    void reverseDirectionTransferMutations_lockOrderingAvoidsDeadlock() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "balance.reversetransfer@test.com");
        String accountA = createAccountWithBalance(jwt, "0.00");
        String accountB = createAccountWithBalance(jwt, "0.00");

        // Two transfers in opposite directions between the same pair of accounts, so a
        // non-deterministic lock order would have one thread lock A-then-B while the other locks
        // B-then-A.
        String txAtoB = createTransaction(jwt, "TRANSFER", "50.00", accountA, accountB);
        String txBtoA = createTransaction(jwt, "TRANSFER", "30.00", accountB, accountA);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceUpdateAmount(jwt, txAtoB, "60.00", ready, go)),
                    executor.submit(() -> raceUpdateAmount(jwt, txBtoA, "40.00", ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            // A real Postgres deadlock would surface as a 5xx from an unhandled
            // DeadlockLoserDataAccessException; both must complete with a normal 200 well inside
            // the join timeout below.
            for (Future<MvcResult> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
        } finally {
            executor.shutdown();
        }

        assertBalanceInvariant(accountA);
        assertBalanceInvariant(accountB);
        Account a = accountRepository.findById(Long.valueOf(accountA)).orElseThrow();
        Account b = accountRepository.findById(Long.valueOf(accountB)).orElseThrow();
        // A: -60 (updated A->B transfer) + 40 (updated B->A transfer) = -20
        assertThat(a.getCurrentBalance()).isEqualByComparingTo("-20.00");
        // B: +60 (updated A->B transfer) - 40 (updated B->A transfer) = 20
        assertThat(b.getCurrentBalance()).isEqualByComparingTo("20.00");
    }

    // ── Transaction create races account recomputation ──────────────────────

    @Test
    void createRacesRecompute_finalBalanceIncludesCreatedTransactionExactlyOnce() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "balance.createrecompute@test.com");
        String accountId = createAccountWithBalance(jwt, "100.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceCreate(jwt, "INCOME", "20.00", accountId, null, ready, go)),
                    executor.submit(() -> raceRecompute(jwt, accountId, ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            assertThat(futures.get(0).get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(futures.get(1).get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            executor.shutdown();
        }

        assertBalanceInvariant(accountId);
        Account account = accountRepository.findById(Long.valueOf(accountId)).orElseThrow();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("120.00");
    }

    // ── Transaction create races account initial-balance change ─────────────

    @Test
    void createRacesInitialBalanceChange_finalBalanceUsesNewInitialPlusTransactionOnce() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "balance.createinitial@test.com");
        String accountId = createAccountWithBalance(jwt, "100.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceCreate(jwt, "INCOME", "20.00", accountId, null, ready, go)),
                    executor.submit(() -> raceInitialBalanceChange(jwt, accountId, "500.00", ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            assertThat(futures.get(0).get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(futures.get(1).get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally {
            executor.shutdown();
        }

        assertBalanceInvariant(accountId);
        Account account = accountRepository.findById(Long.valueOf(accountId)).orElseThrow();
        assertThat(account.getInitialBalance()).isEqualByComparingTo("500.00");
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("520.00");
    }
}
