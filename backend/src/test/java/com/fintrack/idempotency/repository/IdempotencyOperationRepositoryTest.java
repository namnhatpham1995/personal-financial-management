package com.fintrack.idempotency.repository;

import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.idempotency.domain.IdempotencyOperation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the atomic native claim query (V15's {@code ON CONFLICT DO NOTHING}) against a real
 * PostgreSQL database with Flyway enabled, proving both that the migration applies cleanly and
 * that the claim semantics a plain Mockito test cannot meaningfully verify actually hold. H2
 * (used by the lighter-weight {@code @DataJpaTest} pattern elsewhere in this repo) does not
 * support PostgreSQL's {@code ON CONFLICT} syntax, so this needs a real Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Transactional // each test runs in (and rolls back) its own transaction; also required for the @Modifying claim() query
class IdempotencyOperationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_idempotency_test")
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
    }

    @Autowired
    private IdempotencyOperationRepository repository;

    @Autowired
    private UserRepository userRepository;

    private User persistUser(String email) {
        return userRepository.save(User.builder()
                .email(email).passwordHash("h").fullName("Test User").build());
    }

    @Test
    void claim_firstAttempt_insertsRowAndReturnsOne() {
        User user = persistUser("claim-1@test.com");
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        int result = repository.claim(user.getId(), "account.create", "a".repeat(64), "b".repeat(64), expiresAt);

        assertThat(result).isEqualTo(1);
        Optional<IdempotencyOperation> found =
                repository.findByUserIdAndOperationAndKeyHash(user.getId(), "account.create", "a".repeat(64));
        assertThat(found).isPresent();
        assertThat(found.get().getState().name()).isEqualTo("PROCESSING");
    }

    @Test
    void claim_secondAttemptSameTuple_returnsZeroAndDoesNotOverwrite() {
        User user = persistUser("claim-2@test.com");
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        int first = repository.claim(user.getId(), "account.create", "c".repeat(64), "d".repeat(64), expiresAt);
        int second = repository.claim(user.getId(), "account.create", "c".repeat(64), "e".repeat(64), expiresAt);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);

        Optional<IdempotencyOperation> found =
                repository.findByUserIdAndOperationAndKeyHash(user.getId(), "account.create", "c".repeat(64));
        assertThat(found).isPresent();
        // Second claim attempt must not have overwritten the first row's request hash.
        assertThat(found.get().getRequestHash()).isEqualTo("d".repeat(64));
    }

    @Test
    void claim_sameKeyDifferentOperation_doesNotCollide() {
        User user = persistUser("claim-3@test.com");
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        int forCreate = repository.claim(user.getId(), "account.create", "f".repeat(64), "g".repeat(64), expiresAt);
        int forUpdate = repository.claim(user.getId(), "account.update", "f".repeat(64), "g".repeat(64), expiresAt);

        assertThat(forCreate).isEqualTo(1);
        assertThat(forUpdate).isEqualTo(1);
    }

    @Test
    void claim_sameKeyDifferentUser_doesNotCollide() {
        User alice = persistUser("claim-alice@test.com");
        User bob = persistUser("claim-bob@test.com");
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        int forAlice = repository.claim(alice.getId(), "account.create", "h".repeat(64), "i".repeat(64), expiresAt);
        int forBob = repository.claim(bob.getId(), "account.create", "h".repeat(64), "i".repeat(64), expiresAt);

        assertThat(forAlice).isEqualTo(1);
        assertThat(forBob).isEqualTo(1);
    }

    @Test
    void findByUserIdAndOperationAndKeyHash_noMatch_returnsEmpty() {
        assertThat(repository.findByUserIdAndOperationAndKeyHash(9999L, "account.create", "z".repeat(64)))
                .isEmpty();
    }
}
