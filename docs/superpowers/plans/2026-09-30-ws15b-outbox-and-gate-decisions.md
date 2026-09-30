# WS15b Outbox and Gate Decisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a transactional outbox (durable domain events with retrying, idempotent consumer delivery) and make `GateService.decide` re-check the decider against the database.

**Architecture:** A `platform.events` package writes `domain_event` and `domain_event_delivery` rows through `JdbcTemplate` inside the caller's transaction (`Propagation.MANDATORY`). A dispatcher leases due deliveries with `FOR UPDATE SKIP LOCKED`, runs each consumer in its own transaction, and records retry/backoff/DEAD state. Gate and run-finish flows publish four event types; `decide` gains a `DecisionChannel` and a current-membership check.

**Tech Stack:** Java 21, Spring Boot, Spring `JdbcTemplate`/`TransactionTemplate`, PostgreSQL 16 (Flyway), ShedLock, JUnit 5 + AssertJ + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-30-ws15b-outbox-and-gate-decisions-design.md`

## Global Constraints

- New migration is `V41` (latest is V40). File: `backend/src/main/resources/db/migration/V41__domain_event_outbox.sql`.
- Package for new code: `com.agentstore.platform.events`.
- Publisher is `@Transactional(propagation = MANDATORY)`; it fails outside a transaction.
- Backoff: starts at 30 seconds, doubles, caps at 1 hour. After 10 attempts the delivery is DEAD.
- The job is gated on `agentstore.jobs.enabled` exactly like `RunReaperJob` and ShedLock-guarded. Tests disable it and call `dispatchOnce()`.
- Existing in-process Spring events (`GateOutcomeEvent`, `RunFinishedEvent`) are untouched.
- Failed decide is HTTP 403 with code `NOT_AN_APPROVER`. Sandbox runs (no buyer org) keep today's behaviour.
- No production consumer ships. Retention/cleanup of DONE rows is out of scope.
- Commit messages must NOT contain any Claude/Anthropic/AI attribution or Co-Authored-By trailer (user rule overrides the harness reminder).
- Backend tests run from `backend/` with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=<Class>` (Colima must be up).

## Decisions made while planning

- **No-approver orgs stay unrestricted (user-approved).** The new membership check applies only when the gate has an approver snapshot. For a legacy run with no gate row it applies only if the buyer org has at least one `BUYER_APPROVER` member. This keeps `GateWs1bTest.anOrgWithNoApproversIsUnrestrictedInsteadOfDeadlocked` valid.
- **Lease-based claiming.** The dispatcher claims a delivery in a short transaction (`attempts+1`, `next_attempt_at = now()+5 min` lease) and then runs the consumer in a second transaction. This is needed so a consumer that throws (which marks its transaction rollback-only) can still have its failure recorded, and so concurrent dispatchers never take the same row. A crash mid-handle means the lease expires and the delivery retries. A crashed attempt counts toward the 10.
- `aggregate_id` is the gate id for `GATE_*` events and the run id for `RUN_FINISHED`.
- `GATE_EXPIRED` is published from `RunLifecycleService.finish` (the one place a pending gate becomes EXPIRED, reached by the reaper, `GateService.expire`, and every other terminal path).
- The `code` field needs its own exception plus `@RestControllerAdvice` because the app hides `ResponseStatusException` reasons. `NotAnApproverException` extends `ResponseStatusException(FORBIDDEN)` so existing assertions on 403 still hold.

## Review Focus

- A consumer that throws leaves no partial writes behind and a retry succeeds (Task 2 test: fails twice then succeeds, handler side effect recorded once).
- A delivery whose consumer bean no longer exists is retried and eventually DEAD, not silently dropped or crashing the dispatcher loop (Task 2).
- Two dispatchers racing over many deliveries handle each exactly once (Task 2).
- A member removed after the gate opened is refused even though still on the snapshot (Task 4).
- A member whose role was downgraded from BUYER_APPROVER after the gate opened is refused (Task 4).
- A `RUN_FINISHED`/`GATE_EXPIRED` is published on every terminal path, including reaper expiry, not only `finish` via callbacks (Task 5).

---

## File Structure

Create (main):
- `backend/src/main/resources/db/migration/V41__domain_event_outbox.sql`
- `backend/src/main/java/com/agentstore/platform/events/DomainEvent.java` – immutable record passed to consumers
- `.../events/DomainEventConsumer.java` – consumer interface
- `.../events/DomainEventPublisher.java` – writes event + deliveries
- `.../events/OutboxDispatcher.java` – leases, delivers, retries
- `.../events/OutboxDispatcherJob.java` – scheduled + ShedLock
- `backend/src/main/java/com/agentstore/platform/execution/DecisionChannel.java`
- `.../execution/NotAnApproverException.java`
- `.../execution/GateApiErrors.java`

