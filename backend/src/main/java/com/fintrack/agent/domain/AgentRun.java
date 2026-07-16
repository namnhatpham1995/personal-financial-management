package com.fintrack.agent.domain;

import com.fintrack.auth.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "agent_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "vault_document_id", nullable = false, length = 24)
    private String vaultDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> extraction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> proposals;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;

    @Column(nullable = false)
    private boolean retryable;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "created_transaction_ids", columnDefinition = "jsonb")
    private List<Long> createdTransactionIds;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True when another run for the same document is still in flight (not a terminal status). */
    public boolean isActive() {
        return status == AgentRunStatus.EXTRACTING || status == AgentRunStatus.AWAITING_REVIEW;
    }
}
