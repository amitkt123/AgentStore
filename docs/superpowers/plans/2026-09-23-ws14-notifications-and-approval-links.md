# WS14 — Notifications and Approvals Anywhere Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Approval requests reach the right approvers within a minute on web, email and Slack. An approver can decide from a phone with a one-tap link. Everything else agents do lands in one notification centre, with per-person channel preferences and quiet hours.

**Architecture:**
- **Recipients.** A new outbox consumer, `notifications` (the outbox comes from WS15), turns domain events into one `notification` row per recipient, in the recipient's workspace. Recipients for approvals come from `GateDecisionService.eligibleApprovers`, the same rule that authorizes the decision.
- **Channels.** Each row records which channels it still owes (email, Slack) and when they are due; quiet hours push that time later. A ShedLock-guarded `ChannelDelivery` job sends what is due.
- **Links.** For approvals, the job mints a per-person, single-use approval link, bound to the specific gate instance (run id + the moment the gate opened), just before it sends.
- **Deciding.** Opening a link only shows the card. Deciding is a POST that goes through `GateDecisionService`, so a link never grants more than the person already has.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (`RestClient` for Slack), Postgres 16 + Flyway, ShedLock 5.16.0, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §5 **WS14** (read it first), §10 rows 6 and 10, §8 Q10 and Q12.

**Prerequisites:**
- WS0 plan merged: `RunLifecycleService`, `Run.awaitingSince`, the `agentstore.runs.gate-timeout` config, `ExecutionFixtures`.
- WS13 plan merged: `EmailSender`, `EmailMessage`, `TokenHasher`, `RecordingEmailSender`, `IdentityFixtures`, `MeController.currentClaims()`.
- WS15 plan merged: `DomainEventPublisher`, `DomainEventConsumer`, `OutboxDispatcher`, `GateDecisionService`, `DecisionChannel`, `Connection.getNotifyChannels()`, `NotifyChannel`, `ApiProblemException`.

## Global Constraints

- Flyway: WS0 `V12`–`V16`, WS13 `V17`–`V21`, WS15 `V22`–`V25`. **This plan uses `V26`–`V29`.** WS16 follows with `V30`–`V31`. Renumber if another plan merges first.
- Categories, exactly: `APPROVAL_NEEDED`, `MORE_ACCESS_REQUESTED`, `RUN_STOPPED_EARLY`, `RUN_FINISHED`, `SPEND_LIMITS`, `WEEKLY_SUMMARY`. The first two are always on for web.

  Default channels (web / email / Slack), per the mockup:

  | Category | Web | Email | Slack |
  |---|---|---|---|
  | `APPROVAL_NEEDED` | on | on | on |
  | `MORE_ACCESS_REQUESTED` | on | on | off |
  | `RUN_STOPPED_EARLY` | on | on | off |
  | `RUN_FINISHED` | on | off | off |
  | `SPEND_LIMITS` | on | on | off |
  | `WEEKLY_SUMMARY` | off | on | off |
- Filters: `needs_you` = APPROVAL_NEEDED + MORE_ACCESS_REQUESTED; `agents` = RUN_FINISHED + RUN_STOPPED_EARLY + WEEKLY_SUMMARY; `money` = SPEND_LIMITS.
- For `APPROVAL_NEEDED`, email and Slack are sent only if **both** the person's preference and the connection's `notifyChannels` allow that channel.
- Quiet hours (default 20:00–08:00, Asia/Kolkata, off until enabled) hold email and Slack for every category except `APPROVAL_NEEDED`.
- Approval links:
  - 32 random bytes, stored as SHA-256 only.
  - One per person, channel and gate instance.
  - Single use, and they expire with the gate (`awaitingSince + agentstore.runs.gate-timeout`).
  - `GET` never changes state. The decision is `POST` only.
  - They are refused (410) once the run is no longer waiting on that same gate.
  - Timestamps are compared at millisecond precision, because Postgres keeps microseconds and Java keeps nanoseconds.
