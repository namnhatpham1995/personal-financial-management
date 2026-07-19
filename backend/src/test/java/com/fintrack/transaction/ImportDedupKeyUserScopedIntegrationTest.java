package com.fintrack.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers tasks.md 3.5: proves {@code import_dedup_key} uniqueness is enforced per-user (via V15's
 * composite {@code uq_transactions_user_import_dedup_key} index) rather than globally, now that
 * V16 has dropped the legacy V5 global unique index and {@code Transaction.importDedupKey} no
 * longer declares {@code @Column(unique = true)}.
 *
 * <p>Uses a real PostgreSQL Testcontainers instance with {@code ddl-auto: validate} (unlike most
 * other integration tests in this suite, which use {@code ddl-auto: none}) specifically to prove
 * — per the CRITICAL WARNING learned from a prior migration/entity mismatch in this series — that
 * dropping the V5 index and removing {@code unique = true} from the entity does not break
 * Hibernate schema validation against the real V16-migrated schema. If this class's Spring context
 * fails to start, that is itself the regression this test exists to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ImportDedupKeyUserScopedIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_dedup_scope_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        // Deliberately "validate", not "none" — see class Javadoc.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_dedup_scope_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionService transactionService;
    @Autowired UserRepository userRepository;

    @Test
    void contextStartsWithDdlAutoValidate_afterV16MigrationAndEntityChange() {
        // No-op assertion: the real proof is that Spring context creation above did not throw
        // SchemaManagementException/BeanCreationException while validating the entity mapping
        // against the real, V16-migrated schema.
        assertThat(userRepository).isNotNull();
    }

    @Test
    void sameLiteralImportDedupKey_acrossTwoDifferentUsers_bothSucceed() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "dedup.userA@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "dedup.userB@test.com");
        String accountIdA = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "EUR");
        String accountIdB = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtB, "EUR");
        User userA = userRepository.findByEmail("dedup.userA@test.com").orElseThrow();
        User userB = userRepository.findByEmail("dedup.userB@test.com").orElseThrow();

        String sharedDedupKey = "shared-statement-fingerprint-001";

        var reqA = new CreateTransactionRequest(TransactionType.EXPENSE, new BigDecimal("11.00"),
                LocalDate.now(), Long.parseLong(accountIdA), null, null, null, "Import A");
        var reqB = new CreateTransactionRequest(TransactionType.EXPENSE, new BigDecimal("22.00"),
                LocalDate.now(), Long.parseLong(accountIdB), null, null, null, "Import B");

        var responseA = transactionService.createWithImportDedupKey(userA.getId(), reqA, sharedDedupKey);
        var responseB = transactionService.createWithImportDedupKey(userB.getId(), reqB, sharedDedupKey);

        assertThat(responseA.id()).isNotNull();
        assertThat(responseB.id()).isNotNull();
        assertThat(responseA.id()).isNotEqualTo(responseB.id());
        assertThat(transactionService.list(userA.getId(), null, null, null, null, null, null, null,
                null, 0, 20, null, "desc").totalElements()).isEqualTo(1);
        assertThat(transactionService.list(userB.getId(), null, null, null, null, null, null, null,
                null, 0, 20, null, "desc").totalElements()).isEqualTo(1);
    }

    @Test
    void sameUserSameImportDedupKeyTwice_secondAttemptSkippedViaConstraintViolation() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "dedup.repeat@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        User user = userRepository.findByEmail("dedup.repeat@test.com").orElseThrow();

        String dedupKey = "repeat-import-fingerprint-002";
        var req = new CreateTransactionRequest(TransactionType.EXPENSE, new BigDecimal("9.00"),
                LocalDate.now(), Long.parseLong(accountId), null, null, null, "Import once");

        transactionService.createWithImportDedupKey(user.getId(), req, dedupKey);

        assertThatThrownBy(() -> transactionService.createWithImportDedupKey(user.getId(), req, dedupKey))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(transactionService.list(user.getId(), null, null, null, null, null, null, null,
                null, 0, 20, null, "desc").totalElements()).isEqualTo(1);
    }
}
