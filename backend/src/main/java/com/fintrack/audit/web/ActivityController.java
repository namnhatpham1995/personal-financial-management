package com.fintrack.audit.web;

import com.fintrack.audit.domain.AuditLogRepository;
import com.fintrack.audit.web.dto.ActivityEventResponse;
import com.fintrack.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
@Tag(name = "Activity")
@SecurityRequirement(name = "bearerAuth")
public class ActivityController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @Operation(summary = "List recent activity events for the current user")
    public Page<ActivityEventResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return auditLogRepository
                .findByUserIdOrderByTsDesc(principal.getUserId(), PageRequest.of(page, Math.min(size, 100)))
                .map(e -> new ActivityEventResponse(e.getId(), e.getAction(), e.getTs(),
                        e.getCorrelationId(), e.getMeta()));
    }
}
