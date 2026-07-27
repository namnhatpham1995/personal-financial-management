package com.fintrack.vault.domain;

import com.fintrack.agent.domain.AgentRunStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Exhaustive mapping between the two backing status enums and the single {@link VaultStage}
 * vocabulary presented to users. A statement's stage is derived from its own
 * {@link VaultDocumentStatus}; a receipt's stage is derived from its latest ingestion run's
 * {@link AgentRunStatus}, or {@link VaultStage#NOT_PROCESSED} when it has never been ingested.
 */
public final class VaultStageMapper {

    private static final Map<VaultDocumentStatus, VaultStage> STATEMENT_STAGES =
            new EnumMap<>(VaultDocumentStatus.class);
    private static final Map<AgentRunStatus, VaultStage> RECEIPT_STAGES =
            new EnumMap<>(AgentRunStatus.class);

    static {
        STATEMENT_STAGES.put(VaultDocumentStatus.UPLOADED, VaultStage.READY_TO_IMPORT);
        STATEMENT_STAGES.put(VaultDocumentStatus.STAGED, VaultStage.NEEDS_REVIEW);
        STATEMENT_STAGES.put(VaultDocumentStatus.CONFIRMING, VaultStage.NEEDS_REVIEW);
        STATEMENT_STAGES.put(VaultDocumentStatus.ACTIVE, VaultStage.IMPORTED);

        RECEIPT_STAGES.put(AgentRunStatus.EXTRACTING, VaultStage.PROCESSING);
        RECEIPT_STAGES.put(AgentRunStatus.AWAITING_REVIEW, VaultStage.NEEDS_REVIEW);
        RECEIPT_STAGES.put(AgentRunStatus.COMMITTED, VaultStage.IMPORTED);
        RECEIPT_STAGES.put(AgentRunStatus.FAILED, VaultStage.FAILED);
        RECEIPT_STAGES.put(AgentRunStatus.REJECTED, VaultStage.DISMISSED);
        RECEIPT_STAGES.put(AgentRunStatus.INVALIDATED, VaultStage.DISMISSED);
    }

    /** Stages that ever apply to a STATEMENT document. */
    public static final Set<VaultStage> STATEMENT_APPLICABLE_STAGES =
            EnumSet.copyOf(STATEMENT_STAGES.values());

    /** Stages that ever apply to a RECEIPT document, including NOT_PROCESSED. */
    public static final Set<VaultStage> RECEIPT_APPLICABLE_STAGES = EnumSet.copyOf(RECEIPT_STAGES.values());

    static {
        RECEIPT_APPLICABLE_STAGES.add(VaultStage.NOT_PROCESSED);
    }

    private VaultStageMapper() {
    }

    public static VaultStage forStatementStatus(VaultDocumentStatus status) {
        VaultStage stage = STATEMENT_STAGES.get(status);
        if (stage == null) {
            throw new IllegalArgumentException("Unmapped statement status: " + status);
        }
        return stage;
    }

    /** {@code latestIngestionStatus} is null when the receipt has never been ingested. */
    public static VaultStage forReceiptIngestionStatus(AgentRunStatus latestIngestionStatus) {
        if (latestIngestionStatus == null) {
            return VaultStage.NOT_PROCESSED;
        }
        VaultStage stage = RECEIPT_STAGES.get(latestIngestionStatus);
        if (stage == null) {
            throw new IllegalArgumentException("Unmapped ingestion status: " + latestIngestionStatus);
        }
        return stage;
    }

    /** Statement statuses that derive to the given stage, empty if the stage never applies to statements. */
    public static Set<VaultDocumentStatus> statementStatusesForStage(VaultStage stage) {
        EnumSet<VaultDocumentStatus> result = EnumSet.noneOf(VaultDocumentStatus.class);
        STATEMENT_STAGES.forEach((status, mappedStage) -> {
            if (mappedStage == stage) {
                result.add(status);
            }
        });
        return result;
    }

    /** Ingestion statuses that derive to the given stage, empty if the stage never applies to receipts. */
    public static Set<AgentRunStatus> receiptIngestionStatusesForStage(VaultStage stage) {
        EnumSet<AgentRunStatus> result = EnumSet.noneOf(AgentRunStatus.class);
        RECEIPT_STAGES.forEach((status, mappedStage) -> {
            if (mappedStage == stage) {
                result.add(status);
            }
        });
        return result;
    }
}
