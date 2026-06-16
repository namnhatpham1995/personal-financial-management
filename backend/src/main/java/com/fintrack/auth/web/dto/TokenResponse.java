package com.fintrack.auth.web.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserInfo user
) {
    public record UserInfo(Long id, String email, String firstName, String lastName) {}

    public static TokenResponse of(
            String accessToken, String refreshToken, long expiresInMs,
            Long userId, String email, String firstName, String lastName) {
        return new TokenResponse(
                accessToken, refreshToken, "Bearer", expiresInMs / 1000,
                new UserInfo(userId, email, firstName, lastName));
    }
}
