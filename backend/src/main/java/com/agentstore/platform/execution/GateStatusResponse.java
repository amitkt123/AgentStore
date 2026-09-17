package com.agentstore.platform.execution;

public record GateStatusResponse(String decision) {
    static GateStatusResponse from(RunStatus status) {
        return switch (status) {
            case AWAITING_APPROVAL -> new GateStatusResponse("PENDING");
            case CANCELLED -> new GateStatusResponse("REJECTED");
            default -> new GateStatusResponse("APPROVED");
        };
    }
}
