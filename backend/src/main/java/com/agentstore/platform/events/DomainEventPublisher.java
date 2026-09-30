package com.agentstore.platform.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an outbox event and one PENDING delivery per registered consumer that handles its type. It joins the caller's
 * transaction (and refuses to run without one) so the event exists if and only if the state change committed.
 */
@Component
public class DomainEventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<DomainEventConsumer> consumers;

    public DomainEventPublisher(JdbcTemplate jdbc, ObjectMapper mapper, List<DomainEventConsumer> consumers) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.consumers = consumers;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String type, UUID orgId, UUID aggregateId, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserializable payload for " + type, e);
        }
        jdbc.update("insert into domain_event (id, type, org_id, aggregate_id, payload) values (?, ?, ?, ?, ?::jsonb)",
                id, type, orgId, aggregateId, json);
        for (DomainEventConsumer consumer : consumers) {
            if (consumer.handles(type)) {
                jdbc.update("insert into domain_event_delivery (id, event_id, consumer, status) values (?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), id, consumer.name());
            }
        }
        return id;
    }
}
