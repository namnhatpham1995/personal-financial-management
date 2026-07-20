package com.fintrack.auth.repository;

import com.fintrack.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomic compare-and-swap: flips {@code revoked} false -> true and stamps {@code rotatedAt}
     * only if the row is still unrevoked. Returns 1 for the sole winner of a concurrent rotation
     * race, 0 for every loser (already revoked, whether by a winning rotation, logout, or
     * family-wide revocation). Row-level locking under concurrent UPDATEs makes this safe without
     * an explicit {@code PESSIMISTIC_WRITE} read.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true, r.rotatedAt = :now WHERE r.id = :id AND r.revoked = false")
    int consumeIfNotRevoked(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId AND rt.revoked = false")
    void revokeAllByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < CURRENT_TIMESTAMP OR rt.revoked = true")
    void deleteExpiredAndRevoked();
}
