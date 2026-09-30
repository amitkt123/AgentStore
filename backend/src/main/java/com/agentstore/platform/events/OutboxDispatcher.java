package com.agentstore.platform.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Delivers outbox events to their consumers. A delivery is first leased in a short transaction of its own (attempts+1,
 * pushed out by {@link #LEASE}), so concurrent dispatchers skip it and a crash leaves it to be retried once the lease
 * lapses. The consumer then runs in a second transaction together with the mark-DONE, so a consumer that throws
 * leaves nothing behind and its failure is recorded afterwards, outside the rolled-back transaction.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    static final int MAX_ATTEMPTS = 10;
    static final int BATCH = 20;
    static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    static final Duration MAX_BACKOFF = Duration.ofHours(1);
    static final Duration LEASE = Duration.ofMinutes(5);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Map<String, DomainEventConsumer> consumers;

    public OutboxDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
                            List<DomainEventConsumer> consumers) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.consumers = consumers.stream().collect(Collectors.toMap(DomainEventConsumer::name, Function.identity()));
    }

    /** Delay before attempt {@code attempts + 1}: 30s, doubling, capped at one hour. */
    static Duration backoff(int attempts) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = BASE_BACKOFF.multipliedBy(factor);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** Processes up to {@link #BATCH} due deliveries; returns how many were attempted. */
    public int dispatchOnce() {
        int attempted = 0;
        while (attempted < BATCH) {
            Claimed claimed = claim();
            if (claimed == null) {
                break;
            }
            attempted++;
            deliver(claimed);
        }
        return attempted;
    }

    private record Claimed(UUID deliveryId, String consumer, int attempts, DomainEvent event) {
    }

    private Claimed claim() {
        return tx.execute(status -> {
            List<Claimed> rows = jdbc.query(
                    "select d.id, d.consumer, d.attempts, e.id as event_id, e.type, e.org_id, e.aggregate_id, "
                            + "e.payload::text as payload, e.created_at "
                            + "from domain_event_delivery d join domain_event e on e.id = d.event_id "
                            + "where d.status = 'PENDING' and d.next_attempt_at <= now() "
                            + "order by d.next_attempt_at limit 1 for update of d skip locked",
                    (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getString("consumer"),
                            rs.getInt("attempts") + 1,
                            new DomainEvent(rs.getObject("event_id", UUID.class), rs.getString("type"),
                                    rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                    parse(rs.getString("payload")), rs.getTimestamp("created_at").toInstant())));
            if (rows.isEmpty()) {
                return null;
            }
            Claimed claimed = rows.get(0);
            jdbc.update("update domain_event_delivery set attempts = ?, "
                            + "next_attempt_at = now() + make_interval(secs => ?) where id = ?",
                    claimed.attempts(), (double) LEASE.toSeconds(), claimed.deliveryId());
            return claimed;
        });
    }

    private void deliver(Claimed claimed) {
        try {
            DomainEventConsumer consumer = consumers.get(claimed.consumer());
            if (consumer == null) {
                throw new IllegalStateException("No consumer registered with name " + claimed.consumer());
            }
            tx.executeWithoutResult(status -> {
                consumer.handle(claimed.event());
                jdbc.update("update domain_event_delivery set status = 'DONE', last_error = null where id = ?",
                        claimed.deliveryId());
            });
        } catch (RuntimeException e) {
            recordFailure(claimed, e);
        }
    }

    private void recordFailure(Claimed claimed, RuntimeException failure) {
        String error = String.valueOf(failure);
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
        String message = error;
        boolean dead = claimed.attempts() >= MAX_ATTEMPTS;
        if (dead) {
            log.error("Delivery {} to {} is dead after {} attempts", claimed.deliveryId(), claimed.consumer(),
                    claimed.attempts(), failure);
            tx.executeWithoutResult(status -> jdbc.update(
                    "update domain_event_delivery set status = 'DEAD', last_error = ? where id = ?",
                    message, claimed.deliveryId()));
        } else {
            log.warn("Delivery {} to {} failed (attempt {})", claimed.deliveryId(), claimed.consumer(),
                    claimed.attempts(), failure);
            double seconds = backoff(claimed.attempts()).toSeconds();
            tx.executeWithoutResult(status -> jdbc.update(
                    "update domain_event_delivery set last_error = ?, "
                            + "next_attempt_at = now() + make_interval(secs => ?) where id = ?",
                    message, seconds, claimed.deliveryId()));
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt event payload", e);
        }
    }
}
