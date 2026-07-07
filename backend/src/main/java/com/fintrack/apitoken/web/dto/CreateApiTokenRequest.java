package com.fintrack.apitoken.web.dto;

import com.fintrack.apitoken.domain.ApiTokenScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateApiTokenRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ApiTokenScope scope,
        @NotNull Integer expiryDays
) {}
