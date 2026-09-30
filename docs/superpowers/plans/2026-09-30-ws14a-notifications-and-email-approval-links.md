# WS14a Notifications and Email Approval Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Approvers are notified of an opened gate and can approve or reject it from an emailed one-tap link; other run events land in an in-app notification centre with per-person preferences and quiet hours.

**Architecture:** A `notifications` outbox consumer (WS15b) writes one `notification` row per recipient, recording whether an email is owed and when. A ShedLock-guarded job claims due rows with a lease, sends the email outside any outbox transaction, and mints a single-use approval link just before sending. Public `GET`/`POST /api/v1/approvals/{token}` show the card and decide through `GateService.decide` with `DecisionChannel.EMAIL_LINK`, so a link never grants more than the person already has.

**Tech Stack:** Java 21, Spring Boot, `JdbcTemplate`/`TransactionTemplate`, PostgreSQL 16 (Flyway), ShedLock, JUnit 5 + AssertJ + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-30-ws14a-notifications-and-email-approval-links-design.md`

## Global Constraints

- Migrations: `V42__notification.sql` (notification, preferences, quiet-hours columns, `run.started_by_user_id`) and `V43__approval_link.sql`. Latest existing is V41.
- New package `com.agentstore.platform.notifications`. Data access uses `JdbcTemplate` like `platform.events`. Pass timestamps as `java.sql.Timestamp`, never `Instant`.
- No foreign keys from `notification` to `run`, `gate_request`, `app_user` or `org`, so cleanup of those never blocks (the spec does not require them).
- Categories, exactly: `APPROVAL_NEEDED`, `RUN_FINISHED`, `RUN_STOPPED_EARLY`, `SPEND_LIMITS`, `WEEKLY_SUMMARY`. Defaults (web/email): APPROVAL_NEEDED on/on, RUN_STOPPED_EARLY on/on, RUN_FINISHED on/off, SPEND_LIMITS on/on, WEEKLY_SUMMARY off/on. Only the first three are produced now.
- Filters: `needs_you` = APPROVAL_NEEDED; `agents` = RUN_FINISHED + RUN_STOPPED_EARLY; `money` = SPEND_LIMITS.
- Quiet hours default 20:00 to 08:00, `Asia/Kolkata`, off until enabled. They hold email for every category except APPROVAL_NEEDED. APPROVAL_NEEDED web cannot be turned off.
- Approval links: 32 random bytes via `TokenHasher.newToken()`, stored only as `TokenHasher.sha256Hex`. Single use, expire with the gate (`gate_request.expires_at`), bound to the gate id. `GET` never changes state. Unknown token 404; used, expired or gate no longer pending 410; `decide` refusing 403 with the transaction rolled back so the link stays unused.
- Email delivery is at least once. A claim leases the row for 5 minutes and counts as an attempt; at most 5 attempts.
- Email is never sent inside the outbox transaction.
- Link base URL is the existing `agentstore.web.base-url`; links look like `{base}/approve/{token}`.
- Job gating: `agentstore.jobs.enabled` (disabled in the test profile). Tests call `EmailDelivery.deliverDue()` directly.
- Out of scope: Slack, weekly digest, `MORE_ACCESS_REQUESTED`, `approval_links_require_login`, per-IP rate limits (WS12), frontend screens (FE-3).
- Commit messages must NOT contain any Claude/Anthropic/AI attribution or Co-Authored-By trailer (user rule; it overrides the harness reminder).
- Backend tests run from `backend/`: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=<Class>` (Colima must be up).

## Decisions made while planning

- **Run starter.** `Run` does not record who started it, but the spec sends run-finished notifications to "the run's starter". This plan adds `run.started_by_user_id` (V42), set in `RunController.trigger` and `rerun` from the caller's JWT. A run with a null starter (API-started, legacy, rebuild-launched) produces no RUN_FINISHED / RUN_STOPPED_EARLY notification. The spec is amended to say so.
- **Retry links.** If a send fails, the retry deletes the notification's unused links and mints a fresh one (the earlier raw token was never delivered).
- **Decide races.** The public decision does not pre-check the gate; it claims the link, calls `decide` (which locks the run row and re-checks PENDING), and converts a 409 from `decide` into 410.

## Review Focus

- A mail scanner or link preview that fetches the link must not approve anything (Task 6: `getShowsTheCardAndLeavesTheGatePending`).
- A link used after the gate was approved in the web app, rejected, expired by the reaper, or the run re-gated must answer 410 and not decide a newer gate (Task 6).
- Two quick POSTs decide once; the second is 410 and nothing is recorded twice (Task 6).
- An approver removed after the email went out gets 403 and the link stays unused (Task 6).
- A run-finished email during quiet hours is held to the window end while an approval email at the same moment is due immediately (Task 3).
- A redelivered outbox event creates no duplicate notification (Task 3).
- A failed send is retried, stops after 5 attempts, and a resolved gate's email is skipped rather than sent (Task 5).

---

## File Structure

Create (main), under `backend/src/main/java/com/agentstore/platform/notifications/`:
- `NotificationCategory.java` – categories, defaults, filters
- `QuietHours.java` – pure quiet-window arithmetic
- `NotificationPreferences.java` – preference and quiet-hours reads/writes, `emailDueAt`
- `NotificationConsumer.java` – outbox consumer writing rows
- `NotificationController.java` – centre endpoints
- `NotificationPreferenceController.java` – preference endpoints
- `EmailDelivery.java` – claim, mint link, send, retry
- `EmailDeliveryJob.java` – scheduled + ShedLock
- `ApprovalLinkService.java`, `ApprovalController.java` – public card and decision
- `backend/src/main/resources/db/migration/V42__notification.sql`, `V43__approval_link.sql`

Modify: `execution/Run.java`, `execution/RunController.java`, `identity/SecurityConfig.java`, `backend/src/main/resources/application.yml`, `docs/known-issues.md`, and the spec (Task 7).

Create (test), under `backend/src/test/java/com/agentstore/platform/notifications/`: `QuietHoursTest`, `NotificationPreferencesTest`, `NotificationConsumerTest`, `NotificationApiTest`, `EmailDeliveryTest`, `ApprovalLinkTest`. Modify `execution/RunRerunTest.java`.

---

### Task 1: Categories and quiet-hours arithmetic

**Files:**
- Create: `.../notifications/NotificationCategory.java`
- Create: `.../notifications/QuietHours.java`
- Test: `backend/src/test/java/com/agentstore/platform/notifications/QuietHoursTest.java`

**Interfaces:**
- Produces:
  - `enum NotificationCategory { APPROVAL_NEEDED, RUN_FINISHED, RUN_STOPPED_EARLY, SPEND_LIMITS, WEEKLY_SUMMARY; boolean defaultWeb(); boolean defaultEmail(); String filter(); static List<NotificationCategory> inFilter(String filter) }` (`inFilter` returns the categories for `needs_you|agents|money`, or throws `IllegalArgumentException` for anything else)
  - `static Instant QuietHours.nextAllowed(Instant now, LocalTime start, LocalTime end, ZoneId zone)`: `now` outside the window `[start, end)`, otherwise the next instant the window ends. Windows may wrap midnight.

- [ ] **Step 1: Write the failing test**

`QuietHoursTest.java`:

```java
package com.agentstore.platform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuietHoursTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);
    private static final LocalTime EIGHT_AM = LocalTime.of(8, 0);

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void outsideTheWindowTheSendIsNotHeld() {
        Instant noon = at("2026-09-30T12:00:00Z");
        assertThat(QuietHours.nextAllowed(noon, EIGHT_PM, EIGHT_AM, UTC)).isEqualTo(noon);
    }

    @Test
    void inTheEveningPartOfAWrappingWindowItHoldsUntilNextMorning() {
        assertThat(QuietHours.nextAllowed(at("2026-09-30T21:00:00Z"), EIGHT_PM, EIGHT_AM, UTC))
                .isEqualTo(at("2026-10-01T08:00:00Z"));
    }

    @Test
    void inTheEarlyMorningPartOfAWrappingWindowItHoldsUntilThatMorning() {
        assertThat(QuietHours.nextAllowed(at("2026-09-30T03:00:00Z"), EIGHT_PM, EIGHT_AM, UTC))
                .isEqualTo(at("2026-09-30T08:00:00Z"));
    }

    @Test
    void theStartIsInsideAndTheEndIsOutside() {
        assertThat(QuietHours.nextAllowed(at("2026-09-30T20:00:00Z"), EIGHT_PM, EIGHT_AM, UTC))
                .isEqualTo(at("2026-10-01T08:00:00Z"));
        Instant end = at("2026-09-30T08:00:00Z");
        assertThat(QuietHours.nextAllowed(end, EIGHT_PM, EIGHT_AM, UTC)).isEqualTo(end);
    }

    @Test
    void aNonWrappingWindowHoldsInsideAndReleasesOutside() {
        LocalTime one = LocalTime.of(13, 0);
        LocalTime three = LocalTime.of(15, 0);
        assertThat(QuietHours.nextAllowed(at("2026-09-30T14:00:00Z"), one, three, UTC))
                .isEqualTo(at("2026-09-30T15:00:00Z"));
        Instant later = at("2026-09-30T16:00:00Z");
        assertThat(QuietHours.nextAllowed(later, one, three, UTC)).isEqualTo(later);
    }

    @Test
    void theWindowIsReadInThePersonsTimezone() {
        // 15:00Z is 20:30 in Kolkata (UTC+5:30), inside 20:00-08:00; the window ends 08:00 IST = 02:30Z next day.
        assertThat(QuietHours.nextAllowed(at("2026-09-30T15:00:00Z"), EIGHT_PM, EIGHT_AM, ZoneId.of("Asia/Kolkata")))
                .isEqualTo(at("2026-10-01T02:30:00Z"));
    }

    @Test
    void categoriesCarryTheirDefaultsAndFilters() {
        assertThat(NotificationCategory.APPROVAL_NEEDED.defaultWeb()).isTrue();
        assertThat(NotificationCategory.APPROVAL_NEEDED.defaultEmail()).isTrue();
        assertThat(NotificationCategory.RUN_FINISHED.defaultEmail()).isFalse();
        assertThat(NotificationCategory.RUN_STOPPED_EARLY.defaultEmail()).isTrue();
        assertThat(NotificationCategory.WEEKLY_SUMMARY.defaultWeb()).isFalse();
        assertThat(NotificationCategory.inFilter("needs_you")).containsExactly(NotificationCategory.APPROVAL_NEEDED);
        assertThat(NotificationCategory.inFilter("agents")).containsExactlyInAnyOrder(
                NotificationCategory.RUN_FINISHED, NotificationCategory.RUN_STOPPED_EARLY);
        assertThat(NotificationCategory.inFilter("money")).isEqualTo(List.of(NotificationCategory.SPEND_LIMITS));
        assertThatThrownBy(() -> NotificationCategory.inFilter("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run (from `backend/`): `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=QuietHoursTest`
Expected: compilation FAIL (`QuietHours`, `NotificationCategory` do not exist).

- [ ] **Step 3: Write the implementation**

`NotificationCategory.java`:

```java
package com.agentstore.platform.notifications;

