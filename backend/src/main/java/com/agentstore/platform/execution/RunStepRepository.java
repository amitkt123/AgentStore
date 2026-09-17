package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunStepRepository extends JpaRepository<RunStep, UUID> {
    List<RunStep> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
