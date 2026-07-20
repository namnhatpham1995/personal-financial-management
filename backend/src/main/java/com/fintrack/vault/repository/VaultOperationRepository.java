package com.fintrack.vault.repository;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VaultOperationRepository extends MongoRepository<VaultOperation, String> {

    Optional<VaultOperation> findByUserIdAndOperationAndKeyHash(Long userId, String operation, String keyHash);

    /**
     * Used by the stale-operation recovery job to find operations stuck in {@code PROCESSING}
     * past a staleness cutoff. {@code pageable} bounds how many a single sweep run processes.
     */
    List<VaultOperation> findByStateAndCreatedAtBefore(VaultOperationState state, Instant cutoff, Pageable pageable);
}
