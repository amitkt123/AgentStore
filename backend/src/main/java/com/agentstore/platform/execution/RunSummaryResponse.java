package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RunSummaryResponse(UUID id, String agentName, RunStatus status, BigDecimal totalCharged, Instant startedAt) {
    static RunSummaryResponse from(Run run) {
        return new RunSummaryResponse(run.getId(), run.getConnection().getAgent().getName(), run.getStatus(),
                run.getTotalCharged(), run.getStartedAt());
    }
}
