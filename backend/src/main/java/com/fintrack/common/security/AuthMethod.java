package com.fintrack.common.security;

public enum AuthMethod {
    JWT,
    PAT,
    /** Short-lived, per-run scoped token minted for the receipt ingestion agent service. */
    AGENT
}
