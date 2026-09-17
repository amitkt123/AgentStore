package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID> {
    List<UsageEvent> findByRunId(UUID runId);

    List<UsageEvent> findByProcessedFalse();
}
