package com.fintrack.apitoken.service;

import com.fintrack.apitoken.domain.ApiToken;
import com.fintrack.apitoken.exception.ApiTokenIdempotencyConflictException;
import com.fintrack.apitoken.repository.ApiTokenRepository;
import com.fintrack.apitoken.web.dto.ApiTokenResponse;
import com.fintrack.apitoken.web.dto.CreateApiTokenRequest;
import com.fintrack.apitoken.web.dto.CreatedApiTokenResponse;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotencyKeyValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ApiTokenService {

    private static final String TOKEN_PREFIX = "fintrack_pat_";
    private static final Set<Integer> ALLOWED_EXPIRY_DAYS = Set.of(30, 90, 365);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String IDEMPOTENCY_OPERATION = "apitoken.create";

    private final ApiTokenRepository apiTokenRepository;
    private final UserRepository userRepository;
    private final IdempotencyKeyValidator idempotencyKeyValidator;
    private final IdempotencyHasher idempotencyHasher;
    private final ApiTokenWriter apiTokenWriter;

    /** Unprotected create — behaves exactly as before idempotency binding existed. */
    @Transactional
    public CreatedApiTokenResponse create(Long userId, CreateApiTokenRequest request) {
        return create(userId, request, null);
    }

    /**
     * PAT creation is deliberately NOT routed through the generic {@code IdempotentMutationExecutor}
     * used by other creates: a generic replay would return the same stored response body — including
     * plaintext — on retry, which design.md explicitly forbids. Instead, any existing row for
     * {@code (userId, idempotencyKeyHash)} always wins, regardless of whether the retried request's
     * settings match; the retry gets a typed conflict with non-secret metadata, never a second token
     * and never the plaintext again.
     */
    @Transactional
    public CreatedApiTokenResponse create(Long userId, CreateApiTokenRequest request, String idempotencyKey) {
        if (!ALLOWED_EXPIRY_DAYS.contains(request.expiryDays())) {
            throw new IllegalArgumentException("expiryDays must be one of " + ALLOWED_EXPIRY_DAYS);
        }

        String keyHash = null;
        String requestHash = null;
        if (idempotencyKey != null) {
            idempotencyKeyValidator.validate(idempotencyKey);
            keyHash = idempotencyHasher.hashKey(idempotencyKey);
            requestHash = idempotencyHasher.hashJsonRequest(IDEMPOTENCY_OPERATION, request);

            Optional<ApiToken> existing = apiTokenRepository.findByUserIdAndIdempotencyKeyHash(userId, keyHash);
            if (existing.isPresent()) {
                throw conflictFor(existing.get());
            }
        }

        User user = userRepository.getReferenceById(userId);
        String rawToken = generateRawToken();
        ApiToken token = ApiToken.builder()
                .user(user)
                .name(request.name())
                .tokenHash(hashToken(rawToken))
                .tokenPrefix(rawToken.substring(0, Math.min(rawToken.length(), TOKEN_PREFIX.length() + 4)))
                .scope(request.scope())
                .expiresAt(Instant.now().plus(request.expiryDays(), ChronoUnit.DAYS))
                .idempotencyKeyHash(keyHash)
                .requestHash(requestHash)
                .build();

        try {
            token = apiTokenWriter.save(token);
        } catch (DataIntegrityViolationException e) {
            // Lost the concurrent-create race against another request for the same key: the
            // partial unique index (user_id, idempotency_key_hash) is the safety net.
            if (keyHash == null) {
                throw e;
            }
            ApiToken winner = apiTokenRepository.findByUserIdAndIdempotencyKeyHash(userId, keyHash)
                    .orElseThrow(() -> e);
            throw conflictFor(winner);
        }
        return CreatedApiTokenResponse.of(toResponse(token), rawToken);
    }

    private ApiTokenIdempotencyConflictException conflictFor(ApiToken existing) {
        return new ApiTokenIdempotencyConflictException(
                "This idempotency key already created a token; the original secret cannot be "
                        + "recovered — revoke it and create a new one if you need a new token.",
                toResponse(existing));
    }

    @Transactional(readOnly = true)
    public List<ApiTokenResponse> listByUser(Long userId) {
        return apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void revoke(Long userId, Long tokenId) {
        ApiToken token = apiTokenRepository.findByIdAndUserId(tokenId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("ApiToken", tokenId));
        token.setRevokedAt(Instant.now());
        apiTokenRepository.save(token);
    }

    /**
     * Called from PatAuthenticationFilter on every PAT-bearing request. Empty result means
     * "unknown, expired, or revoked" without distinguishing which — callers must not leak that
     * distinction back to the client.
     */
    @Transactional
    public Optional<ApiToken> authenticate(String rawToken) {
        String hash = hashToken(rawToken);
        Optional<ApiToken> found = apiTokenRepository.findByTokenHashWithUser(hash);
        if (found.isEmpty() || !found.get().isValid()) {
            return Optional.empty();
        }
        ApiToken token = found.get();
        token.setLastUsedAt(Instant.now());
        apiTokenRepository.save(token);
        return Optional.of(token);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private ApiTokenResponse toResponse(ApiToken token) {
        return new ApiTokenResponse(
                token.getId(),
                token.getName(),
                token.getTokenPrefix(),
                token.getScope(),
                token.getCreatedAt(),
                token.getExpiresAt(),
                token.getLastUsedAt(),
                token.isRevoked()
        );
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
