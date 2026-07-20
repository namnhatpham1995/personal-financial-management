package com.fintrack.auth.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    /**
     * Id of the refresh token this row was rotated into. Non-secret lineage only — never the raw
     * successor token value. Null until this row wins a rotation race.
     */
    @Column(name = "successor_id")
    private Long successorId;

    /**
     * When this row was consumed by rotation. Null when the row was revoked directly (e.g.
     * logout or family-wide theft revocation) rather than via a successful rotation — that
     * distinction is what lets {@code AuthService.refresh} tell a benign concurrent-rotation
     * replay apart from an explicit revoke.
     */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
