package com.fintrack.audit.service;

import com.fintrack.audit.domain.ActivityEvent;
import com.fintrack.audit.domain.ActivityEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Best-effort audit recorder — a MongoDB failure MUST NOT propagate to the caller.
 * Business operations own their own transactions; this write is fire-and-forget.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(ActivityEventRepository.class)
public class ActivityRecorder {

    private final ActivityEventRepository repository;

    public void record(Long userId, String action, String correlationId, Map<String, Object> meta) {
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
