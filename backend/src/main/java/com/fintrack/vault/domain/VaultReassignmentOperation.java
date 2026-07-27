package com.fintrack.vault.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "vault_reassignment_operations")
@CompoundIndex(name = "uq_vault_reassignment_user_operation_key",
        def = "{'userId': 1, 'operation': 1, 'keyHash': 1}", unique = true)
@CompoundIndex(name = "idx_vault_reassignment_state_created",
        def = "{'state': 1, 'createdAt': 1}")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultReassignmentOperation {

    @Id
    private String id;

    private Long userId;
    private String operation;
    private String keyHash;
    private String requestHash;
    private String documentId;
    private Long sourceAccountId;
    private Long targetAccountId;
    private VaultReassignmentState state;
    private List<Long> removedTransactionIds;
    private Integer removedTransactionCount;
    private Boolean manualLinkDetached;
    private Instant createdAt;
    private Instant completedAt;
    private String failureReason;

    @Indexed(name = "idx_vault_reassignment_expires_at", expireAfter = "0s")
    private Instant expiresAt;
}