Modify: `GateService.java`, `RunLifecycleService.java`, `RunController.java`, `SandboxRunController.java`, `ExecutionFixtures.java` (test), `SchedulingWiringTest.java`, plus existing tests that call `decide(...)`.

Create (test): `backend/src/test/java/com/agentstore/platform/events/OutboxTestConsumers.java`, `DomainEventPublisherTest.java`, `OutboxDispatcherTest.java`, `backend/src/test/java/com/agentstore/platform/execution/GateEventsTest.java`, `GateDecisionCheckTest.java`.

---

### Task 1: Outbox tables, publisher and consumer contract

**Files:**
- Create: `backend/src/main/resources/db/migration/V41__domain_event_outbox.sql`
- Create: `backend/src/main/java/com/agentstore/platform/events/DomainEvent.java`
- Create: `backend/src/main/java/com/agentstore/platform/events/DomainEventConsumer.java`
- Create: `backend/src/main/java/com/agentstore/platform/events/DomainEventPublisher.java`
- Create (test): `backend/src/test/java/com/agentstore/platform/events/OutboxTestConsumers.java`
- Test: `backend/src/test/java/com/agentstore/platform/events/DomainEventPublisherTest.java`

**Interfaces:**
- Produces:
  - `record DomainEvent(UUID id, String type, UUID orgId, UUID aggregateId, JsonNode payload, Instant createdAt)`
  - `interface DomainEventConsumer { String name(); boolean handles(String type); void handle(DomainEvent event); }`
  - `UUID DomainEventPublisher.publish(String type, UUID orgId, UUID aggregateId, Map<String, Object> payload)` (`orgId` nullable)
  - Test beans in `OutboxTestConsumers` (a `@TestConfiguration`): `RecordingConsumer` (name `recording`, handles `TEST_EVENT`, has `List<UUID> handled()` and `void failNext(int n)`), `AlwaysFailingConsumer` (name `failing`, handles `TEST_FAIL`, always throws `IllegalStateException("boom")`), `OtherConsumer` (name `other`, handles `OTHER`).

- [ ] **Step 1: Write the migration**

`V41__domain_event_outbox.sql`:

```sql
CREATE TABLE domain_event (
    id           UUID PRIMARY KEY,
    type         TEXT        NOT NULL,
    org_id       UUID        NULL,               -- null for events with no buyer org (sandbox runs)
    aggregate_id UUID        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_domain_event_aggregate ON domain_event (aggregate_id);

CREATE TABLE domain_event_delivery (
    id              UUID PRIMARY KEY,
    event_id        UUID        NOT NULL REFERENCES domain_event (id),
    consumer        TEXT        NOT NULL,
    status          TEXT        NOT NULL,        -- PENDING, DONE, DEAD
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT        NULL,
    CONSTRAINT uq_delivery_event_consumer UNIQUE (event_id, consumer)
);
CREATE INDEX idx_delivery_due ON domain_event_delivery (status, next_attempt_at);
```

- [ ] **Step 2: Write the contract types**

`DomainEvent.java`:

```java
package com.agentstore.platform.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** An outbox event as a consumer sees it. {@code orgId} is null for events with no buyer org. */
public record DomainEvent(UUID id, String type, UUID orgId, UUID aggregateId, JsonNode payload, Instant createdAt) {
}
```

`DomainEventConsumer.java`:

```java
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
```

- [ ] **Step 3: Write the test consumers**

`OutboxTestConsumers.java` (test source root, package `com.agentstore.platform.events`):

```java
package com.agentstore.platform.events;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
public class OutboxTestConsumers {

    public static class RecordingConsumer implements DomainEventConsumer {
        private final List<UUID> handled = new CopyOnWriteArrayList<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();
        private volatile long delayMillis;

        @Override public String name() { return "recording"; }
        @Override public boolean handles(String type) { return "TEST_EVENT".equals(type); }

        @Override
        public void handle(DomainEvent event) {
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("transient");
            }
            handled.add(event.id());
        }

        public List<UUID> handled() { return handled; }
        public void failNext(int n) { failuresLeft.set(n); }
        public void delayMillis(long millis) { delayMillis = millis; }
        public void reset() { handled.clear(); failuresLeft.set(0); delayMillis = 0; }
    }

    public static class AlwaysFailingConsumer implements DomainEventConsumer {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public String name() { return "failing"; }
        @Override public boolean handles(String type) { return "TEST_FAIL".equals(type); }
        @Override public void handle(DomainEvent event) { calls.incrementAndGet(); throw new IllegalStateException("boom"); }
        public int calls() { return calls.get(); }
        public void reset() { calls.set(0); }
    }

    public static class OtherConsumer implements DomainEventConsumer {
        @Override public String name() { return "other"; }
        @Override public boolean handles(String type) { return "OTHER".equals(type); }
        @Override public void handle(DomainEvent event) { }
    }

    @Bean RecordingConsumer recordingConsumer() { return new RecordingConsumer(); }
    @Bean AlwaysFailingConsumer alwaysFailingConsumer() { return new AlwaysFailingConsumer(); }
    @Bean OtherConsumer otherConsumer() { return new OtherConsumer(); }
}
```

