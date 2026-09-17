package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record AgentEventRequest(@NotNull RunStepKind kind, Map<String, Object> detail) {
}
