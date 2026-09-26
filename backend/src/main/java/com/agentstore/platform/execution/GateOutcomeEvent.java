package com.agentstore.platform.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A gate reached its final status. Published inside the deciding transaction so what it triggers (committing or
 * discarding staged outputs) succeeds or fails together with the decision. {@code requestedAt} is when the gate was
 * opened: only outputs staged before then are covered by it.
 */
public record GateOutcomeEvent(UUID runId, UUID gateId, GateStatus status, List<String> affectsResources,
                               Instant requestedAt) {
}
