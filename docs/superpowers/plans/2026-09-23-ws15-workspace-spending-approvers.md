# WS15 — Buyer Workspace, Spending Controls and Approvers Implementation Plan


**Goal:** Give finance leads the controls the mockups show: a monthly budget for the workspace, a monthly budget and a named approver list per agent, "Add and run now" in one step, a workspace home endpoint, spend alerts, an activity feed and a ⌘K search.

**Architecture:** Spending checks move into one `SpendLimitGuard` that runs inside the wallet row lock WS0 introduced, so the wallet, the org's month-to-date and the agent's month-to-date are all checked and reserved atomically. The run-trigger internals move out of `RunController` into a `RunTriggerService` with two halves: `prepare` (transactional: checks, reservation, PENDING run) and `launch` (sandbox start, outside the transaction). Connection setup can then create a connection *and* its first run in one transaction. Gate decisions go through a `GateDecisionService` that checks the database, not just the token role. State changes publish domain events into a transactional **outbox** (`domain_event` + one `domain_event_delivery` row per consumer). A ShedLock-guarded dispatcher delivers them. The first consumer is the activity feed; WS14's notifications are the second.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Data JPA / Hibernate 6, Postgres 16 + Flyway, ShedLock 5.16.0 (from WS0), Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §5 **WS15** (read it first), plus §10 rows 2, 4 and 9.

**Prerequisites:** WS0 plan merged (`RunLifecycleService`, `WalletReservationService`, connection version pinning, `SchedulingConfig`, `ExecutionFixtures`). WS13 plan merged (`OrgMembership`, `OrgMembershipRepository`, `IdentityFixtures`, `MeController.currentClaims()`).

## Global Constraints

- Flyway: WS0 `V12`–`V16`, WS13 `V17`–`V21`. **This plan uses `V22`–`V25`.** WS14 follows with `V26`–`V29`, WS16 with `V30`–`V31`. Renumber if another plan merges first.
- Money is `BigDecimal`, scale 2, `RoundingMode.HALF_UP`. Limits are INR.
- The billing month is the calendar month in `agentstore.billing.timezone` (default `Asia/Kolkata`).
- Month-to-date spend = Σ `CHARGE` − Σ `REVERSAL` ledger entries since the month started. Reserved amounts count against limits until released.
- A trigger passes three checks, in this order, under the wallet row lock: org monthly limit → agent (connection) monthly limit → wallet `balance − reserved ≥ spend_cap`. Failures are `ApiProblemException`s with a stable `code`:
  - `ORG_MONTHLY_LIMIT` (409)
  - `AGENT_MONTHLY_LIMIT` (409)
  - `INSUFFICIENT_FUNDS` (402, the status WS0 already uses)
  - `SPEND_CAP_OUT_OF_RANGE` (400)
  - `INVALID_APPROVER` (400)
  - `NOT_AN_APPROVER` (403)
- Error bodies are RFC 7807 `ProblemDetail` with an extra `code` property. They are produced only for `ApiProblemException`. Existing `ResponseStatusException`s keep today's shape.
- Approvers: a gate decider must hold a **current** `org_membership` in the run's org with role `BUYER_ADMIN` or `BUYER_APPROVER`. If the connection has approvers who still qualify, the decider must be one of them. If it has none, or none of them still qualifies, any qualifying member may decide.
- Domain events are published with `Propagation.MANDATORY`, always in the same transaction as the state change they describe. Consumers must be idempotent (a delivery can repeat after a crash).
- Spend alert thresholds: 25, 50, 75, 100 percent of the org monthly limit, each at most once per org per month. One charge that crosses several thresholds emits only the highest.
- Jobs follow WS0's pattern: `@ConditionalOnProperty(name = "agentstore.jobs.enabled", …)` plus `@SchedulerLock`. Tests set `agentstore.jobs.enabled=false` and call `dispatchOnce()` directly.
- **Deferred, with the reason:**
  - The spec's `connection.default_inputs` lands with run inputs in WS1.
  - `bindings` in `POST /connections` lands with datasets in WS2.
  - The home endpoint's `reachedBlock` and readable run IDs land in WS1. Its pending version consents land in WS11. Its AI-provider retention flags land in WS3.
  - Search by run display ID lands in WS1.

## Review Focus

1. **A buyer triggering a run on another workspace's connection** (by guessing its id) must get 404. Today's trigger never checks the caller's org. [Task 5]
2. **A spend cap of 0, a negative one, or one above the agent's declared `maxPerRun`** must be refused at connect and at edit time (400 `SPEND_CAP_OUT_OF_RANGE`). Today any number is accepted. [Task 1]
3. **Two runs triggered at the same moment** that each fit the org's monthly limit alone, but not together, must end with exactly one run started and one refused. [Task 1]
4. **An agent whose only named approver has left the workspace** must still be approvable by the remaining admins and approvers, not stuck until the gate expires. [Task 3]
5. **One large charge that jumps from 20% to 60% of the monthly limit** must raise a single 50% alert. A later charge in the same month must not raise 25% or 50% again. [Task 4]

---

## File Structure

```
backend/src/main/resources/db/migration/
  V22__spend_limits.sql              (create: org.monthly_spend_limit, connection.monthly_limit, connection.notify_channels)
  V23__outbox_and_activity.sql       (create: domain_event, domain_event_delivery, activity_event)
  V24__connection_approver.sql       (create)
  V25__spend_alert.sql               (create)
backend/src/main/resources/application.yml   (modify: billing timezone, outbox interval)
backend/src/main/java/com/agentstore/platform/
  common/ApiProblemException.java, ApiProblemHandler.java, BillingClock.java          (create)
  identity/Org.java                                                                  (modify: monthlySpendLimit)
  events/DomainEventType.java, DomainEvent.java, DomainEventRepository.java,
         DomainEventDelivery.java, DomainEventDeliveryRepository.java,
         DomainEventConsumer.java, DomainEventPublisher.java,
         OutboxDispatcher.java, OutboxDispatcherJob.java,
         ActivityEvent.java, ActivityEventRepository.java, ActivityProjector.java    (create)
  ledger/LedgerEntryRepository.java  (modify: netChargedSince, netChargedForConnectionSince)
  ledger/LedgerService.java          (modify: calls SpendAlertService)
  ledger/SpendLimitController.java, SpendAlertService.java                          (create)
  execution/
    Connection.java                  (modify: monthlyLimit, notifyChannels, approverUserIds)
    NotifyChannel.java, DecisionChannel.java                                         (create)
    RunRepository.java               (modify: reservedForConnection, home/search queries)
    SpendLimitGuard.java             (create)
    RunTriggerService.java           (create: prepare + launch, moved from RunController)
    RunController.java               (modify: thin; uses RunTriggerService and GateDecisionService)
    GateDecisionService.java         (create)
    RunLifecycleService.java         (modify: publishes GATE_OPENED and RUN_FINISHED)
    CreateConnectionRequest.java, UpdateConnectionRequest.java, ConnectionResponse.java (modify/create)
    ConnectionSetupService.java      (create)
    ConnectionController.java        (modify: create/patch through ConnectionSetupService)
    WorkspaceHomeController.java, WorkspaceHomeResponse.java                         (create)
    SearchController.java                                                            (create)
backend/src/test/java/com/agentstore/platform/
  execution/SpendLimitTest.java, GateDecisionTest.java, ConnectionSetupTest.java,
            WorkspaceHomeTest.java, SearchTest.java                                  (create)
  events/OutboxTest.java, events/RecordingConsumer.java                              (create)
  ledger/SpendAlertTest.java                                                         (create)
  execution/ApprovalGateTest.java                                                    (modify: real approver membership)
```

All backend commands run from `backend/`.

---

### Task 1: Structured errors, billing month, and monthly limits enforced at trigger

**Files:**
- Create: `db/migration/V22__spend_limits.sql`, `common/ApiProblemException.java`, `common/ApiProblemHandler.java`, `common/BillingClock.java`, `execution/NotifyChannel.java`, `execution/SpendLimitGuard.java`, `execution/UpdateConnectionRequest.java`, `ledger/SpendLimitController.java`
- Modify: `identity/Org.java`, `execution/Connection.java`, `execution/ConnectionResponse.java`, `execution/ConnectionController.java`, `execution/RunController.java`, `execution/RunRepository.java`, `ledger/LedgerEntryRepository.java`, `application.yml`
- Test: create `execution/SpendLimitTest.java`

