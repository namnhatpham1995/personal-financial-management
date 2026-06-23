package com.fintrack.audit.service;

import com.fintrack.audit.domain.AuditLog;
import com.fintrack.audit.domain.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Writes one audit row to PostgreSQL.
 * Uses TransactionTemplate with REQUIRES_NEW so that a FK violation (e.g. userId not
 * yet visible in the calling thread's tx) rolls back cleanly without propagating
 * UnexpectedRollbackException to the interceptor caller.
 * Failures are logged but never propagate — the business response is already sent
 * when this is invoked from afterCompletion.
 */
@Slf4j
@Service
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;
    private final TransactionTemplate requiresNewTx;

    public AuditLogWriter(AuditLogRepository auditLogRepository, PlatformTransactionManager txManager) {
        this.auditLogRepository = auditLogRepository;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void write(Long userId, String action, String correlationId, Map<String, Object> meta) {
        try {
            requiresNewTx.execute(status -> {
                auditLogRepository.save(AuditLog.builder()
                        .userId(userId)
                        .action(action)
                        .ts(Instant.now())
                        .correlationId(correlationId)
                        .meta(meta)
                        .build());
                return null;
            });
        } catch (Exception ex) {
            log.error("Audit write failed: action={} userId={} error={}", action, userId, ex.getMessage(), ex);
        }
    }
}
