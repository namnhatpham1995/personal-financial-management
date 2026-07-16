package com.fintrack.agent.repository;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    Optional<AgentRun> findByIdAndUser_Id(Long id, Long userId);

    List<AgentRun> findByUser_IdOrderByCreatedAtDesc(Long userId);

    List<AgentRun> findByVaultDocumentIdAndUser_IdAndStatusIn(
            String vaultDocumentId, Long userId, List<AgentRunStatus> statuses);

    /** Latest run per vault document, used to surface ingestion status on vault reads/listings. */
    Optional<AgentRun> findFirstByVaultDocumentIdAndUser_IdOrderByCreatedAtDesc(
            String vaultDocumentId, Long userId);

    List<AgentRun> findByVaultDocumentIdInAndUser_Id(List<String> vaultDocumentIds, Long userId);
}
