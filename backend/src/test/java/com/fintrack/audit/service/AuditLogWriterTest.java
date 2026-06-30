package com.fintrack.audit.service;

import com.fintrack.audit.domain.AuditLog;
import com.fintrack.audit.domain.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock PlatformTransactionManager txManager;
    @InjectMocks AuditLogWriter writer;

    @Test
    void write_savesAuditLogWithCorrectFields() {
        writer.write(42L, "account.created", "corr-123", Map.of("uri", "/api/v1/accounts"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getAction()).isEqualTo("account.created");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-123");
        assertThat(saved.getTs()).isNotNull();
        assertThat(saved.getMeta()).containsEntry("uri", "/api/v1/accounts");
    }

    @Test
    void write_repositoryFailure_doesNotPropagate() {
        doThrow(new RuntimeException("DB unavailable")).when(auditLogRepository).save(any());

        assertThatCode(() -> writer.write(1L, "account.created", "corr-x", Map.of()))
                .doesNotThrowAnyException();
    }
}
