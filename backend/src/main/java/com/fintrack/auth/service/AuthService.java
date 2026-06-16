package com.fintrack.auth.service;

import com.fintrack.auth.domain.RefreshToken;
import com.fintrack.auth.domain.Role;
import com.fintrack.auth.domain.User;
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
    private final UserDetailsServiceImpl userDetailsService;
    private final AppProperties appProperties;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        String hash = hashToken(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (!stored.isValid()) {
            // Revoke all tokens for this user on detected reuse of expired/revoked token
            refreshTokenRepository.revokeAllByUserId(stored.getUser().getId());
            throw new BadCredentialsException("Refresh token is expired or revoked");
        }

        // Rotate: revoke the old token, issue a fresh pair
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);
        return issueTokens(stored.getUser());
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
                spaceIdx > 0 ? fullName.substring(spaceIdx + 1) : ""
        );
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TokenResponse issueTokens(User user) {
        UserPrincipal principal = new UserPrincipal(user);
        String accessToken = jwtService.generateAccessToken(principal, user.getId());
        String rawRefresh = generateRawRefreshToken();
        persistRefreshToken(user, rawRefresh);

        String fullName = user.getFullName() != null ? user.getFullName() : "";
        int spaceIdx = fullName.indexOf(' ');
        String firstName = spaceIdx > 0 ? fullName.substring(0, spaceIdx) : fullName;
        String lastName  = spaceIdx > 0 ? fullName.substring(spaceIdx + 1) : "";

        return TokenResponse.of(
                accessToken,
                rawRefresh,
                appProperties.getJwt().getAccessTokenExpiryMs(),
                user.getId(),
                user.getEmail(),
                firstName,
                lastName
        );
    }

    private void persistRefreshToken(User user, String rawToken) {
        Instant expiresAt = Instant.now().plusMillis(appProperties.getJwt().getRefreshTokenExpiryMs());
        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(rawToken))
                .expiresAt(expiresAt)
                .build();
        refreshTokenRepository.save(rt);
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
