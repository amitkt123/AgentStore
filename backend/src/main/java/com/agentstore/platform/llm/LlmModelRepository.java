package com.agentstore.platform.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmModelRepository extends JpaRepository<LlmModel, String> {

    Optional<LlmModel> findByIdAndEnabledTrue(String id);
}
