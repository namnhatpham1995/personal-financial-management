package com.fintrack.audit.web.dto;

import java.time.Instant;
import java.util.Map;

public record ActivityEventResponse(
        Long id,
        String action,
        Instant ts,
        String correlationId,
        Map<String, Object> meta
) {}
