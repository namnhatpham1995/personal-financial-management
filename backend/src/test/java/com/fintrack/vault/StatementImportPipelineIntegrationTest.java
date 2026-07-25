package com.fintrack.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.vault.web.dto.ConfirmImportRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-flow integration test for the vault statement-import pipeline:
 * multipart CSV upload -> parsed-row preview -> confirm -> transactions
 * persisted in Postgres and account balance adjusted.
 *
 * VaultDocument is a MongoDB-backed entity (GridFS-stored file, Mongo-backed
 * staging payload), so this test needs a real MongoDBContainer alongside the
 * usual PostgreSQLContainer — unlike other integration tests in this suite,
 * which intentionally leave Mongo unconfigured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class StatementImportPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_vault_test")
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
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("fintrack_vault_test"));
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final String VALID_CSV =
            "Date,Description,Amount\n" +
            "2026-01-05,Salary,1000.00\n" +
            "2026-01-10,Groceries,-40.00\n";

    private static final String MIXED_CSV =
            "Date,Description,Amount\n" +
            "2026-01-05,Good row income,500.00\n" +
            "not-a-date,Bad row,???\n" +
            "2026-01-06,Good row expense,-25.00\n";

    private ConfirmImportRequest confirmBody(java.util.List<String> dedupKeys) {
        return new ConfirmImportRequest(dedupKeys.stream()
                .map(k -> new ConfirmImportRequest.ConfirmRow(k, null))
                .toList());
    }

    @Test
    void validCsv_uploadPreviewConfirm_createsTransactionsAndAdjustsBalance() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "vault.valid@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE,
                VALID_CSV.getBytes(StandardCharsets.UTF_8));

        MvcResult uploadResult = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-upload-key-0123456789"))
                .andExpect(status().isCreated())
                .andReturn();
        String documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("documentId").asText();

        // Upload no longer parses — the UPLOADED statement must be parsed on demand.
        mockMvc.perform(post("/api/vault/import/" + documentId + "/parse")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        MvcResult rowsResult = mockMvc.perform(get("/api/vault/import/" + documentId + "/rows")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(rowsResult.getResponse().getContentAsString());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("description").asText()).isEqualTo("Salary");
        assertThat(rows.get(1).get("description").asText()).isEqualTo("Groceries");

        java.util.List<String> dedupKeys = new java.util.ArrayList<>();
        rows.forEach(r -> dedupKeys.add(r.get("dedupKey").asText()));

        mockMvc.perform(post("/api/vault/import/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-confirm-key-0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmBody(dedupKeys))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andReturn();

        // Net sum: +1000.00 - 40.00 = +960.00
        MvcResult accountResult = mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        BigDecimal balance = new BigDecimal(
                objectMapper.readTree(accountResult.getResponse().getContentAsString())
                        .get("currentBalance").asText());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("960.00"));

        MvcResult txResult = mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode transactions = objectMapper.readTree(txResult.getResponse().getContentAsString());
        // GET /api/v1/transactions returns a paginated envelope, not a bare array
        assertThat(transactions.get("content")).hasSize(2);
    }

    @Test
    void upload_createsUploadedDocumentWithoutParsing_thenParseIsSafelyReRunnable() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "vault.uploaded@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE,
                VALID_CSV.getBytes(StandardCharsets.UTF_8));

        MvcResult uploadResult = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-upload-key-uploaded-01"))
                .andExpect(status().isCreated())
                .andReturn();
        String documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("documentId").asText();

        // No rows are parsed yet — the document is UPLOADED, not STAGED.
        mockMvc.perform(get("/api/vault/import/" + documentId + "/rows")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNotFound());

        MvcResult firstParse = mockMvc.perform(post("/api/vault/import/" + documentId + "/parse")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstRows = objectMapper.readTree(firstParse.getResponse().getContentAsString());
        assertThat(firstRows).hasSize(2);

        // Re-running parse is safe (deterministic, no side effects) and yields the same rows.
        MvcResult secondParse = mockMvc.perform(post("/api/vault/import/" + documentId + "/parse")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondRows = objectMapper.readTree(secondParse.getResponse().getContentAsString());
        assertThat(secondRows).isEqualTo(firstRows);

        MvcResult listResult = mockMvc.perform(get("/api/vault/by-account")
                        .param("accountId", accountId)
                        .param("type", "STATEMENT")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listBody = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(listBody.get("content")).hasSize(1);
        assertThat(listBody.get("content").get(0).get("accountId").asLong()).isEqualTo(Long.parseLong(accountId));
        assertThat(listBody.get("content").get(0).get("status").asText()).isEqualTo("STAGED");
    }

    @Test
    void download_inlineDisposition_setsInlineHeaderAndScopesToOwner() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "vault.inline@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE,
                VALID_CSV.getBytes(StandardCharsets.UTF_8));
        MvcResult uploadResult = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-upload-key-inline-0001"))
                .andExpect(status().isCreated())
                .andReturn();
        String documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("documentId").asText();

        // Default disposition is a forced download.
        mockMvc.perform(get("/api/vault/" + documentId + "/download")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition"))
                        .startsWith("attachment"));

        // disposition=inline permits in-browser rendering instead.
        mockMvc.perform(get("/api/vault/" + documentId + "/download")
                        .param("disposition", "inline")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition"))
                        .startsWith("inline"));

        // Ownership is respected regardless of disposition — another user gets a plain 404.
        String otherJwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "vault.inline.other@test.com");
        mockMvc.perform(get("/api/vault/" + documentId + "/download")
                        .param("disposition", "inline")
                        .header("Authorization", "Bearer " + otherJwt))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedFormat_xlsx_rejectedWithoutStagingRows() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "vault.xlsx@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not a real xlsx file".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-upload-key-xlsx-0123456"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_statement_format"));
    }

    // Oversized-upload -> 413 is exercised at the handler level in
    // GlobalExceptionHandlerTest: MockMvc's multipart request builder stores parts directly
    // and never routes through the real servlet container's MultipartConfig size enforcement
    // (only a real embedded Tomcat does), so it can't reproduce a genuine MaxUploadSizeExceededException.

    @Test
    void mixedCsv_malformedRowsSkipped_validRowsStillImport() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "vault.mixed@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE,
                MIXED_CSV.getBytes(StandardCharsets.UTF_8));

        MvcResult uploadResult = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-upload-key-0123456789"))
                .andExpect(status().isCreated())
                .andReturn();
        String documentId = objectMapper.readTree(uploadResult.getResponse().getContentAsString())
                .get("documentId").asText();

        mockMvc.perform(post("/api/vault/import/" + documentId + "/parse")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());

        MvcResult rowsResult = mockMvc.perform(get("/api/vault/import/" + documentId + "/rows")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(rowsResult.getResponse().getContentAsString());
        // Only the two well-formed rows survive parsing; the malformed row is silently skipped
        assertThat(rows).hasSize(2);

        java.util.List<String> dedupKeys = new java.util.ArrayList<>();
        rows.forEach(r -> dedupKeys.add(r.get("dedupKey").asText()));

        mockMvc.perform(post("/api/vault/import/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "statement-confirm-key-0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmBody(dedupKeys))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andReturn();

        // Net sum of the two valid rows only: +500.00 - 25.00 = +475.00
        MvcResult accountResult = mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        BigDecimal balance = new BigDecimal(
                objectMapper.readTree(accountResult.getResponse().getContentAsString())
                        .get("currentBalance").asText());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("475.00"));
    }
}
