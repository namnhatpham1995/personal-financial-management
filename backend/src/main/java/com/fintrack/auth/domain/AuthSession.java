package com.fintrack.auth.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Server-authoritative lifetime and activity state for one browser login. */
@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isActive(Instant now, long idleTimeoutMs) {
        return revokedAt == null
                && now.isBefore(absoluteExpiresAt)
                && now.isBefore(lastActivityAt.plusMillis(idleTimeoutMs));
    }
}