- [ ] **Step 4: Write the failing publisher test**

`DomainEventPublisherTest.java`:

```java
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
```

- [ ] **Step 5: Run the test to verify it fails**

Run (from `backend/`): `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DomainEventPublisherTest`
Expected: compilation FAIL (`DomainEventPublisher` does not exist).

- [ ] **Step 6: Write the publisher**

`DomainEventPublisher.java`:

```java
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
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=DomainEventPublisherTest`
Expected: PASS (4 tests). If `Autowired ObjectMapper`/`JdbcTemplate`/`TransactionTemplate` fails to resolve, report it; all three are Spring Boot auto-configured.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V41__domain_event_outbox.sql backend/src/main/java/com/agentstore/platform/events backend/src/test/java/com/agentstore/platform/events
git commit -m "feat(events): outbox tables and transactional domain event publisher"
```

---

### Task 2: Outbox dispatcher (delivery, retry, backoff, DEAD, concurrency)

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/events/OutboxDispatcher.java`
- Test: `backend/src/test/java/com/agentstore/platform/events/OutboxDispatcherTest.java`

**Interfaces:**
- Consumes: `DomainEvent`, `DomainEventConsumer`, `DomainEventPublisher.publish(...)`, `OutboxTestConsumers.*` (Task 1).
- Produces: `int OutboxDispatcher.dispatchOnce()` (number of deliveries processed this call, success or failure; max 20 per call); `static Duration OutboxDispatcher.backoff(int attempts)` (package-private).

- [ ] **Step 1: Write the failing tests**

`OutboxDispatcherTest.java`:

```java
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=OutboxDispatcherTest`
Expected: compilation FAIL (`OutboxDispatcher` does not exist).

- [ ] **Step 3: Write the dispatcher**

`OutboxDispatcher.java`:

```java
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=OutboxDispatcherTest`
Expected: PASS (6 tests). If `attempts` comparisons fail on type (`Integer` vs `int` in `containsEntry`), the map value is `Integer`, which equals the literal.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/events/OutboxDispatcher.java backend/src/test/java/com/agentstore/platform/events/OutboxDispatcherTest.java
git commit -m "feat(events): outbox dispatcher with leases, backoff and dead-lettering"
```

---

### Task 3: Scheduled dispatcher job

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/events/OutboxDispatcherJob.java`
- Modify: `backend/src/test/java/com/agentstore/platform/common/SchedulingWiringTest.java`

**Interfaces:**
- Consumes: `OutboxDispatcher.dispatchOnce()` (Task 2).
- Produces: `OutboxDispatcherJob.run()`, ShedLock name `outboxDispatcher`; property `agentstore.outbox.dispatch-interval` (default `PT5S`).

- [ ] **Step 1: Add the failing assertion**

In `SchedulingWiringTest.java` add the property `"agentstore.outbox.dispatch-interval=PT1H"` to the `@TestPropertySource` array, an import `com.agentstore.platform.events.OutboxDispatcherJob`, a field `@Autowired OutboxDispatcherJob outboxDispatcherJob;`, and this test:

```java
    @Test
    void outboxJobRunsUnderAShedLock() {
        outboxDispatcherJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'outboxDispatcher'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=SchedulingWiringTest`
Expected: compilation FAIL (`OutboxDispatcherJob` does not exist).

- [ ] **Step 3: Write the job**

`OutboxDispatcherJob.java`:

```java
package com.agentstore.platform.events;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcherJob {

    private final OutboxDispatcher dispatcher;

    public OutboxDispatcherJob(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${agentstore.outbox.dispatch-interval:PT5S}",
            initialDelayString = "${agentstore.outbox.dispatch-interval:PT5S}")
    @SchedulerLock(name = "outboxDispatcher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        dispatcher.dispatchOnce();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=SchedulingWiringTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/events/OutboxDispatcherJob.java backend/src/test/java/com/agentstore/platform/common/SchedulingWiringTest.java
git commit -m "feat(events): schedule the outbox dispatcher under a ShedLock"
```

---

