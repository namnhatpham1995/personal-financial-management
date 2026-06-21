package com.fintrack.vault.repository;

import com.fintrack.vault.domain.VaultDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class VaultSearchRepositoryImpl implements VaultSearchRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public Page<VaultDocument> search(
            Long userId,
            String merchant,
            Instant from,
            Instant to,
            String lineItemText,
            BigDecimal maxLineItemAmount,
            Pageable pageable
    ) {
        List<Criteria> criteriaList = new ArrayList<>();
        criteriaList.add(Criteria.where("userId").is(userId));

        if (merchant != null && !merchant.isBlank()) {
            criteriaList.add(Criteria.where("payload.merchant")
                    .regex(merchant, "i"));
        }
        if (from != null) {
            criteriaList.add(Criteria.where("capturedAt").gte(from));
        }
        if (to != null) {
            criteriaList.add(Criteria.where("capturedAt").lte(to));
        }
        if (lineItemText != null && !lineItemText.isBlank()) {
            // Match documents where any lineItem description contains the text
            criteriaList.add(Criteria.where("payload.lineItems.description")
                    .regex(lineItemText, "i"));
        }
        if (maxLineItemAmount != null) {
            criteriaList.add(Criteria.where("payload.lineItems.amount")
                    .lte(maxLineItemAmount));
        }

        Criteria combined = new Criteria().andOperator(criteriaList.toArray(new Criteria[0]));
        Query query = Query.query(combined).with(pageable);

        long total = mongoTemplate.count(Query.query(combined), VaultDocument.class);
        List<VaultDocument> results = mongoTemplate.find(query, VaultDocument.class);

        return new PageImpl<>(results, pageable, total);
    }
}
