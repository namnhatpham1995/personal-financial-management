package com.fintrack.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import com.fintrack.agent.exception.AgentFeatureUnavailableException;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.agent.web.dto.AgentDecisionRequest;
import com.fintrack.agent.web.dto.ProposalDto;
import com.fintrack.agent.web.dto.SubmitProposalsRequest;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.exchangerate.service.ExchangeRateService;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.transaction.web.dto.TransactionResponse;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.repository.VaultDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock AgentRunRepository agentRunRepository;
    @Mock VaultDocumentRepository vaultDocumentRepository;
    @Mock UserRepository userRepository;
    @Mock CategoryService categoryService;
    @Mock AccountService accountService;
    @Mock ExchangeRateService exchangeRateService;
    @Mock TransactionService transactionService;
    @Mock AgentTokenService agentTokenService;
    @Mock AgentServiceClient agentServiceClient;

    AgentRunService service;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new AgentRunService(agentRunRepository, vaultDocumentRepository, userRepository,
                categoryService, accountService, exchangeRateService, transactionService,
                agentTokenService, agentServiceClient, objectMapper);
        lenient().when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR"));
    }

    private User user() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail("user@test.com");
        return u;
    }

    private AgentRun runInStatus(AgentRunStatus status) {
        return AgentRun.builder()
                .id(10L)
                .user(user())
                .vaultDocumentId("doc-1")
                .status(status)
                .retryable(false)
                .build();
    }

    // ── start ────────────────────────────────────────────────────────────────

    @Test
    void start_unconfigured_throwsFeatureUnavailable() {
        when(agentServiceClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.start(USER_ID, "doc-1"))
                .isInstanceOf(AgentFeatureUnavailableException.class);
        verifyNoInteractions(vaultDocumentRepository);
    }

    @Test
    void start_foreignOrMissingDocument_throwsNotFound() {
        when(agentServiceClient.isConfigured()).thenReturn(true);
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, "doc-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void start_activeRunAlreadyExists_throwsConflict() {
        when(agentServiceClient.isConfigured()).thenReturn(true);
        VaultDocument doc = VaultDocument.builder().id("doc-1").userId(USER_ID).type(VaultDocumentType.RECEIPT).build();
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", USER_ID)).thenReturn(Optional.of(doc));
        when(agentRunRepository.findByVaultDocumentIdAndUser_IdAndStatusIn(eq("doc-1"), eq(USER_ID), any()))
                .thenReturn(List.of(runInStatus(AgentRunStatus.EXTRACTING)));

        assertThatThrownBy(() -> service.start(USER_ID, "doc-1"))
                .isInstanceOf(ConflictException.class);
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    void start_ownedReceipt_createsRunAndNotifiesAgent() {
        when(agentServiceClient.isConfigured()).thenReturn(true);
        VaultDocument doc = VaultDocument.builder().id("doc-1").userId(USER_ID).type(VaultDocumentType.RECEIPT).build();
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", USER_ID)).thenReturn(Optional.of(doc));
        when(agentRunRepository.findByVaultDocumentIdAndUser_IdAndStatusIn(eq("doc-1"), eq(USER_ID), any()))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user());
        when(agentRunRepository.save(any())).thenAnswer(inv -> {
            AgentRun run = inv.getArgument(0);
            run.setId(42L);
            return run;
        });
        when(agentTokenService.generateAgentToken(any(), any(), any())).thenReturn("agent-token");

        var response = service.start(USER_ID, "doc-1");

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.status()).isEqualTo(AgentRunStatus.EXTRACTING);
        verify(agentServiceClient).notifyRunStarted(42L, "doc-1", "agent-token");
    }

    // ── submitProposals: deterministic validation ───────────────────────────

    @Test
    void submitProposals_totalsMismatch_flagsAllProposals() {
        AgentRun run = runInStatus(AgentRunStatus.EXTRACTING);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(categoryService.findVisibleOrThrow(eq(USER_ID), any())).thenAnswer(inv ->
                Category.builder().id(inv.getArgument(1)).build());
        when(accountService.findOwned(eq(USER_ID), any())).thenAnswer(inv ->
                Account.builder().id(inv.getArgument(1)).build());
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var proposal = new ProposalDto("Store", LocalDate.now(), new BigDecimal("10.00"), "USD",
                5L, 7L, "item", List.of(), false);
        var request = new SubmitProposalsRequest(Map.of("total", "15.00"), List.of(proposal));

        var result = service.submitProposals(USER_ID, 10L, request);

        assertThat(result.status()).isEqualTo(AgentRunStatus.AWAITING_REVIEW);
        assertThat(result.proposals()).hasSize(1);
        assertThat(result.proposals().get(0).flags()).contains("totals-mismatch");
    }

    @Test
    void submitProposals_foreignCategoryId_strippedAndFlaggedLowConfidence() {
        AgentRun run = runInStatus(AgentRunStatus.EXTRACTING);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(categoryService.findVisibleOrThrow(eq(USER_ID), eq(99L)))
                .thenThrow(new ResourceNotFoundException("Category not found"));
        when(accountService.findOwned(eq(USER_ID), any())).thenAnswer(inv ->
                Account.builder().id(inv.getArgument(1)).build());
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var proposal = new ProposalDto("Store", LocalDate.now(), new BigDecimal("10.00"), "USD",
                99L, 7L, "item", List.of(), false);
        var request = new SubmitProposalsRequest(Map.of("total", "10.00"), List.of(proposal));

        var result = service.submitProposals(USER_ID, 10L, request);

        ProposalDto validated = result.proposals().get(0);
        assertThat(validated.categoryId()).isNull();
        assertThat(validated.flags()).contains("low-confidence");
    }

    @Test
    void submitProposals_runNotExtracting_throwsConflict() {
        AgentRun run = runInStatus(AgentRunStatus.AWAITING_REVIEW);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));

        var request = new SubmitProposalsRequest(Map.of("total", "10.00"), List.of());

        assertThatThrownBy(() -> service.submitProposals(USER_ID, 10L, request))
                .isInstanceOf(ConflictException.class);
    }

    // ── decide ───────────────────────────────────────────────────────────────

    @Test
    void decide_reject_setsRejectedAndCreatesNoTransactions() {
        AgentRun run = runInStatus(AgentRunStatus.AWAITING_REVIEW);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(agentServiceClient.isConfigured()).thenReturn(true);
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.decide(USER_ID, 10L, new AgentDecisionRequest(false, null));

        assertThat(result.status()).isEqualTo(AgentRunStatus.REJECTED);
        verifyNoInteractions(transactionService);
    }

    @Test
    void decide_nonAwaitingReview_throwsConflict() {
        AgentRun run = runInStatus(AgentRunStatus.EXTRACTING);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(agentServiceClient.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.decide(USER_ID, 10L, new AgentDecisionRequest(true, List.of())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void decide_approve_commitsTransactionsThroughTransactionService() {
        AgentRun run = runInStatus(AgentRunStatus.AWAITING_REVIEW);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(agentServiceClient.isConfigured()).thenReturn(true);
        when(categoryService.findVisibleOrThrow(eq(USER_ID), any())).thenAnswer(inv ->
                Category.builder().id(inv.getArgument(1)).build());
        when(accountService.findOwned(eq(USER_ID), any())).thenAnswer(inv ->
                Account.builder().id(inv.getArgument(1)).build());
        when(transactionService.create(eq(USER_ID), any())).thenReturn(
                new TransactionResponse(501L, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, List.of()));
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var proposal = new ProposalDto("Store", LocalDate.now(), new BigDecimal("10.00"), "USD",
                5L, 7L, "item", List.of(), false);
        var result = service.decide(USER_ID, 10L, new AgentDecisionRequest(true, List.of(proposal)));

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMMITTED);
        assertThat(result.createdTransactionIds()).containsExactly(501L);
        ArgumentCaptor<com.fintrack.transaction.web.dto.CreateTransactionRequest> captor =
                ArgumentCaptor.forClass(com.fintrack.transaction.web.dto.CreateTransactionRequest.class);
        verify(transactionService).create(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().importDedupKey()).isEqualTo("agent-run:10:0");
    }

    @Test
    void decide_approve_missingCategoryOrAccount_throws() {
        AgentRun run = runInStatus(AgentRunStatus.AWAITING_REVIEW);
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(agentServiceClient.isConfigured()).thenReturn(true);

        var proposal = new ProposalDto("Store", LocalDate.now(), new BigDecimal("10.00"), "USD",
                null, null, "item", List.of(), false);

        assertThatThrownBy(() -> service.decide(USER_ID, 10L, new AgentDecisionRequest(true, List.of(proposal))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactionService);
    }

    @Test
    void decide_alreadyCommitted_returnsStoredResultIdempotently() {
        AgentRun run = runInStatus(AgentRunStatus.COMMITTED);
        run.setCreatedTransactionIds(List.of(501L));
        run.setProposals(List.of());
        when(agentRunRepository.findByIdAndUser_Id(10L, USER_ID)).thenReturn(Optional.of(run));
        when(agentServiceClient.isConfigured()).thenReturn(true);

        var result = service.decide(USER_ID, 10L, new AgentDecisionRequest(true, List.of()));

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMMITTED);
        assertThat(result.createdTransactionIds()).containsExactly(501L);
        verifyNoInteractions(transactionService);
        verify(agentRunRepository, never()).save(any());
    }
}
