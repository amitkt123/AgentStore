package com.agentstore.platform.execution;

import java.util.UUID;

public record RunResponse(UUID id, String displayId, RunStatus status, TerminalReason terminalReason) {
    static RunResponse from(Run run) {
        return new RunResponse(run.getId(), run.getDisplayId(), run.getStatus(), run.getTerminalReason());
    }
}
