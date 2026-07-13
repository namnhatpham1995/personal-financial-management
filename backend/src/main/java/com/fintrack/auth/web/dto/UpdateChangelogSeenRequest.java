package com.fintrack.auth.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateChangelogSeenRequest(
        @NotNull
        @Positive
        Integer version
) {}
