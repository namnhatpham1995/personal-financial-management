package com.fintrack.apitoken.web.dto;

/** Returned only from the create endpoint — the one place the plaintext token is ever exposed. */
public record CreatedApiTokenResponse(
        ApiTokenResponse token,
        String plaintextToken
) {
    public static CreatedApiTokenResponse of(ApiTokenResponse token, String plaintextToken) {
        return new CreatedApiTokenResponse(token, plaintextToken);
    }
}
