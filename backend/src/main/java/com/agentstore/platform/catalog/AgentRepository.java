package com.agentstore.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
}