**Interfaces:**
- Consumes: `WalletReservationService.reserve/release`, `InsufficientFundsException`, `Wallet.getReserved()`, `Connection.getAgentVersion()` (WS0); `MeController.currentClaims()` (WS13); `ExecutionFixtures.liveTierA(BigDecimal spendCap, BigDecimal walletBalance)` (WS0 test fixture).
- Produces:
  - `ApiProblemException(HttpStatus status, String code, String message)`, `getStatus()`, `getCode()`.
  - `BillingClock.monthStart(): Instant`, `currentMonth(): YearMonth`, `zone(): ZoneId`.
  - `LedgerEntryRepository.netChargedSince(UUID orgId, Instant from): BigDecimal`, `netChargedForConnectionSince(UUID connectionId, Instant from): BigDecimal`.
  - `RunRepository.reservedForConnection(UUID connectionId): BigDecimal`.
  - `Org.getMonthlySpendLimit()/setMonthlySpendLimit(BigDecimal)`.
  - `Connection.getMonthlyLimit()/setMonthlyLimit(BigDecimal)`, `getNotifyChannels(): List<NotifyChannel>`, `setNotifyChannels(List<NotifyChannel>)`.
  - `enum NotifyChannel { WEB, EMAIL, SLACK }`.
  - `SpendLimitGuard.reserveForRun(Connection)` (joins the caller's transaction) and `static void validateSpendCap(BigDecimal spendCap, BigDecimal maxPerRun)`.
  - `PUT /api/v1/orgs/{orgId}/spend-limit` `{monthlyLimit}` (null clears) → `{monthlyLimit}`.
  - `PATCH /api/v1/connections/{id}` `{spendCap?, monthlyLimit?, clearMonthlyLimit?, notifyChannels?}` → `ConnectionResponse`. Absent fields are left unchanged; `"clearMonthlyLimit": true` removes the limit.
  - `ConnectionResponse(UUID id, UUID agentId, List<String> grantedScopes, BigDecimal spendCap, ConnectionStatus status, BigDecimal monthlyLimit, List<NotifyChannel> notifyChannels, List<UUID> approverUserIds, UUID firstRunId)`. Task 3 fills `approverUserIds` and Task 5 fills `firstRunId`; until then they are empty/null.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/SpendLimitTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class SpendLimitTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    LedgerService ledgerService;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders admin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    ResponseEntity<Map> trigger(ExecutionFixtures.Scenario s) {
        return rest.exchange(url("/api/v1/runs"), HttpMethod.POST,
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), admin(s.buyer().getId())), Map.class);
    }

    void setOrgLimit(Org org, String limit) {
        org.setMonthlySpendLimit(limit == null ? null : new BigDecimal(limit));
        orgRepository.save(org);
    }

    void charge(ExecutionFixtures.Scenario s, Run run, String amount) {
        UsageEvent event = usageEventRepository.save(new UsageEvent(null, run.getId(), s.buyer().getId(),
                BigDecimal.ONE, new BigDecimal(amount), new BigDecimal(amount)));
        ledgerService.chargeUsageEvent(event.getId());
    }

    @Test
    void aTriggerThatWouldPassTheOrgMonthlyLimitIsRefusedAndReservesNothing() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("10000.00"));
        setOrgLimit(s.buyer(), "1000.00");
        Run earlier = fixtures.run(s, RunStatus.COMPLETED, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        charge(s, earlier, "800.00");

        var response = trigger(s);   // 800 spent + 300 cap > 1000

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ORG_MONTHLY_LIMIT");
        assertThat(walletRepository.findByOrgId(s.buyer().getId()).orElseThrow().getReserved())
                .isEqualByComparingTo("0");
    }

    @Test
    void theAgentMonthlyLimitCountsOpenReservations() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("10000.00"));
        Connection c = s.connection();
        c.setMonthlyLimit(new BigDecimal("500.00"));
        connectionRepository.save(c);

        assertThat(trigger(s).getStatusCode()).isEqualTo(HttpStatus.OK);   // 300 held
        var second = trigger(s);                                             // 300 + 300 > 500

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("AGENT_MONTHLY_LIMIT");
    }

    @Test
    void anEmptyWalletIsReportedAsInsufficientFunds() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("100.00"));

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().get("code")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void concurrentTriggersNeverTogetherExceedTheOrgLimit() throws Exception {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("10000.00"));
        setOrgLimit(s.buyer(), "500.00");   // one 300 cap fits, two do not
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<HttpStatusCode> call = () -> {
            start.await();
            return trigger(s).getStatusCode();
        };

        Future<HttpStatusCode> a = pool.submit(call);
        Future<HttpStatusCode> b = pool.submit(call);
        start.countDown();
        List<HttpStatusCode> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
    }

    @Test
    void adminsSetAndClearTheWorkspaceLimit() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        String path = url("/api/v1/orgs/" + s.buyer().getId() + "/spend-limit");

        rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(Map.of("monthlyLimit", 50000), admin(s.buyer().getId())),
                Map.class);
        assertThat(orgRepository.findById(s.buyer().getId()).orElseThrow().getMonthlySpendLimit())
                .isEqualByComparingTo("50000");

        Map<String, Object> clear = new HashMap<>();
        clear.put("monthlyLimit", null);
        rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(clear, admin(s.buyer().getId())), Map.class);
        assertThat(orgRepository.findById(s.buyer().getId()).orElseThrow().getMonthlySpendLimit()).isNull();
    }

    @Test
    void patchingAConnectionValidatesTheCapAndClearsTheMonthlyLimitOnlyWhenAskedTo() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        String path = url("/api/v1/connections/" + s.connection().getId());
        HttpHeaders headers = admin(s.buyer().getId());

        var tooHigh = rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of("spendCap", 501), headers), Map.class);
        assertThat(tooHigh.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooHigh.getBody().get("code")).isEqualTo("SPEND_CAP_OUT_OF_RANGE");
        var zero = rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of("spendCap", 0), headers), Map.class);
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of("monthlyLimit", 2000, "spendCap", 250), headers),
                Map.class);
        rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of("notifyChannels", List.of("EMAIL")), headers),
                Map.class);
        Connection after = connectionRepository.findById(s.connection().getId()).orElseThrow();
        assertThat(after.getMonthlyLimit()).isEqualByComparingTo("2000");
        assertThat(after.getSpendCap()).isEqualByComparingTo("250");
        assertThat(after.getNotifyChannels()).containsExactly(NotifyChannel.WEB, NotifyChannel.EMAIL);

        rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(Map.of("clearMonthlyLimit", true), headers), Map.class);
        assertThat(connectionRepository.findById(s.connection().getId()).orElseThrow().getMonthlyLimit()).isNull();
    }

    @Test
    void connectingWithACapAboveTheDeclaredMaximumIsRefused() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));

        var response = rest.exchange(url("/api/v1/connections"), HttpMethod.POST,
                new HttpEntity<>(new CreateConnectionRequest(s.connection().getAgent().getId(), List.of("bank_statement"),
                        new BigDecimal("600.00")), admin(s.buyer().getId())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("SPEND_CAP_OUT_OF_RANGE");
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=SpendLimitTest`
Expected: compilation failure (`setMonthlySpendLimit`, `setMonthlyLimit`, `NotifyChannel` not found).

- [ ] **Step 3: Migration and config**

`backend/src/main/resources/db/migration/V22__spend_limits.sql`:

```sql
ALTER TABLE org ADD COLUMN monthly_spend_limit NUMERIC(14,2);
ALTER TABLE org ADD CONSTRAINT org_monthly_spend_limit_non_negative CHECK (monthly_spend_limit >= 0);
ALTER TABLE connection ADD COLUMN monthly_limit NUMERIC(12,2);
ALTER TABLE connection ADD CONSTRAINT connection_monthly_limit_non_negative CHECK (monthly_limit >= 0);
ALTER TABLE connection ADD COLUMN notify_channels JSONB NOT NULL DEFAULT '["WEB","EMAIL"]';
CREATE INDEX ledger_entry_org_created_idx ON ledger_entry (org_id, created_at);
CREATE INDEX run_connection_status_idx ON run (connection_id, status);
```

In `application.yml` under `agentstore:` add:

```yaml
  billing:
    timezone: Asia/Kolkata
```

- [ ] **Step 4: Common helpers**

`common/ApiProblemException.java`:

```java
package com.agentstore.platform.common;

import org.springframework.http.HttpStatus;

/** An error the web app can act on: an HTTP status plus a stable machine-readable code. */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiProblemException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
```

`common/ApiProblemHandler.java`:

```java
package com.agentstore.platform.common;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiProblemHandler {

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handle(ApiProblemException e) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        body.setProperty("code", e.getCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
```

`common/BillingClock.java`:

```java
package com.agentstore.platform.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/** Billing months are calendar months in the buyer's timezone (India by default). */
@Component
public class BillingClock {

    private final ZoneId zone;
    private final Clock clock;

    public BillingClock(@Value("${agentstore.billing.timezone:Asia/Kolkata}") String zone) {
        this.zone = ZoneId.of(zone);
        this.clock = Clock.system(this.zone);
    }

    public YearMonth currentMonth() {
        return YearMonth.now(clock);
    }

    public Instant monthStart() {
        return currentMonth().atDay(1).atStartOfDay(zone).toInstant();
    }

    public ZoneId zone() {
        return zone;
    }
}
```

- [ ] **Step 5: Entities and queries**

In `identity/Org.java` add after `type` (import `java.math.BigDecimal`):

```java
    /** Workspace spending limit per billing month; null means no limit. */
    private BigDecimal monthlySpendLimit;
```

`execution/NotifyChannel.java`:

```java
package com.agentstore.platform.execution;

/** Where a connection's approval requests reach people. WEB is always on. */
public enum NotifyChannel { WEB, EMAIL, SLACK }
```

In `execution/Connection.java` add (imports `java.util.ArrayList`, `java.util.EnumSet`, `java.util.LinkedHashSet`):

```java
    /** "Monthly limit for this agent"; null means no limit. */
    private BigDecimal monthlyLimit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notify_channels", columnDefinition = "jsonb")
    private String notifyChannelsJson = "[\"WEB\",\"EMAIL\"]";

    public List<NotifyChannel> getNotifyChannels() {
        try {
            return MAPPER.readValue(notifyChannelsJson, new TypeReference<List<NotifyChannel>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stores the channels in enum order, always including WEB. */
    public void setNotifyChannels(List<NotifyChannel> channels) {
        var set = EnumSet.of(NotifyChannel.WEB);
        set.addAll(channels);
        try {
            this.notifyChannelsJson = MAPPER.writeValueAsString(new ArrayList<>(set));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
```

In `ledger/LedgerEntryRepository.java` add (imports `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.math.BigDecimal`, `java.time.Instant`):

```java
    @Query(value = """
            select coalesce(sum(case when type = 'CHARGE' then amount when type = 'REVERSAL' then -amount else 0 end), 0)
            from ledger_entry where org_id = :orgId and created_at >= :from
            """, nativeQuery = true)
    BigDecimal netChargedSince(@Param("orgId") UUID orgId, @Param("from") Instant from);

    @Query(value = """
            select coalesce(sum(case when e.type = 'CHARGE' then e.amount when e.type = 'REVERSAL' then -e.amount else 0 end), 0)
            from ledger_entry e join run r on r.id = e.run_id
            where r.connection_id = :connectionId and e.created_at >= :from
            """, nativeQuery = true)
    BigDecimal netChargedForConnectionSince(@Param("connectionId") UUID connectionId, @Param("from") Instant from);
```

In `execution/RunRepository.java` add (import `java.math.BigDecimal`):

```java
    @Query(value = """
            select coalesce(sum(reserved_amount), 0) from run
            where connection_id = :connectionId and status in ('PENDING', 'RUNNING', 'AWAITING_APPROVAL')
            """, nativeQuery = true)
    BigDecimal reservedForConnection(@Param("connectionId") UUID connectionId);
```

- [ ] **Step 6: `SpendLimitGuard`**

`execution/SpendLimitGuard.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.common.ApiProblemException;
import com.agentstore.platform.common.BillingClock;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.ledger.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Every spending check for starting a run, done under the buyer's wallet row lock so concurrent triggers in one
 * workspace are serialized: org monthly limit, then agent monthly limit, then wallet balance, then the reservation.
 */
@Service
public class SpendLimitGuard {

    private final WalletRepository walletRepository;
    private final WalletReservationService walletReservationService;
    private final OrgRepository orgRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RunRepository runRepository;
    private final BillingClock billingClock;

    public SpendLimitGuard(WalletRepository walletRepository, WalletReservationService walletReservationService,
                           OrgRepository orgRepository, LedgerEntryRepository ledgerEntryRepository,
                           RunRepository runRepository, BillingClock billingClock) {
        this.walletRepository = walletRepository;
        this.walletReservationService = walletReservationService;
        this.orgRepository = orgRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.runRepository = runRepository;
        this.billingClock = billingClock;
    }

    public static void validateSpendCap(BigDecimal spendCap, BigDecimal maxPerRun) {
        if (spendCap == null || spendCap.signum() <= 0 || spendCap.compareTo(maxPerRun) > 0) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "SPEND_CAP_OUT_OF_RANGE",
                    "The most one run can cost must be above ₹0 and at most ₹" + maxPerRun.toPlainString());
        }
    }

    @Transactional
    public void reserveForRun(Connection connection) {
        UUID orgId = connection.getBuyerOrg().getId();
        BigDecimal cap = connection.getSpendCap();
        Wallet wallet = walletRepository.findByOrgId(orgId)   // PESSIMISTIC_WRITE: serializes this workspace
                .orElseThrow(() -> insufficient("This workspace has no wallet yet"));
        Instant from = billingClock.monthStart();

        BigDecimal orgLimit = orgRepository.findById(orgId).map(Org::getMonthlySpendLimit).orElse(null);
        if (orgLimit != null) {
            BigDecimal committed = ledgerEntryRepository.netChargedSince(orgId, from)
                    .add(wallet.getReserved()).add(cap);
            if (committed.compareTo(orgLimit) > 0) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "ORG_MONTHLY_LIMIT",
                        "This run could take the workspace past its monthly limit of ₹" + orgLimit.toPlainString());
            }
        }

        BigDecimal agentLimit = connection.getMonthlyLimit();
        if (agentLimit != null) {
            BigDecimal committed = ledgerEntryRepository.netChargedForConnectionSince(connection.getId(), from)
                    .add(runRepository.reservedForConnection(connection.getId())).add(cap);
            if (committed.compareTo(agentLimit) > 0) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "AGENT_MONTHLY_LIMIT",
                        "This run could take the agent past its monthly limit of ₹" + agentLimit.toPlainString());
            }
        }

        try {
            walletReservationService.reserve(orgId, cap);
        } catch (InsufficientFundsException e) {
            throw insufficient(e.getMessage());
        }
    }

    private static ApiProblemException insufficient(String message) {
        return new ApiProblemException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_FUNDS", message);
    }
}
```

`runRepository.reservedForConnection` reads other runs of this connection. Those rows are only written by transactions that also hold this workspace's wallet lock (reserve and release both lock the wallet), so the read is consistent.

- [ ] **Step 7: Use it at trigger time**

In `RunController.trigger` (WS0 version), replace the whole reservation block:

```java
        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();
        try {
            walletReservationService.reserve(buyerOrgId, spendCap);
        } catch (InsufficientFundsException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        }
```

with:

```java
        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();
        spendLimitGuard.reserveForRun(connection);
```

and swap the constructor dependency `WalletReservationService walletReservationService` for `SpendLimitGuard spendLimitGuard`. Keep `walletReservationService` only if the `catch` around `runRepository.save(pending)` still needs `release`: it does, so keep both dependencies and remove only the unused `InsufficientFundsException` import. (Task 5 moves this code into `RunTriggerService` in one transaction and drops the manual release.)

- [ ] **Step 8: Endpoints**

`execution/UpdateConnectionRequest.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Partial update: a {@code null} component means "not sent, leave as is". Clearing the monthly limit is an
 * explicit flag, because JSON null and an absent field look the same to a record.
 */
public record UpdateConnectionRequest(BigDecimal spendCap, BigDecimal monthlyLimit, Boolean clearMonthlyLimit,
                                      List<NotifyChannel> notifyChannels, List<UUID> approverUserIds) {
}
```

Replace `execution/ConnectionResponse.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConnectionResponse(UUID id, UUID agentId, List<String> grantedScopes, BigDecimal spendCap,
                                 ConnectionStatus status, BigDecimal monthlyLimit, List<NotifyChannel> notifyChannels,
                                 List<UUID> approverUserIds, UUID firstRunId) {
    static ConnectionResponse from(Connection c) {
        return from(c, null);
    }

    static ConnectionResponse from(Connection c, UUID firstRunId) {
        return new ConnectionResponse(c.getId(), c.getAgent().getId(), c.getGrantedScopes(), c.getSpendCap(),
                c.getStatus(), c.getMonthlyLimit(), c.getNotifyChannels(), List.of(), firstRunId);
    }
}
```

In `ConnectionController.create`, after parsing the manifest and checking the requested scopes, add:

```java
        SpendLimitGuard.validateSpendCap(request.spendCap(), manifest.costEnvelope().maxPerRun());
```

Add to `ConnectionController` (imports `com.agentstore.platform.identity.MeController`, `org.springframework.transaction.annotation.Transactional`, `java.util.UUID`):

```java
    @PatchMapping("/{connectionId}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @Transactional
    public ConnectionResponse update(@PathVariable UUID connectionId, @RequestBody UpdateConnectionRequest request) {
        Connection connection = connectionRepository.findById(connectionId)
                .filter(c -> c.getBuyerOrg().getId().equals(MeController.currentClaims().orgId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (request.spendCap() != null) {
            var manifest = manifestParser.parse(connection.getAgentVersion().getManifestJson());
            SpendLimitGuard.validateSpendCap(request.spendCap(), manifest.costEnvelope().maxPerRun());
            connection.setSpendCap(request.spendCap());
        }
        if (Boolean.TRUE.equals(request.clearMonthlyLimit())) {
            connection.setMonthlyLimit(null);
        } else if (request.monthlyLimit() != null) {
            if (request.monthlyLimit().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monthly limit cannot be negative");
            }
            connection.setMonthlyLimit(request.monthlyLimit());
        }
        if (request.notifyChannels() != null) {
            connection.setNotifyChannels(request.notifyChannels());
        }
        return ConnectionResponse.from(connectionRepository.save(connection));
    }
```

(`java.math.BigDecimal` import as needed.)

`ledger/SpendLimitController.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.MeController;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
public class SpendLimitController {

    private final OrgRepository orgRepository;

    public SpendLimitController(OrgRepository orgRepository) {
        this.orgRepository = orgRepository;
    }

    /** A null {@code monthlyLimit} removes the workspace limit. */
    public record SpendLimit(BigDecimal monthlyLimit) {}

    @PutMapping("/api/v1/orgs/{orgId}/spend-limit")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @Transactional
    public SpendLimit set(@PathVariable UUID orgId, @RequestBody SpendLimit request) {
        if (!orgId.equals(MeController.currentClaims().orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your workspace");
        }
        if (request.monthlyLimit() != null && request.monthlyLimit().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monthly limit cannot be negative");
        }
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setMonthlySpendLimit(request.monthlyLimit());
        orgRepository.save(org);
        return new SpendLimit(org.getMonthlySpendLimit());
    }
}
```

- [ ] **Step 9: Run the tests**

Run: `mvn -q test -Dtest='SpendLimitTest,RunController*Test,ConnectionControllerTest,EndToEnd*Test,PlatformApplicationTest'`
Expected: all PASS. `RunControllerWalletTest` from WS0 asserts a 402 on insufficient funds, which is unchanged.

- [ ] **Step 10: Commit**

```bash
git add backend/src
git commit -m "feat(execution): workspace and per-agent monthly limits checked atomically with the wallet reservation"
```

---

### Task 2: Transactional outbox and the activity feed

**Files:**
- Create: `db/migration/V23__outbox_and_activity.sql`
- Create: `events/DomainEventType.java`, `events/DomainEvent.java`, `events/DomainEventRepository.java`, `events/DomainEventDelivery.java`, `events/DomainEventDeliveryRepository.java`, `events/DomainEventConsumer.java`, `events/DomainEventPublisher.java`, `events/OutboxDispatcher.java`, `events/OutboxDispatcherJob.java`, `events/ActivityEvent.java`, `events/ActivityEventRepository.java`, `events/ActivityProjector.java`
- Modify: `execution/RunLifecycleService.java`, `application.yml`
- Test: create `events/RecordingConsumer.java`, `events/OutboxTest.java`

**Interfaces:**
- Consumes: `RunLifecycleService.recordEvent/finish` (WS0), `ExecutionFixtures` (WS0).
- Produces:
  - `enum DomainEventType { GATE_OPENED, GATE_DECIDED, RUN_FINISHED, SPEND_THRESHOLD_CROSSED, CONNECTION_CREATED }`.
  - `DomainEventPublisher.publish(DomainEventType type, UUID orgId, String subjectType, UUID subjectId, UUID actorUserId, Map<String, Object> payload): DomainEvent` (`Propagation.MANDATORY`).
  - `interface DomainEventConsumer { String name(); void handle(DomainEvent event); }`. Each implementation is a Spring bean. `name()` is stable, because it is stored.
  - `DomainEvent`: `getId()`, `getOrgId()`, `getType()`, `getSubjectType()`, `getSubjectId()`, `getActorUserId()`, `getCreatedAt()`, `Map<String, Object> payload()`.
  - `OutboxDispatcher.dispatchOnce(): int` (deliveries completed).
  - `ActivityEventRepository.findTop10ByOrgIdOrderByCreatedAtDesc(UUID): List<ActivityEvent>`.
  - Published by `RunLifecycleService`:
    - `GATE_OPENED` with payload `{agentName, connectionId, summary?}`.
    - `RUN_FINISHED` with payload `{agentName, connectionId, status, charged}`.
    - Both use `subjectType = "RUN"` and `subjectId = runId`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/events/RecordingConsumer.java`:

```java
package com.agentstore.platform.events;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A test consumer that records what it handled and can be told to fail once for a given event. */
@Component
public class RecordingConsumer implements DomainEventConsumer {

    public final List<UUID> handled = new CopyOnWriteArrayList<>();
    private final Set<UUID> failOnce = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return "test-recorder";
    }

    @Override
    public void handle(DomainEvent event) {
        if (failOnce.remove(event.getId())) {
            throw new IllegalStateException("simulated consumer failure");
        }
        handled.add(event.getId());
    }

    public void failOnceFor(UUID eventId) {
        failOnce.add(eventId);
    }
}
```

`backend/src/test/java/com/agentstore/platform/events/OutboxTest.java`:

```java
package com.agentstore.platform.events;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTest extends AbstractIntegrationTest {

    @Autowired
    DomainEventPublisher publisher;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    RecordingConsumer recorder;
    @Autowired
    ActivityEventRepository activityEventRepository;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;

    DomainEvent publishOne(UUID orgId) {
        return tx.execute(s -> publisher.publish(DomainEventType.RUN_FINISHED, orgId, "RUN", UUID.randomUUID(), null,
                Map.of("agentName", "Bank Reconciliation", "status", "COMPLETED", "charged", "284.00")));
    }

    @Test
    void publishingOutsideATransactionIsAProgrammingError() {
        assertThatThrownBy(() -> publisher.publish(DomainEventType.RUN_FINISHED, UUID.randomUUID(), "RUN",
                UUID.randomUUID(), null, Map.of())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void anEventIsDeliveredToEveryConsumerOnce() {
        UUID orgId = UUID.randomUUID();
        DomainEvent event = publishOne(orgId);

        dispatcher.dispatchOnce();
        dispatcher.dispatchOnce();

        assertThat(recorder.handled).containsOnlyOnce(event.getId());
        assertThat(activityEventRepository.findTop10ByOrgIdOrderByCreatedAtDesc(orgId))
                .singleElement().satisfies(a -> assertThat(a.getKind()).isEqualTo("RUN_FINISHED"));
    }

    @Test
    void aFailedDeliveryIsRetriedLaterWithoutBlockingOtherConsumers() {
        UUID orgId = UUID.randomUUID();
        DomainEvent event = publishOne(orgId);
        recorder.failOnceFor(event.getId());

        dispatcher.dispatchOnce();
        assertThat(recorder.handled).doesNotContain(event.getId());
        assertThat(activityEventRepository.findTop10ByOrgIdOrderByCreatedAtDesc(orgId)).hasSize(1);

        // make the retry due now instead of after the backoff
        jdbc.update("update domain_event_delivery set next_attempt_at = now() where event_id = ?", event.getId());
        dispatcher.dispatchOnce();
        assertThat(recorder.handled).containsOnlyOnce(event.getId());
    }

    @Test
    void aRolledBackStateChangeLeavesNoEvent() {
        UUID orgId = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                publisher.publish(DomainEventType.RUN_FINISHED, orgId, "RUN", UUID.randomUUID(), null, Map.of());
                throw new IllegalStateException("roll back");
            });
        } catch (IllegalStateException expected) {
            // the transaction rolled back
        }

        assertThat(jdbc.queryForObject("select count(*) from domain_event where org_id = ?", Integer.class, orgId))
                .isZero();
    }

    @Test
    void theRunLifecyclePublishesGateOpenedAndRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());

        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("summary", "Post 142 journal entries"));
        lifecycle.rejectGate(run.getId());
        dispatcher.dispatchOnce();

        assertThat(activityEventRepository.findTop10ByOrgIdOrderByCreatedAtDesc(s.buyer().getId()))
                .extracting(ActivityEvent::getKind)
                .containsExactlyInAnyOrder("GATE_OPENED", "RUN_FINISHED");
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=OutboxTest`
Expected: compilation failure (`DomainEventPublisher` not found).

- [ ] **Step 3: Migration and config**

`backend/src/main/resources/db/migration/V23__outbox_and_activity.sql`:

```sql
CREATE TABLE domain_event (
    id            UUID        PRIMARY KEY,
    org_id        UUID,
    type          VARCHAR(50) NOT NULL,
    subject_type  VARCHAR(20),
    subject_id    UUID,
    actor_user_id UUID,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX domain_event_org_idx ON domain_event (org_id, created_at);

-- One row per (event, consumer): each consumer progresses and retries independently.
CREATE TABLE domain_event_delivery (
    event_id        UUID        NOT NULL REFERENCES domain_event(id),
    consumer        VARCHAR(40) NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMPTZ,
    given_up_at     TIMESTAMPTZ,
    PRIMARY KEY (event_id, consumer)
);
CREATE INDEX domain_event_delivery_due_idx ON domain_event_delivery (consumer, next_attempt_at)
    WHERE delivered_at IS NULL AND given_up_at IS NULL;

CREATE TABLE activity_event (
    id            UUID        PRIMARY KEY,          -- the domain_event id, so projecting twice is a no-op
    org_id        UUID        NOT NULL,
    kind          VARCHAR(50) NOT NULL,
    actor_user_id UUID,
    subject_type  VARCHAR(20),
    subject_id    UUID,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX activity_event_org_idx ON activity_event (org_id, created_at DESC);
```

In `application.yml` under `agentstore:` add:

```yaml
  outbox:
    interval: PT5S
```

- [ ] **Step 4: Event model and publisher**

`events/DomainEventType.java`:

```java
package com.agentstore.platform.events;

public enum DomainEventType { GATE_OPENED, GATE_DECIDED, RUN_FINISHED, SPEND_THRESHOLD_CROSSED, CONNECTION_CREATED }
```

`events/DomainEvent.java`:

```java
package com.agentstore.platform.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "domain_event")
@Getter
@NoArgsConstructor
public class DomainEvent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orgId;

    @Enumerated(EnumType.STRING)
    private DomainEventType type;

    private String subjectType;
    private UUID subjectId;
    private UUID actorUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    private Instant createdAt;

    public DomainEvent(DomainEventType type, UUID orgId, String subjectType, UUID subjectId, UUID actorUserId,
                       Map<String, Object> payload) {
        this.type = type;
        this.orgId = orgId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.actorUserId = actorUserId;
        try {
            this.payload = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Event payload is not JSON-serializable", e);
        }
    }

    public Map<String, Object> payload() {
        try {
            return MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String payloadJson() {
        return payload;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`events/DomainEventRepository.java`:

```java
package com.agentstore.platform.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DomainEventRepository extends JpaRepository<DomainEvent, UUID> {
}
```

`events/DomainEventDelivery.java`:

```java
package com.agentstore.platform.events;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "domain_event_delivery")
@IdClass(DomainEventDelivery.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class DomainEventDelivery {

    @Id
    private UUID eventId;

    @Id
    private String consumer;

    private int attempts;
    private String lastError;
    private Instant nextAttemptAt;
    private Instant deliveredAt;
    private Instant givenUpAt;

    public DomainEventDelivery(UUID eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
        this.nextAttemptAt = Instant.now();
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID eventId;
        private String consumer;
    }
}
```

`events/DomainEventDeliveryRepository.java`:

```java
package com.agentstore.platform.events;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DomainEventDeliveryRepository extends JpaRepository<DomainEventDelivery, DomainEventDelivery.Key> {

    @Query(value = """
            select * from domain_event_delivery
            where consumer = :consumer and delivered_at is null and given_up_at is null and next_attempt_at <= now()
            order by next_attempt_at
            limit :limit
            """, nativeQuery = true)
    List<DomainEventDelivery> findDue(@Param("consumer") String consumer, @Param("limit") int limit);
}
```

`events/DomainEventConsumer.java`:

```java
package com.agentstore.platform.events;

/** Receives outbox events. Must be idempotent: a delivery can repeat after a crash between handling and marking. */
public interface DomainEventConsumer {

    /** Stable id stored with each delivery. Never rename a consumer that has pending deliveries. */
    String name();

    void handle(DomainEvent event);
}
```

`events/DomainEventPublisher.java`:

```java
package com.agentstore.platform.events;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Writes an event and one delivery per consumer in the caller's transaction, so both commit or neither does. */
@Service
public class DomainEventPublisher {

    private final DomainEventRepository eventRepository;
    private final DomainEventDeliveryRepository deliveryRepository;
    private final ObjectProvider<DomainEventConsumer> consumers;   // lazy: consumers may depend on publishers

    public DomainEventPublisher(DomainEventRepository eventRepository,
                                DomainEventDeliveryRepository deliveryRepository,
                                ObjectProvider<DomainEventConsumer> consumers) {
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.consumers = consumers;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEvent publish(DomainEventType type, UUID orgId, String subjectType, UUID subjectId, UUID actorUserId,
                               Map<String, Object> payload) {
        DomainEvent event = eventRepository.save(new DomainEvent(type, orgId, subjectType, subjectId, actorUserId,
                payload));
        consumers.orderedStream().forEach(c -> deliveryRepository.save(new DomainEventDelivery(event.getId(), c.name())));
        return event;
    }
}
```

- [ ] **Step 5: Dispatcher, job and activity projector**

`events/OutboxDispatcher.java`:

```java
package com.agentstore.platform.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/** Delivers due outbox rows. Each delivery is handled in its own transaction; a failure only delays that row. */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    static final int BATCH = 100;
    static final int MAX_ATTEMPTS = 10;

    private final ObjectProvider<DomainEventConsumer> consumers;
    private final DomainEventRepository eventRepository;
    private final DomainEventDeliveryRepository deliveryRepository;
    private final TransactionTemplate tx;

    public OutboxDispatcher(ObjectProvider<DomainEventConsumer> consumers, DomainEventRepository eventRepository,
                            DomainEventDeliveryRepository deliveryRepository, PlatformTransactionManager txManager) {
        this.consumers = consumers;
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int dispatchOnce() {
        int delivered = 0;
        for (DomainEventConsumer consumer : consumers.orderedStream().toList()) {
            for (DomainEventDelivery due : deliveryRepository.findDue(consumer.name(), BATCH)) {
                if (deliverOne(consumer, due)) delivered++;
            }
        }
        return delivered;
    }

    private boolean deliverOne(DomainEventConsumer consumer, DomainEventDelivery due) {
        DomainEventDelivery.Key key = new DomainEventDelivery.Key(due.getEventId(), due.getConsumer());
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                DomainEventDelivery delivery = deliveryRepository.findById(key).orElse(null);
                if (delivery == null || delivery.getDeliveredAt() != null) return false;
                consumer.handle(eventRepository.findById(delivery.getEventId()).orElseThrow());
                delivery.setDeliveredAt(Instant.now());
                deliveryRepository.save(delivery);
                return true;
            }));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(status -> deliveryRepository.findById(key).ifPresent(d -> {
                d.setAttempts(d.getAttempts() + 1);
                d.setLastError(String.valueOf(e.getMessage()));
                if (d.getAttempts() >= MAX_ATTEMPTS) {
                    d.setGivenUpAt(Instant.now());
                    log.error("Gave up delivering event {} to {} after {} attempts", d.getEventId(),
                            d.getConsumer(), d.getAttempts(), e);
                } else {
                    long seconds = Math.min(3600, 10L << Math.min(d.getAttempts(), 9));   // 20s, 40s … 1h
                    d.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(seconds)));
                    log.warn("Delivery of event {} to {} failed (attempt {})", d.getEventId(), d.getConsumer(),
                            d.getAttempts(), e);
                }
                deliveryRepository.save(d);
            }));
            return false;
        }
    }
}
```

`events/OutboxDispatcherJob.java`:

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

    @Scheduled(fixedDelayString = "${agentstore.outbox.interval:PT5S}")
    @SchedulerLock(name = "outboxDispatcher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        dispatcher.dispatchOnce();
    }
}
```

`events/ActivityEvent.java`:

```java
package com.agentstore.platform.events;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** The workspace's "Recent" feed. The id is the source domain event's id. */
@Entity
@Table(name = "activity_event")
@Getter
@NoArgsConstructor
public class ActivityEvent {

    @Id
    private UUID id;

    private UUID orgId;
    private String kind;
    private UUID actorUserId;
    private String subjectType;
    private UUID subjectId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    private Instant createdAt;

    public ActivityEvent(DomainEvent source) {
        this.id = source.getId();
        this.orgId = source.getOrgId();
        this.kind = source.getType().name();
        this.actorUserId = source.getActorUserId();
        this.subjectType = source.getSubjectType();
        this.subjectId = source.getSubjectId();
        this.payload = source.payloadJson();
        this.createdAt = source.getCreatedAt();
    }
}
```

`events/ActivityEventRepository.java`:

```java
package com.agentstore.platform.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {
    List<ActivityEvent> findTop10ByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
```

`events/ActivityProjector.java`:

```java
package com.agentstore.platform.events;

import org.springframework.stereotype.Component;

@Component
public class ActivityProjector implements DomainEventConsumer {

    private final ActivityEventRepository repository;

    public ActivityProjector(ActivityEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "activity";
    }

    @Override
    public void handle(DomainEvent event) {
        if (event.getOrgId() != null && !repository.existsById(event.getId())) {
            repository.save(new ActivityEvent(event));
        }
    }
}
```

- [ ] **Step 6: Publish from the run lifecycle**

In `execution/RunLifecycleService.java` add the constructor dependency `DomainEventPublisher eventPublisher` (field + assignment; imports `com.agentstore.platform.events.DomainEventPublisher`, `com.agentstore.platform.events.DomainEventType`, `java.util.HashMap`).

In `recordEvent`, change the `GATE` branch to:

```java
            case GATE -> {
                step(run, kind, safeDetail);
                run.setStatus(RunStatus.AWAITING_APPROVAL);
                run.setAwaitingSince(Instant.now());
                runRepository.save(run);
                Map<String, Object> payload = runPayload(run);
                if (safeDetail.get("summary") != null) payload.put("summary", String.valueOf(safeDetail.get("summary")));
                eventPublisher.publish(DomainEventType.GATE_OPENED, run.getConnection().getBuyerOrg().getId(), "RUN",
                        run.getId(), null, payload);
            }
```

At the end of `finish(Run run, RunStatus status)`, after the sandbox kill, add:

```java
        Map<String, Object> payload = runPayload(run);
        payload.put("status", status.name());
        payload.put("charged", run.getTotalCharged().toPlainString());
        eventPublisher.publish(DomainEventType.RUN_FINISHED, run.getConnection().getBuyerOrg().getId(), "RUN",
                run.getId(), null, payload);
```

and add the helper:

```java
    private static Map<String, Object> runPayload(Run run) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentName", run.getConnection().getAgent().getName());
        payload.put("connectionId", run.getConnection().getId().toString());
        return payload;
    }
```

Every caller of `finish` and `recordEvent` is already `@Transactional`, which `publish`'s `MANDATORY` propagation requires.

- [ ] **Step 7: Run the tests**

Run: `mvn -q test -Dtest='OutboxTest,RunLifecycleServiceTest,RunReaperTest,AgentCallback*Test,ApprovalGateTest,EndToEnd*Test'`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat(events): transactional outbox with per-consumer retries, and the workspace activity feed"
```

---

### Task 3: Named approvers and a database-checked gate decision

**Files:**
- Create: `db/migration/V24__connection_approver.sql`, `execution/DecisionChannel.java`, `execution/GateDecisionService.java`
- Modify: `execution/Connection.java`, `execution/ConnectionResponse.java`, `execution/ConnectionController.java`, `execution/RunController.java`
- Test: create `execution/GateDecisionTest.java`; modify `execution/ApprovalGateTest.java`

**Interfaces:**
- Consumes: `RunLifecycleService.approveGate/rejectGate` (WS0), `OrgMembershipRepository`, `OrgMembershipId`, `IdentityFixtures` (WS13), `DomainEventPublisher` (Task 2).
- Produces:
  - `Connection.getApproverUserIds(): Set<UUID>`, `setApproverUserIds(Set<UUID>)`.
  - `enum DecisionChannel { WEB }` (WS14 adds `EMAIL_LINK`, `SLACK_LINK`).
  - `GateDecisionService.decide(UUID runId, UUID userId, boolean approve, DecisionChannel channel): Run`. Throws 404 for an unknown run, 403 `NOT_AN_APPROVER` for anyone not eligible, and 409 when nothing is waiting.
  - `GateDecisionService.isEligible(Connection connection, UUID userId): boolean`, `eligibleApprovers(Connection): List<OrgMembership>` (WS14 uses it to pick recipients).
  - `GateDecisionService.validateApprovers(UUID orgId, Collection<UUID> userIds)` (400 `INVALID_APPROVER`).
  - `PATCH /api/v1/connections/{id}` now also accepts `approverUserIds`. `ConnectionResponse.approverUserIds` is filled.
  - `GATE_DECIDED` event with actor = decider and payload `{agentName, connectionId, decision: APPROVED|REJECTED, channel}`.
  - `POST /api/v1/runs/{id}/approve|reject` now allows `BUYER_ADMIN` and `BUYER_APPROVER`, subject to the eligibility rule.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/GateDecisionTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class GateDecisionTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    OrgMembershipRepository membershipRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(OrgMembership m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(m));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email() {
        return "gate-" + UUID.randomUUID() + "@acme.example";
    }

    record Setup(ExecutionFixtures.Scenario scenario, Run run, OrgMembership admin, OrgMembership priya,
                 OrgMembership arjun) {}

    Setup awaiting() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership admin = identity.member(s.buyer(), email(), UserRole.BUYER_ADMIN, "Finance lead");
        OrgMembership priya = identity.member(s.buyer(), email(), UserRole.BUYER_APPROVER, null);
        OrgMembership arjun = identity.member(s.buyer(), email(), UserRole.BUYER_APPROVER, null);
        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("summary", "Post entries"));
        return new Setup(s, run, admin, priya, arjun);
    }

    ResponseEntity<Map> approve(Setup setup, OrgMembership who) {
        return rest.exchange(url("/api/v1/runs/" + setup.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("ok"), bearer(who)), Map.class);
    }

    void nameApprovers(Setup setup, OrgMembership... approvers) {
        Connection c = connectionRepository.findById(setup.scenario().connection().getId()).orElseThrow();
        Set<UUID> ids = new HashSet<>();
        for (OrgMembership m : approvers) ids.add(m.getUser().getId());
        c.setApproverUserIds(ids);
        connectionRepository.save(c);
    }

    @Test
    void withNoNamedApproversAnyAdminOrApproverMayDecide() {
        Setup setup = awaiting();
        assertThat(approve(setup, setup.admin()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void namedApproversAreTheOnlyOnesWhoMayDecide() {
        Setup setup = awaiting();
        nameApprovers(setup, setup.priya());

        var arjun = approve(setup, setup.arjun());
        assertThat(arjun.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(arjun.getBody().get("code")).isEqualTo("NOT_AN_APPROVER");
        assertThat(approve(setup, setup.priya()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ifEveryNamedApproverLeftTheRemainingTeamCanStillDecide() {
        Setup setup = awaiting();
        nameApprovers(setup, setup.priya());
        membershipRepository.delete(setup.priya());

        assertThat(approve(setup, setup.priya()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(approve(setup, setup.arjun()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aTokenForARemovedMemberIsRefusedEvenThoughItStillCarriesTheRole() {
        Setup setup = awaiting();
        HttpHeaders stale = bearer(setup.arjun());
        membershipRepository.delete(setup.arjun());

        var response = rest.exchange(url("/api/v1/runs/" + setup.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("ok"), stale), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approversMustBeApproverCapableMembersOfTheWorkspace() {
        Setup setup = awaiting();
        OrgMembership outsider = identity.buyerAdmin(email());
        String path = url("/api/v1/connections/" + setup.scenario().connection().getId());

        var bad = rest.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("approverUserIds", List.of(outsider.getUser().getId())), bearer(setup.admin())),
                Map.class);
        var good = rest.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("approverUserIds", List.of(setup.priya().getUser().getId())),
                        bearer(setup.admin())), Map.class);

        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_APPROVER");
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) good.getBody().get("approverUserIds"))
                .containsExactly(setup.priya().getUser().getId().toString());
    }
}
```

In `ApprovalGateTest.java`, autowire `@Autowired IdentityFixtures identity;` (import `com.agentstore.platform.identity.IdentityFixtures` is covered by the existing `identity.*` import) and replace the body of `approverHeaders(UUID orgId)` with a real member of that org:

```java
    HttpHeaders approverHeaders(UUID orgId) {
        Org org = orgRepository.findById(orgId).orElseThrow();
        var approver = identity.member(org, "approver-" + UUID.randomUUID() + "@acme.example",
                UserRole.BUYER_APPROVER, null);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(approver));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
```

`approverFromAnotherOrgIsForbidden` keeps passing: the intruder is a real approver, but in a different org.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=GateDecisionTest`
Expected: compilation failure (`setApproverUserIds` not found).

- [ ] **Step 3: Migration and entity**

`backend/src/main/resources/db/migration/V24__connection_approver.sql`:

```sql
CREATE TABLE connection_approver (
    connection_id UUID NOT NULL REFERENCES connection(id),
    user_id       UUID NOT NULL REFERENCES app_user(id),
    PRIMARY KEY (connection_id, user_id)
);
```

In `execution/Connection.java` add (imports `java.util.HashSet`, `java.util.Set`):

```java
    /** "Who can approve its writes". Empty means any admin or approver of the workspace. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "connection_approver", joinColumns = @JoinColumn(name = "connection_id"))
    @Column(name = "user_id")
    private Set<UUID> approverUserIds = new HashSet<>();
```

In `ConnectionResponse.from(Connection c, UUID firstRunId)`, replace `List.of()` with `c.getApproverUserIds().stream().sorted().toList()`.

- [ ] **Step 4: `GateDecisionService`**

`execution/DecisionChannel.java`:

```java
package com.agentstore.platform.execution;

/** Where a gate decision was made. WS14 adds the one-tap link channels. */
public enum DecisionChannel { WEB }
```

`execution/GateDecisionService.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.common.ApiProblemException;
import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.events.DomainEventType;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Who may approve or reject a waiting run, checked against the database rather than the token's role, so a
 * removed teammate's still-valid token cannot decide.
 */
@Service
public class GateDecisionService {

    private static final Set<UserRole> APPROVER_ROLES = EnumSet.of(UserRole.BUYER_ADMIN, UserRole.BUYER_APPROVER);

    private final RunRepository runRepository;
    private final RunLifecycleService lifecycle;
    private final OrgMembershipRepository membershipRepository;
    private final DomainEventPublisher eventPublisher;

    public GateDecisionService(RunRepository runRepository, RunLifecycleService lifecycle,
                               OrgMembershipRepository membershipRepository, DomainEventPublisher eventPublisher) {
        this.runRepository = runRepository;
        this.lifecycle = lifecycle;
        this.membershipRepository = membershipRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Run decide(UUID runId, UUID userId, boolean approve, DecisionChannel channel) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (!isEligible(run.getConnection(), userId)) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "NOT_AN_APPROVER",
                    "You are not one of the approvers for this agent");
        }
        Run after = approve ? lifecycle.approveGate(runId) : lifecycle.rejectGate(runId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("agentName", run.getConnection().getAgent().getName());
        payload.put("connectionId", run.getConnection().getId().toString());
        payload.put("decision", approve ? "APPROVED" : "REJECTED");
        payload.put("channel", channel.name());
        eventPublisher.publish(DomainEventType.GATE_DECIDED, run.getConnection().getBuyerOrg().getId(), "RUN",
                runId, userId, payload);
        return after;
    }

    public boolean isEligible(Connection connection, UUID userId) {
        return eligibleApprovers(connection).stream().anyMatch(m -> m.getUser().getId().equals(userId));
    }

    /** Named approvers who still qualify; if none do, every admin and approver of the workspace. */
    public List<OrgMembership> eligibleApprovers(Connection connection) {
        UUID orgId = connection.getBuyerOrg().getId();
        List<OrgMembership> qualified = membershipRepository.findByIdOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(m -> APPROVER_ROLES.contains(m.getRole()))
                .toList();
        List<OrgMembership> named = qualified.stream()
                .filter(m -> connection.getApproverUserIds().contains(m.getUser().getId()))
                .toList();
        return named.isEmpty() ? qualified : named;
    }

    public void validateApprovers(UUID orgId, Collection<UUID> userIds) {
        for (UUID userId : userIds) {
            boolean ok = membershipRepository.findById(new OrgMembershipId(userId, orgId))
                    .filter(m -> APPROVER_ROLES.contains(m.getRole()))
                    .isPresent();
            if (!ok) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_APPROVER",
                        "Approvers must be admins or approvers of this workspace");
            }
        }
    }
}
```

- [ ] **Step 5: Wire the controllers**

In `RunController`, replace the `approve` and `reject` endpoints and delete `requireOwnedByCaller` (add dependency `GateDecisionService gateDecisionService`; imports `com.agentstore.platform.identity.MeController`):

```java
    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse approve(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        return RunResponse.from(gateDecisionService.decide(runId, MeController.currentClaims().userId(), true,
                DecisionChannel.WEB));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse reject(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        return RunResponse.from(gateDecisionService.decide(runId, MeController.currentClaims().userId(), false,
                DecisionChannel.WEB));
    }
```

In `ConnectionController.update` (Task 1), add dependency `GateDecisionService gateDecisionService` and, before the save:

```java
        if (request.approverUserIds() != null) {
            gateDecisionService.validateApprovers(connection.getBuyerOrg().getId(), request.approverUserIds());
            connection.setApproverUserIds(new java.util.HashSet<>(request.approverUserIds()));
        }
```

- [ ] **Step 6: Run the tests**

Run: `mvn -q test -Dtest='GateDecisionTest,ApprovalGateTest,EndToEndTierBTest,OutboxTest,SpendLimitTest'`
Expected: all PASS. `EndToEndTierBTest` approves with the invited approver from WS13, who is a real member.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(execution): named approvers per agent, with gate decisions checked against current membership"
```

---

### Task 4: Spend alerts at 25, 50, 75 and 100 percent

**Files:**
- Create: `db/migration/V25__spend_alert.sql`, `ledger/SpendAlertService.java`
- Modify: `ledger/LedgerService.java`
- Test: create `ledger/SpendAlertTest.java`

**Interfaces:**
- Consumes: `LedgerEntryRepository.netChargedSince`, `BillingClock`, `Org.getMonthlySpendLimit` (Task 1); `DomainEventPublisher` (Task 2).
- Produces:
  - `SpendAlertService.afterCharge(UUID orgId)`, called by `LedgerService.chargeUsageEvent` in the same transaction.
  - `SPEND_THRESHOLD_CROSSED` event, `subjectType = "ORG"`, payload `{threshold: 50, spent: "600.00", limit: "1000.00", month: "2026-09"}`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/ledger/SpendAlertTest.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.DomainEventType;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.OrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpendAlertTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    UsageEventRepository usageEventRepository;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    JdbcTemplate jdbc;

    List<String> thresholdsAlerted(UUID orgId) {
        return jdbc.queryForList("select payload->>'threshold' from domain_event where org_id = ? and type = ? "
                + "order by created_at", String.class, orgId, DomainEventType.SPEND_THRESHOLD_CROSSED.name());
    }

    void charge(UUID orgId, Run run, String amount) {
        UsageEvent e = usageEventRepository.save(new UsageEvent(null, run.getId(), orgId, BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount)));
        ledgerService.chargeUsageEvent(e.getId());
    }

    @Test
    void onlyTheHighestNewlyCrossedThresholdIsAlertedAndNeverRepeated() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("100000.00"));
        var org = s.buyer();
        org.setMonthlySpendLimit(new BigDecimal("1000.00"));
        orgRepository.save(org);
        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());

        charge(org.getId(), run, "200.00");   // 20%: nothing
        charge(org.getId(), run, "400.00");   // 60%: crosses 25 and 50 → one alert, 50
        charge(org.getId(), run, "10.00");    // 61%: nothing new
        charge(org.getId(), run, "400.00");   // 101%: crosses 75 and 100 → one alert, 100

        assertThat(thresholdsAlerted(org.getId())).containsExactly("50", "100");
    }

    @Test
    void workspacesWithoutALimitGetNoAlerts() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("100000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());

        charge(s.buyer().getId(), run, "5000.00");

        assertThat(thresholdsAlerted(s.buyer().getId())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q test -Dtest=SpendAlertTest`
Expected: FAIL (`expected ["50", "100"] but was []`).

- [ ] **Step 3: Migration and service**

`backend/src/main/resources/db/migration/V25__spend_alert.sql`:

```sql
CREATE TABLE spend_alert (
    org_id     UUID        NOT NULL REFERENCES org(id),
    period     CHAR(7)     NOT NULL,        -- 2026-09
    threshold  INT         NOT NULL,        -- 25, 50, 75, 100
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, period, threshold)
);
```

`ledger/SpendAlertService.java`:

```java
package com.agentstore.platform.ledger;

import com.agentstore.platform.common.BillingClock;
import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.events.DomainEventType;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class SpendAlertService {

    static final int[] THRESHOLDS = {25, 50, 75, 100};

    private final OrgRepository orgRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final JdbcTemplate jdbc;
    private final DomainEventPublisher eventPublisher;
    private final BillingClock billingClock;

    public SpendAlertService(OrgRepository orgRepository, LedgerEntryRepository ledgerEntryRepository,
                             JdbcTemplate jdbc, DomainEventPublisher eventPublisher, BillingClock billingClock) {
        this.orgRepository = orgRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.jdbc = jdbc;
        this.eventPublisher = eventPublisher;
        this.billingClock = billingClock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void afterCharge(UUID orgId) {
        BigDecimal limit = orgRepository.findById(orgId).map(Org::getMonthlySpendLimit).orElse(null);
        if (limit == null || limit.signum() <= 0) return;
        BigDecimal spent = ledgerEntryRepository.netChargedSince(orgId, billingClock.monthStart());
        String period = billingClock.currentMonth().toString();

        int highestNew = 0;
        for (int threshold : THRESHOLDS) {
            boolean crossed = spent.multiply(BigDecimal.valueOf(100))
                    .compareTo(limit.multiply(BigDecimal.valueOf(threshold))) >= 0;
            if (crossed && recordIfNew(orgId, period, threshold)) {
                highestNew = threshold;
            }
        }
        if (highestNew > 0) {
            eventPublisher.publish(DomainEventType.SPEND_THRESHOLD_CROSSED, orgId, "ORG", orgId, null, Map.of(
                    "threshold", highestNew, "spent", spent.toPlainString(), "limit", limit.toPlainString(),
                    "month", period));
        }
    }

    /** The primary key (org, month, threshold) makes each threshold fire once per org per month. */
    private boolean recordIfNew(UUID orgId, String period, int threshold) {
        return jdbc.update("insert into spend_alert (org_id, period, threshold) values (?, ?, ?) on conflict do nothing",
                orgId, period, threshold) == 1;
    }
}
```

- [ ] **Step 4: Call it after each charge**

In `ledger/LedgerService.java` add the constructor dependency `SpendAlertService spendAlertService`, and in `chargeUsageEvent`, just before `return entry;`, add:

```java
        spendAlertService.afterCharge(event.getOrgId());
```

`JdbcTemplate` uses the same `DataSource` and joins the Spring-managed transaction, so the `spend_alert` insert commits or rolls back with the charge. `chargeUsageEvent` is `@Transactional`, and in production it runs inside the run lifecycle's transaction, so the ledger entry, the wallet debit and the alert commit together.

- [ ] **Step 5: Run the tests**

Run: `mvn -q test -Dtest='SpendAlertTest,LedgerService*Test,StatementControllerTest,AgentCallback*Test,PlatformApplicationTest'`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(ledger): alert once per month when workspace spend crosses 25, 50, 75 and 100 percent"
```

---

### Task 5: Connect with limits and approvers, and "Add and run now", in one step

**Files:**
- Create: `execution/RunTriggerService.java`, `execution/ConnectionSetupService.java`
- Modify: `execution/RunController.java`, `execution/ConnectionController.java`, `execution/CreateConnectionRequest.java`
- Test: create `execution/ConnectionSetupTest.java`

**Interfaces:**
- Consumes: `SpendLimitGuard` (Task 1), `GateDecisionService.validateApprovers` (Task 3), `DomainEventPublisher` (Task 2), `RunLifecycleService.markStarted/terminate` (WS0), `RunTokenService` (WS0).
- Produces:
  - `RunTriggerService.SANDBOX_TIMEOUT_SECONDS = 900`.
  - `RunTriggerService.prepare(Connection): PreparedRun` (`@Transactional`, joins the caller's transaction).
  - `record PreparedRun(UUID runId, String rawToken)`.
  - `RunTriggerService.launch(PreparedRun): Run` (no transaction; 502 on sandbox failure, with the run `FAILED` and its reservation released).
  - `RunTriggerService.prepareForCaller(UUID connectionId, UUID callerOrgId): PreparedRun` (`@Transactional`; 404 for another org's connection).
  - Callers always invoke `prepare…` and `launch` as two separate calls on the Spring bean. A method calling its own `@Transactional` method bypasses the proxy and runs without a transaction.
  - `CreateConnectionRequest(UUID agentId, List<String> grantedScopes, BigDecimal spendCap, BigDecimal monthlyLimit, List<UUID> approverUserIds, List<NotifyChannel> notifyChannels, boolean runNow)`, plus the old 3-argument constructor.
  - `ConnectionSetupService.create(CreateConnectionRequest, UUID orgId, UUID userId): Created(Connection connection, PreparedRun firstRun)`.
  - `CONNECTION_CREATED` event with actor = creator and payload `{agentName, connectionId}`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/ConnectionSetupTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSetupTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(OrgMembership m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(m));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    String email() {
        return "setup-" + UUID.randomUUID() + "@acme.example";
    }

    @Test
    void addAndRunNowCreatesTheConnectionWithItsSettingsAndStartsTheFirstRun() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        OrgMembership admin = identity.member(s.buyer(), email(), UserRole.BUYER_ADMIN, null);
        OrgMembership approver = identity.member(s.buyer(), email(), UserRole.BUYER_APPROVER, null);

        var response = rest.exchange(url("/api/v1/connections"), HttpMethod.POST, new HttpEntity<>(
                new CreateConnectionRequest(s.connection().getAgent().getId(), List.of("bank_statement"),
                        new BigDecimal("250.00"), new BigDecimal("3000.00"), List.of(approver.getUser().getId()),
                        List.of(NotifyChannel.EMAIL), true), bearer(admin)), ConnectionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ConnectionResponse body = response.getBody();
        assertThat(body.monthlyLimit()).isEqualByComparingTo("3000");
        assertThat(body.approverUserIds()).containsExactly(approver.getUser().getId());
        assertThat(body.firstRunId()).isNotNull();
        assertThat(runRepository.findById(body.firstRunId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void whenTheFirstRunCannotBeFundedNothingIsCreated() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("100.00"));   // wallet < cap
        OrgMembership admin = identity.member(s.buyer(), email(), UserRole.BUYER_ADMIN, null);
        long before = connectionRepository.findByBuyerOrgId(s.buyer().getId()).size();

        var response = rest.exchange(url("/api/v1/connections"), HttpMethod.POST, new HttpEntity<>(
                new CreateConnectionRequest(s.connection().getAgent().getId(), List.of("bank_statement"),
                        new BigDecimal("250.00"), null, null, null, true), bearer(admin)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(connectionRepository.findByBuyerOrgId(s.buyer().getId())).hasSize((int) before);
        assertThat(walletRepository.findByOrgId(s.buyer().getId()).orElseThrow().getReserved())
                .isEqualByComparingTo("0");
    }

    @Test
    void anInvalidApproverMeansNothingIsCreated() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        OrgMembership admin = identity.member(s.buyer(), email(), UserRole.BUYER_ADMIN, null);
        long before = connectionRepository.findByBuyerOrgId(s.buyer().getId()).size();

        var response = rest.exchange(url("/api/v1/connections"), HttpMethod.POST, new HttpEntity<>(
                new CreateConnectionRequest(s.connection().getAgent().getId(), List.of("bank_statement"),
                        new BigDecimal("250.00"), null, List.of(UUID.randomUUID()), null, false), bearer(admin)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(connectionRepository.findByBuyerOrgId(s.buyer().getId())).hasSize((int) before);
    }

    @Test
    void nobodyCanTriggerARunOnAnotherWorkspacesConnection() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        OrgMembership stranger = identity.buyerAdmin(email());

        var response = rest.exchange(url("/api/v1/runs"), HttpMethod.POST,
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), bearer(stranger)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=ConnectionSetupTest`
Expected: compilation failure (the 7-argument `CreateConnectionRequest` constructor does not exist).

- [ ] **Step 3: Request shape**

Replace `execution/CreateConnectionRequest.java`:

```java
package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The "Plug in data and set limits" step. {@code runNow} is "Add and run now". Dataset bindings join this
 * request in WS2.
 */
public record CreateConnectionRequest(@NotNull UUID agentId, @NotEmpty List<String> grantedScopes,
                                      @NotNull BigDecimal spendCap, BigDecimal monthlyLimit,
                                      List<UUID> approverUserIds, List<NotifyChannel> notifyChannels,
                                      boolean runNow) {

    public CreateConnectionRequest(UUID agentId, List<String> grantedScopes, BigDecimal spendCap) {
        this(agentId, grantedScopes, spendCap, null, null, null, false);
    }
}
```

- [ ] **Step 4: `RunTriggerService` (moved out of `RunController`)**

`execution/RunTriggerService.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Starting a run, in two halves. {@link #prepare} does every check and the reservation and writes the PENDING run
 * inside the caller's transaction. {@link #launch} starts the sandbox after that transaction has committed.
 */
@Service
public class RunTriggerService {

    public static final int SANDBOX_TIMEOUT_SECONDS = 900;

    public record PreparedRun(UUID runId, String rawToken) {}

    private final ConnectionRepository connectionRepository;
    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final SpendLimitGuard spendLimitGuard;
    private final RunLifecycleService runLifecycleService;
    private final String callbackBaseUrl;

    public RunTriggerService(ConnectionRepository connectionRepository, RunRepository runRepository,
                             SecretRepository secretRepository, SecretService secretService,
                             ManifestParser manifestParser, SandboxProvider sandboxProvider,
                             RunTokenService runTokenService, SpendLimitGuard spendLimitGuard,
                             RunLifecycleService runLifecycleService,
                             @Value("${agentstore.callback.base-url}") String callbackBaseUrl) {
        this.connectionRepository = connectionRepository;
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.spendLimitGuard = spendLimitGuard;
        this.runLifecycleService = runLifecycleService;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    @Transactional
    public PreparedRun prepareForCaller(UUID connectionId, UUID callerOrgId) {
        Connection connection = connectionRepository.findById(connectionId)
                .filter(c -> c.getBuyerOrg().getId().equals(callerOrgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        return prepare(connection);
    }

    @Transactional
    public PreparedRun prepare(Connection connection) {
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        AgentVersion version = connection.getAgentVersion();
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Connection is not pinned to an agent version; reconnect to the agent");
        }
        if (version.getStatus() != VersionStatus.LIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned agent version is no longer LIVE");
        }

        spendLimitGuard.reserveForRun(connection);

        String rawToken = runTokenService.generate();
        Instant now = Instant.now();
        BigDecimal spendCap = connection.getSpendCap();
        Run pending = new Run(null, connection, version, RunStatus.PENDING, spendCap, BigDecimal.ZERO, null,
                RunTokenService.hash(rawToken));
        pending.setReservedAmount(spendCap);
        pending.setStartedAt(now);
        pending.setDeadlineAt(now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        return new PreparedRun(runRepository.save(pending).getId(), rawToken);
    }

    /** Call only after {@link #prepare}'s transaction has committed. */
    public Run launch(PreparedRun prepared) {
        Run run = runRepository.findById(prepared.runId()).orElseThrow();
        Connection connection = run.getConnection();
        AgentVersion version = run.getAgentVersion();
        var manifest = manifestParser.parse(version.getManifestJson());

        Map<String, String> envVars = new HashMap<>();
        envVars.put("AGENT_RUN_ID", run.getId().toString());
        envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + run.getId());
        envVars.put("AGENT_CALLBACK_TOKEN", prepared.rawToken());
        for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
            envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
        }

        List<String> allowOut = new ArrayList<>();
        allowOut.add(URI.create(callbackBaseUrl).getHost());
        allowOut.addAll(manifest.networkAllowlist());

        SandboxHandle handle;
        try {
            handle = sandboxProvider.create(new SandboxSpec(version.getImageRef(), SANDBOX_TIMEOUT_SECONDS, envVars,
                    allowOut));
        } catch (RuntimeException e) {
            runLifecycleService.terminate(run.getId(), RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Sandbox could not be started"));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sandbox could not be started", e);
        }
        runLifecycleService.markStarted(run.getId(), handle.sandboxId());
        return runRepository.findById(run.getId()).orElseThrow();
    }
}
```

This is WS0's trigger code, moved as is. There are two changes:
- The caller's org is now checked (Review Focus 1).
- The reservation and the PENDING run commit together, so the manual `release` on a failed save is gone. A rollback undoes both.

Replace `RunController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.identity.MeController;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    static final int SANDBOX_TIMEOUT_SECONDS = RunTriggerService.SANDBOX_TIMEOUT_SECONDS;

    private final RunTriggerService runTriggerService;
    private final GateDecisionService gateDecisionService;

    public RunController(RunTriggerService runTriggerService, GateDecisionService gateDecisionService) {
        this.runTriggerService = runTriggerService;
        this.gateDecisionService = gateDecisionService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        // Two calls through the proxy: prepare commits its transaction before the sandbox starts.
        var prepared = runTriggerService.prepareForCaller(request.connectionId(), MeController.currentClaims().orgId());
        return RunResponse.from(runTriggerService.launch(prepared));
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse approve(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        return RunResponse.from(gateDecisionService.decide(runId, MeController.currentClaims().userId(), true,
                DecisionChannel.WEB));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse reject(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        return RunResponse.from(gateDecisionService.decide(runId, MeController.currentClaims().userId(), false,
                DecisionChannel.WEB));
    }
}
```

- [ ] **Step 5: `ConnectionSetupService` and the create endpoint**

`execution/ConnectionSetupService.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.events.DomainEventType;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Creates a connection with its limits, approvers and (optionally) its first PENDING run in one transaction. */
@Service
public class ConnectionSetupService {

    public record Created(Connection connection, RunTriggerService.PreparedRun firstRun) {}

    private final ConnectionRepository connectionRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final OrgRepository orgRepository;
    private final ManifestParser manifestParser;
    private final GateDecisionService gateDecisionService;
    private final RunTriggerService runTriggerService;
    private final DomainEventPublisher eventPublisher;

    public ConnectionSetupService(ConnectionRepository connectionRepository, AgentRepository agentRepository,
                                  AgentVersionRepository agentVersionRepository, OrgRepository orgRepository,
                                  ManifestParser manifestParser, GateDecisionService gateDecisionService,
                                  RunTriggerService runTriggerService, DomainEventPublisher eventPublisher) {
        this.connectionRepository = connectionRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestParser = manifestParser;
        this.gateDecisionService = gateDecisionService;
        this.runTriggerService = runTriggerService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Created create(CreateConnectionRequest request, UUID orgId, UUID userId) {
        Agent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        var liveVersion = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(agent.getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));

        var manifest = manifestParser.parse(liveVersion.getManifestJson());
        Set<String> declared = manifest.scopes().stream().map(Scope::resource).collect(Collectors.toSet());
        for (String requested : request.grantedScopes()) {
            if (!declared.contains(requested)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scope not declared by manifest: " + requested);
            }
        }
        SpendLimitGuard.validateSpendCap(request.spendCap(), manifest.costEnvelope().maxPerRun());
        if (request.monthlyLimit() != null && request.monthlyLimit().signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monthly limit cannot be negative");
        }
        if (request.approverUserIds() != null) {
            gateDecisionService.validateApprovers(orgId, request.approverUserIds());
        }

        Org buyerOrg = orgRepository.getReferenceById(orgId);
        Connection connection = new Connection(null, buyerOrg, agent, request.grantedScopes(), request.spendCap(),
                ConnectionStatus.ACTIVE);
        connection.setAgentVersion(liveVersion);
        connection.setMonthlyLimit(request.monthlyLimit());
        if (request.notifyChannels() != null) connection.setNotifyChannels(request.notifyChannels());
        if (request.approverUserIds() != null) connection.setApproverUserIds(new HashSet<>(request.approverUserIds()));
        connection = connectionRepository.save(connection);

        eventPublisher.publish(DomainEventType.CONNECTION_CREATED, orgId, "CONNECTION", connection.getId(), userId,
                Map.of("agentName", agent.getName(), "connectionId", connection.getId().toString()));

        RunTriggerService.PreparedRun firstRun = request.runNow() ? runTriggerService.prepare(connection) : null;
        return new Created(connection, firstRun);
    }
}
```

In `ConnectionController`, replace the body of `create` (keep its annotations) with:

```java
        var claims = MeController.currentClaims();
        var created = connectionSetupService.create(request, claims.orgId(), claims.userId());
        UUID firstRunId = null;
        if (created.firstRun() != null) {
            firstRunId = runTriggerService.launch(created.firstRun()).getId();
        }
        return ConnectionResponse.from(connectionRepository.findById(created.connection().getId()).orElseThrow(),
                firstRunId);
```

Add the dependencies `ConnectionSetupService connectionSetupService` and `RunTriggerService runTriggerService`. Remove `AgentRepository`, `AgentVersionRepository` and `OrgRepository` from `ConnectionController` if nothing else in it uses them (`update` uses `manifestParser`, `connectionRepository` and `gateDecisionService`).

Tokens minted in tests with a random user id still create connections. The `CONNECTION_CREATED` actor is then an id with no user row, which the activity feed shows as "Someone".

If the sandbox fails after the connection committed, the connection stays and the first run is `FAILED` with its reservation released. That is a platform fault the buyer can retry. Spending and approver refusals happen before anything commits, so nothing is left behind.

- [ ] **Step 6: Run the tests**

Run: `mvn -q test -Dtest='ConnectionSetupTest,ConnectionControllerTest,SpendLimitTest,GateDecisionTest,RunController*Test,RunVersionPinningTest,EndToEnd*Test'`
Expected: all PASS. `RunControllerTest` still reads `RunController.SANDBOX_TIMEOUT_SECONDS`.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(execution): connect with limits and approvers and start the first run in one step"
```

---

### Task 6: Workspace home in one call

**Files:**
- Create: `execution/WorkspaceHomeResponse.java`, `execution/WorkspaceHomeController.java`
- Modify: `execution/RunRepository.java`
- Test: create `execution/WorkspaceHomeTest.java`

**Interfaces:**
- Consumes: `GateDecisionService.isEligible` (Task 3), `ActivityEventRepository` (Task 2), `LedgerEntryRepository.netChargedSince`, `BillingClock` (Task 1), `WalletRepository` (WS0 `reserved`), `UserRepository` (WS13).
- Produces: `GET /api/v1/orgs/{orgId}/home` (BUYER_ADMIN or BUYER_APPROVER of that org) → `WorkspaceHomeResponse` with:
  - `needsYou[]`: `{runId, agentName, connectionId, waitingSince, expiresAt, summary, canDecide}`
  - `workingNow[]`: `{runId, agentName, status, startedAt, charged, spendCap}`
  - `agents[]`: `{connectionId, agentId, agentName, publisher, version, reads, writes, writesNeedApproval, runsThisMonth, spendThisMonth}`
  - `spend`: `{monthToDate, monthlyLimit}`
  - `wallet`: `{balance, held}`
  - `exposure`: `{resourcesRead, writeScopes, outsideHosts[]}`
  - `recent[]`: `{kind, actorName, payload, at}`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/WorkspaceHomeTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.OutboxDispatcher;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceHomeTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    OutboxDispatcher dispatcher;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(OrgMembership m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(m));
        return headers;
    }

    @Test
    void homeShowsWhatNeedsYouWhatIsRunningYourAgentsAndMoney() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("87520.00"));
        OrgMembership priya = identity.member(s.buyer(), "home-" + UUID.randomUUID() + "@acme.example",
                UserRole.BUYER_ADMIN, "Finance lead");
        Run waiting = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(waiting.getId(), RunStepKind.GATE, Map.of("summary", "Post 142 journal entries"));
        Run working = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        dispatcher.dispatchOnce();

        var home = rest.exchange(url("/api/v1/orgs/" + s.buyer().getId() + "/home"), HttpMethod.GET,
                new HttpEntity<>(bearer(priya)), WorkspaceHomeResponse.class).getBody();

        assertThat(home.needsYou()).singleElement().satisfies(n -> {
            assertThat(n.runId()).isEqualTo(waiting.getId());
            assertThat(n.summary()).isEqualTo("Post 142 journal entries");
            assertThat(n.canDecide()).isTrue();
            assertThat(Duration.between(n.waitingSince(), n.expiresAt())).isEqualTo(Duration.ofMinutes(30));
        });
        assertThat(home.workingNow()).extracting(WorkspaceHomeResponse.Working::runId).containsExactly(working.getId());
        assertThat(home.agents()).singleElement().satisfies(a -> {
            assertThat(a.agentName()).isEqualTo("Bank Reconciliation");
            assertThat(a.publisher()).isEqualTo("DevCo");
            assertThat(a.version()).isEqualTo("1.0.0");
            assertThat(a.reads()).isEqualTo(1);
            assertThat(a.writes()).isZero();
            assertThat(a.runsThisMonth()).isEqualTo(2);
        });
        assertThat(home.wallet().balance()).isEqualByComparingTo("87520");
        assertThat(home.exposure().outsideHosts()).isEmpty();
        assertThat(home.recent()).extracting(WorkspaceHomeResponse.Recent::kind).contains("GATE_OPENED");
    }

    @Test
    void anotherWorkspaceCannotReadYourHome() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership stranger = identity.buyerAdmin("stranger-" + UUID.randomUUID() + "@acme.example");

        var response = rest.exchange(url("/api/v1/orgs/" + s.buyer().getId() + "/home"), HttpMethod.GET,
                new HttpEntity<>(bearer(stranger)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q test -Dtest=WorkspaceHomeTest`
Expected: compilation failure (`WorkspaceHomeResponse` not found).

- [ ] **Step 3: Queries**

In `execution/RunRepository.java` add:

```java
    List<Run> findByConnectionBuyerOrgIdAndStatusInOrderByStartedAtDesc(UUID buyerOrgId, Collection<RunStatus> statuses);

    interface ConnectionMonth {
        UUID getConnectionId();
        long getRuns();
        BigDecimal getSpend();
    }

    @Query(value = """
            select r.connection_id as connectionId, count(*) as runs, coalesce(sum(r.total_charged), 0) as spend
            from run r join connection c on c.id = r.connection_id
            where c.buyer_org_id = :orgId and r.started_at >= :from
            group by r.connection_id
            """, nativeQuery = true)
    List<ConnectionMonth> monthByConnection(@Param("orgId") UUID orgId, @Param("from") java.time.Instant from);
```

and in `execution/RunStepRepository.java`:

```java
    java.util.Optional<RunStep> findFirstByRunIdAndKindOrderByCreatedAtDesc(UUID runId, RunStepKind kind);
```

- [ ] **Step 4: Response and controller**

`execution/WorkspaceHomeResponse.java`:

```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkspaceHomeResponse(List<NeedsYou> needsYou, List<Working> workingNow, List<AgentRow> agents,
                                    Spend spend, WalletView wallet, Exposure exposure, List<Recent> recent) {

    public record NeedsYou(UUID runId, String agentName, UUID connectionId, Instant waitingSince, Instant expiresAt,
                           String summary, boolean canDecide) {}

    public record Working(UUID runId, String agentName, RunStatus status, Instant startedAt, BigDecimal charged,
                          BigDecimal spendCap) {}

    public record AgentRow(UUID connectionId, UUID agentId, String agentName, String publisher, String version,
                           int reads, int writes, boolean writesNeedApproval, long runsThisMonth,
                           BigDecimal spendThisMonth) {}

    public record Spend(BigDecimal monthToDate, BigDecimal monthlyLimit) {}

    public record WalletView(BigDecimal balance, BigDecimal held) {}

    public record Exposure(int resourcesRead, int writeScopes, List<String> outsideHosts) {}

    public record Recent(String kind, String actorName, Map<String, Object> payload, Instant at) {}
}
```

`execution/WorkspaceHomeController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.common.BillingClock;
import com.agentstore.platform.events.ActivityEvent;
import com.agentstore.platform.events.ActivityEventRepository;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.LedgerEntryRepository;
import com.agentstore.platform.ledger.WalletRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ScopeType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
public class WorkspaceHomeController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final ConnectionRepository connectionRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final ActivityEventRepository activityEventRepository;
    private final GateDecisionService gateDecisionService;
    private final ManifestParser manifestParser;
    private final BillingClock billingClock;
    private final Duration gateTimeout;

    public WorkspaceHomeController(RunRepository runRepository, RunStepRepository runStepRepository,
                                   ConnectionRepository connectionRepository, WalletRepository walletRepository,
                                   LedgerEntryRepository ledgerEntryRepository, OrgRepository orgRepository,
                                   UserRepository userRepository, ActivityEventRepository activityEventRepository,
                                   GateDecisionService gateDecisionService, ManifestParser manifestParser,
                                   BillingClock billingClock,
                                   @Value("${agentstore.runs.gate-timeout:PT30M}") Duration gateTimeout) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.connectionRepository = connectionRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.activityEventRepository = activityEventRepository;
        this.gateDecisionService = gateDecisionService;
        this.manifestParser = manifestParser;
        this.billingClock = billingClock;
        this.gateTimeout = gateTimeout;
    }

    @GetMapping("/api/v1/orgs/{orgId}/home")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    @Transactional(readOnly = true)
    public WorkspaceHomeResponse home(@PathVariable UUID orgId) {
        var claims = MeController.currentClaims();
        if (!claims.orgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your workspace");
        }
        var from = billingClock.monthStart();

        var needsYou = runRepository.findByConnectionBuyerOrgIdAndStatusInOrderByStartedAtDesc(orgId,
                        List.of(RunStatus.AWAITING_APPROVAL)).stream()
                .map(run -> new WorkspaceHomeResponse.NeedsYou(run.getId(), run.getConnection().getAgent().getName(),
                        run.getConnection().getId(), run.getAwaitingSince(),
                        run.getAwaitingSince() == null ? null : run.getAwaitingSince().plus(gateTimeout),
                        gateSummary(run), gateDecisionService.isEligible(run.getConnection(), claims.userId())))
                .toList();

        var workingNow = runRepository.findByConnectionBuyerOrgIdAndStatusInOrderByStartedAtDesc(orgId,
                        List.of(RunStatus.PENDING, RunStatus.RUNNING)).stream()
                .map(run -> new WorkspaceHomeResponse.Working(run.getId(), run.getConnection().getAgent().getName(),
                        run.getStatus(), run.getStartedAt(), run.getTotalCharged(), run.getCostCap()))
                .toList();

        Map<UUID, RunRepository.ConnectionMonth> month = runRepository.monthByConnection(orgId, from).stream()
                .collect(Collectors.toMap(RunRepository.ConnectionMonth::getConnectionId, Function.identity()));
        Set<String> resourcesRead = new TreeSet<>();
        Set<String> hosts = new TreeSet<>();
        int writeScopes = 0;
        List<WorkspaceHomeResponse.AgentRow> agents = new ArrayList<>();
        for (Connection c : connectionRepository.findByBuyerOrgId(orgId)) {
            if (c.getStatus() != ConnectionStatus.ACTIVE || c.getAgentVersion() == null) continue;
            var manifest = manifestParser.parse(c.getAgentVersion().getManifestJson());
            Set<String> granted = new HashSet<>(c.getGrantedScopes());
            var grantedScopes = manifest.scopes().stream().filter(sc -> granted.contains(sc.resource())).toList();
            int reads = (int) grantedScopes.stream().filter(sc -> sc.type() == ScopeType.READ).count();
            int writes = grantedScopes.size() - reads;
            grantedScopes.stream().filter(sc -> sc.type() == ScopeType.READ).forEach(sc -> resourcesRead.add(sc.resource()));
            writeScopes += writes;
            hosts.addAll(manifest.networkAllowlist());
            var m = month.get(c.getId());
            agents.add(new WorkspaceHomeResponse.AgentRow(c.getId(), c.getAgent().getId(), c.getAgent().getName(),
                    c.getAgent().getDeveloperOrg().getName(), c.getAgentVersion().getVersionNumber(), reads, writes,
                    writes > 0 && !manifest.approvalGates().isEmpty(), m == null ? 0 : m.getRuns(),
                    m == null ? BigDecimal.ZERO : m.getSpend()));
        }

        var wallet = walletRepository.findByOrgId(orgId)
                .map(w -> new WorkspaceHomeResponse.WalletView(w.getBalance(), w.getReserved()))
                .orElse(new WorkspaceHomeResponse.WalletView(BigDecimal.ZERO, BigDecimal.ZERO));
        var spend = new WorkspaceHomeResponse.Spend(ledgerEntryRepository.netChargedSince(orgId, from),
                orgRepository.findById(orgId).map(Org::getMonthlySpendLimit).orElse(null));

        var recent = activityEventRepository.findTop10ByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(this::recent).toList();

        return new WorkspaceHomeResponse(needsYou, workingNow, agents, spend, wallet,
                new WorkspaceHomeResponse.Exposure(resourcesRead.size(), writeScopes, List.copyOf(hosts)), recent);
    }

    private String gateSummary(Run run) {
        return runStepRepository.findFirstByRunIdAndKindOrderByCreatedAtDesc(run.getId(), RunStepKind.GATE)
                .map(step -> {
                    try {
                        Object summary = MAPPER.readValue(step.getDetail(), new TypeReference<Map<String, Object>>() {})
                                .get("summary");
                        return summary == null ? null : summary.toString();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private WorkspaceHomeResponse.Recent recent(ActivityEvent e) {
        String actor = e.getActorUserId() == null ? null
                : userRepository.findById(e.getActorUserId()).map(User::getFullName).orElse("Someone");
        Map<String, Object> payload;
        try {
            payload = MAPPER.readValue(e.getPayload(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            payload = Map.of();
        }
        return new WorkspaceHomeResponse.Recent(e.getKind(), actor, payload, e.getCreatedAt());
    }
}
```

The home screen's "Writes, each behind approval" count maps to `writesNeedApproval`. Manifest v1 cannot tie a gate to a specific write, so it means "this agent writes, and it declares at least one approval gate". Manifest v2 (`requiresApproval`) makes it exact.

- [ ] **Step 5: Run the test**

Run: `mvn -q test -Dtest=WorkspaceHomeTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(execution): workspace home endpoint with approvals waiting, running work, agents, spend and activity"
```

---

### Task 7: ⌘K search

**Files:**
- Create: `execution/SearchController.java`
- Modify: `catalog/AgentRepository.java`, `execution/RunRepository.java`
- Test: create `execution/SearchTest.java`

**Interfaces:**
- Consumes: `MeController.currentClaims()` (WS13), `ExecutionFixtures` (WS0).
- Produces: `GET /api/v1/search?q=` (authenticated, 2–100 characters) → `SearchController.Results(List<AgentHit> agents, List<RunHit> runs)`:
  - `AgentHit(UUID agentId, String name, String category)` from the LIVE catalog.
  - `RunHit(UUID runId, String agentName, RunStatus status, Instant startedAt)` from the caller's own workspace only.
  - Five of each at most. `%` and `_` in the query match literally.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/SearchTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    JwtService jwtService;

    HttpHeaders buyer(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        return headers;
    }

    SearchController.Results search(String q, UUID orgId) {
        return rest.exchange("http://localhost:" + port + "/api/v1/search?q={q}", HttpMethod.GET,
                new HttpEntity<>(buyer(orgId)), SearchController.Results.class, q).getBody();
    }

    @Test
    void findsLiveAgentsAndOnlyYourOwnRuns() {
        var mine = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        var theirs = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        Run myRun = fixtures.run(mine, RunStatus.COMPLETED, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        Run theirRun = fixtures.run(theirs, RunStatus.COMPLETED, "fake-sandbox-" + UUID.randomUUID(),
                "tok-" + UUID.randomUUID());

        var results = search("reconcil", mine.buyer().getId());

        assertThat(results.agents()).isNotEmpty()
                .allSatisfy(a -> assertThat(a.name() + a.category()).containsIgnoringCase("reconcil"));
        assertThat(results.runs()).extracting(SearchController.RunHit::runId)
                .contains(myRun.getId())
                .doesNotContain(theirRun.getId());
    }

    @Test
    void wildcardCharactersAreMatchedLiterally() {
        var mine = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));

        var results = search("%_", mine.buyer().getId());

        assertThat(results.agents()).isEmpty();
        assertThat(results.runs()).isEmpty();
    }

    @Test
    void tooShortAQueryIsRejected() {
        var response = rest.exchange("http://localhost:" + port + "/api/v1/search?q=a", HttpMethod.GET,
                new HttpEntity<>(buyer(UUID.randomUUID())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q test -Dtest=SearchTest`
Expected: compilation failure (`SearchController` not found).

- [ ] **Step 3: Queries**

In `catalog/AgentRepository.java` add (imports `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`):

```java
    @Query(value = """
            select a.* from agent a
            where exists (select 1 from agent_version v where v.agent_id = a.id and v.status = 'LIVE')
              and (a.name ilike :pattern escape '\\' or a.category ilike :pattern escape '\\')
            order by a.name
            limit 5
            """, nativeQuery = true)
    List<Agent> searchLive(@Param("pattern") String pattern);
```

In `execution/RunRepository.java` add:

```java
    @Query(value = """
            select r.* from run r
            join connection c on c.id = r.connection_id
            join agent a on a.id = c.agent_id
            where c.buyer_org_id = :orgId and a.name ilike :pattern escape '\\'
            order by r.started_at desc nulls last
            limit 5
            """, nativeQuery = true)
    List<Run> searchInOrg(@Param("orgId") UUID orgId, @Param("pattern") String pattern);
```

- [ ] **Step 4: Controller**

`execution/SearchController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.identity.MeController;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The ⌘K bar: plain matching over agents and the caller's runs. "Ask Karya" questions are spec §8 Q9. */
@RestController
public class SearchController {

    private final AgentRepository agentRepository;
    private final RunRepository runRepository;

    public SearchController(AgentRepository agentRepository, RunRepository runRepository) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
    }

    public record AgentHit(UUID agentId, String name, String category) {}

    public record RunHit(UUID runId, String agentName, RunStatus status, Instant startedAt) {}

    public record Results(List<AgentHit> agents, List<RunHit> runs) {}

    @GetMapping("/api/v1/search")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Results search(@RequestParam String q) {
        String query = q.trim();
        if (query.length() < 2 || query.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search needs 2 to 100 characters");
        }
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var agents = agentRepository.searchLive(pattern).stream()
                .map(a -> new AgentHit(a.getId(), a.getName(), a.getCategory())).toList();
        var runs = runRepository.searchInOrg(MeController.currentClaims().orgId(), pattern).stream()
                .map(r -> new RunHit(r.getId(), r.getConnection().getAgent().getName(), r.getStatus(), r.getStartedAt()))
                .toList();
        return new Results(agents, runs);
    }
}
```

- [ ] **Step 5: Run the test**

Run: `mvn -q test -Dtest=SearchTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(execution): workspace search across live agents and your own runs"
```

---

### Task 8: Record decisions in the spec and verify everything

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (WS15 and WS14 sections)

- [ ] **Step 1: Update the spec**

In §5 WS15:
- Change the `activity_event` DDL comment from `-- fed by the WS14 outbox` to `-- projected from the domain_event outbox (introduced here, also consumed by WS14)`, and add the `domain_event` and `domain_event_delivery` tables to the data model.
- Under **Rules at trigger**, add: *"Limit refusals return 409 (`ORG_MONTHLY_LIMIT`, `AGENT_MONTHLY_LIMIT`); an empty wallet returns 402 (`INSUFFICIENT_FUNDS`). A trigger on another workspace's connection returns 404. `spend_cap` must be above 0 and at most the manifest `maxPerRun` (400 `SPEND_CAP_OUT_OF_RANGE`)."*
- Under **Approvers**, replace the rule with the implemented one: *"A decider must currently be an admin or approver of the workspace (checked in the database). If the connection names approvers who still qualify, only they may decide; otherwise any admin or approver may."*
- Add a **Deferred** line: `default_inputs` (WS1), `bindings` (WS2), `reachedBlock` and run display IDs (WS1), pending version consents on home (WS11), AI-provider retention flags (WS3).

In §5 WS14 **Design**, change the first bullet to say the outbox already exists from WS15 and WS14 adds a `notifications` consumer.

- [ ] **Step 2: Full backend suite**

Run (from `backend/`): `mvn -q test`
Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md
git commit -m "docs(spec): record WS15 implementation decisions"
```
