package com.fintrack.idempotency.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.auth.domain.User;
import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises {@link IdempotentMutationExecutor} against a real {@link IdempotencyClaimRunner} and
 * {@link IdempotencyResponseCodec}, backed by a hand-rolled in-memory fake for
 * {@link IdempotencyOperationRepository} (its claim method is a native query that Mockito cannot
 * meaningfully verify semantics for, so a small stateful fake is simpler and more faithful than
 * mocking every call in isolation).
 *
 * <p>Note: outside of Spring, {@code @Transactional} on {@link IdempotencyClaimRunner} is inert
 * in this test — there is no real rollback of the fake store's claim row when businessLogic
 * throws. The rollback assertion below therefore checks the property this test CAN prove: no
 * completion (repository.save) is ever recorded for a failed business operation. The full
 * transactional-rollback guarantee (claim row disappearing too) is Spring's responsibility and is
 * exercised by the executor's javadoc contract, not re-provable without a real datasource.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentMutationExecutorTest {

    @Mock
    private IdempotencyOperationRepository repository;

    private final IdempotencyKeyValidator validator = new IdempotencyKeyValidator();
    private final IdempotencyHasher hasher = new IdempotencyHasher();
    private final IdempotencyResponseCodec responseCodec = new IdempotencyResponseCodec(new ObjectMapper());
    private final Map<String, IdempotencyOperation> store = new ConcurrentHashMap<>();

    private IdempotentMutationExecutor executor;

    private record TestBody(String value) {}

    @BeforeEach
    void setUp() {
        AuditReplaySignal auditReplaySignal = new AuditReplaySignal();
        IdempotencyMetrics metrics = new IdempotencyMetrics(new SimpleMeterRegistry());
        IdempotencyClaimRunner claimRunner = new IdempotencyClaimRunner(repository, responseCodec, auditReplaySignal, metrics);
        executor = new IdempotentMutationExecutor(validator, hasher, repository, claimRunner, responseCodec, auditReplaySignal, metrics);

        lenient().when(repository.claim(anyLong(), anyString(), anyString(), anyString(), any(Instant.class)))
                .thenAnswer(inv -> {
                    Long userId = inv.getArgument(0);
                    String operation = inv.getArgument(1);
                    String keyHash = inv.getArgument(2);
                    String requestHash = inv.getArgument(3);
                    Instant expiresAt = inv.getArgument(4);
                    String storeKey = storeKey(userId, operation, keyHash);
                    if (store.containsKey(storeKey)) {
                        return 0;
                    }
                    IdempotencyOperation op = IdempotencyOperation.builder()
                            .id((long) (store.size() + 1))
                            .user(User.builder().id(userId).build())
                            .operation(operation)
                            .keyHash(keyHash)
                            .requestHash(requestHash)
                            .state(IdempotencyOperationState.PROCESSING)
                            .expiresAt(expiresAt)
                            .build();
                    store.put(storeKey, op);
                    return 1;
                });

        lenient().when(repository.findByUserIdAndOperationAndKeyHash(anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(
                        store.get(storeKey(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))));

        lenient().when(repository.save(any(IdempotencyOperation.class)))
                .thenAnswer(inv -> {
                    IdempotencyOperation op = inv.getArgument(0);
                    store.put(storeKey(op.getUser().getId(), op.getOperation(), op.getKeyHash()), op);
                    return op;
                });
    }

    private static String storeKey(Long userId, String operation, String keyHash) {
        return userId + "|" + operation + "|" + keyHash;
    }

    private static String key(String suffix) {
        return ("test-idempotency-key-" + suffix).repeat(1); // already >=16 chars, URL-safe
    }

    @Test
    void claimSucceeds_invokesBusinessLogicExactlyOnce_persistsCompletion() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(new TestBody("ok"));
        };

        ResponseEntity<TestBody> response = executor.execute(
                1L, "account.create", key("a"), Map.of("name", "cash"), TestBody.class, businessLogic);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().value()).isEqualTo("ok");
        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isNull();

        IdempotencyOperation stored = store.get(storeKey(1L, "account.create", hasher.hashKey(key("a"))));
        assertThat(stored.getState()).isEqualTo(IdempotencyOperationState.COMPLETED);
        assertThat(stored.getResponseStatus()).isEqualTo(201);
        verify(repository, atLeastOnce()).save(any(IdempotencyOperation.class));
    }

    @Test
    void sameKeySamePayload_replaysStoredResponse_businessLogicNotInvokedAgain() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(new TestBody("first"));
        };
        Map<String, Object> payload = Map.of("name", "cash");
        String idemKey = key("b");

        executor.execute(1L, "account.create", idemKey, payload, TestBody.class, businessLogic);
        ResponseEntity<TestBody> replay =
                executor.execute(1L, "account.create", idemKey, payload, TestBody.class, businessLogic);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().value()).isEqualTo("first");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void sameKeyDifferentPayload_throwsConflict_businessLogicNotInvokedAgain() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> {
            calls.incrementAndGet();
            return ResponseEntity.ok(new TestBody("v"));
        };
        String idemKey = key("c");

        executor.execute(1L, "account.create", idemKey, Map.of("name", "a"), TestBody.class, businessLogic);

        assertThatThrownBy(() -> executor.execute(
                1L, "account.create", idemKey, Map.of("name", "b"), TestBody.class, businessLogic))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void businessLogicThrows_noCompletionPersisted() {
        String idemKey = key("d");
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> executor.execute(
                1L, "account.create", idemKey, Map.of("name", "a"), TestBody.class, businessLogic))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(repository, never()).save(any());
        IdempotencyOperation stored = store.get(storeKey(1L, "account.create", hasher.hashKey(idemKey)));
        assertThat(stored.getState()).isEqualTo(IdempotencyOperationState.PROCESSING);
    }

    @Test
    void sameLiteralKey_differentUser_doesNotCollide() {
        String idemKey = key("e");
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> {
            int n = calls.incrementAndGet();
            return ResponseEntity.ok(new TestBody("v" + n));
        };

        executor.execute(1L, "account.create", idemKey, Map.of("name", "a"), TestBody.class, businessLogic);
        executor.execute(2L, "account.create", idemKey, Map.of("name", "a"), TestBody.class, businessLogic);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void sameLiteralKey_differentOperation_doesNotCollide() {
        String idemKey = key("f");
        AtomicInteger calls = new AtomicInteger();
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> {
            int n = calls.incrementAndGet();
            return ResponseEntity.ok(new TestBody("v" + n));
        };

        executor.execute(1L, "account.create", idemKey, Map.of("name", "a"), TestBody.class, businessLogic);
        executor.execute(1L, "budget.create", idemKey, Map.of("name", "a"), TestBody.class, businessLogic);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void claim_isScopedByUserOperationAndKeyHash() {
        String idemKey = key("g");
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> ResponseEntity.ok(new TestBody("v"));

        executor.execute(7L, "budget.create", idemKey, Map.of("x", 1), TestBody.class, businessLogic);

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> operationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyHashCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).claim(userIdCaptor.capture(), operationCaptor.capture(), keyHashCaptor.capture(), anyString(), any());

        assertThat(userIdCaptor.getValue()).isEqualTo(7L);
        assertThat(operationCaptor.getValue()).isEqualTo("budget.create");
        assertThat(keyHashCaptor.getValue()).isEqualTo(hasher.hashKey(idemKey));
    }

    @Test
    void secretResponseHeaders_neverPersistedOrReplayed() {
        String idemKey = key("h");
        Map<String, Object> payload = Map.of("name", "a");
        Supplier<ResponseEntity<TestBody>> businessLogic = () -> ResponseEntity.status(HttpStatus.CREATED)
                .header("Set-Cookie", "session=abc123; HttpOnly")
                .header(HttpHeaders.AUTHORIZATION, "Bearer secret-token")
                .body(new TestBody("ok"));

        // Original caller still gets whatever businessLogic actually returned, headers included —
        // only the persisted/replayed snapshot strips headers (design.md Decision #1).
        ResponseEntity<TestBody> first = executor.execute(1L, "account.create", idemKey, payload, TestBody.class, businessLogic);
        assertThat(first.getHeaders().getFirst("Set-Cookie")).isEqualTo("session=abc123; HttpOnly");

        ResponseEntity<TestBody> replay = executor.execute(1L, "account.create", idemKey, payload, TestBody.class, businessLogic);
        assertThat(replay.getHeaders().get("Set-Cookie")).isNull();
        assertThat(replay.getHeaders().get(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }
}
