package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateStatus;

import java.time.Instant;
import java.util.UUID;

public record GateView(UUID gateId, GateStatus status, String note, Instant expiresAt, Instant decidedAt) {
    static GateView from(GateRequest gate) {
        return new GateView(gate.getId(), gate.getStatus(), gate.getDecisionNote(), gate.getExpiresAt(), gate.getDecidedAt());
    }
}
