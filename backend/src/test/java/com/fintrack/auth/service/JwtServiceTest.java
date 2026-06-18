package com.fintrack.auth.service;

import com.fintrack.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    // 32-char secret satisfies HMAC-SHA256 minimum key length (256 bits)
    private static final String SECRET = "test-secret-key-32-chars-minimum";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        AppProperties.Jwt jwt = new AppProperties.Jwt();
        jwt.setSecret(SECRET);
        jwt.setAccessTokenExpiryMs(900_000L);   // 15 min
        props.setJwt(jwt);
        jwtService = new JwtService(props);
    }

    private UserDetails userDetails(String username) {
        return new User(username, "irrelevant", Collections.emptyList());
    }

    @Test
    void generateAndValidate_validToken_returnsTrue() {
        UserDetails ud = userDetails("alice@example.com");
        String token = jwtService.generateAccessToken(ud, 1L);

        assertThat(jwtService.isTokenValid(token, ud)).isTrue();
    }

    @Test
    void extractSubject_returnsUsername() {
        UserDetails ud = userDetails("bob@example.com");
        String token = jwtService.generateAccessToken(ud, 2L);

        assertThat(jwtService.extractSubject(token)).isEqualTo("bob@example.com");
    }

    @Test
    void isTokenValid_expiredToken_returnsFalse() {
        AppProperties props = new AppProperties();
        AppProperties.Jwt jwt = new AppProperties.Jwt();
        jwt.setSecret(SECRET);
        jwt.setAccessTokenExpiryMs(-1000L);  // already expired
        props.setJwt(jwt);
        JwtService expiredJwtService = new JwtService(props);

        UserDetails ud = userDetails("carol@example.com");
        String token = expiredJwtService.generateAccessToken(ud, 3L);

        assertThat(expiredJwtService.isTokenValid(token, ud)).isFalse();
    }

    @Test
    void isTokenValid_tamperedSignature_returnsFalse() {
        UserDetails ud = userDetails("dave@example.com");
        String token = jwtService.generateAccessToken(ud, 4L);

        // Flip one character in the signature (last segment)
        String[] parts = token.split("\\.");
        String tamperedSig = parts[2].substring(0, parts[2].length() - 1) + "X";
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSig;

        assertThat(jwtService.isTokenValid(tampered, ud)).isFalse();
    }

    @Test
    void isTokenValid_differentUser_returnsFalse() {
        UserDetails alice = userDetails("alice@example.com");
        UserDetails bob = userDetails("bob@example.com");
        String token = jwtService.generateAccessToken(alice, 1L);

        assertThat(jwtService.isTokenValid(token, bob)).isFalse();
    }
}
