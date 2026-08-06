package com.fintrack.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import com.fintrack.vault.service.GridFsFileStore;
import com.fintrack.vault.service.VaultOperationRecoveryScheduler;
import com.fintrack.vault.service.VaultReassignmentOperationMigration;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers coverage (task 6.4) for the Mongo/GridFS upload idempotency coordinator wired
 * into both {@code POST /api/vault/upload} and {@code POST /api/vault/import/upload}: sequential
 * and concurrent replay, changed-file conflict, cross-user isolation, document-save-failure
 * compensation, and stale-operation recovery. Follows the same Postgres+Mongo Testcontainers
 * pattern as {@code StatementImportPipelineIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class VaultUploadIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_vault_idem_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

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
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("fintrack_vault_idem_test"));
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        // Disable the real cron trigger: this class calls recoverStaleOperations()/migrateOnce()
        // directly, and a background firing between test methods would consume the migration's
        // one-shot guard and the ShedLock out from under the explicit calls.
        registry.add("vault.recovery.cron", () -> "-");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GridFsTemplate gridFsTemplate;
    @Autowired VaultOperationRepository vaultOperationRepository;
    @Autowired GridFsFileStore gridFsFileStore;
    @Autowired VaultOperationRecoveryScheduler recoveryScheduler;
    @Autowired VaultReassignmentOperationMigration reassignmentOperationMigration;
    @Autowired UserRepository userRepository;
    @Autowired MongoTemplate mongoTemplate;

    private String register(String email) throws Exception {
        return HttpTestHelper.registerAndLogin(mockMvc, objectMapper, email);
    }

    private Long userIdOf(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private long countGridFsFilesForUser(Long userId) {
        return gridFsTemplate.find(Query.query(Criteria.where("metadata.userId").is(userId)))
                .into(new ArrayList<>())
                .size();
    }

    @Test
    void legacyReceipt_canBeListedAndAssignedOnce_withoutCrossUserAccess() throws Exception {
        String ownerEmail = "vault.legacy.owner@test.com";
        String ownerJwt = register(ownerEmail);
        Long ownerId = userIdOf(ownerEmail);
        String ownerAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, ownerJwt, "USD");
        String secondOwnerAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, ownerJwt, "EUR");

        String legacyReceiptId = "legacy-receipt-" + UUID.randomUUID();
        mongoTemplate.insert(new Document("_id", legacyReceiptId)
                .append("userId", ownerId)
                .append("type", "RECEIPT")
                .append("status", "ACTIVE")
                .append("source", "manual")
                .append("capturedAt", java.util.Date.from(Instant.now()))
                .append("originalFilename", "old-receipt.pdf"), "vault_documents");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/vault/unassigned-receipts")
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(legacyReceiptId))
                .andExpect(jsonPath("$.content[0].accountId").doesNotExist());

        String otherJwt = register("vault.legacy.other@test.com");
        String otherAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, otherJwt, "USD");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/vault/{id}/account", legacyReceiptId)
                        .param("accountId", otherAccountId)
                        .header("Authorization", "Bearer " + otherJwt))
                .andExpect(status().isNotFound());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/vault/{id}/account", legacyReceiptId)
                        .param("accountId", ownerAccountId)
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(Long.parseLong(ownerAccountId)));

        Document assigned = mongoTemplate.findById(legacyReceiptId, Document.class, "vault_documents");
        assertThat(assigned).isNotNull();
        assertThat(assigned.get("accountId")).isEqualTo(Long.parseLong(ownerAccountId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/vault/{id}/account", legacyReceiptId)
                        .param("accountId", secondOwnerAccountId)
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isConflict());
    }

    @Test
    void overallReceiptListing_andReassignment_keepBinaryAndSupportReplay() throws Exception {
        String email = "vault.receipt.reassign@test.com";
        String jwt = register(email);
        Long userId = userIdOf(email);
        String sourceAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String targetAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt-to-move.jpg", MediaType.IMAGE_JPEG_VALUE, "receipt-to-move".getBytes(StandardCharsets.UTF_8));

        MvcResult upload = mockMvc.perform(multipart("/api/vault/upload").file(file)
                        .param("type", "RECEIPT").param("accountId", sourceAccountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "receipt-reassign-upload-0123"))
                .andExpect(status().isCreated()).andReturn();
        String documentId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/vault").param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(documentId))
                .andExpect(jsonPath("$.content[0].accountId").value(Long.parseLong(sourceAccountId)));

        String moveKey = "receipt-reassign-key-012345";
        mockMvc.perform(post("/api/vault/" + documentId + "/reassign")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", moveKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAccountId\":" + targetAccountId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.accountId").value(Long.parseLong(targetAccountId)))
                .andExpect(jsonPath("$.removedImportedTransactions").value(0));
        mockMvc.perform(post("/api/vault/" + documentId + "/reassign")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", moveKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAccountId\":" + targetAccountId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertThat(countGridFsFilesForUser(userId)).isEqualTo(1);
    }

    // ── sequential replay ───────────────────────────────────────────────────────

    @Test
    void vaultUpload_sequentialReplay_sameKeySameFile_returnsSameDocumentAndOneBinary() throws Exception {
        String email = "vault.replay@test.com";
        String jwt = register(email);
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", MediaType.IMAGE_JPEG_VALUE, "receipt-bytes".getBytes(StandardCharsets.UTF_8));

        MvcResult first = mockMvc.perform(multipart("/api/vault/upload")
                        .file(file)
                        .param("type", "RECEIPT")
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isNull();

        MvcResult second = mockMvc.perform(multipart("/api/vault/upload")
                        .file(file)
                        .param("type", "RECEIPT")
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
    }

    @Test
    void statementUpload_sequentialReplay_sameKeySameFile_returnsSameDocumentId() throws Exception {
        String email = "statement.replay@test.com";
        String jwt = register(email);
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        String csv = "Date,Description,Amount\n2026-01-05,Salary,1000.00\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE, csv.getBytes(StandardCharsets.UTF_8));

        MvcResult first = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();
        String firstDocId = objectMapper.readTree(first.getResponse().getContentAsString()).get("documentId").asText();

        MvcResult second = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        String secondDocId = objectMapper.readTree(second.getResponse().getContentAsString()).get("documentId").asText();

        assertThat(secondDocId).isEqualTo(firstDocId);
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
    }

    // ── concurrent replay ───────────────────────────────────────────────────────

    @Test
    void vaultUpload_concurrentRetries_onlyOneBinaryAndOneDocumentCreated() throws Exception {
        String email = "vault.concurrent@test.com";
        String jwt = register(email);
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        byte[] bytes = "concurrent-receipt-bytes".getBytes(StandardCharsets.UTF_8);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    MockMultipartFile file = new MockMultipartFile(
                            "file", "receipt.jpg", MediaType.IMAGE_JPEG_VALUE, bytes);
                    return mockMvc.perform(multipart("/api/vault/upload")
                                    .file(file)
                                    .param("type", "RECEIPT")
                                    .param("accountId", accountId)
                                    .header("Authorization", "Bearer " + jwt)
                                    .header("Idempotency-Key", key))
                            .andReturn();
                }));
            }
            startLatch.countDown();

            List<String> documentIds = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(15, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
                documentIds.add(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
            }

            assertThat(documentIds.get(0)).isEqualTo(documentIds.get(1));
            assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── changed-file conflict ───────────────────────────────────────────────────

    @Test
    void vaultUpload_sameKeyDifferentFile_returnsConflictAndNoSecondArtifact() throws Exception {
        String email = "vault.conflict@test.com";
        String jwt = register(email);
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        MockMultipartFile fileA = new MockMultipartFile(
                "file", "receipt-a.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes-a".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fileB = new MockMultipartFile(
                "file", "receipt-b.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes-b-different".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileA)
                        .param("type", "RECEIPT")
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileB)
                        .param("type", "RECEIPT")
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_key_conflict"));

        // Only the first file's binary exists — the conflicting second file was never stored.
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
    }

    // ── cross-user isolation ────────────────────────────────────────────────────

    @Test
    void vaultUpload_sameLiteralKeyAcrossUsers_bothSucceedIndependently() throws Exception {
        String emailA = "vault.userA@test.com";
        String emailB = "vault.userB@test.com";
        String jwtA = register(emailA);
        String jwtB = register(emailB);
        String accountIdA = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "USD");
        String accountIdB = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtB, "USD");
        String sharedKey = "shared-literal-key-0123456789ab";

        MockMultipartFile fileA = new MockMultipartFile(
                "file", "a.jpg", MediaType.IMAGE_JPEG_VALUE, "user-a-bytes".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fileB = new MockMultipartFile(
                "file", "b.jpg", MediaType.IMAGE_JPEG_VALUE, "user-b-bytes".getBytes(StandardCharsets.UTF_8));

        MvcResult resultA = mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileA)
                        .param("type", "RECEIPT")
                        .param("accountId", accountIdA)
                        .header("Authorization", "Bearer " + jwtA)
                        .header("Idempotency-Key", sharedKey))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult resultB = mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileB)
                        .param("type", "RECEIPT")
                        .param("accountId", accountIdB)
                        .header("Authorization", "Bearer " + jwtB)
                        .header("Idempotency-Key", sharedKey))
                .andExpect(status().isCreated())
                .andReturn();

        String idA = objectMapper.readTree(resultA.getResponse().getContentAsString()).get("id").asText();
        String idB = objectMapper.readTree(resultB.getResponse().getContentAsString()).get("id").asText();
        assertThat(idA).isNotEqualTo(idB);
        assertThat(countGridFsFilesForUser(userIdOf(emailA))).isEqualTo(1);
        assertThat(countGridFsFilesForUser(userIdOf(emailB))).isEqualTo(1);
    }

    // ── document-save failure compensation ─────────────────────────────────────

    // Upload no longer embeds parsed rows in the vault document (that only happens later, on
    // demand, via parse — which touches no GridFS binary and so has nothing to compensate), so
    // the previous "huge parsed-rows payload" trick for forcing a genuine 16MB-BSON-limit
    // document-save failure no longer applies. An oversized originalFilename was tried as a
    // replacement but blows up GridFS's own fs.files metadata document during binaryStore.store()
    // itself, before ever reaching VaultDocument save — it can no longer isolate "GridFS succeeds,
    // document save fails" through the real HTTP path. That exact compensation branch (GridFS
    // store succeeds, document save throws) is covered at the unit level instead:
    // VaultUploadIdempotencyCoordinatorTest#documentSaveFails_afterGridFsStoreSucceeds_compensatesAndMarksFailed().

    // ── stale-operation recovery ────────────────────────────────────────────────

    @Test
    void staleProcessingOperation_recoveryJobDeletesOrphanedBinaryAndUnsticksOperation() throws Exception {
        String email = "vault.stale@test.com";
        register(email);
        Long userId = userIdOf(email);

        // Simulate a process that claimed an operation, stored the GridFS binary, then died
        // before saving the VaultDocument: insert a real orphaned GridFS file plus a PROCESSING
        // VaultOperation referencing it, with an old createdAt.
        String gridFsFileId = gridFsFileStore.store(
                new MockMultipartFile("file", "orphan.jpg", MediaType.IMAGE_JPEG_VALUE, "orphan-bytes".getBytes()),
                userId, "stale-op-marker");

        VaultOperation stale = VaultOperation.builder()
                .userId(userId)
                .operation("vault.upload")
                .keyHash("stale-op-key-hash")
                .requestHash("stale-op-request-hash")
                .state(VaultOperationState.PROCESSING)
                .gridFsFileId(gridFsFileId)
                .createdAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        VaultOperation saved = vaultOperationRepository.insert(stale);

        assertThat(countGridFsFilesForUser(userId)).isEqualTo(1);

        recoveryScheduler.recoverStaleOperations();

        assertThat(countGridFsFilesForUser(userId)).isEqualTo(0);
        VaultOperation reloaded = vaultOperationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(VaultOperationState.FAILED);
    }

    @Test
    void reassignmentMigration_movesOnlyNonCompletedRowsAndFoldsPayloadFields() {
        long userId = 9_000_000L + UUID.randomUUID().hashCode();
        String suffix = UUID.randomUUID().toString();
        Instant createdAt = Instant.now().minus(20, ChronoUnit.MINUTES);
        String processingKeyHash = "legacy-processing-" + suffix;
        String completedKeyHash = "legacy-completed-" + suffix;

        mongoTemplate.insert(new Document("_id", "legacy-processing-" + suffix)
                .append("userId", userId)
                .append("operation", "vault.reassign")
                .append("keyHash", processingKeyHash)
                .append("requestHash", "request-processing-" + suffix)
                .append("documentId", "document-" + suffix)
                .append("sourceAccountId", 10L)
                .append("targetAccountId", 20L)
                .append("state", "PROCESSING")
                .append("removedTransactionIds", List.of(101L, 102L))
                .append("removedTransactionCount", 2)
                .append("manualLinkDetached", true)
                .append("createdAt", Date.from(createdAt))
                .append("expiresAt", Date.from(createdAt.plus(7, java.time.temporal.ChronoUnit.DAYS))),
                VaultReassignmentOperationMigration.LEGACY_COLLECTION);
        mongoTemplate.insert(new Document("_id", "legacy-completed-" + suffix)
                .append("userId", userId)
                .append("operation", "vault.reassign")
                .append("keyHash", completedKeyHash)
                .append("requestHash", "request-completed-" + suffix)
                .append("documentId", "completed-document-" + suffix)
                .append("targetAccountId", 30L)
                .append("state", "COMPLETED")
                .append("createdAt", Date.from(createdAt))
                .append("expiresAt", Date.from(createdAt.plus(7, java.time.temporal.ChronoUnit.DAYS))),
                VaultReassignmentOperationMigration.LEGACY_COLLECTION);

        reassignmentOperationMigration.migrateOnce();

        Document migrated = mongoTemplate.findOne(Query.query(new Criteria()
                        .andOperator(
                                Criteria.where("userId").is(userId),
                                Criteria.where("operation").is("vault.reassign"),
                                Criteria.where("keyHash").is(processingKeyHash))),
                Document.class, "vault_operations");
        assertThat(migrated).isNotNull();
        assertThat(migrated.get("state")).isEqualTo("PROCESSING");
        assertThat(migrated.get("payload", Document.class))
                .containsEntry("documentId", "document-" + suffix)
                .containsEntry("sourceAccountId", 10L)
                .containsEntry("targetAccountId", 20L)
                .containsEntry("removedTransactionCount", 2)
                .containsEntry("manualLinkDetached", true);

        assertThat(mongoTemplate.count(Query.query(Criteria.where("keyHash").is(completedKeyHash)),
                "vault_operations")).isZero();
    }
}
