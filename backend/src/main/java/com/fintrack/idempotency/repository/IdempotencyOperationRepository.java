package com.fintrack.idempotency.repository;

import com.fintrack.idempotency.domain.IdempotencyOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyOperationRepository extends JpaRepository<IdempotencyOperation, Long> {

    /**
     * Atomic claim: inserts a PROCESSING row for this (user, operation, key) tuple unless one
     * already exists. Returns 1 when this call won the claim, 0 when a row already existed —
     * the caller must then look the existing row up via
     * {@link #findByUserIdAndOperationAndKeyHash} to decide how to respond.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_operations (user_id, operation, key_hash, request_hash, state, created_at, expires_at)
            VALUES (:userId, :operation, :keyHash, :requestHash, 'PROCESSING', now(), :expiresAt)
            ON CONFLICT (user_id, operation, key_hash) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("userId") Long userId,
              @Param("operation") String operation,
              @Param("keyHash") String keyHash,
              @Param("requestHash") String requestHash,
              @Param("expiresAt") Instant expiresAt);

    Optional<IdempotencyOperation> findByUserIdAndOperationAndKeyHash(
            Long userId, String operation, String keyHash);
}
