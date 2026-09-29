package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RunSummaryResponse(UUID id, String displayId, String agentName, RunStatus status,
                                 TerminalReason terminalReason, BigDecimal totalCharged, Instant startedAt) {
    static RunSummaryResponse from(Run run) {
        return new RunSummaryResponse(run.getId(), run.getDisplayId(), run.getConnection().getAgent().getName(),
                run.getStatus(), run.getTerminalReason(), run.getTotalCharged(), run.getStartedAt());
    }
}
