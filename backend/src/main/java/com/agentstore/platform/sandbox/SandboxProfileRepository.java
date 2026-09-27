package com.agentstore.platform.sandbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SandboxProfileRepository extends JpaRepository<SandboxProfile, UUID> {

    List<SandboxProfile> findByAgentIdAndInlineFalseOrderByNameAsc(UUID agentId);

    boolean existsByAgentIdAndName(UUID agentId, String name);
}
