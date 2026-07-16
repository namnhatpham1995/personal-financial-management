package com.fintrack.agent.domain;

/** Lifecycle of an ingestion run: EXTRACTING -> AWAITING_REVIEW -> COMMITTED | REJECTED | FAILED. */
public enum AgentRunStatus {
    EXTRACTING,
    AWAITING_REVIEW,
    COMMITTED,
    REJECTED,
    FAILED
}
