package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotNull;

public record GateDecisionRequest(@NotNull GateDecision decision, String note) {
}
