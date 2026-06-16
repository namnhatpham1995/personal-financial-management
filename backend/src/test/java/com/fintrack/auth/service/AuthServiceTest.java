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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock UserDetailsServiceImpl userDetailsService;
    @Mock AppProperties appProperties;

    @InjectMocks AuthService authService;

    private AppProperties.Jwt jwtProps;
    private Role userRole;

    @BeforeEach
    void setUp() {
        jwtProps = new AppProperties.Jwt();
        jwtProps.setSecret("test-secret");
        jwtProps.setAccessTokenExpiryMs(900_000L);
        jwtProps.setRefreshTokenExpiryMs(604_800_000L);

        // lenient: only needed by tests that reach issueTokens(); others throw first
        lenient().when(appProperties.getJwt()).thenReturn(jwtProps);

        userRole = new Role("ROLE_USER");
        userRole.setId(1L);
    }

    @Test
    void register_success_returnsTokens() {
        RegisterRequest req = new RegisterRequest("a@b.com", "password123", "Alice", "Smith");

        when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));

        User savedUser = User.builder()
                .id(1L).email("a@b.com").fullName("Alice Smith")
                .roles(Set.of(userRole)).build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateAccessToken(any(), anyLong())).thenReturn("access-token");
        when(refreshTokenRepository.save(any())).thenReturn(new RefreshToken());

        TokenResponse response = authService.register(req);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().email()).isEqualTo("a@b.com");
        assertThat(response.user().firstName()).isEqualTo("Alice");
    }

    @Test
    void register_duplicateEmail_throwsConflict() {
        when(userRepository.existsByEmail("dup@b.com")).thenReturn(true);

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("dup@b.com", "pw12345678", "A", "B"))
        ).isInstanceOf(ConflictException.class);
    }

    @Test
    void login_wrongPassword_throwsBadCredentials() {
        User user = User.builder().id(1L).email("x@b.com").passwordHash("hash").fullName("X Y").build();
        when(userRepository.findByEmail("x@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() ->
                authService.login(new LoginRequest("x@b.com", "wrong"))
        ).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_success_returnsTokenPair() {
        User user = User.builder().id(2L).email("y@b.com").passwordHash("hash").fullName("Bob Jones").build();
        when(userRepository.findByEmail("y@b.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass1234", "hash")).thenReturn(true);
        when(jwtService.generateAccessToken(any(), anyLong())).thenReturn("new-access");
        when(refreshTokenRepository.save(any())).thenReturn(new RefreshToken());

        TokenResponse response = authService.login(new LoginRequest("y@b.com", "pass1234"));

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void refresh_revokedToken_revokesAllAndThrows() {
        RefreshToken expired = RefreshToken.builder()
                .tokenHash("somehash")
                .expiresAt(Instant.now().minusSeconds(1))
                .revoked(false)
                .user(User.builder().id(99L).build())
                .build();

        // We need to find by hash — AuthService hashes the raw token with SHA-256
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("raw-token-value"))
                .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).revokeAllByUserId(99L);
    }
}
