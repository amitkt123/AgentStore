package com.agentstore.platform.execution;

import java.time.Instant;
import java.util.UUID;

public record RunListQuery(RunGroup group, UUID agentId, String q, Instant from, Instant to, int page, int size) {
}
