package com.fintrack.vault.service;

import com.fintrack.account.domain.Account;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.web.dto.VaultReassignmentPreviewResponse;

/**
 * Builds {@link VaultReassignmentPreviewResponse}, split out of {@link VaultReassignmentService}
 * so preview construction is a separate unit from orchestration. Stateless — see
 * {@link VaultDocumentClaimFinalizer}'s javadoc for why this stays a static utility rather than
 * a Spring bean.
 */
final class VaultReassignmentPreviewFactory {

    private VaultReassignmentPreviewFactory() {
    }

    static VaultReassignmentPreviewResponse toPreview(VaultDocument document, Account source, Account target,
                                                       int importedCount, boolean detachManualLink) {
        String sourceCurrency = source == null ? null : source.getCurrency();
        String targetCurrency = target.getCurrency();
        return new VaultReassignmentPreviewResponse(document.getId(), document.getType(),
                document.getOriginalFilename(), source == null ? null : source.getId(),
                source == null ? null : source.getName(), sourceCurrency, target.getId(), target.getName(),
                targetCurrency, sourceCurrency != null && !sourceCurrency.equals(targetCurrency),
                importedCount, detachManualLink);
    }
}
