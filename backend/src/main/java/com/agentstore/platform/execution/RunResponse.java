package com.agentstore.platform.execution;

import java.util.UUID;

public record RunResponse(UUID id, RunStatus status) {
    static RunResponse from(Run run) {
        return new RunResponse(run.getId(), run.getStatus());
    }
}