- Each decision via link records `decided_ip` and `decided_user_agent` on the link. It is authorized by `GateDecisionService` against current membership.
- Org setting `approval_links_require_login` (default false): when true, deciding needs a JWT for the same user as the link.
- Slack: links only, no interactive buttons (spec §8 Q12). The bot token is AES-GCM encrypted with the existing `AesGcmCipher`. The OAuth `state` is a random one-time value stored hashed, never an access token.
- External sends (email, Slack) happen from the delivery job, never inside the outbox consumer. Delivery is at least once: a crash between send and commit can repeat one message.
- Commit messages are conventional-commit style. **Never add any Claude/Anthropic/AI attribution or `Co-Authored-By` trailer** (user's global instruction).
- **Deferred, with the reason:**
  - The `WEEKLY_SUMMARY` digest job lands with the reporting work. Its preference is stored now so the screen works.
  - `MORE_ACCESS_REQUESTED` is produced once WS11 publishes version-consent events. The category and its preference exist now.
  - Per-IP rate limits on `/api/v1/approvals/**` land in WS12.
  - The unread badge is served by `GET /api/v1/notifications/unread-count`, not by `/me`: identity must not depend on notifications.

## Review Focus

1. **A mail scanner (or link preview) that fetches the approval link** must not approve anything. Only an explicit POST decides. [Task 3]
2. **An approval link used after the run was already approved in the web app, rejected, expired, or re-gated** (a second gate on the same run) must answer 410. It must not decide the new gate. [Task 3]
3. **Two taps on "Approve" in quick succession** (double POST) must decide once. The second call answers 410 and nothing is recorded twice. [Task 3]
4. **An approver removed from the workspace after the email was sent** must not be able to decide with the link they still hold. [Task 3]
5. **A run-finished email during quiet hours** is held until quiet hours end, while an approval request at the same moment goes out immediately. [Task 2]

---

## File Structure

```
backend/src/main/resources/db/migration/
  V26__notification.sql                       (create)
  V27__notification_preferences.sql           (create: preferences, quiet hours, org.approval_links_require_login)
  V28__approval_link.sql                      (create)
  V29__slack.sql                              (create: installation, user link, oauth state)
backend/src/main/resources/application.yml    (modify: notifications interval, slack config)
backend/src/main/java/com/agentstore/platform/
  execution/DecisionChannel.java              (modify: EMAIL_LINK, SLACK_LINK)
  execution/RunLifecycleService.java          (modify: GATE_OPENED payload carries gateOpenedAt)
  identity/Org.java                           (modify: approvalLinksRequireLogin)
  identity/SecurityConfig.java                (modify: permit /api/v1/approvals/**, slack callback)
  notifications/
    NotificationCategory.java, Notification.java, NotificationRepository.java,
    NotificationConsumer.java, NotificationController.java                   (create, Task 1)
    NotificationPreference.java, NotificationPreferenceRepository.java,
    QuietHours.java, QuietHoursRepository.java, PreferenceService.java,
    PreferencesController.java                                                (create, Task 2)
    ChannelStatus.java, ChannelDelivery.java, ChannelDeliveryJob.java,
    NotificationMessages.java, ApprovalLink.java, ApprovalLinkRepository.java,
    ApprovalLinkService.java, ApprovalController.java                         (create, Task 3)
    SlackClient.java, HttpSlackClient.java, SlackInstallation.java,
    SlackInstallationRepository.java, SlackUserLink.java, SlackUserLinkRepository.java,
    SlackService.java, SlackController.java                                   (create, Task 4)
backend/src/test/java/com/agentstore/platform/notifications/
  NotificationCentreTest.java, PreferencesTest.java, QuietHoursTest.java,
  ApprovalLinkTest.java, SlackTest.java, FakeSlackClient.java                 (create)
```

All backend commands run from `backend/`.

---

### Task 1: The notification centre

**Files:**
- Create: `db/migration/V26__notification.sql`, `notifications/NotificationCategory.java`, `notifications/Notification.java`, `notifications/NotificationRepository.java`, `notifications/NotificationConsumer.java`, `notifications/NotificationController.java`
- Modify: `execution/RunLifecycleService.java`
- Test: create `notifications/NotificationCentreTest.java`

**Interfaces:**
- Consumes: `DomainEvent`, `DomainEventConsumer`, `OutboxDispatcher.dispatchOnce()` (WS15); `GateDecisionService.eligibleApprovers(Connection): List<OrgMembership>` (WS15); `OrgMembershipRepository.findByIdOrgIdOrderByCreatedAtAsc` (WS13); `ExecutionFixtures`, `IdentityFixtures`.
- Produces:
  - `enum NotificationCategory` with `boolean webLocked()`, `boolean defaultWeb()/defaultEmail()/defaultSlack()`, `String filterGroup()` (`needs_you`, `agents`, `money`).
  - `Notification` entity: `getId()`, `getOrgId()`, `getUserId()`, `getCategory()`, `getSourceEventId()`, `getSubjectType()`, `getSubjectId()`, `payload(): Map<String, Object>`, `isWebVisible()`, `getReadAt()`, `getEmailStatus()/getEmailDueAt()`, `getSlackStatus()/getSlackDueAt()`, `getChannelAttempts()`, `getCreatedAt()`.
  - `NotificationConsumer` (outbox consumer `"notifications"`), `NotificationConsumer.Target(NotificationCategory category, List<UUID> userIds, Connection connection /* nullable */)`, and a package-private `route(DomainEvent): Optional<Target>` that Task 2 builds on.
  - `GET /api/v1/notifications?filter=&before=&limit=` → `Page(long unreadCount, List<Item> items)`. `Item(UUID id, NotificationCategory category, String subjectType, UUID subjectId, Map<String,Object> payload, boolean read, Instant createdAt)`.
  - `GET /api/v1/notifications/unread-count` → `{count}`. `POST /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all` → 204.
  - `GATE_OPENED` payload gains `gateOpenedAt` (ISO instant, milliseconds).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/notifications/NotificationCentreTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.OutboxDispatcher;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationCentreTest extends AbstractIntegrationTest {

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
    @Autowired
    ConnectionRepository connectionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(OrgMembership m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(m));
        return headers;
    }

    String email() {
        return "notif-" + UUID.randomUUID() + "@acme.example";
    }

    NotificationController.Page page(OrgMembership m, String filter) {
        return rest.exchange(url("/api/v1/notifications?filter=" + filter), HttpMethod.GET,
                new HttpEntity<>(bearer(m)), NotificationController.Page.class).getBody();
    }

    record Team(ExecutionFixtures.Scenario s, OrgMembership admin, OrgMembership priya, OrgMembership arjun) {}

    Team team() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        return new Team(s, identity.member(s.buyer(), email(), UserRole.BUYER_ADMIN, null),
                identity.member(s.buyer(), email(), UserRole.BUYER_APPROVER, null),
                identity.member(s.buyer(), email(), UserRole.BUYER_APPROVER, null));
    }

    Run gate(Team t) {
        Run run = fixtures.run(t.s(), RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("summary", "Post 142 journal entries"));
        dispatcher.dispatchOnce();
        return run;
    }

    @Test
    void anOpenedGateNotifiesEveryEligibleApproverOnce() {
        Team t = team();
        Run run = gate(t);
        dispatcher.dispatchOnce();   // a second pass must not duplicate

        for (OrgMembership m : List.of(t.admin(), t.priya(), t.arjun())) {
            var page = page(m, "needs_you");
            assertThat(page.items()).singleElement().satisfies(n -> {
                assertThat(n.category()).isEqualTo(NotificationCategory.APPROVAL_NEEDED);
                assertThat(n.subjectId()).isEqualTo(run.getId());
                assertThat(n.payload().get("summary")).isEqualTo("Post 142 journal entries");
                assertThat(n.payload().get("gateOpenedAt")).isNotNull();
            });
            assertThat(page.unreadCount()).isEqualTo(1);
        }
    }

    @Test
    void namedApproversAreTheOnlyOnesAskedWhenTheyExist() {
        Team t = team();
        Connection c = connectionRepository.findById(t.s().connection().getId()).orElseThrow();
        c.setApproverUserIds(new HashSet<>(Set.of(t.priya().getUser().getId())));
        connectionRepository.save(c);

        gate(t);

        assertThat(page(t.priya(), "needs_you").items()).hasSize(1);
        assertThat(page(t.arjun(), "needs_you").items()).isEmpty();
        assertThat(page(t.admin(), "needs_you").items()).isEmpty();
    }

    @Test
    void aRunStoppedByTimeoutReachesAdminsUnderAgents() {
        Team t = team();
        Run run = fixtures.run(t.s(), RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.terminate(run.getId(), RunStatus.TIMED_OUT, RunStepKind.TIMEOUT, Map.of("summary", "time limit"));
        dispatcher.dispatchOnce();

        assertThat(page(t.admin(), "agents").items()).singleElement()
                .satisfies(n -> assertThat(n.category()).isEqualTo(NotificationCategory.RUN_STOPPED_EARLY));
        assertThat(page(t.priya(), "all").items()).isEmpty();   // approvers are not admins
    }

    @Test
    void markingReadAndMarkAllRead() {
        Team t = team();
        gate(t);
        gate(t);
        var items = page(t.priya(), "all").items();
        assertThat(items).hasSize(2);

        rest.exchange(url("/api/v1/notifications/" + items.get(0).id() + "/read"), HttpMethod.POST,
                new HttpEntity<>(bearer(t.priya())), Void.class);
        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET,
                new HttpEntity<>(bearer(t.priya())), Map.class).getBody().get("count")).isEqualTo(1);

        rest.exchange(url("/api/v1/notifications/read-all"), HttpMethod.POST, new HttpEntity<>(bearer(t.priya())),
                Void.class);
        assertThat(page(t.priya(), "all").unreadCount()).isZero();
        assertThat(page(t.arjun(), "all").unreadCount()).isEqualTo(2);   // only mine were marked
    }

    @Test
    void youCannotMarkSomeoneElsesNotification() {
        Team t = team();
        gate(t);
        UUID arjunsId = page(t.arjun(), "all").items().get(0).id();

        var response = rest.exchange(url("/api/v1/notifications/" + arjunsId + "/read"), HttpMethod.POST,
                new HttpEntity<>(bearer(t.priya())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=NotificationCentreTest`
Expected: compilation failure (`NotificationController` not found).

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V26__notification.sql`:

```sql
CREATE TABLE notification (
    id               UUID        PRIMARY KEY,
    org_id           UUID        NOT NULL REFERENCES org(id),
    user_id          UUID        NOT NULL REFERENCES app_user(id),
    category         VARCHAR(40) NOT NULL,
    source_event_id  UUID        NOT NULL REFERENCES domain_event(id),
    subject_type     VARCHAR(20),
    subject_id       UUID,
    payload          JSONB       NOT NULL,
    web_visible      BOOLEAN     NOT NULL DEFAULT true,
    read_at          TIMESTAMPTZ,
    email_status     VARCHAR(10) NOT NULL DEFAULT 'NONE',   -- NONE, PENDING, SENT, SKIPPED, FAILED
    email_due_at     TIMESTAMPTZ,
    slack_status     VARCHAR(10) NOT NULL DEFAULT 'NONE',
    slack_due_at     TIMESTAMPTZ,
    channel_attempts INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_event_id, user_id)
);
CREATE INDEX notification_inbox_idx ON notification (user_id, org_id, created_at DESC);
CREATE INDEX notification_email_due_idx ON notification (email_due_at) WHERE email_status = 'PENDING';
CREATE INDEX notification_slack_due_idx ON notification (slack_due_at) WHERE slack_status = 'PENDING';
```

- [ ] **Step 4: Category, entity, repository**

`notifications/NotificationCategory.java`:

```java
package com.agentstore.platform.notifications;

/** The rows of "Where we reach you". Defaults match the mockup; the first two can't be turned off on web. */
public enum NotificationCategory {
    APPROVAL_NEEDED(true, true, true, true, "needs_you"),
    MORE_ACCESS_REQUESTED(true, true, true, false, "needs_you"),
    RUN_STOPPED_EARLY(false, true, true, false, "agents"),
    RUN_FINISHED(false, true, false, false, "agents"),
    SPEND_LIMITS(false, true, true, false, "money"),
    WEEKLY_SUMMARY(false, false, true, false, "agents");

    private final boolean webLocked;
    private final boolean defaultWeb;
    private final boolean defaultEmail;
    private final boolean defaultSlack;
    private final String filterGroup;

    NotificationCategory(boolean webLocked, boolean defaultWeb, boolean defaultEmail, boolean defaultSlack,
                         String filterGroup) {
        this.webLocked = webLocked;
        this.defaultWeb = defaultWeb;
        this.defaultEmail = defaultEmail;
        this.defaultSlack = defaultSlack;
        this.filterGroup = filterGroup;
    }

    public boolean webLocked() { return webLocked; }
    public boolean defaultWeb() { return defaultWeb; }
    public boolean defaultEmail() { return defaultEmail; }
    public boolean defaultSlack() { return defaultSlack; }
    public String filterGroup() { return filterGroup; }

    /** Approvals always go out at once; everything else waits for quiet hours to end. */
    public boolean bypassesQuietHours() {
        return this == APPROVAL_NEEDED;
    }
}
```

`notifications/Notification.java`:

```java
package com.agentstore.platform.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID orgId;
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private NotificationCategory category;

    private UUID sourceEventId;
    private String subjectType;
    private UUID subjectId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    private boolean webVisible = true;
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    private ChannelStatus emailStatus = ChannelStatus.NONE;
    private Instant emailDueAt;

    @Enumerated(EnumType.STRING)
    private ChannelStatus slackStatus = ChannelStatus.NONE;
    private Instant slackDueAt;

    private int channelAttempts;
    private Instant createdAt;

    public Notification(UUID orgId, UUID userId, NotificationCategory category, UUID sourceEventId,
                        String subjectType, UUID subjectId, String payloadJson) {
        this.orgId = orgId;
        this.userId = userId;
        this.category = category;
        this.sourceEventId = sourceEventId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.payload = payloadJson;
    }

    public Map<String, Object> payload() {
        try {
            return MAPPER.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`notifications/ChannelStatus.java` (used by the entity now; delivery arrives in Task 3):

```java
package com.agentstore.platform.notifications;

public enum ChannelStatus { NONE, PENDING, SENT, SKIPPED, FAILED }
```

`notifications/NotificationRepository.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsBySourceEventIdAndUserId(UUID sourceEventId, UUID userId);

    @Query("""
            select n from Notification n
            where n.userId = :userId and n.orgId = :orgId and n.webVisible = true
              and n.category in :categories and n.createdAt < :before
            order by n.createdAt desc
            """)
    List<Notification> inbox(@Param("userId") UUID userId, @Param("orgId") UUID orgId,
                             @Param("categories") Collection<NotificationCategory> categories,
                             @Param("before") Instant before, Pageable page);

    long countByUserIdAndOrgIdAndWebVisibleTrueAndReadAtIsNull(UUID userId, UUID orgId);

    Optional<Notification> findByIdAndUserIdAndOrgId(UUID id, UUID userId, UUID orgId);

    @Modifying
    @Query("update Notification n set n.readAt = :now "
            + "where n.userId = :userId and n.orgId = :orgId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("orgId") UUID orgId, @Param("now") Instant now);
}
```

- [ ] **Step 5: The consumer**

`notifications/NotificationConsumer.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.events.DomainEventConsumer;
import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.GateDecisionService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Turns outbox events into one notification per recipient. Idempotent through (source_event_id, user_id). */
@Component
public class NotificationConsumer implements DomainEventConsumer {

    public record Target(NotificationCategory category, List<UUID> userIds, Connection connection) {}

    private final NotificationRepository notificationRepository;
    private final RunRepository runRepository;
    private final GateDecisionService gateDecisionService;
    private final OrgMembershipRepository membershipRepository;

    public NotificationConsumer(NotificationRepository notificationRepository, RunRepository runRepository,
                                GateDecisionService gateDecisionService, OrgMembershipRepository membershipRepository) {
        this.notificationRepository = notificationRepository;
        this.runRepository = runRepository;
        this.gateDecisionService = gateDecisionService;
        this.membershipRepository = membershipRepository;
    }

    @Override
    public String name() {
        return "notifications";
    }

    @Override
    public void handle(DomainEvent event) {
        route(event).ifPresent(target -> {
            for (UUID userId : target.userIds()) {
                if (notificationRepository.existsBySourceEventIdAndUserId(event.getId(), userId)) continue;
                notificationRepository.save(create(event, target, userId));
            }
        });
    }

    /** Task 2 replaces this with the preference- and quiet-hours-aware version. */
    Notification create(DomainEvent event, Target target, UUID userId) {
        return new Notification(event.getOrgId(), userId, target.category(), event.getId(), event.getSubjectType(),
                event.getSubjectId(), event.payloadJson());
    }

    Optional<Target> route(DomainEvent event) {
        return switch (event.getType()) {
            case GATE_OPENED -> runRepository.findById(event.getSubjectId()).map(run -> new Target(
                    NotificationCategory.APPROVAL_NEEDED,
                    gateDecisionService.eligibleApprovers(run.getConnection()).stream()
                            .map(m -> m.getUser().getId()).toList(),
                    run.getConnection()));
            case RUN_FINISHED -> {
                String status = String.valueOf(event.payload().get("status"));
                NotificationCategory category = switch (status) {
                    case "COMPLETED" -> NotificationCategory.RUN_FINISHED;
                    case "FAILED", "TIMED_OUT", "EXPIRED" -> NotificationCategory.RUN_STOPPED_EARLY;
                    default -> null;   // CANCELLED means a person rejected it; the activity feed covers that
                };
                yield category == null ? Optional.empty()
                        : Optional.of(new Target(category, admins(event.getOrgId()), null));
            }
            case SPEND_THRESHOLD_CROSSED ->
                    Optional.of(new Target(NotificationCategory.SPEND_LIMITS, admins(event.getOrgId()), null));
            default -> Optional.empty();
        };
    }

    private List<UUID> admins(UUID orgId) {
        return membershipRepository.findByIdOrgIdOrderByCreatedAtAsc(orgId).stream()
                .filter(m -> m.getRole() == UserRole.BUYER_ADMIN)
                .map(m -> m.getUser().getId()).toList();
    }
}
```

`OutboxDispatcher` runs `handle` inside a transaction, so the lazy loads through `run.getConnection()` work.

- [ ] **Step 6: Carry the gate instant in the event**

In `execution/RunLifecycleService.recordEvent`, inside the `GATE` branch built in WS15, after `Map<String, Object> payload = runPayload(run);` add (import `java.time.temporal.ChronoUnit`):

```java
                payload.put("gateOpenedAt", run.getAwaitingSince().truncatedTo(ChronoUnit.MILLIS).toString());
```

- [ ] **Step 7: The API**

`notifications/NotificationController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.MeController;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository repository;

    public NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    public record Item(UUID id, NotificationCategory category, String subjectType, UUID subjectId,
                       Map<String, Object> payload, boolean read, Instant createdAt) {
        static Item from(Notification n) {
            return new Item(n.getId(), n.getCategory(), n.getSubjectType(), n.getSubjectId(), n.payload(),
                    n.getReadAt() != null, n.getCreatedAt());
        }
    }

    public record Page(long unreadCount, List<Item> items) {}

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Page inbox(@RequestParam(defaultValue = "all") String filter,
                      @RequestParam(required = false) Instant before,
                      @RequestParam(defaultValue = "30") int limit) {
        var claims = MeController.currentClaims();
        List<NotificationCategory> categories = Arrays.stream(NotificationCategory.values())
                .filter(c -> filter.equals("all") || c.filterGroup().equals(filter))
                .toList();
        if (categories.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown filter: " + filter);
        }
        var items = repository.inbox(claims.userId(), claims.orgId(), categories,
                        before == null ? Instant.now().plusSeconds(1) : before,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
                .stream().map(Item::from).toList();
        return new Page(repository.countByUserIdAndOrgIdAndWebVisibleTrueAndReadAtIsNull(claims.userId(),
                claims.orgId()), items);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> unreadCount() {
        var claims = MeController.currentClaims();
        return Map.of("count",
                repository.countByUserIdAndOrgIdAndWebVisibleTrueAndReadAtIsNull(claims.userId(), claims.orgId()));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        var claims = MeController.currentClaims();
        Notification n = repository.findByIdAndUserIdAndOrgId(id, claims.userId(), claims.orgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repository.save(n);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> markAllRead() {
        var claims = MeController.currentClaims();
        repository.markAllRead(claims.userId(), claims.orgId(), Instant.now());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `mvn -q test -Dtest='NotificationCentreTest,OutboxTest,WorkspaceHomeTest,GateDecisionTest'`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): notification centre fed by the outbox, with approvers asked per the approval rule"
```

---

### Task 2: Preferences, quiet hours and which channels a notification owes

**Files:**
- Create: `db/migration/V27__notification_preferences.sql`, `notifications/NotificationPreference.java`, `notifications/NotificationPreferenceRepository.java`, `notifications/QuietHours.java`, `notifications/QuietHoursRepository.java`, `notifications/PreferenceService.java`, `notifications/PreferencesController.java`
- Modify: `notifications/NotificationConsumer.java`, `identity/Org.java`
- Test: create `notifications/QuietHoursTest.java`, `notifications/PreferencesTest.java`

**Interfaces:**
- Consumes: `NotificationConsumer.Target`, `Notification` (Task 1); `Connection.getNotifyChannels()`, `NotifyChannel` (WS15).
- Produces:
  - `PreferenceService.effective(UUID userId, UUID orgId, NotificationCategory): Effective(boolean web, boolean email, boolean slack)`. Defaults apply when no row exists, and web is forced on for locked categories.
  - `PreferenceService.deliverableAt(UUID userId, NotificationCategory, Instant now): Instant`.
  - `static Instant QuietHours.nextAllowed(boolean enabled, LocalTime start, LocalTime end, ZoneId zone, Instant now)`: pure, and handles the window crossing midnight.
  - `GET/PUT /api/v1/me/notification-preferences` → `List<PreferencesController.Row(NotificationCategory category, boolean web, boolean email, boolean slack, boolean webLocked)>`.
  - `GET/PUT /api/v1/me/quiet-hours` → `PreferencesController.QuietHoursView(boolean enabled, LocalTime start, LocalTime end, String timezone)`.
  - `Org.isApprovalLinksRequireLogin()/setApprovalLinksRequireLogin(boolean)` (used in Task 3).
  - New notifications carry `emailStatus = PENDING` and/or `slackStatus = PENDING` with due times. `slackStatus` becomes PENDING only when the user has a Slack link (Task 4 adds the check). Until then, Slack stays `NONE`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/notifications/QuietHoursTest.java`:

```java
package com.agentstore.platform.notifications;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

class QuietHoursTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalTime EIGHT_PM = LocalTime.of(20, 0);
    static final LocalTime EIGHT_AM = LocalTime.of(8, 0);

    Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    @Test
    void outsideTheWindowIsNow() {
        Instant noon = at("2026-09-23T12:00");
        assertThat(QuietHours.nextAllowed(true, EIGHT_PM, EIGHT_AM, IST, noon)).isEqualTo(noon);
    }

    @Test
    void lateEveningWaitsUntilEightTheNextMorning() {
        assertThat(QuietHours.nextAllowed(true, EIGHT_PM, EIGHT_AM, IST, at("2026-09-23T22:30")))
                .isEqualTo(at("2026-09-24T08:00"));
    }

    @Test
    void earlyMorningWaitsUntilEightTheSameMorning() {
        assertThat(QuietHours.nextAllowed(true, EIGHT_PM, EIGHT_AM, IST, at("2026-09-24T06:15")))
                .isEqualTo(at("2026-09-24T08:00"));
    }

    @Test
    void aSameDayWindowWorksToo() {
        LocalTime one = LocalTime.of(13, 0), two = LocalTime.of(14, 0);
        assertThat(QuietHours.nextAllowed(true, one, two, IST, at("2026-09-23T13:30"))).isEqualTo(at("2026-09-23T14:00"));
        assertThat(QuietHours.nextAllowed(true, one, two, IST, at("2026-09-23T14:00"))).isEqualTo(at("2026-09-23T14:00"));
    }

    @Test
    void disabledIsAlwaysNow() {
        Instant late = at("2026-09-23T23:00");
        assertThat(QuietHours.nextAllowed(false, EIGHT_PM, EIGHT_AM, IST, late)).isEqualTo(late);
    }
}
```

`backend/src/test/java/com/agentstore/platform/notifications/PreferencesTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.OutboxDispatcher;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PreferencesTest extends AbstractIntegrationTest {

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
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    ConnectionRepository connectionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(OrgMembership m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(m));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    OrgMembership admin(ExecutionFixtures.Scenario s) {
        return identity.member(s.buyer(), "pref-" + UUID.randomUUID() + "@acme.example", UserRole.BUYER_ADMIN, null);
    }

    Notification only(OrgMembership m) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(m.getUser().getId())).findFirst().orElseThrow();
    }

    void quietNowFor(OrgMembership m) {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        rest.exchange(url("/api/v1/me/quiet-hours"), HttpMethod.PUT, new HttpEntity<>(Map.of("enabled", true,
                "start", now.minusHours(1).withSecond(0).withNano(0).toString(),
                "end", now.plusHours(1).withSecond(0).withNano(0).toString(),
                "timezone", "Asia/Kolkata"), bearer(m)), Map.class);
    }

    @Test
    void defaultsMatchTheScreenAndLockedCategoriesStayOnForWeb() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership m = admin(s);

        var rows = Arrays.asList(rest.exchange(url("/api/v1/me/notification-preferences"), HttpMethod.GET,
                new HttpEntity<>(bearer(m)), PreferencesController.Row[].class).getBody());
        assertThat(rows).filteredOn(r -> r.category() == NotificationCategory.WEEKLY_SUMMARY).singleElement()
                .satisfies(r -> {
                    assertThat(r.web()).isFalse();
                    assertThat(r.email()).isTrue();
                });

        rest.exchange(url("/api/v1/me/notification-preferences"), HttpMethod.PUT, new HttpEntity<>(List.of(
                Map.of("category", "APPROVAL_NEEDED", "web", false, "email", false, "slack", false)), bearer(m)),
                Void.class);
        var after = Arrays.asList(rest.exchange(url("/api/v1/me/notification-preferences"), HttpMethod.GET,
                new HttpEntity<>(bearer(m)), PreferencesController.Row[].class).getBody());
        assertThat(after).filteredOn(r -> r.category() == NotificationCategory.APPROVAL_NEEDED).singleElement()
                .satisfies(r -> {
                    assertThat(r.web()).isTrue();          // locked
                    assertThat(r.webLocked()).isTrue();
                    assertThat(r.email()).isFalse();
                });
    }

    @Test
    void duringQuietHoursARunFinishedEmailWaitsButAnApprovalGoesNow() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership m = admin(s);
        rest.exchange(url("/api/v1/me/notification-preferences"), HttpMethod.PUT, new HttpEntity<>(List.of(
                Map.of("category", "RUN_FINISHED", "web", true, "email", true, "slack", false)), bearer(m)), Void.class);
        quietNowFor(m);

        Run finished = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(finished.getId(), RunStepKind.COMPLETE, Map.of());
        Run gated = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(gated.getId(), RunStepKind.GATE, Map.of());
        dispatcher.dispatchOnce();

        var mine = notificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(m.getUser().getId())).toList();
        Notification runFinished = mine.stream()
                .filter(n -> n.getCategory() == NotificationCategory.RUN_FINISHED).findFirst().orElseThrow();
        Notification approval = mine.stream()
                .filter(n -> n.getCategory() == NotificationCategory.APPROVAL_NEEDED).findFirst().orElseThrow();
        assertThat(runFinished.getEmailStatus()).isEqualTo(ChannelStatus.PENDING);
        assertThat(runFinished.getEmailDueAt()).isAfter(Instant.now().plus(Duration.ofMinutes(30)));
        assertThat(approval.getEmailStatus()).isEqualTo(ChannelStatus.PENDING);
        assertThat(approval.getEmailDueAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void aConnectionThatOnlyAsksOnKaryaSendsNoApprovalEmail() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership m = admin(s);
        Connection c = connectionRepository.findById(s.connection().getId()).orElseThrow();
        c.setNotifyChannels(List.of(NotifyChannel.WEB));
        connectionRepository.save(c);

        Run gated = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(gated.getId(), RunStepKind.GATE, Map.of());
        dispatcher.dispatchOnce();

        Notification n = only(m);
        assertThat(n.isWebVisible()).isTrue();
        assertThat(n.getEmailStatus()).isEqualTo(ChannelStatus.NONE);
    }

    @Test
    void withEveryChannelOffNothingIsCreated() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership m = admin(s);
        rest.exchange(url("/api/v1/me/notification-preferences"), HttpMethod.PUT, new HttpEntity<>(List.of(
                Map.of("category", "RUN_STOPPED_EARLY", "web", false, "email", false, "slack", false)), bearer(m)),
                Void.class);

        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.terminate(run.getId(), RunStatus.TIMED_OUT, RunStepKind.TIMEOUT, Map.of());
        dispatcher.dispatchOnce();

        assertThat(notificationRepository.findAll()).noneMatch(n -> n.getUserId().equals(m.getUser().getId()));
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest='QuietHoursTest,PreferencesTest'`
Expected: compilation failure (`QuietHours`, `PreferencesController` not found).

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V27__notification_preferences.sql`:

```sql
CREATE TABLE notification_preference (
    user_id  UUID        NOT NULL REFERENCES app_user(id),
    org_id   UUID        NOT NULL REFERENCES org(id),
    category VARCHAR(40) NOT NULL,
    web      BOOLEAN     NOT NULL,
    email    BOOLEAN     NOT NULL,
    slack    BOOLEAN     NOT NULL,
    PRIMARY KEY (user_id, org_id, category)
);

CREATE TABLE quiet_hours (
    user_id     UUID        PRIMARY KEY REFERENCES app_user(id),
    enabled     BOOLEAN     NOT NULL,
    start_local TIME        NOT NULL,
    end_local   TIME        NOT NULL,
    timezone    VARCHAR(40) NOT NULL DEFAULT 'Asia/Kolkata'
);

ALTER TABLE org ADD COLUMN approval_links_require_login BOOLEAN NOT NULL DEFAULT false;
```

In `identity/Org.java` add:

```java
    /** When true, a one-tap approval link still asks the approver to sign in before deciding (spec §8 Q10). */
    private boolean approvalLinksRequireLogin;
```

- [ ] **Step 4: Entities, repositories and quiet-hours logic**

`notifications/NotificationPreference.java`:

```java
package com.agentstore.platform.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "notification_preference")
@IdClass(NotificationPreference.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

    @Id
    private UUID userId;

    @Id
    private UUID orgId;

    @Id
    @Enumerated(EnumType.STRING)
    private NotificationCategory category;

    private boolean web;
    private boolean email;
    private boolean slack;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private UUID orgId;
        private NotificationCategory category;
    }
}
```

`notifications/NotificationPreferenceRepository.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, NotificationPreference.Key> {
    List<NotificationPreference> findByUserIdAndOrgId(UUID userId, UUID orgId);
}
```

`notifications/QuietHours.java`:

```java
package com.agentstore.platform.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "quiet_hours")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class QuietHours {

    public static final LocalTime DEFAULT_START = LocalTime.of(20, 0);
    public static final LocalTime DEFAULT_END = LocalTime.of(8, 0);

    @Id
    private UUID userId;

    private boolean enabled;

    @Column(name = "start_local")
    private LocalTime start;

    @Column(name = "end_local")
    private LocalTime end;

    private String timezone;

    /** The earliest moment at or after {@code now} outside the window. The window may cross midnight. */
    public static Instant nextAllowed(boolean enabled, LocalTime start, LocalTime end, ZoneId zone, Instant now) {
        if (!enabled || start.equals(end)) return now;
        ZonedDateTime local = now.atZone(zone);
        LocalTime t = local.toLocalTime();
        boolean crossesMidnight = start.isAfter(end);
        boolean inside = crossesMidnight ? (!t.isBefore(start) || t.isBefore(end)) : (!t.isBefore(start) && t.isBefore(end));
        if (!inside) return now;
        LocalDate endDate = crossesMidnight && !t.isBefore(start) ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        return ZonedDateTime.of(endDate, end, zone).toInstant();
    }
}
```

`notifications/QuietHoursRepository.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuietHoursRepository extends JpaRepository<QuietHours, UUID> {
}
```

`notifications/PreferenceService.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class PreferenceService {

    public record Effective(boolean web, boolean email, boolean slack) {}

    private final NotificationPreferenceRepository preferenceRepository;
    private final QuietHoursRepository quietHoursRepository;

    public PreferenceService(NotificationPreferenceRepository preferenceRepository,
                             QuietHoursRepository quietHoursRepository) {
        this.preferenceRepository = preferenceRepository;
        this.quietHoursRepository = quietHoursRepository;
    }

    public Effective effective(UUID userId, UUID orgId, NotificationCategory category) {
        return preferenceRepository.findById(new NotificationPreference.Key(userId, orgId, category))
                .map(p -> new Effective(p.isWeb() || category.webLocked(), p.isEmail(), p.isSlack()))
                .orElseGet(() -> new Effective(category.defaultWeb(), category.defaultEmail(), category.defaultSlack()));
    }

    public Instant deliverableAt(UUID userId, NotificationCategory category, Instant now) {
        if (category.bypassesQuietHours()) return now;
        return quietHoursRepository.findById(userId)
                .map(q -> QuietHours.nextAllowed(q.isEnabled(), q.getStart(), q.getEnd(), ZoneId.of(q.getTimezone()), now))
                .orElse(now);
    }
}
```

- [ ] **Step 5: Channels in the consumer**

In `NotificationConsumer`, add the dependency `PreferenceService preferenceService` (constructor + field; imports `com.agentstore.platform.execution.NotifyChannel`, `java.time.Instant`), change `handle` to skip `null`s, and replace `create`:

```java
    @Override
    public void handle(DomainEvent event) {
        route(event).ifPresent(target -> {
            for (UUID userId : target.userIds()) {
                if (notificationRepository.existsBySourceEventIdAndUserId(event.getId(), userId)) continue;
                Notification n = create(event, target, userId);
                if (n != null) notificationRepository.save(n);
            }
        });
    }

    /** Null when the person has every channel off for this category. */
    Notification create(DomainEvent event, Target target, UUID userId) {
        var prefs = preferenceService.effective(userId, event.getOrgId(), target.category());
        boolean connectionAllowsEmail = target.connection() == null
                || target.connection().getNotifyChannels().contains(NotifyChannel.EMAIL);
        boolean connectionAllowsSlack = target.connection() == null
                || target.connection().getNotifyChannels().contains(NotifyChannel.SLACK);
        boolean email = prefs.email() && connectionAllowsEmail;
        boolean slack = prefs.slack() && connectionAllowsSlack && slackReachable(userId, event.getOrgId());
        if (!prefs.web() && !email && !slack) return null;

        Notification n = new Notification(event.getOrgId(), userId, target.category(), event.getId(),
                event.getSubjectType(), event.getSubjectId(), event.payloadJson());
        n.setWebVisible(prefs.web());
        Instant due = preferenceService.deliverableAt(userId, target.category(), Instant.now());
        if (email) {
            n.setEmailStatus(ChannelStatus.PENDING);
            n.setEmailDueAt(due);
        }
        if (slack) {
            n.setSlackStatus(ChannelStatus.PENDING);
            n.setSlackDueAt(due);
        }
        return n;
    }

    /** Task 4 checks for a linked Slack user; until then nobody is reachable on Slack. */
    boolean slackReachable(UUID userId, UUID orgId) {
        return false;
    }
```

- [ ] **Step 6: Preferences API**

`notifications/PreferencesController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.MeController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
public class PreferencesController {

    private final NotificationPreferenceRepository preferenceRepository;
    private final QuietHoursRepository quietHoursRepository;
    private final PreferenceService preferenceService;

    public PreferencesController(NotificationPreferenceRepository preferenceRepository,
                                 QuietHoursRepository quietHoursRepository, PreferenceService preferenceService) {
        this.preferenceRepository = preferenceRepository;
        this.quietHoursRepository = quietHoursRepository;
        this.preferenceService = preferenceService;
    }

    public record Row(@NotNull NotificationCategory category, boolean web, boolean email, boolean slack,
                      boolean webLocked) {}

    public record QuietHoursView(boolean enabled, LocalTime start, LocalTime end, String timezone) {}

    @GetMapping("/notification-preferences")
    @PreAuthorize("isAuthenticated()")
    public List<Row> preferences() {
        var claims = MeController.currentClaims();
        return Arrays.stream(NotificationCategory.values()).map(c -> {
            var e = preferenceService.effective(claims.userId(), claims.orgId(), c);
            return new Row(c, e.web(), e.email(), e.slack(), c.webLocked());
        }).toList();
    }

    /** Accepts any subset of categories; the ones not sent keep their current values. */
    @PutMapping("/notification-preferences")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> update(@RequestBody List<@Valid Row> rows) {
        var claims = MeController.currentClaims();
        for (Row row : rows) {
            preferenceRepository.save(new NotificationPreference(claims.userId(), claims.orgId(), row.category(),
                    row.web() || row.category().webLocked(), row.email(), row.slack()));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/quiet-hours")
    @PreAuthorize("isAuthenticated()")
    public QuietHoursView quietHours() {
        var userId = MeController.currentClaims().userId();
        return quietHoursRepository.findById(userId)
                .map(q -> new QuietHoursView(q.isEnabled(), q.getStart(), q.getEnd(), q.getTimezone()))
                .orElse(new QuietHoursView(false, QuietHours.DEFAULT_START, QuietHours.DEFAULT_END, "Asia/Kolkata"));
    }

    @PutMapping("/quiet-hours")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public QuietHoursView setQuietHours(@RequestBody QuietHoursView view) {
        if (view.start() == null || view.end() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start and end are required");
        }
        String zone = view.timezone() == null ? "Asia/Kolkata" : view.timezone();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown timezone: " + zone);
        }
        var userId = MeController.currentClaims().userId();
        quietHoursRepository.save(new QuietHours(userId, view.enabled(), view.start(), view.end(), zone));
        return new QuietHoursView(view.enabled(), view.start(), view.end(), zone);
    }
}
```

Preferences are per person *per workspace* (the mockup's table sits inside a workspace). Quiet hours are per person, because they describe the person's evenings, not the workspace's.

- [ ] **Step 7: Run the tests**

Run: `mvn -q test -Dtest='QuietHoursTest,PreferencesTest,NotificationCentreTest'`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): per-person channel preferences and quiet hours that never hold an approval"
```

---

### Task 3: Email delivery and one-tap approval links

**Files:**
- Create: `db/migration/V28__approval_link.sql`, `notifications/ApprovalLink.java`, `notifications/ApprovalLinkRepository.java`, `notifications/ApprovalLinkService.java`, `notifications/ApprovalController.java`, `notifications/NotificationMessages.java`, `notifications/ChannelDelivery.java`, `notifications/ChannelDeliveryJob.java`
- Modify: `execution/DecisionChannel.java`, `identity/SecurityConfig.java`, `application.yml`
- Test: create `notifications/ApprovalLinkTest.java`

**Interfaces:**
- Consumes: `EmailSender`, `EmailMessage`, `TokenHasher`, `RecordingEmailSender.tokenFromLink(message, "approve")` (WS13); `GateDecisionService.decide(runId, userId, approve, channel)` (WS15); `Notification`, `ChannelStatus` (Tasks 1–2); `Org.isApprovalLinksRequireLogin()` (Task 2); `Run.getAwaitingSince()` and the `agentstore.runs.gate-timeout` config (WS0).
- Produces:
  - `DecisionChannel { WEB, EMAIL_LINK, SLACK_LINK }`.
  - `ApprovalLinkService.mint(Run run, UUID userId, DecisionChannel channel): Optional<String>` (the raw token, or empty if the gate is no longer open).
  - `ApprovalLinkService.card(String token): Card`, `decide(String token, boolean approve, UUID signedInUserId, String ip, String userAgent): Card`.
  - `Card(UUID runId, String agentName, String summary, String state /* OPEN, APPROVED, REJECTED, CLOSED */, Instant expiresAt, BigDecimal charged, BigDecimal spendCap, boolean requiresLogin)`.
  - `GET /api/v1/approvals/{token}` and `POST /api/v1/approvals/{token}/decision {decision: APPROVE|REJECT}` (public).
  - `NotificationMessages.email(Notification, String userName, String approvalUrl /* nullable */): EmailMessage` and `slackText(Notification, String approvalUrl): String`.
  - `ChannelDelivery.deliverDue(): int` and `ChannelDelivery.deliverSlack(Notification)` (a package-private hook, finished in Task 4).
  - Email links look like `{agentstore.web.base-url}/approve/{token}`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/notifications/ApprovalLinkTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.RecordingEmailSender;
import com.agentstore.platform.events.OutboxDispatcher;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalLinkTest extends AbstractIntegrationTest {

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
    @Autowired
    ChannelDelivery delivery;
    @Autowired
    RecordingEmailSender emails;
    @Autowired
    RunRepository runRepository;
    @Autowired
    OrgMembershipRepository membershipRepository;
    @Autowired
    OrgRepository orgRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    record Sent(ExecutionFixtures.Scenario s, Run run, OrgMembership approver, String token) {}

    Sent gateAndEmail() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership approver = identity.member(s.buyer(), "link-" + UUID.randomUUID() + "@acme.example",
                UserRole.BUYER_APPROVER, null);
        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("summary", "Post 142 journal entries"));
        dispatcher.dispatchOnce();
        delivery.deliverDue();
        var mail = emails.lastTo(approver.getUser().getEmail()).orElseThrow();
        assertThat(mail.subject()).contains("Bank Reconciliation");
        return new Sent(s, run, approver, RecordingEmailSender.tokenFromLink(mail, "approve"));
    }

    ResponseEntity<Map> decide(String token, String decision) {
        return rest.postForEntity(url("/api/v1/approvals/" + token + "/decision"), Map.of("decision", decision), Map.class);
    }

    RunStatus statusOf(Run run) {
        return runRepository.findById(run.getId()).orElseThrow().getStatus();
    }

    @Test
    void openingTheLinkShowsTheCardAndDecidesNothing() {
        Sent sent = gateAndEmail();

        var card = rest.getForEntity(url("/api/v1/approvals/" + sent.token()), Map.class).getBody();
        rest.getForEntity(url("/api/v1/approvals/" + sent.token()), Map.class);   // a mail scanner prefetching twice

        assertThat(card.get("state")).isEqualTo("OPEN");
        assertThat(card.get("summary")).isEqualTo("Post 142 journal entries");
        assertThat(card.get("agentName")).isEqualTo("Bank Reconciliation");
        assertThat(statusOf(sent.run())).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void approvingFromTheLinkResumesTheRunOnce() {
        Sent sent = gateAndEmail();

        var first = decide(sent.token(), "APPROVE");
        var second = decide(sent.token(), "APPROVE");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("state")).isEqualTo("APPROVED");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(sent.run())).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void doubleTapAtTheSameMomentDecidesOnce() throws Exception {
        Sent sent = gateAndEmail();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<HttpStatusCode> tap = () -> {
            go.await();
            return decide(sent.token(), "APPROVE").getStatusCode();
        };

        Future<HttpStatusCode> a = pool.submit(tap);
        Future<HttpStatusCode> b = pool.submit(tap);
        go.countDown();
        List<HttpStatusCode> results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.GONE);
    }

    @Test
    void aLinkForAGateAlreadyDecidedOnTheWebIsGone() {
        Sent sent = gateAndEmail();
        lifecycle.approveGate(sent.run().getId());

        assertThat(decide(sent.token(), "REJECT").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(rest.getForEntity(url("/api/v1/approvals/" + sent.token()), Map.class).getBody().get("state"))
                .isEqualTo("CLOSED");
    }

    @Test
    void aLinkNeverDecidesALaterGateOnTheSameRun() {
        Sent sent = gateAndEmail();
        lifecycle.approveGate(sent.run().getId());
        lifecycle.recordEvent(sent.run().getId(), RunStepKind.GATE, Map.of("summary", "second gate"));

        assertThat(decide(sent.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(sent.run())).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void aRemovedApproverCannotUseTheirLink() {
        Sent sent = gateAndEmail();
        membershipRepository.delete(sent.approver());

        var response = decide(sent.token(), "APPROVE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(sent.run())).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void workspacesCanRequireSignInBeforeALinkDecides() {
        Sent sent = gateAndEmail();
        Org org = orgRepository.findById(sent.s().buyer().getId()).orElseThrow();
        org.setApprovalLinksRequireLogin(true);
        orgRepository.save(org);

        var anonymous = decide(sent.token(), "APPROVE");
        HttpHeaders signedIn = new HttpHeaders();
        signedIn.setBearerAuth(identity.tokenFor(sent.approver()));
        signedIn.setContentType(MediaType.APPLICATION_JSON);
        var withLogin = rest.exchange(url("/api/v1/approvals/" + sent.token() + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "APPROVE"), signedIn), Map.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anEmailDueAfterTheGateClosedIsSkipped() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership approver = identity.member(s.buyer(), "late-" + UUID.randomUUID() + "@acme.example",
                UserRole.BUYER_APPROVER, null);
        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of());
        dispatcher.dispatchOnce();
        lifecycle.rejectGate(run.getId());   // decided before the email job ran

        delivery.deliverDue();

        assertThat(emails.lastTo(approver.getUser().getEmail())).isEmpty();
    }

    @Test
    void anUnknownTokenIsGone() {
        assertThat(decide("not-a-token", "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=ApprovalLinkTest`
Expected: compilation failure (`ChannelDelivery` not found).

- [ ] **Step 3: Migration, config, channels**

`backend/src/main/resources/db/migration/V28__approval_link.sql`:

```sql
CREATE TABLE approval_link (
    id                 UUID        PRIMARY KEY,
    run_id             UUID        NOT NULL REFERENCES run(id),
    gate_opened_at     TIMESTAMPTZ NOT NULL,     -- identifies the gate instance; WS1 replaces it with gate_request_id
    user_id            UUID        NOT NULL REFERENCES app_user(id),
    channel            VARCHAR(12) NOT NULL,
    token_hash         CHAR(64)    NOT NULL UNIQUE,
    expires_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ,
    decided_ip         VARCHAR(64),
    decided_user_agent VARCHAR(255),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX approval_link_run_idx ON approval_link (run_id);
```

In `application.yml` under `agentstore:` add:

```yaml
  notifications:
    delivery-interval: PT15S
```

Replace `execution/DecisionChannel.java`:

```java
package com.agentstore.platform.execution;

/** Where a gate decision was made. */
public enum DecisionChannel { WEB, EMAIL_LINK, SLACK_LINK }
```

In `SecurityConfig.filterChain`, extend the first matcher with `"/api/v1/approvals/**"`.

- [ ] **Step 4: Links**

`notifications/ApprovalLink.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.execution.DecisionChannel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval_link")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID runId;
    private Instant gateOpenedAt;
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private DecisionChannel channel;

    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private String decidedIp;
    private String decidedUserAgent;
    private Instant createdAt;

    public ApprovalLink(UUID runId, Instant gateOpenedAt, UUID userId, DecisionChannel channel, String tokenHash,
                        Instant expiresAt) {
        this.runId = runId;
        this.gateOpenedAt = gateOpenedAt;
        this.userId = userId;
        this.channel = channel;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`notifications/ApprovalLinkRepository.java`:

```java
package com.agentstore.platform.notifications;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalLinkRepository extends JpaRepository<ApprovalLink, UUID> {

    Optional<ApprovalLink> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ApprovalLink l where l.tokenHash = :hash")
    Optional<ApprovalLink> findByTokenHashForUpdate(@Param("hash") String hash);
}
```

`notifications/ApprovalLinkService.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.OrgRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One-tap approval links. A link belongs to one person and one gate instance (run + the moment the gate opened),
 * works once, and decides only through {@link GateDecisionService}, so it never grants more than the person has.
 */
@Service
public class ApprovalLinkService {

    public record Card(UUID runId, String agentName, String summary, String state, Instant expiresAt,
                       BigDecimal charged, BigDecimal spendCap, boolean requiresLogin) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalLinkRepository linkRepository;
    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final GateDecisionService gateDecisionService;
    private final OrgRepository orgRepository;
    private final Duration gateTimeout;

    public ApprovalLinkService(ApprovalLinkRepository linkRepository, RunRepository runRepository,
                               RunStepRepository runStepRepository, GateDecisionService gateDecisionService,
                               OrgRepository orgRepository,
                               @Value("${agentstore.runs.gate-timeout:PT30M}") Duration gateTimeout) {
        this.linkRepository = linkRepository;
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.gateDecisionService = gateDecisionService;
        this.orgRepository = orgRepository;
        this.gateTimeout = gateTimeout;
    }

    static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /** Empty when the run is not (or no longer) waiting on a gate. */
    @Transactional
    public Optional<String> mint(Run run, UUID userId, DecisionChannel channel) {
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL || run.getAwaitingSince() == null) return Optional.empty();
        String raw = TokenHasher.newToken();
        Instant openedAt = millis(run.getAwaitingSince());
        linkRepository.save(new ApprovalLink(run.getId(), openedAt, userId, channel, TokenHasher.sha256Hex(raw),
                openedAt.plus(gateTimeout)));
        return Optional.of(raw);
    }

    @Transactional(readOnly = true)
    public Card card(String raw) {
        ApprovalLink link = linkRepository.findByTokenHash(TokenHasher.sha256Hex(raw)).orElseThrow(ApprovalLinkService::gone);
        Run run = runRepository.findById(link.getRunId()).orElseThrow(ApprovalLinkService::gone);
        return card(link, run, stateOf(link, run));
    }

    @Transactional
    public Card decide(String raw, boolean approve, UUID signedInUserId, String ip, String userAgent) {
        ApprovalLink link = linkRepository.findByTokenHashForUpdate(TokenHasher.sha256Hex(raw))
                .orElseThrow(ApprovalLinkService::gone);   // concurrent taps queue here; the second sees usedAt
        Run run = runRepository.findById(link.getRunId()).orElseThrow(ApprovalLinkService::gone);
        if (!"OPEN".equals(stateOf(link, run))) throw gone();

        boolean requiresLogin = orgRepository.findById(run.getConnection().getBuyerOrg().getId())
                .map(o -> o.isApprovalLinksRequireLogin()).orElse(false);
        if (requiresLogin) {
            if (signedInUserId == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to decide");
            }
            if (!signedInUserId.equals(link.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This link was sent to someone else");
            }
        }

        Run after = gateDecisionService.decide(run.getId(), link.getUserId(), approve, link.getChannel());
        link.setUsedAt(Instant.now());
        link.setDecidedIp(ip == null ? null : ip.substring(0, Math.min(ip.length(), 64)));
        link.setDecidedUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255)));
        linkRepository.save(link);
        return card(link, after, approve ? "APPROVED" : "REJECTED");
    }

    private String stateOf(ApprovalLink link, Run run) {
        boolean sameGate = run.getStatus() == RunStatus.AWAITING_APPROVAL
                && link.getGateOpenedAt().equals(millis(run.getAwaitingSince()));
        boolean open = link.getUsedAt() == null && link.getExpiresAt().isAfter(Instant.now()) && sameGate;
        return open ? "OPEN" : "CLOSED";
    }

    private Card card(ApprovalLink link, Run run, String state) {
        boolean requiresLogin = orgRepository.findById(run.getConnection().getBuyerOrg().getId())
                .map(o -> o.isApprovalLinksRequireLogin()).orElse(false);
        return new Card(run.getId(), run.getConnection().getAgent().getName(), gateSummary(run), state,
                link.getExpiresAt(), run.getTotalCharged(), run.getCostCap(), requiresLogin);
    }

    private String gateSummary(Run run) {
        return runStepRepository.findFirstByRunIdAndKindOrderByCreatedAtDesc(run.getId(), RunStepKind.GATE)
                .map(step -> {
                    try {
                        Object s = MAPPER.readValue(step.getDetail(), new TypeReference<Map<String, Object>>() {})
                                .get("summary");
                        return s == null ? null : s.toString();
                    } catch (Exception e) {
                        return null;
                    }
                }).orElse(null);
    }

    private static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE, "This request has closed");
    }
}
```

`GateDecisionService.decide` runs inside this transaction and throws 403 `NOT_AN_APPROVER` for someone who has left, so the link is not marked used and nothing changes.

`notifications/ApprovalController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Public endpoints behind the /approve/{token} page that works on a phone. */
@RestController
@RequestMapping("/api/v1/approvals/{token}")
public class ApprovalController {

    private final ApprovalLinkService service;

    public ApprovalController(ApprovalLinkService service) {
        this.service = service;
    }

    public record DecisionRequest(@Pattern(regexp = "APPROVE|REJECT") String decision) {}

    @GetMapping
    public ApprovalLinkService.Card card(@PathVariable String token) {
        return service.card(token);
    }

    @PostMapping("/decision")
    public ApprovalLinkService.Card decide(@PathVariable String token, @Valid @RequestBody DecisionRequest request,
                                           HttpServletRequest http) {
        return service.decide(token, "APPROVE".equals(request.decision()), signedInUserId(), http.getRemoteAddr(),
                http.getHeader("User-Agent"));
    }

    private static UUID signedInUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof JwtService.Claims c ? c.userId() : null;
    }
}
```

Behind the production load balancer, `getRemoteAddr()` needs `server.forward-headers-strategy: framework`. That is recorded for WS12 deployment.

- [ ] **Step 5: Messages and the delivery job**

`notifications/NotificationMessages.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.common.EmailMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Plain, short copy in the product's voice. Structured facts come from the event payload. */
@Component
public class NotificationMessages {

    private final String webBaseUrl;

    public NotificationMessages(@Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String approvalUrl(String token) {
        return webBaseUrl + "/approve/" + token;
    }

    public EmailMessage email(Notification n, String toEmail, String userName, String approvalUrl) {
        Map<String, Object> p = n.payload();
        String agent = String.valueOf(p.getOrDefault("agentName", "An agent"));
        String hello = "Hi " + (userName == null ? "there" : userName.split(" ")[0]) + ",\n\n";
        return switch (n.getCategory()) {
            case APPROVAL_NEEDED -> new EmailMessage(toEmail, agent + " needs your approval",
                    hello + agent + " is waiting for you: " + p.getOrDefault("summary", "it wants to write data") + ".\n"
                            + "Nothing is written until you approve.\n\n"
                            + "Review and decide: " + approvalUrl + "\n\n"
                            + "If nobody decides within 30 minutes, the run stops by itself.");
            case RUN_STOPPED_EARLY -> new EmailMessage(toEmail, agent + " stopped early",
                    hello + agent + " stopped before finishing (" + p.get("status") + "). Open Karya to see why: "
                            + webBaseUrl + "/runs/" + n.getSubjectId());
            case RUN_FINISHED -> new EmailMessage(toEmail, agent + " finished",
                    hello + agent + " finished. ₹" + p.getOrDefault("charged", "0") + " charged. "
                            + webBaseUrl + "/runs/" + n.getSubjectId());
            case SPEND_LIMITS -> new EmailMessage(toEmail,
                    "Spend reached " + p.get("threshold") + "% of your monthly limit",
                    hello + "Your workspace has spent ₹" + p.get("spent") + " of its ₹" + p.get("limit")
                            + " monthly limit. " + webBaseUrl + "/billing");
            case MORE_ACCESS_REQUESTED, WEEKLY_SUMMARY -> new EmailMessage(toEmail, "Update from Karya",
                    hello + "There is something new in your workspace: " + webBaseUrl + "/notifications");
        };
    }

    public String slackText(Notification n, String approvalUrl) {
        Map<String, Object> p = n.payload();
        String agent = String.valueOf(p.getOrDefault("agentName", "An agent"));
        return switch (n.getCategory()) {
            case APPROVAL_NEEDED -> agent + " needs your approval: " + p.getOrDefault("summary", "it wants to write data")
                    + ". Review and decide: " + approvalUrl;
            case RUN_STOPPED_EARLY -> agent + " stopped early. " + webBaseUrl + "/runs/" + n.getSubjectId();
            case RUN_FINISHED -> agent + " finished. " + webBaseUrl + "/runs/" + n.getSubjectId();
            case SPEND_LIMITS -> "Spend reached " + p.get("threshold") + "% of the monthly limit. " + webBaseUrl + "/billing";
            case MORE_ACCESS_REQUESTED, WEEKLY_SUMMARY -> "Something new in Karya: " + webBaseUrl + "/notifications";
        };
    }
}
```

`notifications/ChannelDelivery.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends email (and, from Task 4, Slack) for notifications that are due. Each send happens in its own transaction;
 * an approval whose gate has closed is skipped instead of sent.
 */
@Component
public class ChannelDelivery {

    private static final Logger log = LoggerFactory.getLogger(ChannelDelivery.class);
    static final int BATCH = 50;
    static final int MAX_ATTEMPTS = 5;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final RunRepository runRepository;
    private final ApprovalLinkService approvalLinkService;
    private final NotificationMessages messages;
    private final EmailSender emailSender;
    private final TransactionTemplate tx;

    public ChannelDelivery(NotificationRepository notificationRepository, UserRepository userRepository,
                           RunRepository runRepository, ApprovalLinkService approvalLinkService,
                           NotificationMessages messages, EmailSender emailSender, PlatformTransactionManager txManager) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.runRepository = runRepository;
        this.approvalLinkService = approvalLinkService;
        this.messages = messages;
        this.emailSender = emailSender;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int deliverDue() {
        int sent = 0;
        for (UUID id : notificationRepository.dueEmailIds(Instant.now(), BATCH)) {
            if (attempt(id, true)) sent++;
        }
        for (UUID id : notificationRepository.dueSlackIds(Instant.now(), BATCH)) {
            if (attempt(id, false)) sent++;
        }
        return sent;
    }

    private boolean attempt(UUID id, boolean email) {
        try {
            return Boolean.TRUE.equals(tx.execute(s -> {
                Notification n = notificationRepository.findById(id).orElse(null);
                if (n == null) return false;
                ChannelStatus status = email ? n.getEmailStatus() : n.getSlackStatus();
                if (status != ChannelStatus.PENDING) return false;
                boolean done = email ? sendEmail(n) : deliverSlack(n);
                if (email) n.setEmailStatus(done ? ChannelStatus.SENT : ChannelStatus.SKIPPED);
                else n.setSlackStatus(done ? ChannelStatus.SENT : ChannelStatus.SKIPPED);
                notificationRepository.save(n);
                return done;
            }));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> notificationRepository.findById(id).ifPresent(n -> {
                n.setChannelAttempts(n.getChannelAttempts() + 1);
                Instant retry = Instant.now().plus(Duration.ofMinutes(n.getChannelAttempts()));
                if (email) {
                    n.setEmailStatus(n.getChannelAttempts() >= MAX_ATTEMPTS ? ChannelStatus.FAILED : ChannelStatus.PENDING);
                    n.setEmailDueAt(retry);
                } else {
                    n.setSlackStatus(n.getChannelAttempts() >= MAX_ATTEMPTS ? ChannelStatus.FAILED : ChannelStatus.PENDING);
                    n.setSlackDueAt(retry);
                }
                notificationRepository.save(n);
            }));
            log.warn("Delivery of notification {} ({}) failed", id, email ? "email" : "slack", e);
            return false;
        }
    }

    /** False means "skipped": an approval whose gate is no longer open. */
    private boolean sendEmail(Notification n) {
        User user = userRepository.findById(n.getUserId()).orElse(null);
        if (user == null) return false;
        String url = null;
        if (n.getCategory() == NotificationCategory.APPROVAL_NEEDED) {
            Optional<String> token = openGate(n).flatMap(run -> approvalLinkService.mint(run, n.getUserId(),
                    DecisionChannel.EMAIL_LINK));
            if (token.isEmpty()) return false;
            url = messages.approvalUrl(token.get());
        }
        emailSender.send(messages.email(n, user.getEmail(), user.getFullName(), url));
        return true;
    }

    /** Task 4 implements Slack delivery. */
    boolean deliverSlack(Notification n) {
        return false;
    }

    /** The run, if it is still waiting on the same gate this notification is about. */
    Optional<Run> openGate(Notification n) {
        Object openedAt = n.payload().get("gateOpenedAt");
        return runRepository.findById(n.getSubjectId())
                .filter(run -> run.getAwaitingSince() != null && openedAt != null
                        && ApprovalLinkService.millis(run.getAwaitingSince()).equals(Instant.parse(openedAt.toString())));
    }
}
```

Add to `NotificationRepository`:

```java
    @Query(value = "select id from notification where email_status = 'PENDING' and email_due_at <= :now "
            + "order by email_due_at limit :limit", nativeQuery = true)
    List<UUID> dueEmailIds(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "select id from notification where slack_status = 'PENDING' and slack_due_at <= :now "
            + "order by slack_due_at limit :limit", nativeQuery = true)
    List<UUID> dueSlackIds(@Param("now") Instant now, @Param("limit") int limit);
```

`openGate` also requires `AWAITING_APPROVAL`: `mint` returns empty for any other status, so a rejected or approved gate is skipped.

`notifications/ChannelDeliveryJob.java`:

```java
package com.agentstore.platform.notifications;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ChannelDeliveryJob {

    private final ChannelDelivery delivery;

    public ChannelDeliveryJob(ChannelDelivery delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${agentstore.notifications.delivery-interval:PT15S}")
    @SchedulerLock(name = "notificationDelivery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        delivery.deliverDue();
    }
}
```

Worst-case latency from a gate opening to the email: the outbox interval (5 s) plus the delivery interval (15 s). That is well inside the spec's "same minute".

- [ ] **Step 6: Run the tests**

Run: `mvn -q test -Dtest='ApprovalLinkTest,PreferencesTest,NotificationCentreTest,GateDecisionTest,SecurityConfigTest'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): approval emails with single-use one-tap links bound to the gate that sent them"
```

---

### Task 4: Slack

**Files:**
- Create: `db/migration/V29__slack.sql`, `notifications/SlackClient.java`, `notifications/HttpSlackClient.java`, `notifications/SlackInstallation.java`, `notifications/SlackInstallationRepository.java`, `notifications/SlackUserLink.java`, `notifications/SlackUserLinkRepository.java`, `notifications/SlackService.java`, `notifications/SlackController.java`
- Modify: `notifications/NotificationConsumer.java` (`slackReachable`), `notifications/ChannelDelivery.java` (`deliverSlack`), `identity/SecurityConfig.java`, `application.yml`
- Test: create `notifications/FakeSlackClient.java`, `notifications/SlackTest.java`

**Interfaces:**
- Consumes: `AesGcmCipher` (existing), `TokenHasher` (WS13), `ApprovalLinkService.mint`, `NotificationMessages.slackText`, `ChannelDelivery.openGate` (Task 3).
- Produces:
  - `SlackClient`: `OAuthResult exchangeCode(String code, String redirectUri)` with `record OAuthResult(String teamId, String botToken)`, `Optional<String> lookupUserIdByEmail(String botToken, String email)`, `void postMessage(String botToken, String slackUserId, String text)`.
  - `SlackService.installUrl(UUID orgId, UUID userId): String`, `completeInstall(String code, String state)`, `uninstall(UUID orgId)`, `linkMe(UUID userId, UUID orgId)`, `isReachable(UUID userId, UUID orgId): boolean`, `send(UUID orgId, UUID userId, String text)`.
  - Endpoints:
    - `GET /api/v1/orgs/{orgId}/integrations/slack/install-url` (BUYER_ADMIN) → `{url}`.
    - `GET /api/v1/integrations/slack/callback?code&state` (public) → 302 to `{web}/settings/notifications?slack=connected`, or `?slack=failed`.
    - `DELETE /api/v1/orgs/{orgId}/integrations/slack` (BUYER_ADMIN) → 204.
    - `POST /api/v1/me/slack-link` → 204, 404 `SLACK_USER_NOT_FOUND`, or 409 `SLACK_NOT_INSTALLED`.
  - Config: `agentstore.slack.client-id`, `agentstore.slack.client-secret`, `agentstore.slack.redirect-uri`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/notifications/FakeSlackClient.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Primary
public class FakeSlackClient implements SlackClient {

    public record Posted(String botToken, String slackUserId, String text) {}

    public final List<Posted> posted = new CopyOnWriteArrayList<>();
    private final Map<String, String> usersByEmail = new ConcurrentHashMap<>();

    public void addUser(String email, String slackUserId) {
        usersByEmail.put(email.toLowerCase(), slackUserId);
    }

    @Override
    public OAuthResult exchangeCode(String code, String redirectUri) {
        if (!code.startsWith("good-")) throw new IllegalStateException("invalid_code");
        return new OAuthResult("T-" + code, "xoxb-" + code);
    }

    @Override
    public Optional<String> lookupUserIdByEmail(String botToken, String email) {
        return Optional.ofNullable(usersByEmail.get(email.toLowerCase()));
    }

    @Override
    public void postMessage(String botToken, String slackUserId, String text) {
        posted.add(new Posted(botToken, slackUserId, text));
    }
}
```

`backend/src/test/java/com/agentstore/platform/notifications/SlackTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.OutboxDispatcher;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class SlackTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    IdentityFixtures identity;
    @Autowired
    FakeSlackClient slack;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    ChannelDelivery delivery;
    @Autowired
    ConnectionRepository connectionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(OrgMembership m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(m));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** A private client that never follows redirects, so the callback's 302 can be inspected. */
    final org.springframework.web.client.RestTemplate noRedirect = new org.springframework.web.client.RestTemplate(
            new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()));

    String callbackLocation(String code, String state) {
        return noRedirect.getForEntity(url("/api/v1/integrations/slack/callback?code=" + code + "&state=" + state),
                String.class).getHeaders().getLocation().toString();
    }

    String stateFor(OrgMembership admin) {
        String installUrl = (String) rest.exchange(url("/api/v1/orgs/" + admin.getOrg().getId()
                        + "/integrations/slack/install-url"), HttpMethod.GET, new HttpEntity<>(bearer(admin)), Map.class)
                .getBody().get("url");
        return UriComponentsBuilder.fromUriString(installUrl).build().getQueryParams().getFirst("state");
    }

    String install(OrgMembership admin, String code) {
        return callbackLocation(code, stateFor(admin));
    }

    @Test
    void installLinkAndReceiveAnApprovalRequestAsADirectMessage() {
        var s = fixtures.liveTierA(new BigDecimal("300.00"), new BigDecimal("1000.00"));
        OrgMembership admin = identity.member(s.buyer(), "slack-" + UUID.randomUUID() + "@acme.example",
                UserRole.BUYER_ADMIN, null);
        slack.addUser(admin.getUser().getEmail(), "U-PRIYA");
        Connection c = connectionRepository.findById(s.connection().getId()).orElseThrow();
        c.setNotifyChannels(List.of(NotifyChannel.EMAIL, NotifyChannel.SLACK));
        connectionRepository.save(c);

        assertThat(install(admin, "good-" + UUID.randomUUID())).endsWith("slack=connected");
        assertThat(rest.exchange(url("/api/v1/me/slack-link"), HttpMethod.POST, new HttpEntity<>(bearer(admin)),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Run run = fixtures.run(s, RunStatus.RUNNING, "fake-sandbox-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("summary", "Post 142 journal entries"));
        dispatcher.dispatchOnce();
        delivery.deliverDue();

        assertThat(slack.posted).filteredOn(p -> p.slackUserId().equals("U-PRIYA")).singleElement()
                .satisfies(p -> {
                    assertThat(p.text()).contains("Post 142 journal entries").contains("/approve/");
                    assertThat(p.botToken()).startsWith("xoxb-good-");
                });
    }

    @Test
    void aForgedOrReusedStateIsRejected() {
        OrgMembership admin = identity.buyerAdmin("slack-state-" + UUID.randomUUID() + "@acme.example");

        assertThat(callbackLocation("good-x", "forged")).endsWith("slack=failed");

        String state = stateFor(admin);
        assertThat(callbackLocation("good-1", state)).endsWith("slack=connected");
        assertThat(callbackLocation("good-2", state)).endsWith("slack=failed");
    }

    @Test
    void linkingWithoutAnInstallOrAMatchingSlackUserExplainsWhy() {
        OrgMembership admin = identity.buyerAdmin("slack-none-" + UUID.randomUUID() + "@acme.example");
        var notInstalled = rest.exchange(url("/api/v1/me/slack-link"), HttpMethod.POST,
                new HttpEntity<>(bearer(admin)), Map.class);
        assertThat(notInstalled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notInstalled.getBody().get("code")).isEqualTo("SLACK_NOT_INSTALLED");

        install(admin, "good-" + UUID.randomUUID());
        var noUser = rest.exchange(url("/api/v1/me/slack-link"), HttpMethod.POST, new HttpEntity<>(bearer(admin)),
                Map.class);
        assertThat(noUser.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noUser.getBody().get("code")).isEqualTo("SLACK_USER_NOT_FOUND");
    }
}
```

The test uses its own `RestTemplate` that never follows redirects, rather than reconfiguring the shared `TestRestTemplate`. Changing the shared one would leak into other test classes, and the JDK `HttpURLConnection` factory cannot send `PATCH`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `mvn -q test -Dtest=SlackTest`
Expected: compilation failure (`SlackClient` not found).

- [ ] **Step 3: Migration and config**

`backend/src/main/resources/db/migration/V29__slack.sql`:

```sql
CREATE TABLE slack_installation (
    org_id               UUID         PRIMARY KEY REFERENCES org(id),
    team_id              VARCHAR(50)  NOT NULL,
    bot_token_encrypted  BYTEA        NOT NULL,
    bot_token_nonce      BYTEA        NOT NULL,
    installed_by_user_id UUID         REFERENCES app_user(id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE slack_user_link (
    user_id       UUID        NOT NULL REFERENCES app_user(id),
    org_id        UUID        NOT NULL REFERENCES org(id),
    slack_user_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, org_id)
);

CREATE TABLE slack_oauth_state (
    state_hash  CHAR(64)    PRIMARY KEY,
    org_id      UUID        NOT NULL REFERENCES org(id),
    user_id     UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);
```

In `application.yml` under `agentstore:` add:

```yaml
  slack:
    client-id: ${SLACK_CLIENT_ID:}
    client-secret: ${SLACK_CLIENT_SECRET:}
    redirect-uri: ${SLACK_REDIRECT_URI:http://localhost:8080/api/v1/integrations/slack/callback}
```

In `SecurityConfig.filterChain`, extend the first matcher with `"/api/v1/integrations/slack/callback"`.

- [ ] **Step 4: Client**

`notifications/SlackClient.java`:

```java
package com.agentstore.platform.notifications;

import java.util.Optional;

public interface SlackClient {

    record OAuthResult(String teamId, String botToken) {}

    OAuthResult exchangeCode(String code, String redirectUri);

    Optional<String> lookupUserIdByEmail(String botToken, String email);

    void postMessage(String botToken, String slackUserId, String text);
}
```

`notifications/HttpSlackClient.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** The three Slack Web API calls Karya needs. Slack answers HTTP 200 with {"ok": false} on errors. */
@Component
public class HttpSlackClient implements SlackClient {

    private final RestClient http = RestClient.create("https://slack.com/api");
    private final String clientId;
    private final String clientSecret;

    public HttpSlackClient(@Value("${agentstore.slack.client-id:}") String clientId,
                           @Value("${agentstore.slack.client-secret:}") String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    @SuppressWarnings("unchecked")
    public OAuthResult exchangeCode(String code, String redirectUri) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> body = ok(http.post().uri("/oauth.v2.access")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class));
        Map<String, Object> team = (Map<String, Object>) body.get("team");
        return new OAuthResult(String.valueOf(team.get("id")), String.valueOf(body.get("access_token")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> lookupUserIdByEmail(String botToken, String email) {
        Map<String, Object> body = http.get().uri(u -> u.path("/users.lookupByEmail").queryParam("email", email).build())
                .header("Authorization", "Bearer " + botToken).retrieve().body(Map.class);
        if (body == null || !Boolean.TRUE.equals(body.get("ok"))) return Optional.empty();
        return Optional.of(String.valueOf(((Map<String, Object>) body.get("user")).get("id")));
    }

    @Override
    public void postMessage(String botToken, String slackUserId, String text) {
        ok(http.post().uri("/chat.postMessage").header("Authorization", "Bearer " + botToken)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("channel", slackUserId, "text", text))
                .retrieve().body(Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(Map<?, ?> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("ok"))) {
            throw new IllegalStateException("Slack API error: " + (body == null ? "no body" : body.get("error")));
        }
        return (Map<String, Object>) body;
    }
}
```

- [ ] **Step 5: Entities and service**

`notifications/SlackInstallation.java`:

```java
package com.agentstore.platform.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "slack_installation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SlackInstallation {

    @Id
    private UUID orgId;

    private String teamId;
    private byte[] botTokenEncrypted;
    private byte[] botTokenNonce;
    private UUID installedByUserId;
    private Instant createdAt;
}
```

`notifications/SlackInstallationRepository.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SlackInstallationRepository extends JpaRepository<SlackInstallation, UUID> {
}
```

`notifications/SlackUserLink.java`:

```java
package com.agentstore.platform.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "slack_user_link")
@IdClass(SlackUserLink.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SlackUserLink {

    @Id
    private UUID userId;

    @Id
    private UUID orgId;

    private String slackUserId;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID userId;
        private UUID orgId;
    }
}
```

`notifications/SlackUserLinkRepository.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackUserLinkRepository extends JpaRepository<SlackUserLink, SlackUserLink.Key> {
}
```

`notifications/SlackService.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.common.AesGcmCipher;
import com.agentstore.platform.common.ApiProblemException;
import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.identity.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SlackService {

    static final Duration STATE_TTL = Duration.ofMinutes(10);
    static final String SCOPES = "chat:write,users:read,users:read.email";

    private final SlackClient client;
    private final SlackInstallationRepository installations;
    private final SlackUserLinkRepository links;
    private final UserRepository userRepository;
    private final AesGcmCipher cipher;
    private final JdbcTemplate jdbc;
    private final String clientId;
    private final String redirectUri;

    public SlackService(SlackClient client, SlackInstallationRepository installations, SlackUserLinkRepository links,
                        UserRepository userRepository, AesGcmCipher cipher, JdbcTemplate jdbc,
                        @Value("${agentstore.slack.client-id:}") String clientId,
                        @Value("${agentstore.slack.redirect-uri}") String redirectUri) {
        this.client = client;
        this.installations = installations;
        this.links = links;
        this.userRepository = userRepository;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    @Transactional
    public String installUrl(UUID orgId, UUID userId) {
        String state = TokenHasher.newToken();
        jdbc.update("insert into slack_oauth_state (state_hash, org_id, user_id, expires_at) values (?, ?, ?, ?)",
                TokenHasher.sha256Hex(state), orgId, userId, Timestamp.from(Instant.now().plus(STATE_TTL)));
        return UriComponentsBuilder.fromUriString("https://slack.com/oauth/v2/authorize")
                .queryParam("client_id", clientId).queryParam("scope", SCOPES)
                .queryParam("redirect_uri", redirectUri).queryParam("state", state)
                .build().toUriString();
    }

    /** Consumes the state (single use), exchanges the code, and stores the encrypted bot token. */
    @Transactional
    public void completeInstall(String code, String state) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "delete from slack_oauth_state where state_hash = ? and expires_at > now() returning org_id, user_id",
                TokenHasher.sha256Hex(state));
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown or expired Slack state");
        UUID orgId = (UUID) rows.get(0).get("org_id");
        UUID userId = (UUID) rows.get(0).get("user_id");
        var result = client.exchangeCode(code, redirectUri);
        var encrypted = cipher.encrypt(result.botToken().getBytes(StandardCharsets.UTF_8));
        installations.save(new SlackInstallation(orgId, result.teamId(), encrypted.ciphertext(), encrypted.nonce(),
                userId, Instant.now()));
    }

    @Transactional
    public void uninstall(UUID orgId) {
        jdbc.update("delete from slack_user_link where org_id = ?", orgId);
        installations.deleteById(orgId);
    }

    @Transactional
    public void linkMe(UUID userId, UUID orgId) {
        String botToken = botToken(orgId);
        String email = userRepository.findById(userId).orElseThrow().getEmail();
        String slackUserId = client.lookupUserIdByEmail(botToken, email)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "SLACK_USER_NOT_FOUND",
                        "No one in your Slack workspace uses " + email));
        links.save(new SlackUserLink(userId, orgId, slackUserId));
    }

    public boolean isReachable(UUID userId, UUID orgId) {
        return installations.existsById(orgId) && links.existsById(new SlackUserLink.Key(userId, orgId));
    }

    public void send(UUID orgId, UUID userId, String text) {
        String slackUserId = links.findById(new SlackUserLink.Key(userId, orgId))
                .orElseThrow(() -> new IllegalStateException("User is not linked to Slack")).getSlackUserId();
        client.postMessage(botToken(orgId), slackUserId, text);
    }

    private String botToken(UUID orgId) {
        SlackInstallation installation = installations.findById(orgId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.CONFLICT, "SLACK_NOT_INSTALLED",
                        "Your workspace admin hasn't connected Slack yet"));
        return new String(cipher.decrypt(installation.getBotTokenEncrypted(), installation.getBotTokenNonce()),
                StandardCharsets.UTF_8);
    }
}
```

`notifications/SlackController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.MeController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
public class SlackController {

    private final SlackService slackService;
    private final String webBaseUrl;

    public SlackController(SlackService slackService, @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.slackService = slackService;
        this.webBaseUrl = webBaseUrl;
    }

    @GetMapping("/api/v1/orgs/{orgId}/integrations/slack/install-url")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public Map<String, String> installUrl(@PathVariable UUID orgId) {
        var claims = requireOrg(orgId);
        return Map.of("url", slackService.installUrl(orgId, claims.userId()));
    }

    @GetMapping("/api/v1/integrations/slack/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state) {
        String outcome;
        try {
            if (code == null || state == null) throw new IllegalArgumentException("Missing code or state");
            slackService.completeInstall(code, state);
            outcome = "connected";
        } catch (RuntimeException e) {
            outcome = "failed";
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(webBaseUrl + "/settings/notifications?slack=" + outcome)).build();
    }

    @DeleteMapping("/api/v1/orgs/{orgId}/integrations/slack")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public ResponseEntity<Void> uninstall(@PathVariable UUID orgId) {
        requireOrg(orgId);
        slackService.uninstall(orgId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/me/slack-link")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> linkMe() {
        var claims = MeController.currentClaims();
        slackService.linkMe(claims.userId(), claims.orgId());
        return ResponseEntity.noContent().build();
    }

    private static com.agentstore.platform.identity.JwtService.Claims requireOrg(UUID orgId) {
        var claims = MeController.currentClaims();
        if (!claims.orgId().equals(orgId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your workspace");
        return claims;
    }
}
```

If `completeInstall` fails after deleting the state row, its transaction rolls back and the state row is restored. The same state can then be retried until it expires, but never used twice successfully.

- [ ] **Step 6: Turn Slack on in the consumer and the delivery job**

In `NotificationConsumer`, add the dependency `SlackService slackService` and replace `slackReachable`:

```java
    boolean slackReachable(UUID userId, UUID orgId) {
        return slackService.isReachable(userId, orgId);
    }
```

In `ChannelDelivery`, add the dependency `SlackService slackService` and replace `deliverSlack`:

```java
    boolean deliverSlack(Notification n) {
        String url = null;
        if (n.getCategory() == NotificationCategory.APPROVAL_NEEDED) {
            Optional<String> token = openGate(n).flatMap(run -> approvalLinkService.mint(run, n.getUserId(),
                    DecisionChannel.SLACK_LINK));
            if (token.isEmpty()) return false;
            url = messages.approvalUrl(token.get());
        }
        slackService.send(n.getOrgId(), n.getUserId(), messages.slackText(n, url));
        return true;
    }
```

- [ ] **Step 7: Run the tests**

Run: `mvn -q test -Dtest='SlackTest,ApprovalLinkTest,PreferencesTest,NotificationCentreTest'`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): Slack install, account linking and approval requests as direct messages"
```

---

### Task 5: Record decisions in the spec and verify everything

**Files:**
- Modify: `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (WS14 section, §8 Q10)

- [ ] **Step 1: Update the spec**

In §5 WS14:
- In the `approval_link` DDL, replace `gate_request_id UUID NOT NULL REFERENCES gate_request(id)` with `run_id` + `gate_opened_at` and this note: *"Until WS1 introduces `gate_request`, a gate instance is identified by run and the moment it opened. WS1 adds `gate_request_id` and backfills it."* Add the `decided_ip` and `decided_user_agent` columns.
- Replace *"Header badge: `unreadCount` is included in `GET /me`"* with *"`GET /api/v1/notifications/unread-count` (kept out of `/me` so identity does not depend on notifications)."*
- Add to **Design**: *"Recipients and the notification row are created by the `notifications` outbox consumer. Email and Slack are sent by a separate ShedLock job, at least once, and an approval whose gate has closed is skipped rather than sent."*
- Add a **Deferred** line: `WEEKLY_SUMMARY` digest job (reporting work) and `MORE_ACCESS_REQUESTED` producer (WS11). Both preferences exist now.

In §8 Q10, append: *"Implemented: per-user, single-use links bound to the gate instance; GET never decides; the org setting `approval_links_require_login` defaults to off."*

- [ ] **Step 2: Full backend suite**

Run (from `backend/`): `mvn -q test`
Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md
git commit -m "docs(spec): record WS14 implementation decisions"
```
