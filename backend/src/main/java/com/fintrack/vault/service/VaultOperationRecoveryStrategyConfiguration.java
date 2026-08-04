package com.fintrack.vault.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Maps durable vault operation names to their compensation strategies. */
@Configuration
public class VaultOperationRecoveryStrategyConfiguration {

    @Bean
    Map<String, VaultOperationRecoveryStrategy> vaultOperationRecoveryStrategies(
            UploadRecoveryStrategy uploadRecoveryStrategy,
            ReassignmentRecoveryStrategy reassignmentRecoveryStrategy) {
        return Map.of(
                "vault.upload", uploadRecoveryStrategy,
                "statement.upload", uploadRecoveryStrategy,
                "vault.reassign", reassignmentRecoveryStrategy);
    }
}
