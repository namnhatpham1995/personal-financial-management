package com.fintrack.vault.service;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.vault.domain.RowOutcome;
import com.fintrack.vault.domain.VaultDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultImportOriginServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock AgentRunRepository agentRunRepository;
    @InjectMocks VaultImportOriginService originService;

    @Test
    void collectTransactionIds_unionsSupportedOriginsAndExcludesUnrelatedRows() {
        VaultDocument document = VaultDocument.builder()
                .id("document-1")
                .payload(Map.of("rows", List.of(Map.of("dedupKey", "statement-row"))))
                .confirmationRowOutcomes(Map.of("confirmed-row", RowOutcome.created(2L)))
                .build();
        AgentRun run = AgentRun.builder()
                .id(7L)
                .vaultDocumentId("document-1")
                .createdTransactionIds(List.of(3L))
                .build();

        when(transactionRepository.findByUserIdAndSourceDocumentId(1L, "document-1"))
                .thenReturn(List.of(transaction(1L, "document-1")));
        when(transactionRepository.findByUserIdAndImportDedupKeyIn(eq(1L), any()))
                .thenReturn(List.of(transaction(2L, "document-1"), transaction(99L, "other-document")));
        when(agentRunRepository.findByVaultDocumentIdAndUser_Id("document-1", 1L))
                .thenReturn(List.of(run));
        when(transactionRepository.findByUserIdAndImportDedupKeyStartingWith(1L, "agent-run:7:"))
                .thenReturn(List.of(transaction(4L, null)));

        assertThat(originService.collectTransactionIds(1L, document))
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    private static Transaction transaction(Long id, String sourceDocumentId) {
        return Transaction.builder().id(id).sourceDocumentId(sourceDocumentId).build();
    }
}
