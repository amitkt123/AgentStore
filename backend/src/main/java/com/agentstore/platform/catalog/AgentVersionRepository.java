package com.agentstore.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentVersionRepository extends JpaRepository<AgentVersion, UUID> {
    List<AgentVersion> findByAgentIdAndStatus(UUID agentId, VersionStatus status);

    Optional<AgentVersion> findFirstByAgentIdAndStatusOrderByApprovedAtDesc(UUID agentId, VersionStatus status);

    List<AgentVersion> findByStatus(VersionStatus status);

    Optional<AgentVersion> findByAgentIdAndVersionNumber(UUID agentId, String versionNumber);

    Optional<AgentVersion> findByBundleId(UUID bundleId);

    Optional<AgentVersion> findFirstByAgentIdOrderByCreatedAtDesc(UUID agentId);

    /** Scalar on purpose: lets a caller lock the agent before it loads (and caches) the version. */
    @Query("select v.agent.id from AgentVersion v where v.id = :id")
    Optional<UUID> findAgentIdById(@Param("id") UUID id);
}
