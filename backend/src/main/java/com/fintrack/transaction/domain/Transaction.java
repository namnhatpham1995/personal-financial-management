package com.fintrack.transaction.domain;

import com.fintrack.account.domain.Account;
import com.fintrack.auth.domain.User;
import com.fintrack.category.domain.Category;
import com.fintrack.common.domain.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** Destination account for TRANSFER transactions. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_account_id")
    private Account transferAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 50)
    private TransactionType transactionType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** GridFS / vault document id of the statement/receipt that produced this row. */
    @Column(name = "source_document_id")
    private String sourceDocumentId;

    /** SHA-256 dedup key for import idempotency — unique where not null. */
    @Column(name = "import_dedup_key", unique = true, length = 255)
    private String importDedupKey;

    /** Back-reference to the recurring definition that generated this transaction, if any. */
    @Column(name = "recurring_id")
    private Long recurringId;

    /** Part of idempotency key: (recurring_id, occurrence_date) must be unique. */
    @Column(name = "occurrence_date")
    private LocalDate occurrenceDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
