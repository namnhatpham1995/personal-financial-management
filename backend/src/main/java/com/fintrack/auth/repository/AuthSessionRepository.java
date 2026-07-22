package com.fintrack.auth.repository;

import com.fintrack.auth.domain.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query("UPDATE AuthSession s SET s.lastActivityAt = :now "
            + "WHERE s.id = :id AND s.revokedAt IS NULL "
            + "AND s.absoluteExpiresAt > :now AND s.lastActivityAt > :idleCutoff")
    int touchIfActive(@Param("id") Long id, @Param("now") Instant now,
                      @Param("idleCutoff") Instant idleCutoff);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = COALESCE(s.revokedAt, :now) WHERE s.id = :id")
    int revoke(@Param("id") Long id, @Param("now") Instant now);
}
