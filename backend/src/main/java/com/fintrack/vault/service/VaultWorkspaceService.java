package com.fintrack.vault.service;

import com.fintrack.account.service.AccountService;
import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.domain.VaultStage;
import com.fintrack.vault.domain.VaultStageMapper;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backs the unified Vault workspace: documents are stored in MongoDB but a receipt's stage
 * depends on its latest ingestion run, which lives in PostgreSQL. Filtering and counting by
 * stage therefore first resolves the matching receipt document ids from the ingestion side,
 * then folds them into the MongoDB query alongside the statement statuses that map directly.
 *
 * See {@link VaultStageMapper} for the status-to-stage mapping this service queries against.
 */
@Service
@RequiredArgsConstructor
public class VaultWorkspaceService {

    private static final Set<VaultDocumentType> ALL_TYPES = EnumSet.allOf(VaultDocumentType.class);

    private final MongoTemplate mongoTemplate;
    private final AgentRunRepository agentRunRepository;
    private final AccountService accountService;
    private final VaultService vaultService;

    public Page<VaultDocumentResponse> list(Long userId, Set<VaultDocumentType> types, Set<VaultStage> stages,
                                             Long accountId, Pageable pageable) {
        if (accountId != null) {
            accountService.findOwned(userId, accountId);
        }
        Set<VaultDocumentType> effectiveTypes = types == null || types.isEmpty() ? ALL_TYPES : types;

        Criteria matchCriteria = buildMatchCriteria(userId, effectiveTypes, stages, accountId);
        if (matchCriteria == null) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        Query countQuery = Query.query(matchCriteria);
        long total = mongoTemplate.count(countQuery, VaultDocument.class);
        Query pageQuery = Query.query(matchCriteria).with(pageable);
        List<VaultDocument> docs = mongoTemplate.find(pageQuery, VaultDocument.class);

        Map<String, AgentRunStatus> statuses = vaultService.latestIngestionStatuses(userId, docs);
        List<VaultDocumentResponse> responses = docs.stream()
                .map(doc -> vaultService.toResponse(doc, statuses.get(doc.getId())))
                .toList();
        return new PageImpl<>(responses, pageable, total);
    }

    public Map<VaultStage, Long> stageCounts(Long userId, Set<VaultDocumentType> types, Long accountId) {
        if (accountId != null) {
            accountService.findOwned(userId, accountId);
        }
        Set<VaultDocumentType> effectiveTypes = types == null || types.isEmpty() ? ALL_TYPES : types;
        Map<VaultStage, Long> counts = new EnumMap<>(VaultStage.class);

        if (effectiveTypes.contains(VaultDocumentType.STATEMENT)) {
            for (VaultDocument doc : findDocuments(userId, VaultDocumentType.STATEMENT, accountId)) {
                VaultStage stage = VaultStageMapper.forStatementStatus(doc.getStatus());
                counts.merge(stage, 1L, Long::sum);
            }
        }
        if (effectiveTypes.contains(VaultDocumentType.RECEIPT)) {
            List<String> receiptIds = findDocumentIds(userId, VaultDocumentType.RECEIPT, accountId);
            Map<String, AgentRunStatus> latest = latestStatusesForDocIds(userId, receiptIds);
            for (String receiptId : receiptIds) {
                VaultStage stage = VaultStageMapper.forReceiptIngestionStatus(latest.get(receiptId));
                counts.merge(stage, 1L, Long::sum);
            }
        }
        return counts;
    }

    // ── query construction ──────────────────────────────────────────────────────

    /** Returns null when the requested filters can never match any document. */
    private Criteria buildMatchCriteria(Long userId, Set<VaultDocumentType> types, Set<VaultStage> stages,
                                        Long accountId) {
        List<Criteria> topLevel = new java.util.ArrayList<>();
        topLevel.add(Criteria.where("userId").is(userId));
        if (accountId != null) {
            topLevel.add(Criteria.where("accountId").is(accountId));
        }

        if (stages == null || stages.isEmpty()) {
            topLevel.add(Criteria.where("type").in(types));
            return new Criteria().andOperator(topLevel.toArray(new Criteria[0]));
        }

        List<Criteria> branches = new java.util.ArrayList<>();

        Set<VaultStage> statementStages = EnumSet.copyOf(stages);
        statementStages.retainAll(VaultStageMapper.STATEMENT_APPLICABLE_STAGES);
        if (types.contains(VaultDocumentType.STATEMENT) && !statementStages.isEmpty()) {
            Set<com.fintrack.vault.domain.VaultDocumentStatus> statuses = statementStages.stream()
                    .flatMap(stage -> VaultStageMapper.statementStatusesForStage(stage).stream())
                    .collect(Collectors.toSet());
            branches.add(Criteria.where("type").is(VaultDocumentType.STATEMENT).and("status").in(statuses));
        }

        Set<VaultStage> receiptStages = EnumSet.copyOf(stages);
        receiptStages.retainAll(VaultStageMapper.RECEIPT_APPLICABLE_STAGES);
        if (types.contains(VaultDocumentType.RECEIPT) && !receiptStages.isEmpty()) {
            List<String> receiptIds = findDocumentIds(userId, VaultDocumentType.RECEIPT, accountId);
            Map<String, AgentRunStatus> latest = latestStatusesForDocIds(userId, receiptIds);

            boolean wantsNotProcessed = receiptStages.contains(VaultStage.NOT_PROCESSED);
            Set<VaultStage> otherReceiptStages = EnumSet.copyOf(receiptStages);
            otherReceiptStages.remove(VaultStage.NOT_PROCESSED);

            List<String> matchingIds = receiptIds.stream()
                    .filter(id -> {
                        AgentRunStatus status = latest.get(id);
                        if (status == null) {
                            return wantsNotProcessed;
                        }
                        return otherReceiptStages.contains(VaultStageMapper.forReceiptIngestionStatus(status));
                    })
                    .toList();
            if (!matchingIds.isEmpty()) {
                branches.add(Criteria.where("type").is(VaultDocumentType.RECEIPT).and("_id").in(matchingIds));
            }
        }

        if (branches.isEmpty()) {
            return null;
        }
        topLevel.add(new Criteria().orOperator(branches.toArray(new Criteria[0])));
        return new Criteria().andOperator(topLevel.toArray(new Criteria[0]));
    }

    private List<VaultDocument> findDocuments(Long userId, VaultDocumentType type, Long accountId) {
        Criteria criteria = Criteria.where("userId").is(userId).and("type").is(type);
        if (accountId != null) {
            criteria = criteria.and("accountId").is(accountId);
        }
        return mongoTemplate.find(Query.query(criteria), VaultDocument.class);
    }

    private List<String> findDocumentIds(Long userId, VaultDocumentType type, Long accountId) {
        Criteria criteria = Criteria.where("userId").is(userId).and("type").is(type);
        if (accountId != null) {
            criteria = criteria.and("accountId").is(accountId);
        }
        Query query = Query.query(criteria);
        query.fields().include("_id");
        return mongoTemplate.find(query, VaultDocument.class).stream().map(VaultDocument::getId).toList();
    }

    /** Latest ingestion run status per document id; documents with no run are absent from the result. */
    private Map<String, AgentRunStatus> latestStatusesForDocIds(Long userId, List<String> docIds) {
        if (docIds.isEmpty()) {
            return Map.of();
        }
        return agentRunRepository.findByVaultDocumentIdInAndUser_Id(docIds, userId).stream()
                .collect(Collectors.toMap(
                        AgentRun::getVaultDocumentId,
                        run -> run,
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getStatus()));
    }
}
