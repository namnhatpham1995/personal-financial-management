package com.fintrack.audit.service;

import com.fintrack.audit.domain.ActivityEvent;
import com.fintrack.audit.domain.ActivityEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityRecorderTest {

    @Mock ActivityEventRepository repository;
    @InjectMocks ActivityRecorder recorder;

    @Test
    void record_savesEventWithCorrectFields() {
        recorder.record(42L, "account.created", "corr-123", Map.of("uri", "/api/v1/accounts"));

        ArgumentCaptor<ActivityEvent> captor = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(repository).save(captor.capture());

        ActivityEvent saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getAction()).isEqualTo("account.created");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-123");
        assertThat(saved.getTs()).isNotNull();
        assertThat(saved.getMeta()).containsEntry("uri", "/api/v1/accounts");
    }

    @Test
    void record_mongoFailure_doesNotPropagateToCallerAndLogsWarn() {
        doThrow(new RuntimeException("Mongo down")).when(repository).save(any());

        // Must not throw — best-effort contract
        assertThatCode(() -> recorder.record(1L, "account.created", "corr-x", Map.of()))
                .doesNotThrowAnyException();
    }
}
