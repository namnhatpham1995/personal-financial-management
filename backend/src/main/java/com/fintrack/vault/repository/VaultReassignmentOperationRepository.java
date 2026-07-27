package com.fintrack.vault.repository;

import com.fintrack.vault.domain.VaultReassignmentOperation;
import com.fintrack.vault.domain.VaultReassignmentState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VaultReassignmentOperationRepository extends MongoRepository<VaultReassignmentOperation, String> {

    Optional<VaultReassignmentOperation> findByUserIdAndOperationAndKeyHash(
            Long userId, String operation, String keyHash);

    List<VaultReassignmentOperation> findByStateAndCreatedAtBefore(
            VaultReassignmentState state, Instant cutoff, Pageable pageable);
}
