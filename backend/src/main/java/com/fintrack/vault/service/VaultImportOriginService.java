package com.fintrack.vault.service;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.vault.domain.RowOutcome;
import com.fintrack.vault.domain.RowOutcomeStatus;
import com.fintrack.vault.domain.VaultDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Finds imported transaction ids across current and legacy import-origin signals. */
@Service
@RequiredArgsConstructor
public class VaultImportOriginService {

    private final TransactionRepository transactionRepository;
    private final AgentRunRepository agentRunRepository;

    public Set<Long> collectTransactionIds(Long userId, VaultDocument document) {
        Set<Long> ids = new HashSet<>();
        addTransactions(ids, transactionRepository.findByUserIdAndSourceDocumentId(userId, document.getId()));

        Set<String> dedupKeys = new HashSet<>();
        collectStatementDedupKeys(document, ids, dedupKeys);
        if (!dedupKeys.isEmpty()) {
            addStatementDedupTransactions(ids, document.getId(),
                    transactionRepository.findByUserIdAndImportDedupKeyIn(userId, new ArrayList<>(dedupKeys)));
        }

        for (AgentRun run : agentRunRepository.findByVaultDocumentIdAndUser_Id(document.getId(), userId)) {
            if (run.getCreatedTransactionIds() != null) {
                ids.addAll(run.getCreatedTransactionIds());
            }
            addTransactions(ids, transactionRepository.findByUserIdAndImportDedupKeyStartingWith(
                    userId, "agent-run:" + run.getId() + ":"));
        }
        return ids;
    }

    private void collectStatementDedupKeys(VaultDocument document, Set<Long> ids, Set<String> dedupKeys) {
        Map<String, Object> payload = document.getPayload();
        if (payload != null) {
            addOutcomeKeys(payload.get("rows"), dedupKeys);
        }
        Object outcomes = document.getConfirmationRowOutcomes();
        if (outcomes instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                dedupKeys.add(entry.getKey().toString());
                if (entry.getValue() instanceof RowOutcome outcome
                        && outcome.getStatus() == RowOutcomeStatus.CREATED
                        && outcome.getTransactionId() != null) {
                    ids.add(outcome.getTransactionId());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addOutcomeKeys(Object rowsValue, Set<String> dedupKeys) {
        if (!(rowsValue instanceof Collection<?> rows)) {
            return;
        }
        for (Object rowValue : rows) {
            if (rowValue instanceof Map<?, ?> row && row.get("dedupKey") != null) {
                dedupKeys.add(row.get("dedupKey").toString());
            }
        }
    }

    private void addTransactions(Set<Long> ids, Collection<Transaction> transactions) {
        for (Transaction transaction : transactions) {
            if (transaction.getId() != null) {
                ids.add(transaction.getId());
            }
        }
    }

    /**
     * Dedup keys can be shared by identical rows in different statement files. Only rows whose
     * source-document metadata points at this file are safe to remove; direct CREATED outcome
     * ids are added separately above and remain safe even for legacy rows.
     */
    private void addStatementDedupTransactions(Set<Long> ids, String documentId,
                                                Collection<Transaction> transactions) {
        for (Transaction transaction : transactions) {
            if (transaction.getId() != null && documentId.equals(transaction.getSourceDocumentId())) {
                ids.add(transaction.getId());
            }
        }
    }
}
