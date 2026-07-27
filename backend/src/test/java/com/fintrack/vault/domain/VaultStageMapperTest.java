package com.fintrack.vault.domain;

import com.fintrack.agent.domain.AgentRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class VaultStageMapperTest {

    @ParameterizedTest
    @EnumSource(VaultDocumentStatus.class)
    void everyStatementStatus_mapsToExactlyOneStage(VaultDocumentStatus status) {
        assertThat(VaultStageMapper.forStatementStatus(status)).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(AgentRunStatus.class)
    void everyIngestionStatus_mapsToExactlyOneStage(AgentRunStatus status) {
        assertThat(VaultStageMapper.forReceiptIngestionStatus(status)).isNotNull();
    }

    @Test
    void neverIngestedReceipt_mapsToNotProcessed() {
        assertThat(VaultStageMapper.forReceiptIngestionStatus(null)).isEqualTo(VaultStage.NOT_PROCESSED);
    }

    @Test
    void statementStatus_mapsToExpectedStage() {
        assertThat(VaultStageMapper.forStatementStatus(VaultDocumentStatus.UPLOADED))
                .isEqualTo(VaultStage.READY_TO_IMPORT);
        assertThat(VaultStageMapper.forStatementStatus(VaultDocumentStatus.STAGED))
                .isEqualTo(VaultStage.NEEDS_REVIEW);
        assertThat(VaultStageMapper.forStatementStatus(VaultDocumentStatus.CONFIRMING))
                .isEqualTo(VaultStage.NEEDS_REVIEW);
        assertThat(VaultStageMapper.forStatementStatus(VaultDocumentStatus.ACTIVE))
                .isEqualTo(VaultStage.IMPORTED);
    }

    @Test
    void ingestionStatus_mapsToExpectedStage() {
        assertThat(VaultStageMapper.forReceiptIngestionStatus(AgentRunStatus.EXTRACTING))
                .isEqualTo(VaultStage.PROCESSING);
        assertThat(VaultStageMapper.forReceiptIngestionStatus(AgentRunStatus.AWAITING_REVIEW))
                .isEqualTo(VaultStage.NEEDS_REVIEW);
        assertThat(VaultStageMapper.forReceiptIngestionStatus(AgentRunStatus.COMMITTED))
                .isEqualTo(VaultStage.IMPORTED);
        assertThat(VaultStageMapper.forReceiptIngestionStatus(AgentRunStatus.FAILED))
                .isEqualTo(VaultStage.FAILED);
        assertThat(VaultStageMapper.forReceiptIngestionStatus(AgentRunStatus.REJECTED))
                .isEqualTo(VaultStage.DISMISSED);
        assertThat(VaultStageMapper.forReceiptIngestionStatus(AgentRunStatus.INVALIDATED))
                .isEqualTo(VaultStage.DISMISSED);
    }

    @Test
    void statementApplicableStages_excludeReceiptOnlyStages() {
        assertThat(VaultStageMapper.STATEMENT_APPLICABLE_STAGES)
                .containsExactlyInAnyOrder(VaultStage.READY_TO_IMPORT, VaultStage.NEEDS_REVIEW, VaultStage.IMPORTED)
                .doesNotContain(VaultStage.NOT_PROCESSED, VaultStage.PROCESSING, VaultStage.FAILED, VaultStage.DISMISSED);
    }

    @Test
    void receiptApplicableStages_includeNotProcessedAndExcludeReadyToImport() {
        assertThat(VaultStageMapper.RECEIPT_APPLICABLE_STAGES)
                .containsExactlyInAnyOrder(VaultStage.NOT_PROCESSED, VaultStage.PROCESSING, VaultStage.NEEDS_REVIEW,
                        VaultStage.IMPORTED, VaultStage.FAILED, VaultStage.DISMISSED)
                .doesNotContain(VaultStage.READY_TO_IMPORT);
    }

    @Test
    void statementStatusesForStage_roundTripsWithForStatementStatus() {
        for (VaultDocumentStatus status : VaultDocumentStatus.values()) {
            VaultStage stage = VaultStageMapper.forStatementStatus(status);
            assertThat(VaultStageMapper.statementStatusesForStage(stage)).contains(status);
        }
    }

    @Test
    void receiptIngestionStatusesForStage_roundTripsWithForReceiptIngestionStatus() {
        for (AgentRunStatus status : AgentRunStatus.values()) {
            VaultStage stage = VaultStageMapper.forReceiptIngestionStatus(status);
            assertThat(VaultStageMapper.receiptIngestionStatusesForStage(stage)).contains(status);
        }
    }
}