### Task 4: Database-checked gate decisions with a decision channel

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/DecisionChannel.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/NotAnApproverException.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/GateApiErrors.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/GateService.java` (constructor, `decide`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunController.java:156,164,173`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunController.java:98`
- Modify (test): `backend/src/test/java/com/agentstore/platform/execution/ExecutionFixtures.java`, and every existing test that calls `gateService.decide(` (`GateServiceTest`, `RunListTest`, `TerminalReasonTest`, `RunGatesViewTest`, and any others compile errors reveal)
- Test: `backend/src/test/java/com/agentstore/platform/execution/GateDecisionCheckTest.java`

**Interfaces:**
- Produces:
  - `enum DecisionChannel { WEB, EMAIL_LINK, SLACK_LINK; DecidedVia decidedVia() }`
  - `Run GateService.decide(UUID runId, UUID gateId, boolean approve, UUID userId, String note, DecisionChannel channel)` (the 5-argument overload is removed; every caller passes a channel)
  - `class NotAnApproverException extends ResponseStatusException` (403, code `NOT_AN_APPROVER`)
  - `ExecutionFixtures.approver(Scenario s)` returning a `User` who is a `BUYER_APPROVER` member of the scenario's buyer org.

- [ ] **Step 1: Write the failing tests**

`GateDecisionCheckTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateDecisionCheckTest extends AbstractIntegrationTest {

    @Autowired ExecutionFixtures fixtures;
    @Autowired IdentityFixtures identities;
    @Autowired GateService gateService;
    @Autowired GateRequestRepository gates;
    @Autowired OrgMembershipRepository memberships;

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    private GateRequest open(ExecutionFixtures.Scenario s, String token) {
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
        return gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
    }

    private void assertNotAnApprover(Runnable decide) {
        assertThatThrownBy(decide::run).isInstanceOfSatisfying(NotAnApproverException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getReason()).contains("approver");
        }).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anApproverOnTheSnapshotCanDecideAndTheChannelIsRecorded() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        GateRequest gate = open(s, "tok-gd-1");

        gateService.decide(gate.getRun().getId(), gate.getId(), true, approver, null, DecisionChannel.EMAIL_LINK);

        assertThat(gates.findById(gate.getId()).orElseThrow().getDecidedVia()).isEqualTo(DecidedVia.EMAIL_LINK);
    }

    @Test
    void theSlackChannelMapsToSlackLink() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        GateRequest gate = open(s, "tok-gd-2");

        gateService.decide(gate.getRun().getId(), gate.getId(), false, approver, "no", DecisionChannel.SLACK_LINK);

        assertThat(gates.findById(gate.getId()).orElseThrow().getDecidedVia()).isEqualTo(DecidedVia.SLACK_LINK);
    }

    @Test
    void aMemberRemovedAfterTheGateOpenedIsRefusedEvenThoughStillOnTheSnapshot() {
        var s = scenario();
        UUID removed = fixtures.approver(s).getId();
        fixtures.approver(s);   // a second approver so the snapshot stays restricted
        GateRequest gate = open(s, "tok-gd-3");
        assertThat(gate.eligibleApproverIds()).contains(removed);
        memberships.deleteById(new OrgMembershipId(removed, s.buyer().getId()));

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true, removed, null,
                DecisionChannel.WEB));
        assertThat(gates.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    }

    @Test
    void aMemberDowngradedFromApproverAfterTheGateOpenedIsRefused() {
        var s = scenario();
        var membership = identities.member(s.buyer(), "downgraded-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_APPROVER, "Approver");
        GateRequest gate = open(s, "tok-gd-4");
        membership.setRole(UserRole.BUYER_ADMIN);
        memberships.save(membership);

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true,
                membership.getUser().getId(), null, DecisionChannel.WEB));
    }

    @Test
    void aMemberOfAnotherOrgIsRefused() {
        var s = scenario();
        fixtures.approver(s);
        var other = scenario();
        UUID outsider = fixtures.approver(other).getId();
        GateRequest gate = open(s, "tok-gd-5");

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true, outsider, null,
                DecisionChannel.WEB));
    }

    @Test
    void anApproverNotOnTheSnapshotIsRefused() {
        var s = scenario();
        fixtures.approver(s);
        GateRequest gate = open(s, "tok-gd-6");
        UUID late = fixtures.approver(s).getId();   // joined after the gate opened

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true, late, null,
                DecisionChannel.WEB));
    }

    @Test
    void aBuyerOrgWithNoApproversStaysUnrestricted() {
        var s = scenario();
        GateRequest gate = open(s, "tok-gd-7");
        assertThat(gate.eligibleApproverIds()).isNull();

        gateService.decide(gate.getRun().getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB);

        assertThat(gates.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }
}
```

- [ ] **Step 2: Add the fixture helper**

In `ExecutionFixtures.java` add `OrgMembershipRepository membershipRepository` as a constructor dependency (add the field, constructor parameter and assignment; import `com.agentstore.platform.identity.OrgMembership`, `OrgMembershipRepository`, `UserRole`) and this method:

```java
    /** A new user who is a BUYER_APPROVER member of the scenario's buyer org. */
    public User approver(Scenario scenario) {
        User user = userRepository.save(new User(null, "approver-" + UUID.randomUUID() + "@example.com", null, "Approver"));
        membershipRepository.save(new OrgMembership(user, scenario.buyer(), UserRole.BUYER_APPROVER, "Approver"));
        return user;
    }
