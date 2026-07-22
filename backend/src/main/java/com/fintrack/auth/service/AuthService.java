package com.fintrack.auth.service;

import com.fintrack.auth.domain.RefreshToken;
import com.fintrack.auth.domain.AuthSession;
import com.fintrack.auth.domain.Role;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.exception.RefreshAlreadyRotatedException;
import com.fintrack.auth.repository.RefreshTokenRepository;
import com.fintrack.auth.repository.RoleRepository;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.auth.web.dto.LoginRequest;
import com.fintrack.auth.web.dto.RegisterRequest;
import com.fintrack.auth.web.dto.TokenResponse;
import com.fintrack.common.config.AppProperties;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.common.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthSessionService authSessionService;
    private final UserDetailsServiceImpl userDetailsService;
    private final AppProperties appProperties;
    private final EntityManager entityManager;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Concurrency grace window for benign racing rotations (design.md Decision #5). */
    private static final long REFRESH_GRACE_WINDOW_SECONDS = 10;

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email already in use: " + request.email());
        }
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found — check DB seeding"));

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.firstName() + " " + request.lastName())
                .roles(Set.of(userRole))
                .build();

        user = userRepository.save(user);
        log.info("Registered new user: {}", user.getEmail());
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return issueTokens(user);
    }

    @Transactional(noRollbackFor = {BadCredentialsException.class, RefreshAlreadyRotatedException.class})
    public TokenResponse refresh(String rawRefreshToken) {
        String hash = hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (stored.getSession() != null) {
            authSessionService.requireActive(stored.getSession().getId(), stored.getUser().getId());
        }

        if (stored.isExpired()) {
            refreshTokenRepository.revokeAllByUserId(stored.getUser().getId());
            throw new BadCredentialsException("Refresh token is expired or revoked");
        }

        Instant now = Instant.now();
        int consumed = refreshTokenRepository.consumeIfNotRevoked(stored.getId(), now);
        if (consumed == 1) {
            // Won the rotation race: keep the in-memory entity in sync with the row we just
            // updated (a @Modifying query does not refresh already-loaded instances) before the
            // follow-up save that links the successor.
            stored.setRevoked(true);
            stored.setRotatedAt(now);
            if (stored.getSession() != null) {
                authSessionService.recordActivity(stored.getSession());
            }
            IssuedTokens issued = issueTokensInternal(stored.getUser(), stored.getSession());
            stored.setSuccessorId(issued.refreshToken().getId());
            refreshTokenRepository.save(stored);
            return issued.response();
        }

        // Lost the race, or the row was already revoked before this call even started. `stored`
        // is a managed entity already sitting in this transaction's persistence context — Hibernate
        // returns the same session-cached instance (identity map) for any subsequent find/query on
        // this id rather than overwriting it from a fresh SELECT, so a plain re-query would still
        // see this session's original (pre-block) snapshot. EntityManager.refresh forces an actual
        // re-read from the database, picking up the winner's committed rotatedAt/revoked state.
        entityManager.refresh(stored);

        if (stored.getRotatedAt() != null && !stored.getRotatedAt().plusSeconds(REFRESH_GRACE_WINDOW_SECONDS).isBefore(now)) {
            // A concurrent request rotated this token moments ago — expected benign concurrency
            // (lost-response retry or a second racing tab), not a theft signal.
            throw new RefreshAlreadyRotatedException("Refresh token was already rotated by a concurrent request");
        }

        if (stored.getRotatedAt() == null) {
            // Revoked directly rather than via rotation (e.g. logout). The spec does not carve
            // out a refresh-vs-logout race scenario; logout is a deliberate user action, not a
            // theft signal, so it does not trigger family-wide revocation here.
            throw new BadCredentialsException("Refresh token is expired or revoked");
        }

        // Rotated well outside the grace window and now being replayed: theft-detection posture.
        refreshTokenRepository.revokeAllByUserId(stored.getUser().getId());
        throw new BadCredentialsException("Refresh token is expired or revoked");
    }

    @Transactional(readOnly = true)
    public TokenResponse.UserInfo getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        String fullName = user.getFullName() != null ? user.getFullName() : "";
        int spaceIdx = fullName.indexOf(' ');
        return new TokenResponse.UserInfo(
                user.getId(), user.getEmail(),
                spaceIdx > 0 ? fullName.substring(0, spaceIdx) : fullName,
                spaceIdx > 0 ? fullName.substring(spaceIdx + 1) : "",
                user.getPreferredLanguage(),
                user.getLastSeenChangelogVersion()
        );
    }

    @Transactional
    public TokenResponse.UserInfo updateLanguage(Long userId, String language) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        user.setPreferredLanguage(language);
        userRepository.save(user);
        return getUserInfo(userId);
    }

    @Transactional
    public TokenResponse.UserInfo updateChangelogSeen(Long userId, int version) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        int current = user.getLastSeenChangelogVersion() != null ? user.getLastSeenChangelogVersion() : 0;
        if (version > current) {
            user.setLastSeenChangelogVersion(version);
            userRepository.save(user);
        }
        return getUserInfo(userId);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            if (rt.getSession() != null) {
                authSessionService.revoke(rt.getSession().getId());
            } else {
                rt.setRevoked(true);
                refreshTokenRepository.save(rt);
            }
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TokenResponse issueTokens(User user) {
        return issueTokensInternal(user, authSessionService.start(user)).response();
    }

    /** Pairs the returned {@link TokenResponse} with the persisted successor entity so callers
     *  that need the new row's id (refresh rotation lineage) don't have to re-look it up by hash. */
    private record IssuedTokens(TokenResponse response, RefreshToken refreshToken) {}

    private IssuedTokens issueTokensInternal(User user, AuthSession session) {
        UserPrincipal principal = new UserPrincipal(user);
        String accessToken = session == null
                ? jwtService.generateAccessToken(principal, user.getId())
                : jwtService.generateAccessToken(principal, user.getId(), session.getId());
        String rawRefresh = generateRawRefreshToken();
        RefreshToken saved = persistRefreshToken(user, rawRefresh, session);

        String fullName = user.getFullName() != null ? user.getFullName() : "";
        int spaceIdx = fullName.indexOf(' ');
        String firstName = spaceIdx > 0 ? fullName.substring(0, spaceIdx) : fullName;
        String lastName  = spaceIdx > 0 ? fullName.substring(spaceIdx + 1) : "";

        TokenResponse response = TokenResponse.of(
                accessToken,
                rawRefresh,
                appProperties.getJwt().getAccessTokenExpiryMs(),
                user.getId(),
                user.getEmail(),
                firstName,
                lastName,
                user.getPreferredLanguage(),
                user.getLastSeenChangelogVersion()
        );
        return new IssuedTokens(response, saved);
    }

    private RefreshToken persistRefreshToken(User user, String rawToken, AuthSession session) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(appProperties.getJwt().getRefreshTokenExpiryMs());
        if (session != null && expiresAt.isAfter(session.getAbsoluteExpiresAt())) {
            expiresAt = session.getAbsoluteExpiresAt();
        }
        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .session(session)
                .tokenHash(hashToken(rawToken))
                .expiresAt(expiresAt)
                .build();
        return refreshTokenRepository.save(rt);
    }

    private static String generateRawRefreshToken() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