import java.util.Arrays;
import java.util.List;

/** What a notification is about. The defaults are the channels on for a person who has changed nothing. */
public enum NotificationCategory {
    APPROVAL_NEEDED(true, true, "needs_you"),
    RUN_FINISHED(true, false, "agents"),
    RUN_STOPPED_EARLY(true, true, "agents"),
    SPEND_LIMITS(true, true, "money"),
    WEEKLY_SUMMARY(false, true, null);

    private final boolean defaultWeb;
    private final boolean defaultEmail;
    private final String filter;

    NotificationCategory(boolean defaultWeb, boolean defaultEmail, String filter) {
        this.defaultWeb = defaultWeb;
        this.defaultEmail = defaultEmail;
        this.filter = filter;
    }

    public boolean defaultWeb() {
        return defaultWeb;
    }

    public boolean defaultEmail() {
        return defaultEmail;
    }

    /** The centre tab this category belongs to, or null when it only shows unfiltered. */
    public String filter() {
        return filter;
    }

    public static List<NotificationCategory> inFilter(String filter) {
        List<NotificationCategory> matches = Arrays.stream(values()).filter(c -> filter.equals(c.filter)).toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown filter " + filter);
        }
        return matches;
    }
}
```

`QuietHours.java`:

```java
package com.agentstore.platform.notifications;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Quiet-window arithmetic. The window is {@code [start, end)} in the person's zone and may wrap midnight. */
public final class QuietHours {

    private QuietHours() {
    }

