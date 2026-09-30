package com.agentstore.platform.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** An outbox event as a consumer sees it. {@code orgId} is null for events with no buyer org. */
public record DomainEvent(UUID id, String type, UUID orgId, UUID aggregateId, JsonNode payload, Instant createdAt) {
}
