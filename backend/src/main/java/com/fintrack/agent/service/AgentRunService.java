package com.fintrack.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import com.fintrack.agent.exception.AgentFeatureUnavailableException;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.agent.web.dto.AgentDecisionRequest;
import com.fintrack.agent.web.dto.AgentRunDetailResponse;
import com.fintrack.agent.web.dto.AgentRunSummaryResponse;
import com.fintrack.agent.web.dto.ProposalDto;
import com.fintrack.agent.web.dto.SubmitProposalsRequest;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.exchangerate.service.ExchangeRateService;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.repository.VaultDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the ingestion run lifecycle. The agent process is never trusted more than the LLM it
 * wraps (design.md D6) — every proposal, whether freshly submitted by the agent or edited by
 * the reviewing user, passes back through {@link #validateProposals} before it can affect a
 * transaction, category, or account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private static final List<AgentRunStatus> ACTIVE_STATUSES =
            List.of(AgentRunStatus.EXTRACTING, AgentRunStatus.AWAITING_REVIEW);

    private final AgentRunRepository agentRunRepository;
    private final VaultDocumentRepository vaultDocumentRepository;
    private final UserRepository userRepository;
    private final CategoryService categoryService;
    private final AccountService accountService;
    private final ExchangeRateService exchangeRateService;
    private final TransactionService transactionService;
    private final AgentTokenService agentTokenService;
    private final AgentServiceClient agentServiceClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentRunSummaryResponse start(Long userId, String vaultDocumentId) {
        requireConfigured();

        VaultDocument doc = vaultDocumentRepository.findByIdAndUserId(vaultDocumentId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("VaultDocument", vaultDocumentId));
        if (doc.getType() != VaultDocumentType.RECEIPT) {
            throw ResourceNotFoundException.of("VaultDocument", vaultDocumentId);
        }

        List<AgentRun> active = agentRunRepository
                .findByVaultDocumentIdAndUser_IdAndStatusIn(vaultDocumentId, userId, ACTIVE_STATUSES);
        if (!active.isEmpty()) {
            throw new ConflictException("An ingestion run is already active for this receipt");
        }

        User user = userRepository.getReferenceById(userId);
        AgentRun run = AgentRun.builder()
                .user(user)
                .vaultDocumentId(vaultDocumentId)
                .status(AgentRunStatus.EXTRACTING)
                .retryable(false)
                .build();
        run = agentRunRepository.save(run);

        String token = agentTokenService.generateAgentToken(user.getEmail(), userId, run.getId());
        agentServiceClient.notifyRunStarted(run.getId(), vaultDocumentId, token);

        return AgentRunSummaryResponse.from(run);
    }

    @Transactional(readOnly = true)
    public List<AgentRunSummaryResponse> list(Long userId) {
        requireConfigured();
        return agentRunRepository.findByUser_IdOrderByCreatedAtDesc(userId).stream()
                .map(AgentRunSummaryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentRunDetailResponse get(Long userId, Long runId) {
        requireConfigured();
        AgentRun run = findOwned(userId, runId);
        return AgentRunDetailResponse.from(run, toProposalDtos(run.getProposals()));
    }

    /** Agent-token endpoint: the agent posts extraction + proposals once its own graph completes. */
    @Transactional
    public AgentRunDetailResponse submitProposals(Long userId, Long runId, SubmitProposalsRequest request) {
        AgentRun run = findOwned(userId, runId);
        if (run.getStatus() != AgentRunStatus.EXTRACTING) {
            throw new ConflictException("Run is not awaiting extraction");
        }

        BigDecimal extractedTotal = extractTotal(request.extraction());
        List<ProposalDto> validated = validateProposals(userId, request.proposals(), extractedTotal);

        run.setExtraction(request.extraction());
        run.setProposals(toProposalMaps(validated));
        run.setStatus(AgentRunStatus.AWAITING_REVIEW);
        agentRunRepository.save(run);

        return AgentRunDetailResponse.from(run, validated);
    }

    /** Agent-token or recovery path: reports that extraction/categorization could not complete. */
    @Transactional
    public void fail(Long userId, Long runId, String reason, boolean retryable) {
        AgentRun run = findOwned(userId, runId);
        if (!run.isActive()) {
            throw new ConflictException("Run is already in a terminal state");
        }
        run.setStatus(AgentRunStatus.FAILED);
        run.setFailureReason(reason);
        run.setRetryable(retryable);
        agentRunRepository.save(run);
    }

    /** User decision: approve (with possibly edited proposals) or reject. */
    @Transactional
    public AgentRunDetailResponse decide(Long userId, Long runId, AgentDecisionRequest request) {
        requireConfigured();
        AgentRun run = findOwned(userId, runId);

        if (run.getStatus() == AgentRunStatus.COMMITTED) {
            // Idempotent retry of an already-applied decision — return the stored result rather
            // than erroring, so a duplicate approval submission is safe (spec: retried commit
            // must not duplicate transactions).
            return AgentRunDetailResponse.from(run, toProposalDtos(run.getProposals()));
        }
        if (run.getStatus() != AgentRunStatus.AWAITING_REVIEW) {
            throw new ConflictException("Run is not awaiting review");
        }

        if (Boolean.FALSE.equals(request.approve())) {
            run.setStatus(AgentRunStatus.REJECTED);
            agentRunRepository.save(run);
            return AgentRunDetailResponse.from(run, toProposalDtos(run.getProposals()));
        }

        List<ProposalDto> edited = request.proposals() != null ? request.proposals() : toProposalDtos(run.getProposals());
        return doCommit(userId, run, edited);
    }

    /** Agent-token or recovery path: commits the run's currently stored proposals as-is. */
    @Transactional
    public AgentRunDetailResponse commit(Long userId, Long runId) {
        AgentRun run = findOwned(userId, runId);
        if (run.getStatus() == AgentRunStatus.COMMITTED) {
            return AgentRunDetailResponse.from(run, toProposalDtos(run.getProposals()));
        }
        if (run.getStatus() != AgentRunStatus.AWAITING_REVIEW) {
            throw new ConflictException("Run is not awaiting review");
        }
        return doCommit(userId, run, toProposalDtos(run.getProposals()));
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private AgentRunDetailResponse doCommit(Long userId, AgentRun run, List<ProposalDto> editedProposals) {
        BigDecimal extractedTotal = extractTotal(run.getExtraction());
        List<ProposalDto> validated = validateProposals(userId, editedProposals, extractedTotal).stream()
                .filter(p -> !p.excluded())
                .toList();

        if (validated.isEmpty()) {
            throw new IllegalArgumentException("At least one non-excluded proposal is required to approve a run");
        }
        for (ProposalDto p : validated) {
            if (p.categoryId() == null || p.accountId() == null) {
                throw new IllegalArgumentException(
                        "Every approved proposal must have a category and an account selected");
            }
        }

        List<Long> createdIds = new ArrayList<>();
        for (int i = 0; i < validated.size(); i++) {
            ProposalDto p = validated.get(i);
            String dedupKey = "agent-run:" + run.getId() + ":" + i;
            var txReq = new CreateTransactionRequest(
                    TransactionType.EXPENSE,
                    p.amount(),
                    p.date(),
                    p.accountId(),
                    null,
                    null,
                    p.categoryId(),
                    buildNote(p),
                    dedupKey
            );
            try {
                var response = transactionService.create(userId, txReq);
                createdIds.add(response.id());
            } catch (DataIntegrityViolationException e) {
                log.debug("Skipping already-committed proposal for run {} index {}", run.getId(), i);
            }
        }

        run.setProposals(toProposalMaps(validated));
        run.setCreatedTransactionIds(createdIds);
        run.setStatus(AgentRunStatus.COMMITTED);
        agentRunRepository.save(run);

        return AgentRunDetailResponse.from(run, validated);
    }

    private String buildNote(ProposalDto p) {
        String merchant = p.merchant() == null ? "" : p.merchant();
        String description = p.description() == null ? "" : p.description();
        if (description.isBlank()) {
            return merchant;
        }
        return merchant.isBlank() ? description : merchant + " — " + description;
    }

    /**
     * Deterministic (non-LLM) validation, authoritative regardless of whether the input came
     * from the agent or a user edit: category/account ownership, currency recognition, date
     * plausibility, and totals reconciliation. Failures are never silently dropped — they
     * strip the offending reference and/or attach a reviewer-visible flag.
     */
    private List<ProposalDto> validateProposals(Long userId, List<ProposalDto> proposals, BigDecimal extractedTotal) {
        BigDecimal sum = proposals.stream()
                .filter(p -> !p.excluded())
                .map(ProposalDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean totalsMismatch = extractedTotal != null && sum.compareTo(extractedTotal) != 0;

        List<ProposalDto> result = new ArrayList<>();
        for (ProposalDto p : proposals) {
            List<String> flags = new ArrayList<>(p.flags());
            Long categoryId = p.categoryId();
            Long accountId = p.accountId();

            if (categoryId != null) {
                try {
                    Category category = categoryService.findVisibleOrThrow(userId, categoryId);
                    categoryId = category.getId();
                } catch (ResourceNotFoundException e) {
                    categoryId = null;
                    addFlag(flags, "low-confidence");
                }
            } else {
                addFlag(flags, "low-confidence");
            }

            if (accountId != null) {
                try {
                    Account account = accountService.findOwned(userId, accountId);
                    accountId = account.getId();
                } catch (ResourceNotFoundException e) {
                    accountId = null;
                    addFlag(flags, "low-confidence");
                }
            }

            if (p.date() != null && p.date().isAfter(LocalDate.now())) {
                addFlag(flags, "future-date");
            }

            if (p.currency() == null || !exchangeRateService.supportedCurrencies().contains(p.currency())) {
                addFlag(flags, "unrecognized-currency");
            }

            if (totalsMismatch) {
                addFlag(flags, "totals-mismatch");
            }

            result.add(new ProposalDto(p.merchant(), p.date(), p.amount(), p.currency(),
                    categoryId, accountId, p.description(), flags, p.excluded()));
        }
        return result;
    }

    private void addFlag(List<String> flags, String flag) {
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
    }

    private BigDecimal extractTotal(Map<String, Object> extraction) {
        if (extraction == null || extraction.get("total") == null) {
            return null;
        }
        return new BigDecimal(extraction.get("total").toString());
    }

    private List<Map<String, Object>> toProposalMaps(List<ProposalDto> proposals) {
        return proposals.stream()
                .map(p -> objectMapper.convertValue(p, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}))
                .toList();
    }

    private List<ProposalDto> toProposalDtos(List<Map<String, Object>> maps) {
        if (maps == null) {
            return List.of();
        }
        return maps.stream()
                .map(m -> objectMapper.convertValue(m, ProposalDto.class))
                .toList();
    }

    private AgentRun findOwned(Long userId, Long runId) {
        return agentRunRepository.findByIdAndUser_Id(runId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("AgentRun", runId));
    }

    private void requireConfigured() {
        if (!agentServiceClient.isConfigured()) {
            throw new AgentFeatureUnavailableException(
                    "Receipt ingestion agent is not configured on this deployment");
        }
    }
}
