package com.fintrack.vault.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Mongo-native durable operation record for vault/statement upload idempotency (design.md
 * Decision #4). One row per claimed {@code (userId, operation, keyHash)} tuple — mirrors the
 * PostgreSQL {@code idempotency_operations} table's role for JSON creates, but lives in Mongo
 * because there is no shared transaction manager across Postgres and Mongo/GridFS.
 *
 * <p>See {@code VaultUploadIdempotencyCoordinator} for the claim/complete/compensate lifecycle
 * and {@code VaultOperationRecoveryScheduler} for stale-{@code PROCESSING} cleanup.
 */
@Document(collection = "vault_operations")
@CompoundIndexes({
    @CompoundIndex(
            name = "uq_vault_operations_user_operation_key",
            def = "{'userId': 1, 'operation': 1, 'keyHash': 1}",
            unique = true),
    @CompoundIndex(
            name = "idx_vault_operations_state_created",
            def = "{'state': 1, 'createdAt': 1}")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VaultOperation {

    @Id
    private String id;

    private Long userId;

    /** Stable logical operation name, e.g. {@code "vault.upload"} or {@code "statement.upload"}. */
    private String operation;

    /** SHA-256 hex digest of the raw {@code Idempotency-Key} header. Never the raw key. */
    private String keyHash;

    /** SHA-256 hex digest over the operation name, non-file params, and file bytes. */
    private String requestHash;

    private VaultOperationState state;

    /** GridFS ObjectId (hex string) of the stored binary, once storage has been attempted. */
    private String gridFsFileId;

    /** Resulting {@link VaultDocument} id, once the document save has completed. */
    private String vaultDocumentId;

    private Instant createdAt;

    private Instant completedAt;

    /**
     * TTL index cutoff — seven days from creation, matching the non-secret replay retention
     * window used elsewhere in this change series. Mongo's TTL monitor deletes the document once
     * this instant is in the past; {@code expireAfter = "0s"} means "expire exactly at the stored
     * timestamp" rather than N seconds after it.
     */
    @Indexed(name = "idx_vault_operations_expires_at", expireAfter = "0s")
    private Instant expiresAt;
}
