package com.fintrack.idempotency.service;

import com.fintrack.idempotency.exception.MissingIdempotencyKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Interim accept/observe/enforce toggle for protected-create endpoints.
 *
 * <p>This is a placeholder: it only supports a binary "require key or not" switch. Task 9.5 owns
 * building the full three-mode accept/observe/enforce rollout setting (with client-deployment
 * tracking) described in {@code openspec/changes/harden-idempotent-mutations/design.md}; this
 * class will be replaced/extended by that work. Do not add more modes or configuration here ahead
 * of that task.
 */
@Component
public class IdempotencyEnforcementGuard {

    private final boolean requireKey;

    public IdempotencyEnforcementGuard(
            @Value("${fintrack.idempotency.require-key:false}") boolean requireKey) {
        this.requireKey = requireKey;
    }

    /**
     * Throws {@link MissingIdempotencyKeyException} when enforcement is on and no key was
     * supplied. A no-op in the default accept/observe mode, allowing the endpoint to fall back to
     * calling the service directly without idempotency protection.
     */
    public void requireKeyOrThrow(String rawIdempotencyKey) {
        if (requireKey && rawIdempotencyKey == null) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for this operation");
        }
    }
}
