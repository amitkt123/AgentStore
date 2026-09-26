package com.agentstore.platform.execution;

import java.util.UUID;

/** A run reached a terminal state. Published from {@code RunLifecycleService.finish}, in its transaction. */
public record RunFinishedEvent(UUID runId) {
}
