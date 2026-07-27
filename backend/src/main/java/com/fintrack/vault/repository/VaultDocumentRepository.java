package com.fintrack.vault.repository;

import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VaultDocumentRepository extends MongoRepository<VaultDocument, String>,
        VaultSearchRepository {

    Optional<VaultDocument> findByIdAndUserId(String id, Long userId);

    Page<VaultDocument> findByUserIdOrderByCapturedAtDesc(Long userId, Pageable pageable);

    Page<VaultDocument> findByUserIdAndTypeOrderByCapturedAtDesc(
            Long userId, VaultDocumentType type, Pageable pageable);

    Page<VaultDocument> findByUserIdAndTypeAndAccountIdOrderByCapturedAtDesc(
            Long userId, VaultDocumentType type, Long accountId, Pageable pageable);

    Optional<VaultDocument> findByIdAndUserIdAndStatus(String id, Long userId, VaultDocumentStatus status);

    boolean existsByTransactionIdAndUserId(Long transactionId, Long userId);

    List<VaultDocument> findByTransactionIdInAndUserId(Collection<Long> transactionIds, Long userId);

    Page<VaultDocument> findByUserIdAndAccountIdAndTypeOrderByCapturedAtDesc(
            Long userId, Long accountId, VaultDocumentType type, Pageable pageable);

    Page<VaultDocument> findByUserIdAndTypeAndAccountIdIsNullOrderByCapturedAtDesc(
            Long userId, VaultDocumentType type, Pageable pageable);
}
