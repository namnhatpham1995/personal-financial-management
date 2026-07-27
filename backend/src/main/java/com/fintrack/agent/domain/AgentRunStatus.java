package com.fintrack.agent.domain;

/** Lifecycle of an ingestion run, including retained invalidation after Vault reassignment. */
public enum AgentRunStatus {
    EXTRACTING,
    AWAITING_REVIEW,
    COMMITTED,
    REJECTED,
    FAILED,
    INVALIDATED
}
