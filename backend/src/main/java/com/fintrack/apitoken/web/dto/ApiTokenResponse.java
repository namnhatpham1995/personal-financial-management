package com.fintrack.apitoken.web.dto;

import com.fintrack.apitoken.domain.ApiTokenScope;

import java.time.Instant;

/** Never carries the plaintext token — only {@link CreatedApiTokenResponse} does, once, at creation. */
public record ApiTokenResponse(
        Long id,
        String name,
        String tokenPrefix,
        ApiTokenScope scope,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        boolean revoked
) {}
