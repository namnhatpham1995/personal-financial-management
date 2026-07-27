package com.fintrack.vault.service;

import com.fintrack.agent.service.AgentRunService;
import com.fintrack.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/** PostgreSQL phase of Vault reassignment. */
@Service
@RequiredArgsConstructor
public class VaultReassignmentCleanupService {

    private final TransactionService transactionService;
    private final AgentRunService agentRunService;

    @Transactional
    public CleanupResult clean(Long userId, String documentId, Long sourceAccountId,
                               Collection<Long> transactionIds, String reason) {
        int removed = transactionService.deleteImportedTransactions(userId, transactionIds);
        int invalidatedRuns = agentRunService.invalidateRunsForReassignment(
                userId, documentId, sourceAccountId, reason);
        return new CleanupResult(removed, invalidatedRuns);
    }

    public record CleanupResult(int removedTransactions, int invalidatedRuns) {}
}
