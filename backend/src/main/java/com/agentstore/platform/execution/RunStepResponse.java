package com.agentstore.platform.execution;

import java.time.Instant;
import java.util.UUID;

public record RunStepResponse(UUID id, RunStepKind kind, RunStepSource source, String detail, String block,
                              Instant startedAt, Instant createdAt) {
    public static RunStepResponse from(RunStep step) {
        return new RunStepResponse(step.getId(), step.getKind(), step.getSource(), step.getDetail(), step.getBlock(),
                step.getStartedAt(), step.getCreatedAt());
    }
}
