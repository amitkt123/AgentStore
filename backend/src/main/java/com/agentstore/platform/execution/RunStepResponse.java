package com.agentstore.platform.execution;

import java.time.Instant;

public record RunStepResponse(RunStepKind kind, RunStepSource source, String detail, Instant createdAt) {
    public static RunStepResponse from(RunStep step) {
        return new RunStepResponse(step.getKind(), step.getSource(), step.getDetail(), step.getCreatedAt());
    }
}
