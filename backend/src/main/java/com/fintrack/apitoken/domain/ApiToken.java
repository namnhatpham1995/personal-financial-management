package com.fintrack.apitoken.domain;

import com.fintrack.auth.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "api_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    /** Non-secret display fragment (prefix + a few random chars) — the raw token itself is never stored. */
    @Column(name = "token_prefix", nullable = false, length = 32)
    private String tokenPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApiTokenScope scope;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /**
     * SHA-256 hex hash of the client-supplied {@code Idempotency-Key} used at creation, when one
     * was supplied. Null for tokens created without a key. Never the raw key.
     */
    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;

    /** Canonical hash of the creation request settings (name/scope/expiry), paired with {@link #idempotencyKeyHash}. */
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isValid() {
        return !isRevoked() && !isExpired();
    }
}