```

- [ ] **Step 3: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=GateDecisionCheckTest`
Expected: compilation FAIL (`DecisionChannel`, `NotAnApproverException` and the 6-argument `decide` do not exist).

- [ ] **Step 4: Write the channel, exception and error advice**

`DecisionChannel.java`:

```java
package com.agentstore.platform.execution;

/** How a gate decision reached the platform. Maps onto the persisted {@link DecidedVia}. */
public enum DecisionChannel {
    WEB(DecidedVia.WEB), EMAIL_LINK(DecidedVia.EMAIL_LINK), SLACK_LINK(DecidedVia.SLACK_LINK);

    private final DecidedVia decidedVia;

    DecisionChannel(DecidedVia decidedVia) {
        this.decidedVia = decidedVia;
    }

    public DecidedVia decidedVia() {
        return decidedVia;
    }
}
```

`NotAnApproverException.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The decider is not a current approver for this gate. 403, code NOT_AN_APPROVER (see {@link GateApiErrors}). */
public class NotAnApproverException extends ResponseStatusException {

    public static final String CODE = "NOT_AN_APPROVER";

    public NotAnApproverException() {
        super(HttpStatus.FORBIDDEN, "You are not an approver for this gate");
    }
}
```

`GateApiErrors.java`:

```java
package com.agentstore.platform.execution;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** The app hides {@code ResponseStatusException} reasons, so a refused decision answers {@code {code, message}}. */
@RestControllerAdvice
public class GateApiErrors {

    @ExceptionHandler(NotAnApproverException.class)
    ResponseEntity<Map<String, Object>> notAnApprover(NotAnApproverException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("code", NotAnApproverException.CODE, "message", e.getReason()));
    }
}
```

- [ ] **Step 5: Change `GateService.decide`**

In `GateService.java`:

1. Add imports `com.agentstore.platform.identity.OrgMembershipId`, `com.agentstore.platform.identity.OrgMembershipRepository`, `com.agentstore.platform.identity.UserRole`; add field `private final OrgMembershipRepository memberships;` and constructor parameter `OrgMembershipRepository memberships` (last), assigned in the constructor.
2. Replace `decide` with the version below (keep its javadoc).

```java
    @Transactional
    public Run decide(UUID runId, UUID gateId, boolean approve, UUID userId, String note, DecisionChannel channel) {
        Run run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        Optional<GateRequest> found = gateId != null
                ? Optional.of(gateRequestRepository.findByIdAndRunId(gateId, runId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gate not found")))
                : gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, GateStatus.PENDING);
        if (found.isPresent()) {
            GateRequest gate = found.get();
            if (gate.getStatus() != GateStatus.PENDING) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Gate already " + gate.getStatus());
            }
            requireApprover(run, gate, userId);
            gate.setStatus(approve ? GateStatus.APPROVED : GateStatus.REJECTED);
            gate.setDecidedByUserId(userId);
            gate.setDecisionNote(note);
            gate.setDecidedAt(Instant.now());
            gate.setDecidedVia(channel.decidedVia());
            gateRequestRepository.save(gate);
            events.publishEvent(new GateOutcomeEvent(runId, gate.getId(), gate.getStatus(),
                    gate.affected(), gate.getCreatedAt()));
            Map<String, Object> detail = new HashMap<>();
            detail.put("gateId", gate.getId().toString());
            detail.put("action", gate.getAction());
            detail.put("decision", approve ? "APPROVED" : "REJECTED");
            if (note != null) {
                detail.put("note", note);
            }
            step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);
        } else {
            requireApprover(run, null, userId);
        }
        return approve ? runLifecycleService.approveGate(runId) : runLifecycleService.rejectGate(runId);
    }

    /**
     * The decider must be a current BUYER_APPROVER of the run's buyer org (checked against the database, not the
     * token, which can outlive a membership) and, when the gate carries an approver snapshot, be on it. A sandbox run
     * has no buyer org and is unrestricted; so is a gate with no snapshot (an org with no approver members), which
     * would otherwise deadlock. A run with no gate row is restricted only when its org has an approver member.
     */
    private void requireApprover(Run run, GateRequest gate, UUID userId) {
        if (run.getConnection() == null) {
            return;
        }
        UUID orgId = run.getConnection().getBuyerOrg().getId();
        List<UUID> eligible = gate != null ? gate.eligibleApproverIds() : null;
        boolean restricted = gate != null
                ? eligible != null
                : memberships.countByIdOrgIdAndRole(orgId, UserRole.BUYER_APPROVER) > 0;
        if (!restricted) {
            return;
        }
        boolean currentApprover = memberships.findById(new OrgMembershipId(userId, orgId))
                .map(m -> m.getRole() == UserRole.BUYER_APPROVER).orElse(false);
        if (!currentApprover || (eligible != null && !eligible.contains(userId))) {
            throw new NotAnApproverException();
        }
    }
```

