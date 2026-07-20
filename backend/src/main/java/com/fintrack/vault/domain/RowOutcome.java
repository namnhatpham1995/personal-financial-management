package com.fintrack.vault.domain;

import lombok.*;

/**
 * Durable, per-row outcome recorded during statement confirmation. Stored on
 * {@link VaultDocument#getConfirmationRowOutcomes()} keyed by row fingerprint (dedup key) so a
 * resumed or replayed confirmation attempt can tell which rows are already done without
 * reprocessing them. See {@code StatementImportService}.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RowOutcome {

    private RowOutcomeStatus status;

    /** Set only when {@code status == CREATED}. */
    private Long transactionId;

    /** Set only when {@code status == FAILED}; human-readable cause. */
    private String error;

    public static RowOutcome processing() {
        return RowOutcome.builder().status(RowOutcomeStatus.PROCESSING).build();
    }

    public static RowOutcome created(Long transactionId) {
        return RowOutcome.builder().status(RowOutcomeStatus.CREATED).transactionId(transactionId).build();
    }

    public static RowOutcome duplicate() {
        return RowOutcome.builder().status(RowOutcomeStatus.DUPLICATE).build();
    }

    public static RowOutcome failed(String error) {
        return RowOutcome.builder().status(RowOutcomeStatus.FAILED).error(error).build();
    }
}
