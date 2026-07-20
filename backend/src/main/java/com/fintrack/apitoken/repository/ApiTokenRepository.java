package com.fintrack.apitoken.repository;

import com.fintrack.apitoken.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    // JOIN FETCH: PatAuthenticationFilter reads token.getUser() outside of a caller-managed
    // transaction boundary, so the owning User must come back eagerly loaded.
    @Query("SELECT t FROM ApiToken t JOIN FETCH t.user WHERE t.tokenHash = :tokenHash")
    Optional<ApiToken> findByTokenHashWithUser(@Param("tokenHash") String tokenHash);

    List<ApiToken> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<ApiToken> findByIdAndUserId(Long id, Long userId);

    /** Backs the per-user PAT-creation idempotency binding (unique partial index in V15). */
    Optional<ApiToken> findByUserIdAndIdempotencyKeyHash(Long userId, String idempotencyKeyHash);
}
