package com.agentstore.platform.catalog;

import java.util.UUID;

public record AgentResponse(UUID id, String name, String category, String description) {
    static AgentResponse from(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getName(), agent.getCategory(), agent.getDescription());
    }
}