`OrgMembershipId` has `@AllArgsConstructor` with fields `(userId, orgId)` in that order, so `new OrgMembershipId(userId, orgId)` is correct.

- [ ] **Step 6: Update the callers**

- `RunController.java` lines 156, 164 and 173: append `, DecisionChannel.WEB` as the last argument of each `gateService.decide(...)` call (`DecisionChannel` is in the same package; no import).
- `SandboxRunController.java:98`: `gateService.decide(run.getId(), gateId, body.approve(), claims.userId(), body.note(), com.agentstore.platform.execution.DecisionChannel.WEB);`

- [ ] **Step 7: Fix existing tests that call `decide`**

Run `grep -rn "gateService.decide(\|\.decide(" backend/src/test` and add `, DecisionChannel.WEB` (from `com.agentstore.platform.execution`, import where the test is in another package) to each `GateService.decide` call. Ignore `DeveloperVerificationReviewServiceTest` (different service). Then compile: `mvn -q test-compile`. Expected: no errors.

- [ ] **Step 8: Run the new tests**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=GateDecisionCheckTest`
Expected: PASS (7 tests).

- [ ] **Step 9: Run every existing test touching gates and fix breakage**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest='Gate*Test,ApprovalGateTest,RunReaperTest,RunListTest,TerminalReasonTest,RunGatesViewTest,ConnectionControlsTest,RuntimeGateEndpointsTest,SandboxGateTest,SandboxRunPlumbingTest,RuntimeEndToEndTest,EndToEndTier*Test,DataPlaneEndToEndTest,Ws1bEndToEndTest,RunStepBlockPersistenceTest,AbstractSdkEndToEndTest'`
Expected: PASS. A failure here means a test decides as a user who is on the snapshot or connection approver list but is not a current `BUYER_APPROVER` member of the buyer org (or, on the `/approve` endpoints, an org that has approver members and a decider that is not one). Fix the test's setup so the decider really is a `BUYER_APPROVER` member (`fixtures.approver(scenario)` or `IdentityFixtures.member(...)`). Do not loosen the production check. `GateWs1bTest.anOrgWithNoApproversIsUnrestrictedInsteadOfDeadlocked` must pass unchanged.

- [ ] **Step 10: Commit**

```bash
git add backend/src
git commit -m "feat(gates): check the decider against the database and record the decision channel"
```

---

### Task 5: Publish gate and run events from the real flows

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/GateService.java` (`open`, `decide`, constructor)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java` (`finish`, constructor)
- Test: `backend/src/test/java/com/agentstore/platform/execution/GateEventsTest.java`

**Interfaces:**
- Consumes: `DomainEventPublisher.publish(String, UUID, UUID, Map<String,Object>)` (Task 1); `GateService.decide(..., DecisionChannel)` (Task 4).
- Produces: outbox events (aggregate = gate id for `GATE_*`, run id for `RUN_FINISHED`; org = buyer org or null):
  - `GATE_OPENED` payload `runId, gateId, action, summary, expiresAt (ISO-8601), eligibleApproverIds (array or null)`
  - `GATE_DECIDED` payload `runId, gateId, decision ("APPROVED"|"REJECTED"), decidedBy, channel`
  - `GATE_EXPIRED` payload `runId, gateId`
  - `RUN_FINISHED` payload `runId, status, terminalReason`

- [ ] **Step 1: Write the failing tests**

`GateEventsTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GateEventsTest extends AbstractIntegrationTest {

    @Autowired ExecutionFixtures fixtures;
    @Autowired GateService gateService;
    @Autowired RunLifecycleService lifecycle;
    @Autowired RunReaper reaper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private record Emitted(String type, UUID orgId, JsonNode payload) {
    }

    private List<Emitted> events(UUID aggregateId) {
        return jdbc.query("select type, org_id, payload::text as payload from domain_event where aggregate_id = ? "
                        + "order by created_at, type", (rs, i) -> {
                    try {
                        return new Emitted(rs.getString("type"), rs.getObject("org_id", UUID.class),
                                mapper.readTree(rs.getString("payload")));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, aggregateId);
    }

    private Emitted only(UUID aggregateId, String type) {
        return events(aggregateId).stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void openingAGatePublishesGateOpenedInTheSameTransaction() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID approver = fixtures.approver(s).getId();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-1", "tok-ge-1");

        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post 12 entries", "{}", "[]");

        Emitted opened = only(gate.getId(), "GATE_OPENED");
        assertThat(opened.orgId()).isEqualTo(s.buyer().getId());
        assertThat(opened.payload().get("runId").asText()).isEqualTo(run.getId().toString());
        assertThat(opened.payload().get("gateId").asText()).isEqualTo(gate.getId().toString());
        assertThat(opened.payload().get("action").asText()).isEqualTo("post_journal_entries");
        assertThat(opened.payload().get("summary").asText()).isEqualTo("Post 12 entries");
        assertThat(Instant.parse(opened.payload().get("expiresAt").asText())).isAfter(Instant.now());
        assertThat(opened.payload().get("eligibleApproverIds")).hasSize(1);
        assertThat(opened.payload().get("eligibleApproverIds").get(0).asText()).isEqualTo(approver.toString());
    }

    @Test
    void anUnrestrictedGateEmitsANullApproverList() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-2", "tok-ge-2");

        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        assertThat(only(gate.getId(), "GATE_OPENED").payload().get("eligibleApproverIds").isNull()).isTrue();
    }

    @Test
    void decidingPublishesGateDecidedWithTheChannel() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID approver = fixtures.approver(s).getId();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-3", "tok-ge-3");
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        gateService.decide(run.getId(), gate.getId(), true, approver, "ok", DecisionChannel.EMAIL_LINK);

        Emitted decided = only(gate.getId(), "GATE_DECIDED");
        assertThat(decided.payload().get("decision").asText()).isEqualTo("APPROVED");
        assertThat(decided.payload().get("decidedBy").asText()).isEqualTo(approver.toString());
        assertThat(decided.payload().get("channel").asText()).isEqualTo("EMAIL_LINK");
        assertThat(decided.payload().get("runId").asText()).isEqualTo(run.getId().toString());
        assertThat(events(run.getId())).extracting(Emitted::type).doesNotContain("RUN_FINISHED");
    }

    @Test
    void rejectingPublishesGateDecidedAndRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID approver = fixtures.approver(s).getId();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-4", "tok-ge-4");
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        gateService.decide(run.getId(), gate.getId(), false, approver, "no", DecisionChannel.WEB);

        assertThat(only(gate.getId(), "GATE_DECIDED").payload().get("decision").asText()).isEqualTo("REJECTED");
        Emitted finished = only(run.getId(), "RUN_FINISHED");
        assertThat(finished.payload().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(finished.payload().get("terminalReason").asText()).isEqualTo("GATE_REJECTED");
        assertThat(events(gate.getId())).extracting(Emitted::type).doesNotContain("GATE_EXPIRED");
    }

    @Test
    void theReaperExpiringAGatePublishesGateExpiredAndRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-5", "tok-ge-5");
        GateRequest gate = gateService.open(run.getId(), "a", 1, "s", "{}", "[]");

        reaper.reapAt(Instant.now().plus(Duration.ofMinutes(40)));

        Emitted expired = only(gate.getId(), "GATE_EXPIRED");
        assertThat(expired.payload().get("runId").asText()).isEqualTo(run.getId().toString());
        assertThat(expired.payload().get("gateId").asText()).isEqualTo(gate.getId().toString());
        assertThat(only(run.getId(), "RUN_FINISHED").payload().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(events(gate.getId()).stream().filter(e -> e.type().equals("GATE_EXPIRED"))).hasSize(1);
    }

    @Test
    void aRunFinishingNormallyPublishesRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-6", "tok-ge-6");

        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        Emitted finished = only(run.getId(), "RUN_FINISHED");
        assertThat(finished.orgId()).isEqualTo(s.buyer().getId());
        assertThat(finished.payload().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.payload().get("terminalReason").asText()).isEqualTo("FINISHED");
    }

    @Test
    void aRunThatFailsToFinishLeavesNoEvent() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-7", "tok-ge-7");
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        // A second completion is refused (run already terminal); it must not add another event.
        try {
            lifecycle.complete(run.getId(), Map.of("summary", "again"));
        } catch (RunTerminatedException expected) {
            // expected
        }

        assertThat(events(run.getId()).stream().filter(e -> e.type().equals("RUN_FINISHED"))).hasSize(1);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=GateEventsTest`
