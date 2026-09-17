package com.agentstore.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {
    List<AgentVersion> findByAgentIdAndStatus(UUID agentId, VersionStatus status);

    Optional<AgentVersion> findFirstByAgentIdAndStatusOrderByApprovedAtDesc(UUID agentId, VersionStatus status);

    List<AgentVersion> findByStatus(VersionStatus status);
}
