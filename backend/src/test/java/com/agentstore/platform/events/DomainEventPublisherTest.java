package com.agentstore.platform.events;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(OutboxTestConsumers.class)
class DomainEventPublisherTest extends AbstractIntegrationTest {

    @Autowired DomainEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from domain_event_delivery");
        jdbc.update("delete from domain_event");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void writesTheEventAndOneDeliveryPerConsumerThatHandlesTheType() {
        UUID aggregate = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        UUID eventId = tx.execute(s -> publisher.publish("TEST_EVENT", orgId, aggregate, Map.of("k", "v")));

        assertThat(count("select count(*) from domain_event where id = ? and type = 'TEST_EVENT' and org_id = ? "
                + "and aggregate_id = ? and payload->>'k' = 'v'", eventId, orgId, aggregate)).isEqualTo(1);
        List<Map<String, Object>> deliveries = jdbc.queryForList(
                "select consumer, status, attempts from domain_event_delivery where event_id = ?", eventId);
        assertThat(deliveries).singleElement().satisfies(d -> {
            assertThat(d.get("consumer")).isEqualTo("recording");
            assertThat(d.get("status")).isEqualTo("PENDING");
            assertThat(d.get("attempts")).isEqualTo(0);
        });
    }

    @Test
    void aTypeNobodyHandlesStillRecordsTheEventButCreatesNoDelivery() {
        UUID eventId = tx.execute(s -> publisher.publish("NOBODY_CARES", null, UUID.randomUUID(), Map.of()));

        assertThat(count("select count(*) from domain_event where id = ? and org_id is null", eventId)).isEqualTo(1);
        assertThat(count("select count(*) from domain_event_delivery where event_id = ?", eventId)).isZero();
    }

    @Test
    void aRolledBackTransactionLeavesNoEventOrDelivery() {
        UUID aggregate = UUID.randomUUID();

        tx.executeWithoutResult(s -> {
            publisher.publish("TEST_EVENT", null, aggregate, Map.of());
            s.setRollbackOnly();
        });

        assertThat(count("select count(*) from domain_event where aggregate_id = ?", aggregate)).isZero();
        assertThat(count("select count(*) from domain_event_delivery")).isZero();
    }

    @Test
    void publishingOutsideATransactionFails() {
        assertThatThrownBy(() -> publisher.publish("TEST_EVENT", null, UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
