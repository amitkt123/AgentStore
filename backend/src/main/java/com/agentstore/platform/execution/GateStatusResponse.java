package com.agentstore.platform.execution;

public record GateStatusResponse(String decision) {
    static GateStatusResponse from(RunStatus status) {
        return switch (status) {
            case AWAITING_APPROVAL -> new GateStatusResponse("PENDING");
            case RUNNING, COMPLETED -> new GateStatusResponse("APPROVED");
            // Cancelled, failed, timed out or expired: the agent must not proceed.
            default -> new GateStatusResponse("REJECTED");
        };
    }
}
