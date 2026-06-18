package com.fintrack.audit.service;

import com.fintrack.audit.domain.ActivityEvent;
import com.fintrack.audit.domain.ActivityEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Best-effort audit recorder — a MongoDB failure MUST NOT propagate to the caller.
 * Business operations own their own transactions; this write is fire-and-forget.
 *
 * The repository is optional so this bean is always registered as a @Service.
 * This avoids a Spring @ConditionalOnBean chaining issue where the condition on
 * ActivityAuditInterceptor would incorrectly pass before ActivityRecorder's own
 * condition was definitively evaluated (component scan runs before MongoDB
 * auto-configuration registers the repository bean).
 */
@Slf4j
@Service
public class ActivityRecorder {

    // Injected after MongoDB auto-configuration runs; null when MongoDB is not configured.
    @Autowired(required = false)
    @Nullable
    private ActivityEventRepository repository;

    public void record(Long userId, String action, String correlationId, Map<String, Object> meta) {
        if (repository == null) return;
        try {
            repository.save(ActivityEvent.builder()
                    .userId(userId)
                    .action(action)
                    .ts(Instant.now())
                    .correlationId(correlationId)
                    .meta(meta)
                    .build());
        } catch (Exception ex) {
            log.warn("Audit write failed (best-effort, ignored): action={} userId={} error={}",
                    action, userId, ex.getMessage());
        }
    }
}
