package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GateRequestRepository extends JpaRepository<GateRequest, UUID> {
    Optional<GateRequest> findFirstByRunIdAndStatusOrderByCreatedAtAsc(UUID runId, GateStatus status);

    Optional<GateRequest> findByIdAndRunId(UUID id, UUID runId);

    List<GateRequest> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
