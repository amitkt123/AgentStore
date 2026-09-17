package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {
    List<Run> findByConnectionBuyerOrgIdOrderByStartedAtDesc(UUID buyerOrgId);

    List<Run> findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(UUID buyerOrgId, RunStatus status);
}
