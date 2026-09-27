package com.agentstore.platform.catalog;

import java.util.List;
import java.util.UUID;

public record ReadinessResponse(boolean ready, List<Blocker> blockers) {

    public record Blocker(String code, String message, UUID runId) {
    }
}
