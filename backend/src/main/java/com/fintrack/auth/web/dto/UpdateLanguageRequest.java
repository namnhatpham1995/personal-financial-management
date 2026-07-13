package com.fintrack.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateLanguageRequest(
        @NotBlank
        @Pattern(regexp = "^(en|vi|de|zh)$", message = "Unsupported language code")
        String language
) {}