    /** {@code now} when it is outside the window; otherwise the next instant the window ends. */
    public static Instant nextAllowed(Instant now, LocalTime start, LocalTime end, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        LocalTime time = local.toLocalTime();
        boolean inside = start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
        if (!inside) {
            return now;
        }
        ZonedDateTime endToday = local.toLocalDate().atTime(end).atZone(zone);
        return (endToday.isAfter(local) ? endToday : endToday.plusDays(1)).toInstant();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=QuietHoursTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/notifications backend/src/test/java/com/agentstore/platform/notifications
git commit -m "feat(notifications): categories and quiet-hours arithmetic"
```

---

### Task 2: V42 schema, run starter and preference service

**Files:**
- Create: `backend/src/main/resources/db/migration/V42__notification.sql`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/Run.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunController.java` (`trigger`, `rerun`, `start`)
- Modify (test): `backend/src/test/java/com/agentstore/platform/execution/RunRerunTest.java`
- Create: `.../notifications/NotificationPreferences.java`
- Test: `backend/src/test/java/com/agentstore/platform/notifications/NotificationPreferencesTest.java`

**Interfaces:**
- Consumes: `NotificationCategory`, `QuietHours.nextAllowed` (Task 1).
- Produces:
  - `Run.getStartedByUserId()` / `setStartedByUserId(UUID)`; column `run.started_by_user_id`.
  - `record NotificationPreferences.Channels(boolean web, boolean email)`
  - `record NotificationPreferences.QuietWindow(boolean enabled, LocalTime start, LocalTime end, ZoneId zone)`
  - `Channels NotificationPreferences.channels(UUID userId, NotificationCategory category)`
  - `QuietWindow NotificationPreferences.quiet(UUID userId)`
  - `Map<NotificationCategory, Channels> NotificationPreferences.all(UUID userId)`
  - `void NotificationPreferences.save(UUID userId, Map<NotificationCategory, Channels> changes, QuietWindow quietOrNull)`
  - `Instant NotificationPreferences.emailDueAt(UUID userId, NotificationCategory category, Instant now)` (null when email is off; `now` for APPROVAL_NEEDED; quiet-adjusted otherwise)

- [ ] **Step 1: Write the migration**

`V42__notification.sql`:

```sql
CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    org_id          UUID        NOT NULL,
    category        TEXT        NOT NULL,
    title           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    run_id          UUID        NULL,
    gate_id         UUID        NULL,
    source_event_id UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ NULL,
    resolved_at     TIMESTAMPTZ NULL,
    email_due_at    TIMESTAMPTZ NULL,
    email_sent_at   TIMESTAMPTZ NULL,
    email_attempts  INT         NOT NULL DEFAULT 0,
    email_error     TEXT        NULL,
    email_skipped   BOOLEAN     NOT NULL DEFAULT false,
    email_no_link   BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT uq_notification_user_event UNIQUE (user_id, source_event_id)
);
CREATE INDEX idx_notification_user ON notification (user_id, org_id, created_at DESC);
CREATE INDEX idx_notification_gate ON notification (gate_id) WHERE gate_id IS NOT NULL;
CREATE INDEX idx_notification_email_due ON notification (email_due_at)
    WHERE email_sent_at IS NULL AND NOT email_skipped;

CREATE TABLE notification_preference (
    user_id  UUID    NOT NULL,
    category TEXT    NOT NULL,
    web      BOOLEAN NOT NULL,
    email    BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, category)
);

ALTER TABLE app_user
    ADD COLUMN quiet_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN quiet_start   TIME    NOT NULL DEFAULT '20:00',
    ADD COLUMN quiet_end     TIME    NOT NULL DEFAULT '08:00',
    ADD COLUMN quiet_tz      TEXT    NOT NULL DEFAULT 'Asia/Kolkata';

ALTER TABLE run ADD COLUMN started_by_user_id UUID NULL;
```

- [ ] **Step 2: Write the failing tests**

In `RunRerunTest.java` add this test (the class already has `port`, `rest`, `jwtService`, `runRepository`, `fixtures`, `url`, `finishedRun`):

```java
    @Test
    void aRerunRecordsWhoStartedIt() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run original = finishedRun(s);
        UUID starter = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(starter, s.buyer().getId(), UserRole.BUYER_APPROVER));

        var response = rest.exchange(url("/api/v1/runs/" + original.getId() + "/rerun"), HttpMethod.POST,
                new HttpEntity<>(headers), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runRepository.findById(response.getBody().id()).orElseThrow().getStartedByUserId())
                .isEqualTo(starter);
    }
```

`NotificationPreferencesTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferencesTest extends AbstractIntegrationTest {

    @Autowired NotificationPreferences preferences;
    @Autowired IdentityFixtures identities;

    private UUID newUser() {
        return identities.buyerAdmin("prefs-" + UUID.randomUUID() + "@example.com").getUser().getId();
    }

    @Test
    void aPersonWhoChangedNothingGetsTheDefaults() {
        UUID user = newUser();

        assertThat(preferences.channels(user, NotificationCategory.RUN_FINISHED))
                .isEqualTo(new NotificationPreferences.Channels(true, false));
        assertThat(preferences.channels(user, NotificationCategory.APPROVAL_NEEDED))
                .isEqualTo(new NotificationPreferences.Channels(true, true));
        assertThat(preferences.quiet(user)).isEqualTo(new NotificationPreferences.QuietWindow(
                false, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void savedChoicesOverrideTheDefaultsForThatCategoryOnly() {
        UUID user = newUser();

        preferences.save(user, Map.of(NotificationCategory.RUN_FINISHED, new NotificationPreferences.Channels(true, true)),
                null);

        assertThat(preferences.channels(user, NotificationCategory.RUN_FINISHED).email()).isTrue();
        assertThat(preferences.channels(user, NotificationCategory.RUN_STOPPED_EARLY).email()).isTrue();
        assertThat(preferences.all(user)).hasSize(NotificationCategory.values().length);
    }

    @Test
    void quietHoursAreSavedAndReadBack() {
        UUID user = newUser();
        var window = new NotificationPreferences.QuietWindow(true, LocalTime.of(22, 30), LocalTime.of(7, 0),
                ZoneId.of("Europe/London"));

        preferences.save(user, Map.of(), window);

        assertThat(preferences.quiet(user)).isEqualTo(window);
    }

    @Test
    void emailDueAtIsNullWhenEmailIsOff() {
        UUID user = newUser();

        assertThat(preferences.emailDueAt(user, NotificationCategory.RUN_FINISHED, Instant.now())).isNull();
    }

    @Test
    void emailDueAtIsNowOutsideQuietHoursAndHeldInsideThem() {
        UUID user = newUser();
        preferences.save(user, Map.of(NotificationCategory.RUN_STOPPED_EARLY,
                new NotificationPreferences.Channels(true, true)), new NotificationPreferences.QuietWindow(
                true, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("UTC")));
        Instant noon = Instant.parse("2026-09-30T12:00:00Z");
        Instant night = Instant.parse("2026-09-30T21:00:00Z");

        assertThat(preferences.emailDueAt(user, NotificationCategory.RUN_STOPPED_EARLY, noon)).isEqualTo(noon);
        assertThat(preferences.emailDueAt(user, NotificationCategory.RUN_STOPPED_EARLY, night))
                .isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
    }

    @Test
    void anApprovalRequestIsNeverHeldByQuietHours() {
        UUID user = newUser();
        preferences.save(user, Map.of(), new NotificationPreferences.QuietWindow(
                true, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("UTC")));
        Instant night = Instant.parse("2026-09-30T21:00:00Z");

        assertThat(preferences.emailDueAt(user, NotificationCategory.APPROVAL_NEEDED, night)).isEqualTo(night);
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest='NotificationPreferencesTest,RunRerunTest'`
Expected: compilation FAIL (`NotificationPreferences` and `Run.getStartedByUserId` do not exist).

- [ ] **Step 4: Record the starter on `Run`**

In `Run.java`, after `private UUID replayRunId;` add:

```java
    /** The buyer user who started a production run; null for API-started, legacy and rebuild-launched runs. */
    private UUID startedByUserId;
```

In `RunController.java`:
- `trigger`: change `return start(connection);` to `return start(connection, claims.userId());`
- `rerun`: change `return start(original.getConnection());` to `return start(original.getConnection(), callerUserId());`
- `start`: change the signature to `private RunResponse start(Connection connection, UUID startedByUserId) {` and, right after `pending.setReservedAmount(spendCap);`, add `pending.setStartedByUserId(startedByUserId);`.

- [ ] **Step 5: Write the preference service**

`NotificationPreferences.java`:

```java
package com.agentstore.platform.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A person's channel choices per category and their quiet hours. Anything not saved falls back to the defaults. */
@Component
public class NotificationPreferences {

    public record Channels(boolean web, boolean email) {
    }

    public record QuietWindow(boolean enabled, LocalTime start, LocalTime end, ZoneId zone) {
    }

    private static final QuietWindow DEFAULT_QUIET =
            new QuietWindow(false, LocalTime.of(20, 0), LocalTime.of(8, 0), ZoneId.of("Asia/Kolkata"));

    private final JdbcTemplate jdbc;

    public NotificationPreferences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Channels channels(UUID userId, NotificationCategory category) {
        List<Channels> saved = jdbc.query("select web, email from notification_preference where user_id = ? and category = ?",
                (rs, i) -> new Channels(rs.getBoolean("web"), rs.getBoolean("email")), userId, category.name());
        return saved.isEmpty() ? new Channels(category.defaultWeb(), category.defaultEmail()) : saved.get(0);
    }

    public Map<NotificationCategory, Channels> all(UUID userId) {
        Map<NotificationCategory, Channels> result = new EnumMap<>(NotificationCategory.class);
        for (NotificationCategory category : NotificationCategory.values()) {
            result.put(category, new Channels(category.defaultWeb(), category.defaultEmail()));
        }
        jdbc.query("select category, web, email from notification_preference where user_id = ?", rs -> {
            result.put(NotificationCategory.valueOf(rs.getString("category")),
                    new Channels(rs.getBoolean("web"), rs.getBoolean("email")));
        }, userId);
        return result;
    }

    public QuietWindow quiet(UUID userId) {
        List<QuietWindow> rows = jdbc.query(
                "select quiet_enabled, quiet_start, quiet_end, quiet_tz from app_user where id = ?",
                (rs, i) -> new QuietWindow(rs.getBoolean("quiet_enabled"), rs.getObject("quiet_start", LocalTime.class),
                        rs.getObject("quiet_end", LocalTime.class), ZoneId.of(rs.getString("quiet_tz"))), userId);
        return rows.isEmpty() ? DEFAULT_QUIET : rows.get(0);
    }

    @Transactional
    public void save(UUID userId, Map<NotificationCategory, Channels> changes, QuietWindow quietOrNull) {
        changes.forEach((category, channels) -> jdbc.update(
                "insert into notification_preference (user_id, category, web, email) values (?, ?, ?, ?) "
                        + "on conflict (user_id, category) do update set web = excluded.web, email = excluded.email",
                userId, category.name(), channels.web(), channels.email()));
        if (quietOrNull != null) {
            jdbc.update("update app_user set quiet_enabled = ?, quiet_start = ?, quiet_end = ?, quiet_tz = ? where id = ?",
                    quietOrNull.enabled(), quietOrNull.start(), quietOrNull.end(), quietOrNull.zone().getId(), userId);
        }
    }

    /** When this person's email for the category is due: null if email is off, otherwise now or the end of quiet hours. */
    public Instant emailDueAt(UUID userId, NotificationCategory category, Instant now) {
        if (!channels(userId, category).email()) {
            return null;
        }
        if (category == NotificationCategory.APPROVAL_NEEDED) {
            return now;
        }
        QuietWindow quiet = quiet(userId);
        return quiet.enabled() ? QuietHours.nextAllowed(now, quiet.start(), quiet.end(), quiet.zone()) : now;
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest='NotificationPreferencesTest,RunRerunTest'`
Expected: PASS (6 tests in `NotificationPreferencesTest`, all in `RunRerunTest`). If `rs.getObject(..., LocalTime.class)` is unsupported, read `rs.getTime(..).toLocalTime()` instead.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): notification schema, run starter and preference service"
```

---

### Task 3: The notification consumer

**Files:**
- Create: `.../notifications/NotificationConsumer.java`
- Test: `backend/src/test/java/com/agentstore/platform/notifications/NotificationConsumerTest.java`

**Interfaces:**
- Consumes: `NotificationPreferences.channels/emailDueAt/quiet/save` and `NotificationPreferences.timestamp` (Task 2); `DomainEventConsumer`, `DomainEvent` (WS15b); events `GATE_OPENED {runId, gateId, action, summary, expiresAt, eligibleApproverIds}`, `GATE_DECIDED {runId, gateId, ...}`, `GATE_EXPIRED {runId, gateId}`, `RUN_FINISHED {runId, status, terminalReason}`.
- Produces: consumer bean named `notifications` writing `notification` rows (unique per `(user_id, source_event_id)`).

- [ ] **Step 1: Write the failing tests**

`NotificationConsumerTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConsumerTest extends AbstractIntegrationTest {

    @Autowired NotificationConsumer consumer;
    @Autowired NotificationPreferences preferences;
    @Autowired ExecutionFixtures fixtures;
    @Autowired IdentityFixtures identities;
    @Autowired GateService gateService;
    @Autowired RunLifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    private Run runningRun(ExecutionFixtures.Scenario s) {
        String token = "tok-nc-" + UUID.randomUUID();
        return fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
    }

    /** The outbox event the real flow published for this aggregate, as a consumer would receive it. */
    private DomainEvent eventOf(UUID aggregateId, String type) {
        return jdbc.query("select id, type, org_id, aggregate_id, payload::text as payload, created_at from domain_event "
                        + "where aggregate_id = ? and type = ?", (rs, i) -> {
                    try {
                        return new DomainEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                mapper.readTree(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, aggregateId, type).get(0);
    }

    private List<Map<String, Object>> rows(UUID userId) {
        return jdbc.queryForList("select category, title, gate_id, run_id, email_due_at, email_no_link, resolved_at "
                + "from notification where user_id = ?", userId);
    }

    private static Instant due(Map<String, Object> row) {
        Timestamp t = (Timestamp) row.get("email_due_at");
        return t == null ? null : t.toInstant();
    }

    @Test
    void gateOpenedNotifiesEachSnapshotApproverWithAnEmailDueNow() {
        var s = scenario();
        UUID a = fixtures.approver(s).getId();
        UUID b = fixtures.approver(s).getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post 12 entries", "{}", "[]");

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        for (UUID user : List.of(a, b)) {
            assertThat(rows(user)).singleElement().satisfies(row -> {
                assertThat(row.get("category")).isEqualTo("APPROVAL_NEEDED");
                assertThat(row.get("title").toString()).contains("Post 12 entries");
                assertThat(row.get("gate_id")).isEqualTo(gate.getId());
                assertThat(row.get("email_no_link")).isEqualTo(false);
                assertThat(due(row)).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
            });
        }
    }

    @Test
    void gateOpenedInAnOrgWithNoApproversNotifiesAdminsWithoutALink() {
        var s = scenario();
        UUID admin = identities.member(s.buyer(), "admin-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_ADMIN, "Finance lead").getUser().getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        assertThat(gate.eligibleApproverIds()).isNull();

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        assertThat(rows(admin)).singleElement().satisfies(row -> {
            assertThat(row.get("email_no_link")).isEqualTo(true);
            assertThat(due(row)).isNotNull();
        });
    }

    @Test
    void aRedeliveredEventCreatesNoDuplicateRows() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        DomainEvent event = eventOf(gate.getId(), "GATE_OPENED");

        consumer.handle(event);
        consumer.handle(event);

        assertThat(rows(approver)).hasSize(1);
    }

    @Test
    void anApproverWhoTurnedEmailOffStillGetsTheWebRowButNoEmailIsDue() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        preferences.save(approver, Map.of(NotificationCategory.APPROVAL_NEEDED,
                new NotificationPreferences.Channels(true, false)), null);
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        assertThat(rows(approver)).singleElement().satisfies(row -> assertThat(due(row)).isNull());
    }

    @Test
    void aFinishedRunNotifiesItsStarterOnTheWebOnlyByDefault() {
        var s = scenario();
        UUID starter = UUID.randomUUID();
        Run run = runningRun(s);
        jdbc.update("update run set started_by_user_id = ? where id = ?", starter, run.getId());
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        assertThat(rows(starter)).singleElement().satisfies(row -> {
            assertThat(row.get("category")).isEqualTo("RUN_FINISHED");
            assertThat(due(row)).isNull();
        });
    }

    @Test
    void aFailedRunIsAStoppedEarlyNotificationWithAnEmailDue() {
        var s = scenario();
        UUID starter = UUID.randomUUID();
        Run run = runningRun(s);
        jdbc.update("update run set started_by_user_id = ? where id = ?", starter, run.getId());
        lifecycle.fail(run.getId(), Map.of("error", "boom"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        assertThat(rows(starter)).singleElement().satisfies(row -> {
            assertThat(row.get("category")).isEqualTo("RUN_STOPPED_EARLY");
            assertThat(due(row)).isNotNull();
        });
    }

    @Test
    void aRunWithNoStarterNotifiesNobody() {
        var s = scenario();
        Run run = runningRun(s);
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        assertThat(jdbc.queryForObject("select count(*) from notification where run_id = ?", Integer.class, run.getId()))
                .isZero();
    }

    @Test
    void duringQuietHoursARunFinishedEmailIsHeldButAnApprovalEmailIsDueNow() {
        var s = scenario();
        UUID person = fixtures.approver(s).getId();
        LocalTime nowUtc = LocalTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MINUTES);
        preferences.save(person, Map.of(NotificationCategory.RUN_STOPPED_EARLY,
                new NotificationPreferences.Channels(true, true)), new NotificationPreferences.QuietWindow(
                true, nowUtc.minusHours(1), nowUtc.plusHours(1), ZoneId.of("UTC")));
        Run run = runningRun(s);
        jdbc.update("update run set started_by_user_id = ? where id = ?", person, run.getId());
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));
        gateService.decide(run.getId(), gate.getId(), true, person, null, DecisionChannel.WEB);
        lifecycle.fail(run.getId(), Map.of("error", "boom"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        Map<String, Object> approval = rows(person).stream()
                .filter(r -> r.get("category").equals("APPROVAL_NEEDED")).findFirst().orElseThrow();
        Map<String, Object> finished = rows(person).stream()
                .filter(r -> r.get("category").equals("RUN_STOPPED_EARLY")).findFirst().orElseThrow();
        assertThat(due(approval)).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
        assertThat(Duration.between(Instant.now(), due(finished)))
                .isBetween(Duration.ofMinutes(50), Duration.ofMinutes(70));
    }

    @Test
    void gateDecidedAndGateExpiredResolveTheApprovalNotifications() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        Run decidedRun = runningRun(s);
        GateRequest decided = gateService.open(decidedRun.getId(), "a", 5, "s", "{}", "[]");
        consumer.handle(eventOf(decided.getId(), "GATE_OPENED"));
        gateService.decide(decidedRun.getId(), decided.getId(), true, approver, null, DecisionChannel.WEB);

        consumer.handle(eventOf(decided.getId(), "GATE_DECIDED"));

        assertThat(rows(approver)).singleElement().satisfies(row -> assertThat(row.get("resolved_at")).isNotNull());

        var s2 = scenario();
        UUID approver2 = fixtures.approver(s2).getId();
        Run expiredRun = runningRun(s2);
        GateRequest expired = gateService.open(expiredRun.getId(), "a", 5, "s", "{}", "[]");
        consumer.handle(eventOf(expired.getId(), "GATE_OPENED"));
        lifecycle.fail(expiredRun.getId(), Map.of("error", "boom"));   // ends the run, expiring the pending gate

        consumer.handle(eventOf(expired.getId(), "GATE_EXPIRED"));

        assertThat(rows(approver2)).singleElement().satisfies(row -> assertThat(row.get("resolved_at")).isNotNull());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=NotificationConsumerTest`
Expected: compilation FAIL (`NotificationConsumer` does not exist).

- [ ] **Step 3: Write the consumer**

`NotificationConsumer.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.events.DomainEventConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns gate and run events into notification rows. It only writes rows: email goes out later from
 * {@link EmailDelivery}. Redelivery is harmless because a row is unique per (person, source event).
 */
@Component
public class NotificationConsumer implements DomainEventConsumer {

    private static final Set<String> TYPES = Set.of("GATE_OPENED", "RUN_FINISHED", "GATE_DECIDED", "GATE_EXPIRED");

    private final JdbcTemplate jdbc;
    private final NotificationPreferences preferences;

    public NotificationConsumer(JdbcTemplate jdbc, NotificationPreferences preferences) {
        this.jdbc = jdbc;
        this.preferences = preferences;
    }

    @Override
    public String name() {
        return "notifications";
    }

    @Override
    public boolean handles(String type) {
        return TYPES.contains(type);
    }

    @Override
    public void handle(DomainEvent event) {
        switch (event.type()) {
            case "GATE_OPENED" -> gateOpened(event);
            case "RUN_FINISHED" -> runFinished(event);
            default -> resolveGate(event);
        }
    }

    private void gateOpened(DomainEvent event) {
        if (event.orgId() == null) {
            return;   // a sandbox gate has no buyer org to notify
        }
        JsonNode payload = event.payload();
        UUID runId = UUID.fromString(payload.get("runId").asText());
        UUID gateId = UUID.fromString(payload.get("gateId").asText());
        String summary = payload.get("summary").asText();
        String action = payload.get("action").asText();
        String displayId = jdbc.queryForObject("select display_id from run where id = ?", String.class, runId);
        String title = "Approval needed: " + summary;
        String body = "Run " + displayId + " is waiting for your approval to " + action + ".";

        JsonNode eligible = payload.get("eligibleApproverIds");
        List<UUID> recipients = new ArrayList<>();
        boolean noLink = eligible == null || eligible.isNull();
        if (noLink) {
            recipients.addAll(jdbc.queryForList("select user_id from org_membership where org_id = ? and role = 'BUYER_ADMIN'",
                    UUID.class, event.orgId()));
        } else {
            eligible.forEach(id -> recipients.add(UUID.fromString(id.asText())));
        }
        Instant now = Instant.now();
        for (UUID user : recipients) {
            insert(user, event.orgId(), NotificationCategory.APPROVAL_NEEDED, title, body, runId, gateId, event.id(),
                    preferences.emailDueAt(user, NotificationCategory.APPROVAL_NEEDED, now), noLink);
        }
    }

    private void runFinished(DomainEvent event) {
        if (event.orgId() == null) {
            return;
        }
        UUID runId = UUID.fromString(event.payload().get("runId").asText());
        var row = jdbc.queryForMap("select r.started_by_user_id, r.display_id, a.name as agent_name "
                + "from run r join agent_version av on av.id = r.agent_version_id join agent a on a.id = av.agent_id "
                + "where r.id = ?", runId);
        UUID starter = (UUID) row.get("started_by_user_id");
        if (starter == null) {
            return;
        }
        String status = event.payload().get("status").asText();
        NotificationCategory category = "COMPLETED".equals(status)
                ? NotificationCategory.RUN_FINISHED : NotificationCategory.RUN_STOPPED_EARLY;
        var channels = preferences.channels(starter, category);
        if (!channels.web() && !channels.email()) {
            return;
        }
        String title = category == NotificationCategory.RUN_FINISHED
                ? row.get("agent_name") + " finished" : row.get("agent_name") + " stopped early";
        String body = "Run " + row.get("display_id") + " ended with status " + status + ".";
        insert(starter, event.orgId(), category, title, body, runId, null, event.id(),
                preferences.emailDueAt(starter, category, Instant.now()), false);
    }

    private void resolveGate(DomainEvent event) {
        UUID gateId = UUID.fromString(event.payload().get("gateId").asText());
        jdbc.update("update notification set resolved_at = now() "
                + "where gate_id = ? and category = 'APPROVAL_NEEDED' and resolved_at is null", gateId);
    }

    private void insert(UUID userId, UUID orgId, NotificationCategory category, String title, String body, UUID runId,
                        UUID gateId, UUID eventId, Instant emailDueAt, boolean noLink) {
        jdbc.update("insert into notification (id, user_id, org_id, category, title, body, run_id, gate_id, "
                        + "source_event_id, email_due_at, email_no_link) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (user_id, source_event_id) do nothing",
                UUID.randomUUID(), userId, orgId, category.name(), title, body, runId, gateId, eventId,
                NotificationPreferences.timestamp(emailDueAt), noLink);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=NotificationConsumerTest`
Expected: PASS (9 tests). If `lifecycle.fail(...)`'s signature differs, mirror how `RunLifecycleServiceTest` fails a run; the tests only need a FAILED terminal run. `GATE_EXPIRED` is published by `finish` for a run that ends with a pending gate.

- [ ] **Step 5: Run the outbox and gate suites to confirm the new bean broke nothing**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest='OutboxDispatcherTest,DomainEventPublisherTest,GateEventsTest,GateServiceTest'`
Expected: PASS. (The consumer now creates `notifications` deliveries on every gate and run event; those tests clean or ignore deliveries they do not own.)

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): outbox consumer that writes notification rows"
```

---

### Task 4: Notification centre and preferences API

**Files:**
- Create: `.../notifications/NotificationController.java`
- Create: `.../notifications/NotificationPreferenceController.java`
- Test: `backend/src/test/java/com/agentstore/platform/notifications/NotificationApiTest.java`

**Interfaces:**
- Consumes: `NotificationPreferences` (Task 2), `NotificationCategory.inFilter` (Task 1), `JwtService.Claims(userId, orgId, role)`.
- Produces (JWT-authenticated):
  - `GET /api/v1/notifications?filter=&unread=&limit=` → `[{id, category, title, body, runId, gateId, createdAt, readAt, resolved}]`
  - `GET /api/v1/notifications/unread-count` → `{count}`
  - `POST /api/v1/notifications/{id}/read` → 204 (404 if not the caller's), `POST /api/v1/notifications/read-all` → 204
  - `GET`/`PUT /api/v1/notification-preferences` with body `{categories: [{category, web, email}], quietHours: {enabled, start, end, timezone}}` (both parts optional on PUT)

- [ ] **Step 1: Write the failing tests**

`NotificationApiTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.OrgMembership;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationApiTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identities;
    @Autowired JdbcTemplate jdbc;

    private OrgMembership person() {
        return identities.buyerAdmin("centre-" + UUID.randomUUID() + "@example.com");
    }

    private HttpEntity<Object> as(OrgMembership m, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identities.tokenFor(m));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private UUID seed(OrgMembership m, String category, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notification (id, user_id, org_id, category, title, body, source_event_id) "
                        + "values (?, ?, ?, ?, ?, 'b', ?)",
                id, m.getUser().getId(), m.getOrg().getId(), category, title, UUID.randomUUID());
        return id;
    }

    @Test
    void aPersonSeesOnlyTheirOwnNotificationsInTheirCurrentWorkspace() {
        OrgMembership me = person();
        OrgMembership other = person();
        seed(me, "APPROVAL_NEEDED", "mine");
        seed(other, "APPROVAL_NEEDED", "theirs");

        JsonNode list = rest.exchange(url("/api/v1/notifications"), HttpMethod.GET, as(me, null), JsonNode.class).getBody();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("title").asText()).isEqualTo("mine");
    }

    @Test
    void theFiltersSelectTheirCategories() {
        OrgMembership me = person();
        seed(me, "APPROVAL_NEEDED", "approve");
        seed(me, "RUN_FINISHED", "finished");

        JsonNode needsYou = rest.exchange(url("/api/v1/notifications?filter=needs_you"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();
        JsonNode agents = rest.exchange(url("/api/v1/notifications?filter=agents"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();

        assertThat(needsYou).hasSize(1);
        assertThat(needsYou.get(0).get("title").asText()).isEqualTo("approve");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).get("title").asText()).isEqualTo("finished");
        assertThat(rest.exchange(url("/api/v1/notifications?filter=bogus"), HttpMethod.GET, as(me, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unreadCountFallsAsNotificationsAreRead() {
        OrgMembership me = person();
        UUID first = seed(me, "RUN_FINISHED", "a");
        seed(me, "RUN_FINISHED", "b");

        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET, as(me, null), JsonNode.class)
                .getBody().get("count").asInt()).isEqualTo(2);

        assertThat(rest.exchange(url("/api/v1/notifications/" + first + "/read"), HttpMethod.POST, as(me, null),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET, as(me, null), JsonNode.class)
                .getBody().get("count").asInt()).isEqualTo(1);

        rest.exchange(url("/api/v1/notifications/read-all"), HttpMethod.POST, as(me, null), Void.class);
        assertThat(rest.exchange(url("/api/v1/notifications/unread-count"), HttpMethod.GET, as(me, null), JsonNode.class)
                .getBody().get("count").asInt()).isZero();
    }

    @Test
    void aPersonCannotMarkSomeoneElsesNotificationRead() {
        OrgMembership me = person();
        OrgMembership other = person();
        UUID theirs = seed(other, "RUN_FINISHED", "theirs");

        assertThat(rest.exchange(url("/api/v1/notifications/" + theirs + "/read"), HttpMethod.POST, as(me, null),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void preferencesReturnDefaultsAndAcceptChanges() {
        OrgMembership me = person();

        JsonNode initial = rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();
        assertThat(initial.get("categories")).hasSize(NotificationCategory.values().length);
        assertThat(initial.get("quietHours").get("enabled").asBoolean()).isFalse();
        assertThat(initial.get("quietHours").get("timezone").asText()).isEqualTo("Asia/Kolkata");

        Map<String, Object> change = Map.of(
                "categories", List.of(Map.of("category", "RUN_FINISHED", "web", true, "email", true)),
                "quietHours", Map.of("enabled", true, "start", "22:00", "end", "07:30", "timezone", "Europe/London"));
        assertThat(rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.PUT, as(me, change), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode after = rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.GET, as(me, null),
                JsonNode.class).getBody();
        JsonNode finished = java.util.stream.StreamSupport.stream(after.get("categories").spliterator(), false)
                .filter(c -> c.get("category").asText().equals("RUN_FINISHED")).findFirst().orElseThrow();
        assertThat(finished.get("email").asBoolean()).isTrue();
        assertThat(after.get("quietHours").get("start").asText()).isEqualTo("22:00");
        assertThat(after.get("quietHours").get("timezone").asText()).isEqualTo("Europe/London");
    }

    @Test
    void invalidPreferenceChangesAreRefused() {
        OrgMembership me = person();
        Map<String, Object> webOff = Map.of("categories",
                List.of(Map.of("category", "APPROVAL_NEEDED", "web", false, "email", true)));
        Map<String, Object> badZone = Map.of("quietHours",
                Map.of("enabled", true, "start", "22:00", "end", "07:00", "timezone", "Mars/Olympus"));
        Map<String, Object> emptyWindow = Map.of("quietHours",
                Map.of("enabled", true, "start", "22:00", "end", "22:00", "timezone", "UTC"));

        for (Map<String, Object> body : List.of(webOff, badZone, emptyWindow)) {
            assertThat(rest.exchange(url("/api/v1/notification-preferences"), HttpMethod.PUT, as(me, body), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void theEndpointsRequireAuthentication() {
        assertThat(rest.getForEntity(url("/api/v1/notifications"), String.class).getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=NotificationApiTest`
Expected: FAIL (404 on the new endpoints).

- [ ] **Step 3: Write the controllers**

`NotificationController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The in-app notification centre: a person's own rows in their current workspace. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record NotificationView(UUID id, NotificationCategory category, String title, String body, UUID runId,
                                   UUID gateId, Instant createdAt, Instant readAt, boolean resolved) {
    }

    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<NotificationView> list(@RequestParam(required = false) String filter,
                                       @RequestParam(defaultValue = "false") boolean unread,
                                       @RequestParam(defaultValue = "50") int limit) {
        var claims = claims();
        StringBuilder sql = new StringBuilder("select id, category, title, body, run_id, gate_id, created_at, read_at, "
                + "resolved_at from notification where user_id = ? and org_id = ?");
        List<Object> args = new ArrayList<>(List.of(claims.userId(), claims.orgId()));
        if (filter != null) {
            List<NotificationCategory> categories;
            try {
                categories = NotificationCategory.inFilter(filter);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown filter");
            }
            sql.append(" and category in (").append(String.join(",", categories.stream().map(c -> "?").toList())).append(")");
            categories.forEach(c -> args.add(c.name()));
        }
        if (unread) {
            sql.append(" and read_at is null");
        }
        sql.append(" order by created_at desc limit ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbc.query(sql.toString(), (rs, i) -> new NotificationView(rs.getObject("id", UUID.class),
                NotificationCategory.valueOf(rs.getString("category")), rs.getString("title"), rs.getString("body"),
                rs.getObject("run_id", UUID.class), rs.getObject("gate_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("read_at")),
                rs.getTimestamp("resolved_at") != null), args.toArray());
    }

    @GetMapping("/unread-count")
    public Map<String, Integer> unreadCount() {
        var claims = claims();
        Integer count = jdbc.queryForObject(
                "select count(*) from notification where user_id = ? and org_id = ? and read_at is null",
                Integer.class, claims.userId(), claims.orgId());
        return Map.of("count", count);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        var claims = claims();
        int updated = jdbc.update("update notification set read_at = coalesce(read_at, now()) "
                + "where id = ? and user_id = ? and org_id = ?", id, claims.userId(), claims.orgId());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        var claims = claims();
        jdbc.update("update notification set read_at = now() where user_id = ? and org_id = ? and read_at is null",
                claims.userId(), claims.orgId());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

`NotificationPreferenceController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.identity.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A person's channel choices and quiet hours. */
@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferenceController {

    public record CategoryPref(NotificationCategory category, boolean web, boolean email) {
    }

    public record QuietHoursDto(boolean enabled, String start, String end, String timezone) {
    }

    public record PreferencesDto(List<CategoryPref> categories, QuietHoursDto quietHours) {
    }

    private final NotificationPreferences preferences;

    public NotificationPreferenceController(NotificationPreferences preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public PreferencesDto get() {
        var userId = claims().userId();
        List<CategoryPref> categories = preferences.all(userId).entrySet().stream()
                .map(e -> new CategoryPref(e.getKey(), e.getValue().web(), e.getValue().email())).toList();
        var quiet = preferences.quiet(userId);
        return new PreferencesDto(categories, new QuietHoursDto(quiet.enabled(), quiet.start().toString(),
                quiet.end().toString(), quiet.zone().getId()));
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@RequestBody PreferencesDto body) {
        Map<NotificationCategory, NotificationPreferences.Channels> changes = new EnumMap<>(NotificationCategory.class);
        if (body.categories() != null) {
            for (CategoryPref pref : body.categories()) {
                if (pref.category() == NotificationCategory.APPROVAL_NEEDED && !pref.web()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approval requests always show on the web");
                }
                changes.put(pref.category(), new NotificationPreferences.Channels(pref.web(), pref.email()));
            }
        }
        NotificationPreferences.QuietWindow quiet = null;
        if (body.quietHours() != null) {
            try {
                LocalTime start = LocalTime.parse(body.quietHours().start());
                LocalTime end = LocalTime.parse(body.quietHours().end());
                if (start.equals(end)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quiet hours must not be empty");
                }
                quiet = new NotificationPreferences.QuietWindow(body.quietHours().enabled(), start, end,
                        ZoneId.of(body.quietHours().timezone()));
            } catch (DateTimeException | NullPointerException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quiet hours");
            }
        }
        preferences.save(claims().userId(), changes, quiet);
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=NotificationApiTest`
Expected: PASS (7 tests). `ResponseStatusException` thrown for a start-equals-end check sits inside the `try` that catches `DateTimeException`; it is not one, so it propagates as 400. If an unauthenticated call returns something other than 401/403, mirror how `RunGatesViewTest` asserts it.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): notification centre and preferences API"
```

---

### Task 5: V43 approval links and the email delivery job

**Files:**
- Create: `backend/src/main/resources/db/migration/V43__approval_link.sql`
- Create: `.../notifications/EmailDelivery.java`
- Create: `.../notifications/EmailDeliveryJob.java`
- Modify: `backend/src/main/resources/application.yml` (add `notifications.email-interval`)
- Test: `backend/src/test/java/com/agentstore/platform/notifications/EmailDeliveryTest.java`

**Interfaces:**
- Consumes: `NotificationConsumer` (Task 3), `EmailSender`/`EmailMessage`, `TokenHasher` (existing), `agentstore.web.base-url`.
- Produces: `int EmailDelivery.deliverDue()` (rows handled this call; up to 20); table `approval_link(id, notification_id, user_id, gate_id, token_hash unique, expires_at, used_at, decided_ip, decided_user_agent)`; ShedLock name `emailDelivery`; property `agentstore.notifications.email-interval` (default `PT30S`).

- [ ] **Step 1: Write the migration**

`V43__approval_link.sql`:

```sql
CREATE TABLE approval_link (
    id                 UUID PRIMARY KEY,
    notification_id    UUID        NOT NULL,
    user_id            UUID        NOT NULL,
    gate_id            UUID        NOT NULL,
    token_hash         TEXT        NOT NULL UNIQUE,
    expires_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ NULL,
    decided_ip         TEXT        NULL,
    decided_user_agent TEXT        NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_approval_link_notification ON approval_link (notification_id);
```

- [ ] **Step 2: Write the failing tests**

`EmailDeliveryTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.RecordingEmailSender;
import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDeliveryTest extends AbstractIntegrationTest {

    @Autowired EmailDelivery delivery;
    @Autowired NotificationConsumer consumer;
    @Autowired ExecutionFixtures fixtures;
    @Autowired IdentityFixtures identities;
    @Autowired GateService gateService;
    @Autowired RecordingEmailSender emails;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("delete from approval_link");
        jdbc.update("delete from notification");
    }

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    private GateRequest openGate(ExecutionFixtures.Scenario s) {
        String token = "tok-ed-" + UUID.randomUUID();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post 12 entries", "{}", "[]");
        consumer.handle(gateOpened(gate));
        return gate;
    }

    private DomainEvent gateOpened(GateRequest gate) {
        return jdbc.query("select id, type, org_id, aggregate_id, payload::text as payload, created_at from domain_event "
                        + "where aggregate_id = ? and type = 'GATE_OPENED'", (rs, i) -> {
                    try {
                        return new DomainEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                mapper.readTree(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, gate.getId()).get(0);
    }

    private Map<String, Object> notificationOf(UUID userId) {
        return jdbc.queryForMap("select id, email_sent_at, email_attempts, email_error, email_skipped, "
                + "extract(epoch from (email_due_at - now())) as secs from notification where user_id = ?", userId);
    }

    @Test
    void anApprovalEmailCarriesAWorkingLinkAndIsMarkedSent() {
        var s = scenario();
        User approver = fixtures.approver(s);
        GateRequest gate = openGate(s);

        assertThat(delivery.deliverDue()).isEqualTo(1);

        EmailMessage message = emails.lastTo(approver.getEmail()).orElseThrow();
        assertThat(message.subject()).contains("Post 12 entries");
        String token = RecordingEmailSender.tokenFromLink(message, "approve");
        Map<String, Object> link = jdbc.queryForMap(
                "select gate_id, user_id, used_at, expires_at from approval_link where token_hash = ?",
                TokenHasher.sha256Hex(token));
        assertThat(link.get("gate_id")).isEqualTo(gate.getId());
        assertThat(link.get("user_id")).isEqualTo(approver.getId());
        assertThat(link.get("used_at")).isNull();
        assertThat(((java.sql.Timestamp) link.get("expires_at")).toInstant())
                .isCloseTo(gate.getExpiresAt(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(notificationOf(approver.getId()).get("email_sent_at")).isNotNull();
    }

    @Test
    void aNoApproverNotificationSendsAnEmailWithoutALink() {
        var s = scenario();
        User admin = identities.member(s.buyer(), "admin-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_ADMIN, "Finance lead").getUser();
        openGate(s);

        delivery.deliverDue();

        EmailMessage message = emails.lastTo(admin.getEmail()).orElseThrow();
        assertThat(message.text()).doesNotContain("/approve/").contains("approver");
        assertThat(jdbc.queryForObject("select count(*) from approval_link", Integer.class)).isZero();
    }

    @Test
    void aGateResolvedBeforeSendingIsSkippedNotEmailed() {
        var s = scenario();
        User approver = fixtures.approver(s);
        GateRequest gate = openGate(s);
        gateService.decide(gate.getRun().getId(), gate.getId(), true, approver.getId(), null, DecisionChannel.WEB);

        delivery.deliverDue();

        assertThat(emails.lastTo(approver.getEmail())).isEmpty();
        assertThat(notificationOf(approver.getId())).containsEntry("email_skipped", true);
        assertThat(jdbc.queryForObject("select count(*) from approval_link", Integer.class)).isZero();
    }

    @Test
    void aFailedSendIsRetriedLaterWithAFreshLink() {
        var s = scenario();
        User approver = fixtures.approver(s);
        openGate(s);
        emails.failNextSendTo(approver.getEmail());

        delivery.deliverDue();

        Map<String, Object> failed = notificationOf(approver.getId());
        assertThat(failed.get("email_sent_at")).isNull();
        assertThat(failed).containsEntry("email_attempts", 1);
        assertThat(failed.get("email_error").toString()).contains("Simulated");
        assertThat(((Number) failed.get("secs")).doubleValue()).isBetween(30.0, 90.0);
        assertThat(delivery.deliverDue()).as("not due yet").isZero();

        jdbc.update("update notification set email_due_at = now() where user_id = ?", approver.getId());
        delivery.deliverDue();

        Map<String, Object> retried = notificationOf(approver.getId());
        assertThat(retried.get("email_sent_at")).isNotNull();
        assertThat(retried).containsEntry("email_attempts", 2);
        assertThat(emails.lastTo(approver.getEmail())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from approval_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void afterFiveAttemptsARowIsLeftAlone() {
        var s = scenario();
        User approver = fixtures.approver(s);
        openGate(s);
        jdbc.update("update notification set email_attempts = 5, email_due_at = now() where user_id = ?", approver.getId());

        assertThat(delivery.deliverDue()).isZero();
        assertThat(emails.lastTo(approver.getEmail())).isEmpty();
    }

    @Test
    void aSentEmailIsNeverSentAgain() {
        var s = scenario();
        fixtures.approver(s);
        openGate(s);

        assertThat(delivery.deliverDue()).isEqualTo(1);
        assertThat(delivery.deliverDue()).isZero();
    }

    @Test
    void aRowNotYetDueIsNotSent() {
        var s = scenario();
        User approver = fixtures.approver(s);
        openGate(s);
        jdbc.update("update notification set email_due_at = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1))), approver.getId());

        assertThat(delivery.deliverDue()).isZero();
        assertThat(emails.lastTo(approver.getEmail())).isEmpty();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=EmailDeliveryTest`
Expected: compilation FAIL (`EmailDelivery` does not exist).

- [ ] **Step 4: Write the delivery service and job**

`EmailDelivery.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.common.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Sends the emails that notifications owe. A row is claimed in a short transaction (attempt counted, lease pushed 5
 * minutes out), then the mail is sent with no transaction open. So a slow mail server never holds a lock, a crash
 * simply lets the lease lapse, and a failure is recorded and retried later. Delivery is at least once.
 */
@Component
public class EmailDelivery {

    private static final Logger log = LoggerFactory.getLogger(EmailDelivery.class);

    static final int MAX_ATTEMPTS = 5;
    static final int BATCH = 20;
    static final Duration LEASE = Duration.ofMinutes(5);
    private static final long RETRY_STEP_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public EmailDelivery(JdbcTemplate jdbc, TransactionTemplate tx, EmailSender emailSender,
                         @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
    }

    private record Claimed(UUID id, int attempts, boolean skipped, EmailMessage message) {
    }

    /** Sends up to {@link #BATCH} due emails; returns how many rows were handled (sent, failed or skipped). */
    public int deliverDue() {
        int handled = 0;
        while (handled < BATCH) {
            Claimed claimed = tx.execute(status -> claim());
            if (claimed == null) {
                break;
            }
            handled++;
            if (!claimed.skipped()) {
                send(claimed);
            }
        }
        return handled;
    }

    private Claimed claim() {
        var rows = jdbc.queryForList(
                "select n.id, n.user_id, n.category, n.title, n.body, n.gate_id, n.email_attempts, n.email_no_link, u.email "
                        + "from notification n join app_user u on u.id = n.user_id "
                        + "where n.email_due_at <= now() and n.email_sent_at is null and not n.email_skipped "
                        + "and n.email_attempts < ? order by n.email_due_at limit 1 for update of n skip locked",
                MAX_ATTEMPTS);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.get(0);
        UUID id = (UUID) row.get("id");
        UUID userId = (UUID) row.get("user_id");
        UUID gateId = (UUID) row.get("gate_id");
        boolean approval = "APPROVAL_NEEDED".equals(row.get("category"));
        boolean noLink = (Boolean) row.get("email_no_link");
        int attempts = (Integer) row.get("email_attempts") + 1;
        String text = row.get("body").toString();

        if (approval && gateId != null) {
            var gates = jdbc.queryForList("select status, expires_at from gate_request where id = ?", gateId);
            if (gates.isEmpty() || !"PENDING".equals(gates.get(0).get("status"))) {
                jdbc.update("update notification set email_skipped = true where id = ?", id);
                return new Claimed(id, attempts, true, null);
            }
            if (noLink) {
                text += "\n\nThis workspace has no approver set up. Add an approver, or decide in the web app: " + webBaseUrl;
            } else {
                jdbc.update("delete from approval_link where notification_id = ? and used_at is null", id);
                String token = TokenHasher.newToken();
                jdbc.update("insert into approval_link (id, notification_id, user_id, gate_id, token_hash, expires_at) "
                                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, userId, gateId,
                        TokenHasher.sha256Hex(token), gates.get(0).get("expires_at"));
                text += "\n\nReview and decide: " + webBaseUrl + "/approve/" + token
                        + "\n\nThis link works once and expires when the request does.";
            }
        } else {
            text += "\n\nOpen your notifications: " + webBaseUrl + "/notifications";
        }
        jdbc.update("update notification set email_attempts = ?, email_due_at = now() + make_interval(secs => ?) "
                + "where id = ?", attempts, (double) LEASE.toSeconds(), id);
        return new Claimed(id, attempts, false, new EmailMessage(row.get("email").toString(),
                row.get("title").toString(), text));
    }

    private void send(Claimed claimed) {
        try {
            emailSender.send(claimed.message());
            tx.executeWithoutResult(status -> jdbc.update(
                    "update notification set email_sent_at = now(), email_error = null where id = ?", claimed.id()));
        } catch (RuntimeException e) {
            log.warn("Notification email {} failed (attempt {})", claimed.id(), claimed.attempts(), e);
            String error = String.valueOf(e);
            double seconds = (double) (RETRY_STEP_SECONDS * claimed.attempts());
            tx.executeWithoutResult(status -> jdbc.update(
                    "update notification set email_error = ?, email_due_at = now() + make_interval(secs => ?) where id = ?",
                    error.length() > 2000 ? error.substring(0, 2000) : error, seconds, claimed.id()));
        }
    }
}
```

`EmailDeliveryJob.java`:

```java
package com.agentstore.platform.notifications;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class EmailDeliveryJob {

    private final EmailDelivery delivery;

    public EmailDeliveryJob(EmailDelivery delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${agentstore.notifications.email-interval:PT30S}",
            initialDelayString = "${agentstore.notifications.email-interval:PT30S}")
    @SchedulerLock(name = "emailDelivery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        delivery.deliverDue();
    }
}
```

In `application.yml`, under the `agentstore:` block next to `runs:`, add:

```yaml
  notifications:
    email-interval: PT30S
```

- [ ] **Step 5: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=EmailDeliveryTest`
Expected: PASS (7 tests). If the `expires_at` `isCloseTo` fails on precision, widen the tolerance to 2 seconds; Postgres keeps microseconds and `Instant` nanoseconds.

- [ ] **Step 6: Add the job wiring assertion**

In `SchedulingWiringTest.java` add `"agentstore.notifications.email-interval=PT1H"` to the `@TestPropertySource` array, an `@Autowired EmailDeliveryJob emailDeliveryJob;` field (import `com.agentstore.platform.notifications.EmailDeliveryJob`), and:

```java
    @Test
    void emailJobRunsUnderAShedLock() {
        emailDeliveryJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'emailDelivery'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }
```

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=SchedulingWiringTest`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): email delivery job with leases, retries and single-use approval links"
```

---

### Task 6: Public approval endpoints

**Files:**
- Create: `.../notifications/ApprovalLinkService.java`
- Create: `.../notifications/ApprovalController.java`
- Modify: `backend/src/main/java/com/agentstore/platform/identity/SecurityConfig.java:54`
- Test: `backend/src/test/java/com/agentstore/platform/notifications/ApprovalLinkTest.java`

**Interfaces:**
- Consumes: `approval_link` (Task 5), `GateService.decide(UUID runId, UUID gateId, boolean approve, UUID userId, String note, DecisionChannel channel)`, `DecisionChannel.EMAIL_LINK`, `GateDecision { APPROVE, REJECT }`, `NotAnApproverException` (WS15b), `TokenHasher.sha256Hex`.
- Produces: `GET /api/v1/approvals/{token}` → `{runDisplayId, agentName, action, summary, expiresAt}`; `POST /api/v1/approvals/{token}/decision` body `{decision: "APPROVE"|"REJECT", note}` → `{decision, runDisplayId}`. Both unauthenticated.

- [ ] **Step 1: Write the failing tests**

`ApprovalLinkTest.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.RecordingEmailSender;
import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.execution.DecidedVia;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateRequestRepository;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunReaper;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalLinkTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ExecutionFixtures fixtures;
    @Autowired GateService gateService;
    @Autowired GateRequestRepository gates;
    @Autowired NotificationConsumer consumer;
    @Autowired EmailDelivery delivery;
    @Autowired RecordingEmailSender emails;
    @Autowired RunReaper reaper;
    @Autowired OrgMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("delete from approval_link");
        jdbc.update("delete from notification");
    }

    private record Setup(ExecutionFixtures.Scenario scenario, User approver, Run run, GateRequest gate, String token) {
    }

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    /** Opens a gate, runs the consumer and the email job, and returns the token from the delivered email. */
    private Setup setup(ExecutionFixtures.Scenario s, User approver, int timeoutMinutes) {
        String raw = "tok-al-" + UUID.randomUUID();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-" + raw, raw);
        return open(s, approver, run, timeoutMinutes);
    }

    private Setup open(ExecutionFixtures.Scenario s, User approver, Run run, int timeoutMinutes) {
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", timeoutMinutes, "Post 12 entries",
                "{}", "[]");
        consumer.handle(event(gate.getId()));
        delivery.deliverDue();
        EmailMessage mail = emails.lastTo(approver.getEmail()).orElseThrow();
        return new Setup(s, approver, run, gate, RecordingEmailSender.tokenFromLink(mail, "approve"));
    }

    private DomainEvent event(UUID gateId) {
        return jdbc.query("select id, type, org_id, aggregate_id, payload::text as payload, created_at from domain_event "
                        + "where aggregate_id = ? and type = 'GATE_OPENED'", (rs, i) -> {
                    try {
                        return new DomainEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                mapper.readTree(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, gateId).get(0);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<JsonNode> get(String token) {
        return rest.getForEntity(url("/api/v1/approvals/" + token), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String token, String decision) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "approval-link-test");
        return rest.exchange(url("/api/v1/approvals/" + token + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", decision, "note", "from link"), headers), JsonNode.class);
    }

    private GateStatus statusOf(GateRequest gate) {
        return gates.findById(gate.getId()).orElseThrow().getStatus();
    }

    private int decidedEvents(GateRequest gate) {
        return jdbc.queryForObject("select count(*) from domain_event where aggregate_id = ? and type = 'GATE_DECIDED'",
                Integer.class, gate.getId());
    }

    @Test
    void getShowsTheCardAndLeavesTheGatePending() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        var response = get(t.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("summary").asText()).isEqualTo("Post 12 entries");
        assertThat(response.getBody().get("action").asText()).isEqualTo("post_journal_entries");
        assertThat(response.getBody().has("runDisplayId")).isTrue();
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.PENDING);
        assertThat(jdbc.queryForObject("select used_at from approval_link where token_hash = ?", java.sql.Timestamp.class,
                TokenHasher.sha256Hex(t.token()))).isNull();
    }

    @Test
    void postApprovesAndRecordsTheChannelAndWhoDecided() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        var response = post(t.token(), "APPROVE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("decision").asText()).isEqualTo("APPROVED");
        GateRequest decided = gates.findById(t.gate().getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(decided.getDecidedVia()).isEqualTo(DecidedVia.EMAIL_LINK);
        assertThat(decided.getDecidedByUserId()).isEqualTo(t.approver().getId());
        Map<String, Object> link = jdbc.queryForMap(
                "select used_at, decided_ip, decided_user_agent from approval_link where token_hash = ?",
                TokenHasher.sha256Hex(t.token()));
        assertThat(link.get("used_at")).isNotNull();
        assertThat(link.get("decided_ip")).isNotNull();
        assertThat(link.get("decided_user_agent")).isEqualTo("approval-link-test");
    }

    @Test
    void postCanReject() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        var response = post(t.token(), "REJECT");

        assertThat(response.getBody().get("decision").asText()).isEqualTo("REJECTED");
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.REJECTED);
    }

    @Test
    void aSecondPostIs410AndTheGateIsDecidedOnce() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(decidedEvents(t.gate())).isEqualTo(1);
        assertThat(get(t.token()).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void aLinkUsedAfterTheGateWasApprovedInTheWebAppIs410() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);
        gateService.decide(t.run().getId(), t.gate().getId(), true, t.approver().getId(), null, DecisionChannel.WEB);

        assertThat(get(t.token()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(post(t.token(), "REJECT").getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.APPROVED);
    }

    @Test
    void aLinkUsedAfterTheReaperExpiredTheGateIs410() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 1);

        reaper.reapAt(Instant.now().plus(Duration.ofMinutes(40)));

        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.EXPIRED);
    }

    @Test
    void anOldLinkNeverDecidesASecondGateOnTheSameRun() {
        var s = scenario();
        User approver = fixtures.approver(s);
        Setup first = setup(s, approver, 5);
        gateService.decide(first.run().getId(), first.gate().getId(), true, approver.getId(), null, DecisionChannel.WEB);
        Setup second = open(s, approver, first.run(), 5);

        assertThat(post(first.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(statusOf(second.gate())).isEqualTo(GateStatus.PENDING);
        assertThat(post(second.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anApproverRemovedAfterTheEmailWasSentGets403AndTheLinkStaysUnused() {
        var s = scenario();
        User removed = fixtures.approver(s);
        fixtures.approver(s);
        Setup t = setup(s, removed, 5);
        memberships.deleteById(new OrgMembershipId(removed.getId(), s.buyer().getId()));

        var response = post(t.token(), "APPROVE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_AN_APPROVER");
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.PENDING);
        assertThat(jdbc.queryForObject("select used_at from approval_link where token_hash = ?", java.sql.Timestamp.class,
                TokenHasher.sha256Hex(t.token()))).isNull();
    }

    @Test
    void anUnknownTokenIs404() {
        assertThat(get("not-a-real-token").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("not-a-real-token", "APPROVE").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anExpiredLinkIs410() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);
        jdbc.update("update approval_link set expires_at = now() - interval '1 minute' where token_hash = ?",
                TokenHasher.sha256Hex(t.token()));

        assertThat(get(t.token()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.PENDING);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=ApprovalLinkTest`
Expected: the setup works (Task 5) and each test FAILS: the endpoints answer 401/403 or 404 because nothing is mapped or permitted yet.

- [ ] **Step 3: Write the service and controller**

`ApprovalLinkService.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.GateService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public side of an approval link. Viewing never changes state. Deciding claims the link and decides the gate in
 * one transaction, as the link's own person, so every rule {@code GateService.decide} enforces (current approver,
 * still pending) applies exactly as in the web app, and a refused decision leaves the link unused.
 */
@Service
public class ApprovalLinkService {

    public record Card(String runDisplayId, String agentName, String action, String summary, Instant expiresAt) {
    }

    public record Outcome(String decision, String runDisplayId) {
    }

    private final JdbcTemplate jdbc;
    private final GateService gateService;

    public ApprovalLinkService(JdbcTemplate jdbc, GateService gateService) {
        this.jdbc = jdbc;
        this.gateService = gateService;
    }

    private record Link(UUID id, UUID userId, UUID gateId, boolean used, boolean expired) {
    }

    public Card card(String token) {
        Link link = find(token);
        var rows = jdbc.queryForList("select g.run_id, g.status, g.action, g.summary, g.expires_at, r.display_id, "
                + "a.name as agent_name from gate_request g join run r on r.id = g.run_id "
                + "join agent_version av on av.id = r.agent_version_id join agent a on a.id = av.agent_id "
                + "where g.id = ?", link.gateId());
        if (link.used() || link.expired() || rows.isEmpty() || !"PENDING".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link is no longer valid");
        }
        Map<String, Object> row = rows.get(0);
        return new Card(row.get("display_id").toString(), row.get("agent_name").toString(),
                row.get("action").toString(), row.get("summary").toString(),
                ((java.sql.Timestamp) row.get("expires_at")).toInstant());
    }

    @Transactional
    public Outcome decide(String token, boolean approve, String note, String ip, String userAgent) {
        Link link = find(token);
        int claimed = jdbc.update("update approval_link set used_at = now(), decided_ip = ?, decided_user_agent = ? "
                + "where id = ? and used_at is null and expires_at > now()", ip, userAgent, link.id());
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.GONE, "This link is no longer valid");
        }
        UUID runId = jdbc.queryForObject("select run_id from gate_request where id = ?", UUID.class, link.gateId());
        try {
            gateService.decide(runId, link.gateId(), approve, link.userId(), note, DecisionChannel.EMAIL_LINK);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new ResponseStatusException(HttpStatus.GONE, "This link is no longer valid");
            }
            throw e;   // including NotAnApproverException (403): the transaction rolls back and the link stays unused
        }
        String display = jdbc.queryForObject("select display_id from run where id = ?", String.class, runId);
        return new Outcome(approve ? "APPROVED" : "REJECTED", display);
    }

    private Link find(String token) {
        List<Link> found = jdbc.query("select id, user_id, gate_id, used_at is not null as used, expires_at <= now() as expired "
                        + "from approval_link where token_hash = ?",
                (rs, i) -> new Link(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("gate_id", UUID.class), rs.getBoolean("used"), rs.getBoolean("expired")),
                TokenHasher.sha256Hex(token));
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link");
        }
        return found.get(0);
    }
}
```

`ApprovalController.java`:

```java
package com.agentstore.platform.notifications;

import com.agentstore.platform.execution.GateDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints behind the link in an approval email. The token is the credential; there is no login. */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    public record DecisionBody(@NotNull GateDecision decision, String note) {
    }

    private final ApprovalLinkService links;

    public ApprovalController(ApprovalLinkService links) {
        this.links = links;
    }

    /** Shows the card. Never changes state, so a mail scanner or link preview cannot approve anything. */
    @GetMapping("/{token}")
    public ApprovalLinkService.Card card(@PathVariable String token) {
        return links.card(token);
    }

    @PostMapping("/{token}/decision")
    public ApprovalLinkService.Outcome decide(@PathVariable String token, @Valid @RequestBody DecisionBody body,
                                              HttpServletRequest request) {
        return links.decide(token, body.decision() == GateDecision.APPROVE, body.note(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }
}
```

In `SecurityConfig.java:54` change the `permitAll` matcher list to:

```java
                    .requestMatchers("/api/v1/auth/**", "/api/v1/invitations/**", "/api/v1/approvals/**", "/actuator/**", "/internal/**").permitAll()
```

- [ ] **Step 4: Run to verify it passes**

Run: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=ApprovalLinkTest`
Expected: PASS (10 tests). If the second-gate test cannot open a second gate because the run stays AWAITING_APPROVAL after approval, check `RunLifecycleService.approveGate` returns the run to RUNNING (it does in `GateServiceTest`). If `gate.getRun()` is lazy in `setup`, use `run.getId()` (already done here).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(notifications): public approval link card and decision endpoints"
```

---

### Task 7: Full-suite verification, docs and spec amendment

**Files:**
- Modify: `docs/known-issues.md` (append)
- Modify: `docs/superpowers/specs/2026-09-30-ws14a-notifications-and-email-approval-links-design.md`

- [ ] **Step 1: Run the whole backend suite**

Run (from `backend/`): `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test`
Expected: BUILD SUCCESS. A failure is most likely an existing test that counts `domain_event_delivery` rows now that the `notifications` consumer creates deliveries, or one that builds a `Run` with new constructor arguments; fix the test's setup, do not weaken production code. Report the totals.

- [ ] **Step 2: Amend the spec for the run starter**

In the spec's `Data` section, under `V42__notification.sql`, add the bullet: `- `run.started_by_user_id` (nullable): the buyer user who started a production run, set by `RunController.trigger` and `rerun`. A run with no starter produces no `RUN_FINISHED` or `RUN_STOPPED_EARLY` notification.` In the `Consumer notifications` section, change "recipient is the run's starter" to "recipient is `run.started_by_user_id`; a null starter notifies nobody".

- [ ] **Step 3: Record the follow-ups**

Append to `docs/known-issues.md`:

```markdown
- **WS14a has no retention and no rate limit:** `notification` and `approval_link` rows are never deleted, and the public `/api/v1/approvals/**` endpoints have no per-IP rate limit (WS12). Add both alongside the outbox retention work.
- **WS14a approval emails can repeat once:** delivery is at least once. A crash between sending and recording `email_sent_at` resends after the 5-minute lease. A row that fails 5 times is left with `email_error` set and is not retried; nothing alerts on it yet.
- **WS14a run-finished notifications need a starter:** only runs started from the buyer web API record `started_by_user_id`. API-key-started and rebuild-launched runs notify nobody until those paths record their starter.
- **WS14a link page is not built:** emails link to `{agentstore.web.base-url}/approve/{token}`, which FE-3 must serve using `GET`/`POST /api/v1/approvals/{token}`.
```

- [ ] **Step 4: Commit**

```bash
git add docs/known-issues.md docs/superpowers/specs/2026-09-30-ws14a-notifications-and-email-approval-links-design.md
git commit -m "docs: record WS14a follow-ups and the run starter"
```
