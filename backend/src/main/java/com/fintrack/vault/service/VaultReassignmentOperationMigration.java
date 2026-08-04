package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time bridge from the legacy reassignment collection to the unified vault operation
 * collection. It is invoked immediately before the recovery scheduler's first sweep, so stale
 * legacy rows cannot be missed during a deployment restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultReassignmentOperationMigration {

    public static final String LEGACY_COLLECTION = "vault_reassignment_operations";
    public static final String MIGRATIONS_COLLECTION = "vault_migrations";
    public static final String MIGRATION_MARKER_ID = "unify-vault-operation-tracking";

    private static final String OPERATION_NAME = "vault.reassign";
    private static final String DOCUMENT_ID = "documentId";
    private static final String SOURCE_ACCOUNT_ID = "sourceAccountId";
    private static final String TARGET_ACCOUNT_ID = "targetAccountId";
    private static final String REMOVED_TRANSACTION_IDS = "removedTransactionIds";
    private static final String REMOVED_TRANSACTION_COUNT = "removedTransactionCount";
    private static final String MANUAL_LINK_DETACHED = "manualLinkDetached";
    private static final String FAILURE_REASON = "failureReason";

    private final MongoTemplate mongoTemplate;
    private final AtomicBoolean ranThisProcess = new AtomicBoolean(false);

    public void migrateOnce() {
        if (!ranThisProcess.compareAndSet(false, true)) {
            return;
        }
        try {
            if (mongoTemplate.exists(
                    Query.query(Criteria.where("_id").is(MIGRATION_MARKER_ID)), MIGRATIONS_COLLECTION)) {
                return;
            }

            Query query = Query.query(Criteria.where("state").ne(VaultOperationState.COMPLETED.name()));
            List<Document> legacyOperations = mongoTemplate.find(query, Document.class, LEGACY_COLLECTION);
            for (Document legacy : legacyOperations) {
                upsert(translate(legacy));
            }
            log.info("Vault reassignment operation migration complete: {} operation(s) migrated",
                    legacyOperations.size());

            try {
                mongoTemplate.insert(new Document("_id", MIGRATION_MARKER_ID)
                                .append("completedAt", Instant.now()),
                        MIGRATIONS_COLLECTION);
            } catch (DuplicateKeyException e) {
                // Another replica may have completed the same idempotent migration first.
                log.debug("Vault reassignment operation migration marker already recorded");
            }
        } catch (RuntimeException e) {
            ranThisProcess.set(false);
            throw e;
        }
    }

    private VaultOperation translate(Document legacy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        copyIfPresent(legacy, payload, DOCUMENT_ID, String.class);
        copyIfPresent(legacy, payload, SOURCE_ACCOUNT_ID, value -> numberAsLong(value));
        copyIfPresent(legacy, payload, TARGET_ACCOUNT_ID, value -> numberAsLong(value));
        copyIfPresent(legacy, payload, REMOVED_TRANSACTION_IDS, this::transactionIds);
        copyIfPresent(legacy, payload, REMOVED_TRANSACTION_COUNT, value -> numberAsInteger(value));
        copyIfPresent(legacy, payload, MANUAL_LINK_DETACHED, value -> value);
        copyIfPresent(legacy, payload, FAILURE_REASON, String.class);

        return VaultOperation.builder()
                .userId(numberAsLong(legacy.get("userId")))
                .operation(OPERATION_NAME)
                .keyHash((String) legacy.get("keyHash"))
                .requestHash((String) legacy.get("requestHash"))
                .state(VaultOperationState.valueOf(String.valueOf(legacy.get("state"))))
                .payload(payload)
                .createdAt(toInstant(legacy.get("createdAt")))
                .completedAt(toInstant(legacy.get("completedAt")))
                .expiresAt(toInstant(legacy.get("expiresAt")))
                .build();
    }

    private void upsert(VaultOperation operation) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("userId").is(operation.getUserId()),
                Criteria.where("operation").is(operation.getOperation()),
                Criteria.where("keyHash").is(operation.getKeyHash())));
        Update update = new Update()
                .set("userId", operation.getUserId())
                .set("operation", operation.getOperation())
                .set("keyHash", operation.getKeyHash())
                .set("requestHash", operation.getRequestHash())
                .set("state", operation.getState())
                .set("payload", operation.getPayload())
                .set("createdAt", operation.getCreatedAt())
                .set("completedAt", operation.getCompletedAt())
                .set("expiresAt", operation.getExpiresAt());
        mongoTemplate.upsert(query, update, VaultOperation.class);
    }

    private void copyIfPresent(Document source, Map<String, Object> target, String key,
                               java.util.function.Function<Object, ?> converter) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, converter.apply(source.get(key)));
        }
    }

    private void copyIfPresent(Document source, Map<String, Object> target, String key,
                               Class<?> valueType) {
        copyIfPresent(source, target, key, value -> valueType.cast(value));
    }

    private List<Long> transactionIds(Object raw) {
        if (!(raw instanceof Iterable<?> values)) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(VaultReassignmentOperationMigration::numberAsLong)
                .toList();
    }

    private static Long numberAsLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private static Integer numberAsInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }
}
