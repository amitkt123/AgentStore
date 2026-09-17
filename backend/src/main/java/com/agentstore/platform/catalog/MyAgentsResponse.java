package com.agentstore.platform.catalog;

import java.util.List;
import java.util.UUID;

public record MyAgentsResponse(UUID agentId, String agentName, String category, List<AgentVersionResponse> versions) {
}
