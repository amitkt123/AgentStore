package com.agentstore.platform.events;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Import(OutboxTestConsumers.class)
class OutboxDispatcherTest extends AbstractIntegrationTest {

    @Autowired OutboxDispatcher dispatcher;
    @Autowired DomainEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxTestConsumers.RecordingConsumer recording;
    @Autowired OutboxTestConsumers.AlwaysFailingConsumer failing;

    @BeforeEach
    void clean() {
        jdbc.update("delete from domain_event_delivery");
        jdbc.update("delete from domain_event");
        recording.reset();
        failing.reset();
    }

    private UUID publish(String type) {
        return tx.execute(s -> publisher.publish(type, null, UUID.randomUUID(), Map.of("n", 1)));
    }

    private Map<String, Object> delivery(UUID eventId) {
        return jdbc.queryForMap("select status, attempts, last_error, next_attempt_at > now() as later, "
                + "extract(epoch from (next_attempt_at - now())) as secs from domain_event_delivery where event_id = ?", eventId);
    }

    private void makeDue() {
        jdbc.update("update domain_event_delivery set next_attempt_at = now() where status = 'PENDING'");
    }

    @Test
    void backoffStartsAt30SecondsDoublesAndCapsAtOneHour() {
        assertThat(OutboxDispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxDispatcher.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxDispatcher.backoff(3)).isEqualTo(Duration.ofSeconds(120));
        assertThat(OutboxDispatcher.backoff(7)).isEqualTo(Duration.ofSeconds(1920));
        assertThat(OutboxDispatcher.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxDispatcher.backoff(50)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void deliversAnEventExactlyOnceAndMarksItDone() {
        UUID eventId = publish("TEST_EVENT");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce()).isZero();

        assertThat(recording.handled()).containsExactly(eventId);
        assertThat(delivery(eventId)).containsEntry("status", "DONE").containsEntry("attempts", 1);
    }

    @Test
    void aTransientFailureIsRetriedWithBackoffAndThenSucceedsOnce() {
        recording.failNext(2);
        UUID eventId = publish("TEST_EVENT");

        dispatcher.dispatchOnce();
        assertThat(delivery(eventId)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(delivery(eventId).get("last_error").toString()).contains("transient");
        assertThat(((Number) delivery(eventId).get("secs")).doubleValue()).isBetween(20.0, 35.0);
        assertThat(dispatcher.dispatchOnce()).as("not due yet").isZero();

        makeDue();
        dispatcher.dispatchOnce();
        assertThat(delivery(eventId)).containsEntry("attempts", 2);
        assertThat(((Number) delivery(eventId).get("secs")).doubleValue()).isBetween(50.0, 65.0);

        makeDue();
        dispatcher.dispatchOnce();
        assertThat(delivery(eventId)).containsEntry("status", "DONE").containsEntry("attempts", 3);
        assertThat(recording.handled()).containsExactly(eventId);
    }

    @Test
    void aConsumerThatKeepsFailingIsMarkedDeadAfterTenAttemptsAndNeverRetriedAgain() {
        UUID eventId = publish("TEST_FAIL");

        for (int i = 0; i < 10; i++) {
            makeDue();
            dispatcher.dispatchOnce();
        }

        assertThat(delivery(eventId)).containsEntry("status", "DEAD").containsEntry("attempts", 10);
        assertThat(delivery(eventId).get("last_error").toString()).contains("boom");
        assertThat(failing.calls()).isEqualTo(10);
        makeDue();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(failing.calls()).isEqualTo(10);
    }

    @Test
    void aDeliveryWhoseConsumerNoLongerExistsFailsInsteadOfBreakingTheLoop() {
        UUID orphan = publish("TEST_EVENT");
        jdbc.update("update domain_event_delivery set consumer = 'removed' where event_id = ?", orphan);
        UUID healthy = publish("TEST_EVENT");

        dispatcher.dispatchOnce();

        assertThat(delivery(orphan)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(delivery(orphan).get("last_error").toString()).contains("removed");
        assertThat(delivery(healthy)).containsEntry("status", "DONE");
    }

    @Test
    void twoDispatchersRacingNeverHandleTheSameDeliveryTwice() throws Exception {
        recording.delayMillis(20);
        List<UUID> published = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            published.add(publish("TEST_EVENT"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(dispatcher::dispatchOnce);
            Future<Integer> b = pool.submit(dispatcher::dispatchOnce);
            assertThat(a.get() + b.get()).isEqualTo(16);
        } finally {
            pool.shutdownNow();
        }

        assertThat(recording.handled()).hasSize(16);
        assertThat(new HashSet<>(recording.handled())).containsExactlyInAnyOrderElementsOf(published);
    }
}
