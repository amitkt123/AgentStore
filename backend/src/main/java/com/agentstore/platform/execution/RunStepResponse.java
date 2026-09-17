package com.agentstore.platform.execution;

import java.time.Instant;

public record RunStepResponse(RunStepKind kind, String detail, Instant createdAt) {
    static RunStepResponse from(RunStep step) {
        return new RunStepResponse(step.getKind(), step.getDetail(), step.getCreatedAt());
    }
}
