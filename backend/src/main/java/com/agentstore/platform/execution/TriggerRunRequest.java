package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TriggerRunRequest(@NotNull UUID connectionId) {
}
