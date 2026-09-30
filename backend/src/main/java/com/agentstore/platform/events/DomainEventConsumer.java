package com.agentstore.platform.events;

/**
 * Receives outbox events. A delivery can repeat after a crash or a failed attempt, so {@link #handle} must be
 * idempotent. It runs in its own transaction; throwing rolls that transaction back and schedules a retry.
 */
public interface DomainEventConsumer {

    /** Stable identifier stored on each delivery row. Never rename a consumer that has pending deliveries. */
    String name();

    boolean handles(String type);

    void handle(DomainEvent event);
}