Expected: FAIL (`NoSuchElementException`: no `GATE_OPENED` etc. rows).

- [ ] **Step 3: Publish from `GateService`**

In `GateService.java`: import `com.agentstore.platform.events.DomainEventPublisher`; add field `private final DomainEventPublisher domainEvents;` and a final constructor parameter `DomainEventPublisher domainEvents` (assigned). Add a helper:

```java
    private static UUID buyerOrgId(Run run) {
        return run.getConnection() == null ? null : run.getConnection().getBuyerOrg().getId();
    }
```

In the 8-argument `open`, immediately before `return gate;` add:

```java
        Map<String, Object> opened = new HashMap<>();
        opened.put("runId", run.getId().toString());
        opened.put("gateId", gate.getId().toString());
        opened.put("action", action);
        opened.put("summary", summary);
        opened.put("expiresAt", gate.getExpiresAt().toString());
        List<UUID> eligible = gate.eligibleApproverIds();
        opened.put("eligibleApproverIds", eligible == null ? null : eligible.stream().map(UUID::toString).toList());
        domainEvents.publish("GATE_OPENED", buyerOrgId(run), gate.getId(), opened);
```

In `decide`, right after `step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);` add:

```java
            domainEvents.publish("GATE_DECIDED", buyerOrgId(run), gate.getId(), Map.of(
                    "runId", runId.toString(), "gateId", gate.getId().toString(),
                    "decision", approve ? "APPROVED" : "REJECTED", "decidedBy", userId.toString(),
                    "channel", channel.name()));
```

- [ ] **Step 4: Publish from `RunLifecycleService.finish`**

In `RunLifecycleService.java`: import `com.agentstore.platform.events.DomainEventPublisher`; add a field and a final constructor parameter `DomainEventPublisher domainEvents`. In `finish`, inside the `ifPresent(gate -> { ... })` block, after the existing `events.publishEvent(new GateOutcomeEvent(...))` line, add:

```java
            domainEvents.publish("GATE_EXPIRED", buyerOrgId(run), gate.getId(),
                    Map.of("runId", run.getId().toString(), "gateId", gate.getId().toString()));
```

After `events.publishEvent(new RunFinishedEvent(run.getId()));` add:

```java
        Map<String, Object> finished = new java.util.HashMap<>();
        finished.put("runId", run.getId().toString());
        finished.put("status", status.name());
        finished.put("terminalReason", reason == null ? null : reason.name());
        domainEvents.publish("RUN_FINISHED", buyerOrgId(run), run.getId(), finished);
```

Add the helper next to `killQuietly`:

```java
    private static UUID buyerOrgId(Run run) {
        return run.getConnection() == null ? null : run.getConnection().getBuyerOrg().getId();
    }
```

`finish` is only reached from `@Transactional` methods, so `MANDATORY` holds. If a test that builds these services with `new` fails to compile, `grep -rn "new RunLifecycleService(\|new GateService(" backend/src` returned no matches when this plan was written, so none is expected.

- [ ] **Step 5: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=GateEventsTest`
Expected: PASS (7 tests). If the reaper test fails to expire (the reaper only handles runs it can see), compare with `GateServiceTest.theReaperExpiresAGateFromItsOwnTimeoutNotTheGlobalOne` and mirror its setup.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(events): publish gate and run lifecycle events through the outbox"
```

---

### Task 6: Full-suite verification and docs

**Files:**
- Modify: `docs/known-issues.md` (append)

- [ ] **Step 1: Run the whole backend suite**

Run (from `backend/`): `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test`
Expected: BUILD SUCCESS. Any failure is either a test whose decider is not a real current approver (fix the test setup, see Task 4 Step 9) or an event-count assumption; do not weaken production code. Report the totals.

- [ ] **Step 2: Record the follow-ups**

Append to `docs/known-issues.md`:

```markdown
- **WS15b outbox has no retention and no production consumer:** `domain_event` and `domain_event_delivery` rows are never deleted (DONE rows accumulate), and DEAD deliveries are only visible in the table and the error log. WS14 adds the first real consumer; add cleanup and a DEAD-delivery alert alongside it. A consumer whose `name()` is removed while it still has PENDING deliveries makes those deliveries fail until DEAD.
- **WS15b decide check:** a gate with no approver snapshot (buyer org with no approver members) stays open to any caller the controller lets through, to avoid deadlock; only gates with a snapshot get the current-membership check.
```

- [ ] **Step 3: Commit**

```bash
git add docs/known-issues.md
git commit -m "docs: record WS15b outbox follow-ups"
```
