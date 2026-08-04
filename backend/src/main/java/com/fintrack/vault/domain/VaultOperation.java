package com.fintrack.vault.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Mongo-native durable operation record for vault/statement upload and reassignment idempotency.
 * One row per claimed {@code (userId, operation, keyHash)} tuple — mirrors the PostgreSQL
 * idempotency table's role for JSON creates, but lives in Mongo because there is no shared
 * transaction manager across Postgres and Mongo/GridFS.
 *
 * <p>Operation-specific fields that are not needed by the generic recovery path live in
 * {@link #payload}. Upload keeps its GridFS and vault-document ids top-level for recovery;
 * reassignment stores its document/account and cleanup details in the payload.
 *
 * <p>See {@code VaultUploadIdempotencyCoordinator} and {@code VaultReassignmentService} for the
 * claim/complete lifecycle, and {@code VaultOperationRecoveryScheduler} for stale cleanup.
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

    /** Stable logical operation name, e.g. {@code "vault.upload"} or {@code "vault.reassign"}. */
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

    /** Operation-specific data, such as reassignment document/account and cleanup details. */
    private Map<String, Object> payload;

    private Instant createdAt;

    private Instant completedAt;

    /**
     * TTL index cutoff — seven days from creation, matching the non-secret replay retention
     * window. Mongo's TTL monitor deletes the document once this instant is in the past.
     */
    @Indexed(name = "idx_vault_operations_expires_at", expireAfter = "0s")
    private Instant expiresAt;
}
