package com.fintrack.audit.service;

import com.fintrack.audit.domain.AuditLog;
import com.fintrack.audit.domain.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Writes one audit row to PostgreSQL.
 * REQUIRES_NEW ensures the write commits in its own transaction even if called
 * from a context where the business transaction has already completed.
 * Failures are logged as errors but never propagate to the caller — the business
 * response has already been sent when this is invoked from afterCompletion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long userId, String action, String correlationId, Map<String, Object> meta) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .userId(userId)
                    .action(action)
                    .ts(Instant.now())
                    .correlationId(correlationId)
                    .meta(meta)
                    .build());
        } catch (Exception ex) {
            log.error("Audit write failed: action={} userId={} error={}", action, userId, ex.getMessage(), ex);
        }
    }
}
