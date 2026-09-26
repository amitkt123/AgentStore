package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.GateStatus;

import java.time.Instant;
import java.util.UUID;

public record GateCreatedResponse(UUID gateId, GateStatus status, Instant expiresAt) {
}
