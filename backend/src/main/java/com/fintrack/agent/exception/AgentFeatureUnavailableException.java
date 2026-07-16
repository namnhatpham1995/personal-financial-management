package com.fintrack.agent.exception;

/** Thrown when the agent service is not configured/deployed — keeps the feature dark by default. */
public class AgentFeatureUnavailableException extends RuntimeException {
    public AgentFeatureUnavailableException(String message) {
        super(message);
    }
}
