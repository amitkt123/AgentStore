package com.agentstore.platform.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    List<LlmCall> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
