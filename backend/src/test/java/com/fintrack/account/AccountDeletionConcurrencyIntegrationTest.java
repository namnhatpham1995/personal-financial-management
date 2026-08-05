package com.fintrack.account;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Covers tasks.md 4.2.4-4.2.7: barrier-based PostgreSQL concurrency tests proving
 * {@code AccountService.delete()} now locks every account the user owns (ascending id order)
 * before reading the connected-transaction deletion set, so it serializes against transaction
 * create/batch-create on the same accounts and never deadlocks against
 * {@code TransactionService.lockAffectedAccounts}' own ascending-order locking. See
 * openspec/changes/harden-mutation-contract/specs/account-management/spec.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AccountDeletionConcurrencyIntegrationTest {

    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_account_deletion_concurrency").withUsername("test").withPassword("test");

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
                                "name", "Deletion Concurrency Account", "accountType", "BANK",
                                "currency", "USD", "initialBalance", initialBalance))))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
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

    private MvcResult raceDeleteAccount(String jwt, String accountId,
                                         CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(delete("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn();
    }

    private MvcResult raceBatch(String jwt, String idempotencyKey, List<Map<String, Object>> rows,
                                 CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(post("/api/v1/transactions/batch")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactions", rows))))
                .andReturn();
    }

    private String clientRequestId() {
        return UUID.randomUUID().toString().replace("-", "") + "ab";
    }

    private Map<String, Object> rowBody(String clientRequestId, String type, String amount,
                                         String accountId, String transferAccountId) {
        java.util.HashMap<String, Object> txMap = new java.util.HashMap<>();
        txMap.put("transactionType", type);
        txMap.put("amount", amount);
        txMap.put("transactionDate", "2026-06-01");
        txMap.put("accountId", accountId);
        if (transferAccountId != null) {
            txMap.put("transferAccountId", transferAccountId);
        }
        return Map.of("clientRequestId", clientRequestId, "transaction", txMap);
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

    // ── 4.2.4: create races the deletion of its own account ─────────────────

    @Test
    void createRacesAccountDeletion_noTransactionSurvivesReferencingDeletedAccount() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "delete.createraces@test.com");
        String accountId = createAccountWithBalance(jwt, "0.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<MvcResult> createFuture = executor.submit(() -> raceCreate(jwt, "INCOME", "50.00", accountId, null, ready, go));
            Future<MvcResult> deleteFuture = executor.submit(() -> raceDeleteAccount(jwt, accountId, ready, go));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int createStatus = createFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int deleteStatus = deleteFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

            // Delete always succeeds. Create either commits fully before the deletion window (201,
            // then gets swept up by the cascade delete) or is rejected because the account is
            // already gone by the time it acquires the lock (404) - never left half-applied.
            assertThat(deleteStatus).isEqualTo(204);
            assertThat(createStatus).isIn(201, 404);
        } finally {
            executor.shutdown();
        }

        Long id = Long.valueOf(accountId);
        assertThat(accountRepository.findById(id)).isEmpty();
        assertThat(transactionRepository.findConnectedToAccount(id)).isEmpty();
    }

    // ── 4.2.5: transfer commits into an account while its counterparty is deleted ─

    @Test
    void transferIntoAccount_whileCounterpartyDeleted_survivorBalanceMatchesRemainingTransactions() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "delete.transferraces@test.com");
        String toBeDeleted = createAccountWithBalance(jwt, "0.00");
        String survivor = createAccountWithBalance(jwt, "0.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            // Transfer FROM survivor TO the account being deleted.
            Future<MvcResult> createFuture = executor.submit(() ->
                    raceCreate(jwt, "TRANSFER", "40.00", survivor, toBeDeleted, ready, go));
            Future<MvcResult> deleteFuture = executor.submit(() -> raceDeleteAccount(jwt, toBeDeleted, ready, go));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int createStatus = createFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int deleteStatus = deleteFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

            assertThat(deleteStatus).isEqualTo(204);
            assertThat(createStatus).isIn(201, 404);
        } finally {
            executor.shutdown();
        }

        assertThat(accountRepository.findById(Long.valueOf(toBeDeleted))).isEmpty();
        // Whether the transfer committed (and was then reversed on the survivor by the cascade
        // delete's counterparty-reversal step) or was rejected outright, the survivor's balance
        // must equal initial plus its remaining committed transactions - never a stray leftover
        // transfer effect from a row that no longer exists.
        assertBalanceInvariant(survivor);
    }

    // ── 4.2.6: multi-row batch overlapping the deletion ──────────────────────

    @Test
    void batchRowTargetingAccount_racesAccountDeletion_noRowCommitsInsideDeletionWindow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "delete.batchraces@test.com");
        String accountId = createAccountWithBalance(jwt, "0.00");
        Map<String, Object> row = rowBody(clientRequestId(), "INCOME", "25.00", accountId, null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<MvcResult> batchFuture = executor.submit(() ->
                    raceBatch(jwt, UUID.randomUUID().toString(), List.of(row), ready, go));
            Future<MvcResult> deleteFuture = executor.submit(() -> raceDeleteAccount(jwt, accountId, ready, go));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int batchStatus = batchFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int deleteStatus = deleteFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus();

            assertThat(deleteStatus).isEqualTo(204);
            // The batch endpoint always returns 201 - the per-row outcome (CREATED or FAILED) is
            // carried inside the response body, never a top-level error for one bad row.
            assertThat(batchStatus).isEqualTo(201);
        } finally {
            executor.shutdown();
        }

        // Whichever the row's outcome was, no row survives referencing the now-deleted account.
        Long id = Long.valueOf(accountId);
        assertThat(accountRepository.findById(id)).isEmpty();
        assertThat(transactionRepository.findConnectedToAccount(id)).isEmpty();
    }

    // ── 4.2.7: deletion races an overlapping transfer without deadlock ──────

    @Test
    void deletionRacesOverlappingTransfer_completesWithoutDeadlock() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "delete.deadlockcheck@test.com");
        // accountToDelete is unrelated to the transfer, but AccountService.delete() locks every
        // account the user owns - so its lock set (accountToDelete, transferSource, transferDest)
        // overlaps the transfer's own lock set (transferSource, transferDest). A non-deterministic
        // lock order would risk a classic deadlock cycle; ascending-id ordering on both sides must
        // prevent it.
        String accountToDelete = createAccountWithBalance(jwt, "0.00");
        String transferSource = createAccountWithBalance(jwt, "0.00");
        String transferDest = createAccountWithBalance(jwt, "0.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<MvcResult> transferFuture = executor.submit(() ->
                    raceCreate(jwt, "TRANSFER", "10.00", transferSource, transferDest, ready, go));
            Future<MvcResult> deleteFuture = executor.submit(() -> raceDeleteAccount(jwt, accountToDelete, ready, go));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            // A real Postgres deadlock would surface as a 5xx from an unhandled
            // DeadlockLoserDataAccessException; both must complete normally well inside the join
            // timeout below.
            assertThat(transferFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            assertThat(deleteFuture.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
        } finally {
            executor.shutdown();
        }

        assertThat(accountRepository.findById(Long.valueOf(accountToDelete))).isEmpty();
        assertBalanceInvariant(transferSource);
        assertBalanceInvariant(transferDest);
    }
}
