package com.fintrack.idempotency.domain;

import com.fintrack.auth.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Maps to {@code idempotency_operations}. One row per claimed {@code (user, operation, key)}
 * tuple; see {@link com.fintrack.idempotency.service.IdempotentMutationExecutor} for the
 * claim/complete lifecycle.
 */
@Entity
@Table(name = "idempotency_operations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotency_operations_user_operation_key",
                columnNames = {"user_id", "operation", "key_hash"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String operation;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyOperationState state;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "response_content_type", length = 100)
    private String responseContentType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
