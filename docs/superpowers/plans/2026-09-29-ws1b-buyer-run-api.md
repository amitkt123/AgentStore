# WS1b: Buyer-facing run API Implementation Plan

**Goal:** Give the buyer UI (FE-2) readable run IDs, a stop reason, a grouped run list, and richer approval gates (cost if approved, platform-built preview, decided-via, eligible approvers), plus a network-events intake.

**Architecture:** Two additive Flyway migrations (V38 run/review columns, V39 gate columns). Display IDs are assigned by a Postgres `BEFORE INSERT` trigger so every existing insert path (production, sandbox, tests) keeps working; Hibernate reads them back with `@Generated`. `RunLifecycleService` remains the only place a run ends, and now requires a `TerminalReason`. New read endpoints (`GET /api/v1/runs`, `GET /api/v1/runs/{ref}/gates`) and one runtime endpoint (`POST /runtime/v1/network-events`) reuse existing services.

**Tech Stack:** Java 21, Spring Boot 3, Hibernate 6, Flyway, PostgreSQL 16, Testcontainers, springdoc (runtime contract), TypeScript CLI and frontend types, Python and TypeScript SDK generators.

**Spec:** `docs/superpowers/specs/2026-09-29-ws1b-buyer-run-api-design.md`

**Branch:** implement on `feat/ws1b-buyer-run-api`, cut from master after PR #23 (retire ONTOLOGY.md) merges. Commit messages carry no AI attribution and no Co-Authored-By trailer.

## Global Constraints

- Display IDs: `R-<n>` (production and trial), `S-<n>` (sandbox), `RV-<n>` (review items). Sequences are global.
- `TerminalReason` values, exactly: `FINISHED`, `GATE_REJECTED`, `GATE_EXPIRED`, `COST_CAP_REACHED`, `TIMED_OUT`, `SCOPE_VIOLATIONS`, `AGENT_FAILED`, `CANCELLED`, `SANDBOX_LOST`. `RunStatus` is unchanged.
- Run list groups: `needs_you` = `AWAITING_APPROVAL`; `running` = `PENDING`, `PENDING_BUILD`, `RUNNING`; `finished` = `COMPLETED`; `stopped` = `FAILED`, `CANCELLED`, `TIMED_OUT`, `EXPIRED`. Page size default 25, max 100.
- Gate preview: first 20 rows only; `totalRows`; `total` only when `preview.totalField` is declared. Built by the platform, never supplied by the agent.
- `costIfApproved = min(estimatedUnits x unitPrice, costCap - charged)`, scale 2, HALF_UP, floored at 0.
- `decided_via` values: `WEB`, `EMAIL_LINK`, `SLACK_LINK`; WS1b writes only `WEB`.
- Network events: at most 100 events per call; step source `AGENT`; step has no block.
- `estimatedUnits` uses the same bound as `usage.quantity`: positive, `@DecimalMax("9999999999")`, `@Digits(integer = 10, fraction = 4)`.
- Migrations are additive; backfill before any NOT NULL.
- Backend tests: from `backend/`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=<Class>` (Colima must be running).
- No AI attribution anywhere: commits, PR bodies, docs.

## Review Focus

- A display ID typed in lower case (`r-12`) or padded with spaces resolves like `R-12`; an unknown or another org's ID is 404, never 403 (Task 2).
- Concurrent run inserts never produce a duplicate or skipped-then-colliding display ID (Task 1).
- A gate opened for a buyer org that has no approver members must not deadlock: the snapshot is stored as null (unrestricted) instead of an empty list (Task 4).
- `estimatedUnits` of 0, negative, or with too many digits is a 400, not a database error (Task 4).
- Preview when a `totalField` column holds non-numeric or missing values, when `columns` names a column that does not exist, and when nothing is staged (Task 5).
- Run list `q` containing `%` or `_` matches literally; `page` beyond the last page returns an empty page with correct `counts`; `from` later than `to` returns an empty page, not an error (Task 7).
- Network events: empty `events`, `count` of 0 or negative, 101 events, an oversize `host`, and a terminal run (Task 8).

---

### Task 1: Migration V38, display IDs and new run columns

**Files:**
- Create: `backend/src/main/resources/db/migration/V38__run_display_id_terminal_reason.sql`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/Run.java`
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/ReviewItem.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunResponse.java`, `RunSummaryResponse.java`, `RunDetailResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunViews.java:59`
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/` review item response records (find with `grep -rn "ReviewItem" backend/src/main/java --include=*Response*.java`)
- Create: `backend/src/main/java/com/agentstore/platform/execution/TerminalReason.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunDisplayIdTest.java`

**Interfaces:**
- Produces: `Run.getDisplayId(): String` (read-only, filled by the database), `Run.getTerminalReason()/setTerminalReason(TerminalReason)`, `Run.getReachedBlock()/setReachedBlock(String)`, `Run.getSummary()/setSummary(String)`, `ReviewItem.getDisplayId()`, enum `TerminalReason`.
- Produces: `RunResponse(UUID id, String displayId, RunStatus status, TerminalReason terminalReason)`, `RunSummaryResponse(UUID id, String displayId, String agentName, RunStatus status, TerminalReason terminalReason, BigDecimal totalCharged, Instant startedAt)`, `RunDetailResponse(UUID id, String displayId, RunStatus status, TerminalReason terminalReason, BigDecimal costCap, BigDecimal totalCharged, List<RunStepResponse> steps)`.

- [ ] **Step 1: Write the failing test**

```java
package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RunDisplayIdTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunRepository runRepository;

    @Test
    void productionRunGetsAnRPrefixedId() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-di-1", "tok-di-1");
        assertThat(run.getDisplayId()).matches("R-\\d+");
    }

    @Test
    void sandboxRunGetsAnSPrefixedIdFromItsOwnSequence() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        // a sandbox run has no connection; build one directly
        Run sandbox = new Run(null, null, scenario.version(), RunStatus.RUNNING, new BigDecimal("100.00"),
                BigDecimal.ZERO, "sbx-di-3", RunTokenService.hash("tok-di-3"));
        sandbox.setMode(RunMode.SANDBOX);
        sandbox.setInitiatedByOrgId(scenario.version().getAgent().getDeveloperOrg().getId());
        sandbox = runRepository.save(sandbox);
        assertThat(sandbox.getDisplayId()).matches("S-\\d+");
    }

    @Test
    void concurrentInsertsNeverCollide() throws Exception {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        var pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int n = i;
            Callable<String> task = () -> fixtures.run(scenario, RunStatus.RUNNING, "sbx-c-" + n, "tok-c-" + n).getDisplayId();
            futures.add(pool.submit(task));
        }
        Set<String> ids = new HashSet<>();
        for (Future<String> f : futures) {
            ids.add(f.get());
        }
        pool.shutdown();
        assertThat(ids).hasSize(24);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RunDisplayIdTest`
Expected: compile error, `getDisplayId()` not defined on `Run`.

- [ ] **Step 3: Write the migration**

`V38__run_display_id_terminal_reason.sql`:

```sql
CREATE SEQUENCE run_no_seq;
CREATE SEQUENCE sandbox_run_no_seq;
CREATE SEQUENCE review_no_seq;

ALTER TABLE run ADD COLUMN display_id VARCHAR(20);
ALTER TABLE run ADD COLUMN terminal_reason VARCHAR(30);
ALTER TABLE run ADD COLUMN reached_block VARCHAR(100);
ALTER TABLE run ADD COLUMN summary TEXT;
ALTER TABLE review_item ADD COLUMN display_id VARCHAR(20);

-- Backfill display ids in creation order, then point each sequence past them.
UPDATE run r SET display_id = 'R-' || o.rn
FROM (SELECT id, row_number() OVER (ORDER BY started_at NULLS LAST, id) rn FROM run WHERE mode <> 'SANDBOX') o
WHERE r.id = o.id;
UPDATE run r SET display_id = 'S-' || o.rn
FROM (SELECT id, row_number() OVER (ORDER BY started_at NULLS LAST, id) rn FROM run WHERE mode = 'SANDBOX') o
WHERE r.id = o.id;
UPDATE review_item ri SET display_id = 'RV-' || o.rn
FROM (SELECT id, row_number() OVER (ORDER BY created_at NULLS LAST, id) rn FROM review_item) o
WHERE ri.id = o.id;

SELECT setval('run_no_seq', GREATEST(c, 1), c > 0) FROM (SELECT count(*) c FROM run WHERE mode <> 'SANDBOX') x;
SELECT setval('sandbox_run_no_seq', GREATEST(c, 1), c > 0) FROM (SELECT count(*) c FROM run WHERE mode = 'SANDBOX') x;
SELECT setval('review_no_seq', GREATEST(c, 1), c > 0) FROM (SELECT count(*) c FROM review_item) x;

-- New rows get their id from the database, so every insert path (and every test) keeps working.
CREATE FUNCTION assign_run_display_id() RETURNS trigger AS $$
BEGIN
    IF NEW.display_id IS NULL THEN
        NEW.display_id := CASE WHEN NEW.mode = 'SANDBOX'
            THEN 'S-' || nextval('sandbox_run_no_seq')
            ELSE 'R-' || nextval('run_no_seq') END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER run_display_id BEFORE INSERT ON run FOR EACH ROW EXECUTE FUNCTION assign_run_display_id();

CREATE FUNCTION assign_review_display_id() RETURNS trigger AS $$
BEGIN
    IF NEW.display_id IS NULL THEN
        NEW.display_id := 'RV-' || nextval('review_no_seq');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER review_display_id BEFORE INSERT ON review_item FOR EACH ROW EXECUTE FUNCTION assign_review_display_id();

ALTER TABLE run ALTER COLUMN display_id SET NOT NULL;
ALTER TABLE run ADD CONSTRAINT run_display_id_key UNIQUE (display_id);
ALTER TABLE review_item ALTER COLUMN display_id SET NOT NULL;
ALTER TABLE review_item ADD CONSTRAINT review_item_display_id_key UNIQUE (display_id);

-- terminal_reason: only where status plus the closing step make the reason unambiguous.
UPDATE run SET terminal_reason = 'FINISHED' WHERE status = 'COMPLETED';
UPDATE run SET terminal_reason = 'TIMED_OUT' WHERE status = 'TIMED_OUT';
UPDATE run SET terminal_reason = 'GATE_EXPIRED' WHERE status = 'EXPIRED';   -- EXPIRED only ever comes from gate expiry
UPDATE run r SET terminal_reason = CASE
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'CANCELLED') THEN 'CANCELLED'
        ELSE 'GATE_REJECTED' END
WHERE r.status = 'CANCELLED';
UPDATE run r SET terminal_reason = CASE
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'COST_CAP_REACHED') THEN 'COST_CAP_REACHED'
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'FAILED' AND s.detail->>'code' = 'SCOPE_VIOLATIONS') THEN 'SCOPE_VIOLATIONS'
        WHEN EXISTS (SELECT 1 FROM run_step s WHERE s.run_id = r.id AND s.kind = 'FAILED'
                     AND s.detail->>'summary' = 'Agent stopped without reporting completion') THEN 'SANDBOX_LOST'
        ELSE 'AGENT_FAILED' END
WHERE r.status = 'FAILED';

UPDATE run r SET summary = (SELECT s.detail->>'summary' FROM run_step s
    WHERE s.run_id = r.id AND s.kind IN ('COMPLETE', 'FAILED', 'TIMEOUT', 'COST_CAP_REACHED', 'CANCELLED')
    ORDER BY s.created_at DESC LIMIT 1)
WHERE r.status IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'EXPIRED', 'CANCELLED');
UPDATE run r SET reached_block = (SELECT s.block FROM run_step s
    WHERE s.run_id = r.id AND s.block IS NOT NULL ORDER BY s.created_at DESC LIMIT 1);

CREATE INDEX run_reason_idx ON run (terminal_reason);
```

- [ ] **Step 4: Add the enum and entity fields**

`TerminalReason.java`:

```java
package com.agentstore.platform.execution;

/** Why a run ended. RunStatus says where it ended; this says why (spec WS1b section 2). */
public enum TerminalReason {
    FINISHED, GATE_REJECTED, GATE_EXPIRED, COST_CAP_REACHED, TIMED_OUT, SCOPE_VIOLATIONS, AGENT_FAILED, CANCELLED,
    SANDBOX_LOST
}
```

In `Run.java` add (imports: `org.hibernate.annotations.Generated`, `org.hibernate.generator.EventType`):

```java
    /** 'R-12' or 'S-4'; assigned by the run_display_id trigger and read back after insert. */
    @Setter(AccessLevel.NONE)
    @Generated(event = EventType.INSERT)
    @Column(name = "display_id", insertable = false, updatable = false)
    private String displayId;

    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_reason")
    private TerminalReason terminalReason;

    /** The block of the most recent block-carrying step; kept in step with FlowView.reachedBlock. */
    private String reachedBlock;

    /** The closing step's summary, for the run list search. */
    @Column(columnDefinition = "text")
    private String summary;
```

In `ReviewItem.java` add the same `displayId` field (no setter, `@Generated(event = EventType.INSERT)`, column `display_id`, `insertable = false, updatable = false`).

- [ ] **Step 5: Add displayId and terminalReason to the responses**

```java
public record RunResponse(UUID id, String displayId, RunStatus status, TerminalReason terminalReason) {
    static RunResponse from(Run run) {
        return new RunResponse(run.getId(), run.getDisplayId(), run.getStatus(), run.getTerminalReason());
    }
}
```

```java
public record RunSummaryResponse(UUID id, String displayId, String agentName, RunStatus status,
                                 TerminalReason terminalReason, BigDecimal totalCharged, Instant startedAt) {
    static RunSummaryResponse from(Run run) {
        return new RunSummaryResponse(run.getId(), run.getDisplayId(), run.getConnection().getAgent().getName(),
                run.getStatus(), run.getTerminalReason(), run.getTotalCharged(), run.getStartedAt());
    }
}
```

```java
public record RunDetailResponse(UUID id, String displayId, RunStatus status, TerminalReason terminalReason,
                                BigDecimal costCap, BigDecimal totalCharged, List<RunStepResponse> steps) {
}
```

Update `RunDetailController.get` to `new RunDetailResponse(run.getId(), run.getDisplayId(), run.getStatus(), run.getTerminalReason(), run.getCostCap(), run.getTotalCharged(), steps)`. In `SandboxRunViews.java:59` replace the derived id with `String displayId = run.getDisplayId();`. Add `displayId` to the review item response record(s) the same way, using `item.getDisplayId()`.

- [ ] **Step 6: Fix compile errors and old expectations**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test-compile`. Fix every test that constructs `RunResponse`, `RunSummaryResponse`, `RunDetailResponse` or asserts the old `S-XXXXXXXX` sandbox format (`grep -rn '"S-' backend/src/test`).

- [ ] **Step 7: Run tests**

Run: `mvn -q test -Dtest='RunDisplayIdTest,RunDetailControllerTest,RunSummaryControllerTest,Sandbox*Test'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(runs): readable display ids and terminal_reason/reached_block/summary columns (V38)"
```

---

### Task 2: Run references (UUID or display ID) in every run path

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunLookup.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java`
- Modify: `RunController.java`, `RunDetailController.java` (execution), `backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunController.java`, `backend/src/main/java/com/agentstore/platform/data/OutputsController.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunLookupTest.java`

**Interfaces:**
- Consumes: `Run.getDisplayId()` (Task 1).
- Produces: `RunLookup.find(String ref): Run` (throws `ResponseStatusException` 404 "Run not found"), `RunLookup.idOf(String ref): UUID`, `RunRepository.findByDisplayId(String): Optional<Run>`.

- [ ] **Step 1: Write the failing test**

```java
package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentstore.platform.AbstractIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class RunLookupTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLookup lookup;

    private Run run() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-lk", "tok-lk-" + System.nanoTime());
    }

    @Test
    void resolvesByUuidAndByDisplayId() {
        Run run = run();
        assertThat(lookup.find(run.getId().toString()).getId()).isEqualTo(run.getId());
        assertThat(lookup.find(run.getDisplayId()).getId()).isEqualTo(run.getId());
    }

    @Test
    void displayIdIsCaseAndWhitespaceInsensitive() {
        Run run = run();
        assertThat(lookup.find("  " + run.getDisplayId().toLowerCase() + " ").getId()).isEqualTo(run.getId());
    }

    @Test
    void unknownReferenceIs404() {
        assertThatThrownBy(() -> lookup.find("R-99999999")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        assertThatThrownBy(() -> lookup.find("not-an-id")).isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=RunLookupTest`. Expected: FAIL, `RunLookup` does not exist.

- [ ] **Step 3: Implement**

Add to `RunRepository`: `Optional<Run> findByDisplayId(String displayId);`

```java
package com.agentstore.platform.execution;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Resolves a run reference that is either a UUID or a display ID such as R-2291 or S-121. */
@Component
public class RunLookup {

    private final RunRepository runs;

    public RunLookup(RunRepository runs) {
        this.runs = runs;
    }

    public Run find(String ref) {
        String trimmed = ref == null ? "" : ref.trim();
        Optional<Run> found = parseUuid(trimmed).flatMap(runs::findById);
        if (found.isEmpty()) {
            found = runs.findByDisplayId(trimmed.toUpperCase());
        }
        return found.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    public UUID idOf(String ref) {
        return find(ref).getId();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

In each controller replace `@PathVariable UUID runId` (or `id` for `SandboxRunController`) with `@PathVariable("runId") String runRef` and add `UUID runId = runLookup.idOf(runRef);` as the first line, keeping the rest of the method unchanged. Inject `RunLookup` through each constructor. Endpoints to change: `RunController.approve`, `reject`, `decide`; `RunDetailController.get`, `flow`; `SandboxRunController.get`, `cancel`, `rerun`, `decide` (path variable is `{id}` there: use `@PathVariable("id") String runRef`); `OutputsController` (class-level `/api/v1/runs/{runId}/outputs`: `list` and `download`). `RunDetailController.findRun(UUID)` and `requireOwnedByCaller(UUID)` stay as they are. Do not touch `AgentCallbackController` (the legacy agent-facing path, token authenticated).

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest='RunLookupTest,RunDetailControllerTest,GateDecisionEndpointTest,RunFlowControllerTest,Sandbox*Test,OutputsApiTest'`. Expected: PASS.

- [ ] **Step 5: Add a controller-level test**

Append to `RunDetailControllerTest` a test that fetches `/api/v1/runs/{displayId}` as the owning buyer (same helpers as the existing tests in that class) and asserts 200 with the same `id`; and that another org's buyer gets 403 for the same reference and an unknown `R-99999999` gets 404. Run it, expect PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(runs): every run endpoint accepts a UUID or a display id"
```

---

### Task 3: Terminal reason, reached block and summary from the lifecycle

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`
- Modify: callers of `terminate`: `RunReaper.java`, `RunLauncher.java`, `PendingBuildRuns.java`, `GateService.java`, `backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunService.java`
- Modify: existing tests that call `terminate`/`expire`
- Test: `backend/src/test/java/com/agentstore/platform/execution/TerminalReasonTest.java`

**Interfaces:**
- Consumes: `TerminalReason`, `Run.setTerminalReason/setReachedBlock/setSummary` (Task 1).
- Produces: `RunLifecycleService.terminate(UUID runId, RunStatus terminalStatus, TerminalReason reason, RunStepKind stepKind, Map<String,Object> detail)` and `terminate(UUID runId, RunStatus expectedStatus, RunStatus terminalStatus, TerminalReason reason, RunStepKind stepKind, Map<String,Object> detail)`; `GateService.expire(UUID runId, RunStatus expectedStatus, TerminalReason reason, Map<String,Object> detail)`; private `finish(Run, RunStatus, TerminalReason)`.

- [ ] **Step 1: Write the failing test**

```java
package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TerminalReasonTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    GateService gateService;
    @Autowired
    RunRepository runs;

    private Run running(String tag) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + tag, "tok-" + tag);
    }

    private Run reload(Run run) {
        return runs.findById(run.getId()).orElseThrow();
    }

    @Test
    void completeSetsFinishedAndSummary() {
        Run run = running("tr-1");
        lifecycle.complete(run.getId(), Map.of("summary", "All done"));
        Run done = reload(run);
        assertThat(done.getTerminalReason()).isEqualTo(TerminalReason.FINISHED);
        assertThat(done.getSummary()).isEqualTo("All done");
    }

    @Test
    void agentFailIsAgentFailed() {
        Run run = running("tr-2");
        lifecycle.fail(run.getId(), Map.of("summary", "boom"));
        assertThat(reload(run).getTerminalReason()).isEqualTo(TerminalReason.AGENT_FAILED);
    }

    @Test
    void rejectingAGateIsGateRejected() {
        Run run = running("tr-3");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        gateService.decide(run.getId(), null, false, java.util.UUID.randomUUID(), "no");
        Run done = reload(run);
        assertThat(done.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(done.getTerminalReason()).isEqualTo(TerminalReason.GATE_REJECTED);
    }

    @Test
    void gateExpiryIsGateExpired() {
        Run run = running("tr-4");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        gateService.expire(run.getId(), RunStatus.AWAITING_APPROVAL, TerminalReason.GATE_EXPIRED,
                Map.of("summary", "No approval decision in time; run stopped"));
        Run done = reload(run);
        assertThat(done.getStatus()).isEqualTo(RunStatus.EXPIRED);
        assertThat(done.getTerminalReason()).isEqualTo(TerminalReason.GATE_EXPIRED);
    }

    @Test
    void timeoutAndSandboxLostUseTheReasonPassedByTheCaller() {
        Run a = running("tr-5");
        lifecycle.terminate(a.getId(), RunStatus.RUNNING, RunStatus.TIMED_OUT, TerminalReason.TIMED_OUT,
                RunStepKind.TIMEOUT, Map.of("summary", "Run exceeded its time limit"));
        assertThat(reload(a).getTerminalReason()).isEqualTo(TerminalReason.TIMED_OUT);

        Run b = running("tr-6");
        lifecycle.terminate(b.getId(), RunStatus.RUNNING, RunStatus.FAILED, TerminalReason.SANDBOX_LOST,
                RunStepKind.FAILED, Map.of("summary", "Agent stopped without reporting completion"));
        assertThat(reload(b).getTerminalReason()).isEqualTo(TerminalReason.SANDBOX_LOST);
    }

    @Test
    void repeatedViolationsAreScopeViolations() {
        Run run = running("tr-7");
        for (int i = 0; i < RunLifecycleService.MAX_PRODUCTION_VIOLATIONS; i++) {
            lifecycle.recordViolation(run.getId(), Map.of("attempted", "read", "target", "payroll"));
        }
        assertThat(reload(run).getTerminalReason()).isEqualTo(TerminalReason.SCOPE_VIOLATIONS);
    }

    @Test
    void capReachedIsCostCapReached() {
        var scenario = fixtures.liveTierA(new BigDecimal("1.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-tr-8", "tok-tr-8");
        lifecycle.recordUsage(run.getId(), new BigDecimal("1000000"), Map.of("quantity", 1000000, "unit", "page"));
        assertThat(reload(run).getTerminalReason()).isEqualTo(TerminalReason.COST_CAP_REACHED);
    }

    @Test
    void stepsWithABlockUpdateReachedBlockAndNonTerminalRunsHaveNoReason() {
        Run run = running("tr-9");
        lifecycle.recordStep(run.getId(), RunStepKind.LLM_CALL, RunStepSource.PLATFORM, Map.of("tokens", 5));
        Run r = reload(run);
        assertThat(r.getReachedBlock()).isEqualTo("llm");
        assertThat(r.getTerminalReason()).isNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=TerminalReasonTest`. Expected: compile errors (new `terminate` and `expire` signatures).

- [ ] **Step 3: Change `RunLifecycleService`**

Replace the two `terminate` overloads with reason-carrying ones and thread the reason into `finish`:

```java
    @Transactional
    public boolean terminate(UUID runId, RunStatus terminalStatus, TerminalReason reason, RunStepKind stepKind,
                             Map<String, Object> detail) {
        return terminate(runId, null, terminalStatus, reason, stepKind, detail);
    }

    @Transactional
    public boolean terminate(UUID runId, RunStatus expectedStatus, RunStatus terminalStatus, TerminalReason reason,
                             RunStepKind stepKind, Map<String, Object> detail) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal status: " + terminalStatus);
        }
        Run run = lock(runId);
        boolean blocked = expectedStatus != null ? run.getStatus() != expectedStatus : run.getStatus().isTerminal();
        if (blocked) {
            return false;
        }
        if (stepKind != null) {
            step(run, stepKind, detail == null ? Map.of() : detail);
        }
        finish(run, terminalStatus, reason);
        return true;
    }
```

Change `finish` to `private void finish(Run run, RunStatus status, TerminalReason reason)` and add `run.setTerminalReason(reason);` next to `run.setStatus(status);`. Update every `finish(...)` call:

| Call site | Reason |
| --- | --- |
| `recordEvent` `COMPLETE` | `FINISHED` |
| `recordEvent` `FAILED` | `AGENT_FAILED` |
| `rejectGate` | `GATE_REJECTED` |
| `recordViolation` (stop) | `SCOPE_VIOLATIONS` |
| `charge` (cap reached) | `COST_CAP_REACHED` |
| `complete` | `FINISHED` |
| `fail` | `AGENT_FAILED` |

In `step(Run, RunStepKind, RunStepSource, Map)` after `runStepRepository.save(step)` add:

```java
            if (step.getBlock() != null) {
                run.setReachedBlock(step.getBlock());
            }
            if (CLOSING_KINDS.contains(kind) && detail.get("summary") != null) {
                run.setSummary(String.valueOf(detail.get("summary")));
            }
```

and the constant `private static final Set<RunStepKind> CLOSING_KINDS = EnumSet.of(RunStepKind.COMPLETE, RunStepKind.FAILED, RunStepKind.TIMEOUT, RunStepKind.COST_CAP_REACHED, RunStepKind.CANCELLED);`. Every caller of `step(...)` either saves the run afterwards or calls `finish`, which saves it, so the change persists. Confirm this by reading `recordStep`, `recordAgentLogs`, `recordViolation` and `charge` (all end in `runRepository.save(run)` or `finish`).

- [ ] **Step 4: Update the callers**

- `GateService.expire(UUID runId, RunStatus expectedStatus, TerminalReason reason, Map<String,Object> detail)`: pass `RunStatus.EXPIRED, reason` into `terminate`.
- `RunReaper.reap`: in the awaiting-approval branch pass `gateExpired ? TerminalReason.GATE_EXPIRED : TerminalReason.TIMED_OUT`; the deadline branch passes `TerminalReason.TIMED_OUT`; the sandbox-stopped branch passes `TerminalReason.SANDBOX_LOST`.
- `RunLauncher` (four calls) and `PendingBuildRuns` (one call): `TerminalReason.AGENT_FAILED`.
- `SandboxRunService.cancel`: `lifecycle.terminate(runId, RunStatus.CANCELLED, TerminalReason.CANCELLED, RunStepKind.CANCELLED, Map.of("summary", "Cancelled by the developer"))`.

Run `mvn -q test-compile` and fix the remaining test call sites (`grep -rn "\.terminate(\|\.expire(" backend/src/test`), passing the reason that matches each test's scenario.

- [ ] **Step 5: Run tests**

Run: `mvn -q test -Dtest='TerminalReasonTest,RunReaperTest,RunLifecycleServiceTest,RunLifecycleRuntimeTest,GateServiceTest,GateClosedWhenRunEndsTest,PendingBuildRunsTest,Sandbox*Test,ApprovalGateTest'`. Expected: PASS.

- [ ] **Step 6: Migration backfill test**

Append to `TerminalReasonTest` a test that inserts runs directly through `JdbcTemplate` in statuses COMPLETED, FAILED with a `COST_CAP_REACHED` step and CANCELLED with a `CANCELLED` step, then runs the same `UPDATE` statements copied from V38 and asserts `terminal_reason` is `FINISHED`, `COST_CAP_REACHED`, `CANCELLED`. (The migration itself ran on an empty table; this pins the SQL.) Run it, expect PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(runs): record why a run ended, its reached block and closing summary"
```

---

### Task 4: Gate columns, estimated units, decided-via and eligible approvers

**Files:**
- Create: `backend/src/main/resources/db/migration/V39__gate_request_ws1b.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/DecidedVia.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/EligibleApprovers.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/GateCosts.java`
- Modify: `GateRequest.java`, `GateService.java`, `backend/src/main/java/com/agentstore/platform/runtime/CreateGateRequest.java`, `RuntimeGateController.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/GateWs1bTest.java`

**Interfaces:**
- Consumes: `OrgMembershipRepository.findByIdOrgIdOrderByCreatedAtAsc(UUID)`, `ManifestParser.parse`.
- Produces: `GateService.open(UUID runId, String action, int timeoutMinutes, String summary, String payloadJson, String affectsJson, BigDecimal estimatedUnits, String previewJson): GateRequest` (the existing six-argument overload stays and delegates with nulls); `GateRequest.getEstimatedUnits(): BigDecimal`, `getPreview(): String`, `getDecidedVia(): DecidedVia`, `eligibleApproverIds(): List<UUID>` (null when unrestricted); `GateCosts.costIfApproved(Run, GateRequest, ManifestParser): BigDecimal` (null without an estimate); `CreateGateRequest.estimatedUnits`.

- [ ] **Step 1: Write the failing tests**

```java
package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class GateWs1bTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    GateService gateService;
    @Autowired
    GateRequestRepository gates;
    @Autowired
    RunRepository runs;
    @Autowired
    UserRepository users;
    @Autowired
    OrgMembershipRepository memberships;
    @Autowired
    ManifestParser manifestParser;

    private User approver(Org org, String email, UserRole role) {
        User user = users.save(new User(null, email, null, "Approver"));
        OrgMembership m = new OrgMembership();
        m.setId(new OrgMembershipId(user.getId(), org.getId()));
        m.setUser(user);
        m.setOrg(org);
        m.setRole(role);
        m.setCreatedAt(Instant.now());
        memberships.save(m);
        return user;
    }

    private HttpHeaders bearer(UUID orgId, UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(userId, orgId, UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpStatus decide(Run run, GateRequest gate, HttpHeaders headers) {
        return HttpStatus.valueOf(rest.exchange(
                "http://localhost:" + port + "/api/v1/runs/" + run.getId() + "/gates/" + gate.getId() + "/decision",
                HttpMethod.POST, new HttpEntity<>(java.util.Map.of("decision", "APPROVE"), headers), String.class)
                .getStatusCode().value());
    }

    @Test
    void snapshotsTheOrgsApproversAndRefusesEveryoneElse() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        User eligible = approver(scenario.buyer(), "a1@example.com", UserRole.BUYER_APPROVER);
        approver(scenario.buyer(), "admin1@example.com", UserRole.BUYER_ADMIN);   // admins cannot approve today
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g1", "tok-g1");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");

        assertThat(gate.eligibleApproverIds()).containsExactly(eligible.getId());
        assertThat(decide(run, gate, bearer(scenario.buyer().getId(), UUID.randomUUID())))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(run, gate, bearer(scenario.buyer().getId(), eligible.getId()))).isEqualTo(HttpStatus.OK);
        GateRequest decided = gates.findById(gate.getId()).orElseThrow();
        assertThat(decided.getDecidedVia()).isEqualTo(DecidedVia.WEB);
    }

    @Test
    void anOrgWithNoApproversIsUnrestrictedInsteadOfDeadlocked() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g2", "tok-g2");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        assertThat(gate.eligibleApproverIds()).isNull();
        assertThat(decide(run, gate, bearer(scenario.buyer().getId(), UUID.randomUUID()))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void roleChangeAfterOpeningDoesNotAlterTheSnapshot() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        User eligible = approver(scenario.buyer(), "a2@example.com", UserRole.BUYER_APPROVER);
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g3", "tok-g3");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        User late = approver(scenario.buyer(), "a3@example.com", UserRole.BUYER_APPROVER);
        assertThat(gates.findById(gate.getId()).orElseThrow().eligibleApproverIds())
                .containsExactly(eligible.getId()).doesNotContain(late.getId());
    }

    @Test
    void costIfApprovedIsCappedByRemainingBudgetAndNullWithoutAnEstimate() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        BigDecimal unitPrice = manifestParser.parse(scenario.version().getManifestJson()).costEnvelope().unitPrice();
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g4", "tok-g4");

        GateRequest withEstimate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]",
                new BigDecimal("10"), null);
        assertThat(GateCosts.costIfApproved(run, withEstimate, manifestParser))
                .isEqualByComparingTo(unitPrice.multiply(new BigDecimal("10")).min(new BigDecimal("500.00")));

        GateRequest huge = new GateRequest(run, "x", "s", "{}", "[]", Instant.now().plusSeconds(60));
        huge.setEstimatedUnits(new BigDecimal("9999999999"));
        assertThat(GateCosts.costIfApproved(run, huge, manifestParser)).isEqualByComparingTo("500.00");

        GateRequest none = new GateRequest(run, "x", "s", "{}", "[]", Instant.now().plusSeconds(60));
        assertThat(GateCosts.costIfApproved(run, none, manifestParser)).isNull();
    }
}
```

Also add to this class (same style) three tests through `POST /runtime/v1/gates` using the run token: `estimatedUnits: 0`, `-1` and `"12345678901.5"` each return 400; a valid `estimatedUnits` returns 201 and `gates.findById(...).getEstimatedUnits()` equals it. Use the request helper pattern of `RuntimeGateControllerTest` in `backend/src/test/java/com/agentstore/platform/runtime/`.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=GateWs1bTest`. Expected: compile errors.

- [ ] **Step 3: Migration V39**

```sql
ALTER TABLE gate_request ADD COLUMN estimated_units NUMERIC(14,4);
ALTER TABLE gate_request ADD COLUMN preview JSONB;
ALTER TABLE gate_request ADD COLUMN decided_via VARCHAR(20);
ALTER TABLE gate_request ADD COLUMN eligible_approver_ids JSONB;   -- null = unrestricted
UPDATE gate_request SET decided_via = 'WEB' WHERE status IN ('APPROVED', 'REJECTED');
```

- [ ] **Step 4: Implement**

`DecidedVia.java`: `public enum DecidedVia { WEB, EMAIL_LINK, SLACK_LINK }`.

`GateRequest.java` add:

```java
    @Column(name = "estimated_units")
    private java.math.BigDecimal estimatedUnits;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String preview;

    @Enumerated(EnumType.STRING)
    private DecidedVia decidedVia;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligible_approver_ids", columnDefinition = "jsonb")
    private String eligibleApproverIdsJson;

    /** Null when the gate is unrestricted (sandbox runs, or a buyer org with no approver members). */
    public java.util.List<java.util.UUID> eligibleApproverIds() {
        if (eligibleApproverIdsJson == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(eligibleApproverIdsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.UUID>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt eligible_approver_ids on gate " + id, e);
        }
    }
```

`EligibleApprovers.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may decide a gate, snapshotted when it opens. Today that is the buyer org's BUYER_APPROVER members, which is the
 * rule the decision endpoints already enforce through their role check, made explicit. WS15 swaps the source for the
 * configurable approver list. A sandbox run has no buyer org, and an org with no approver members would otherwise
 * deadlock, so both snapshot as null (unrestricted).
 */
@Component
public class EligibleApprovers {

    private final OrgMembershipRepository memberships;
    private final ObjectMapper mapper;

    public EligibleApprovers(OrgMembershipRepository memberships, ObjectMapper mapper) {
        this.memberships = memberships;
        this.mapper = mapper;
    }

    /** JSON array of user ids, or null for "unrestricted". */
    public String snapshotJson(Run run) {
        if (run.getConnection() == null) {
            return null;
        }
        List<UUID> ids = memberships.findByIdOrgIdOrderByCreatedAtAsc(run.getConnection().getBuyerOrg().getId()).stream()
                .filter(m -> m.getRole() == UserRole.BUYER_APPROVER)
                .map(m -> m.getUser().getId())
                .toList();
        if (ids.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`GateCosts.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.ManifestParser;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The "Cost if approved" figure. Computed by the platform from the agent's declared price; never supplied by the agent. */
public final class GateCosts {

    private GateCosts() {
    }

    public static BigDecimal costIfApproved(Run run, GateRequest gate, ManifestParser manifestParser) {
        if (gate.getEstimatedUnits() == null) {
            return null;
        }
        BigDecimal unitPrice = manifestParser.parse(run.getAgentVersion().getManifestJson()).costEnvelope().unitPrice();
        BigDecimal requested = gate.getEstimatedUnits().multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal spent = run.getConnection() == null ? run.getSimulatedCharged() : run.getTotalCharged();
        BigDecimal remaining = run.getCostCap().subtract(spent).max(BigDecimal.ZERO);
        return requested.min(remaining);
    }
}
```

`GateService`: inject `EligibleApprovers`; add the eight-argument `open` (`estimatedUnits`, `previewJson` last) that sets `gate.setEstimatedUnits(...)`, `gate.setPreview(previewJson)` and `gate.setEligibleApproverIdsJson(eligibleApprovers.snapshotJson(run))` before saving; keep the six-argument `open` delegating with `(null, null)`. In `decide`, right after the `gate.getStatus() != PENDING` check and before setting the status, add:

```java
            List<UUID> eligible = gate.eligibleApproverIds();
            if (eligible != null && !eligible.contains(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not an approver for this gate");
            }
```

and after `gate.setDecidedAt(Instant.now());` add `gate.setDecidedVia(DecidedVia.WEB);`.

`CreateGateRequest`: add a last component `@Positive @DecimalMax("9999999999") @Digits(integer = 10, fraction = 4) BigDecimal estimatedUnits` (optional, so no `@NotNull`); `RuntimeGateController.create` passes `request.estimatedUnits(), null` for now (Task 5 supplies the preview).

- [ ] **Step 5: Run tests**

Run: `mvn -q test -Dtest='GateWs1bTest,GateDecisionEndpointTest,GateServiceTest,ApprovalGateTest,RuntimeGate*Test,GateClosedWhenRunEndsTest'`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(gates): estimated units, decided-via and a snapshot of eligible approvers (V39)"
```

---

### Task 5: Manifest preview block and the platform-built gate preview

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/manifest/ApprovalGateDecl.java`, `ManifestParser.java`
- Modify: `backend/src/main/resources/manifest/manifest-schema.json`
- Create: `backend/src/main/java/com/agentstore/platform/data/GatePreviewBuilder.java`
- Modify: `backend/src/main/java/com/agentstore/platform/runtime/RuntimeGateController.java`
- Modify: `cli/src/` bundled schema (via `npm run sync-schema`), `frontend/src/lib/types.ts`
- Test: `backend/src/test/java/com/agentstore/platform/data/GatePreviewBuilderTest.java`, `backend/src/test/java/com/agentstore/platform/manifest/ApprovalGatePreviewManifestTest.java`

**Interfaces:**
- Consumes: `RunOutputRepository.findByRunIdOrderByCreatedAtAsc`, `ObjectStorage.open`, `Jsonl.readRows`, `Jsonl.MAPPER` (all existing), `GateService.open` eight-argument overload (Task 4).
- Produces: `ApprovalGateDecl(String trigger, String description, int timeoutMinutes, Preview preview)` with nested `record Preview(List<String> columns, String totalField)`; `GatePreviewBuilder.build(UUID runId, List<String> affectedResources, ApprovalGateDecl.Preview config): String` (JSON, or null when nothing is staged).

- [ ] **Step 1: Write the failing tests**

`ApprovalGatePreviewManifestTest`: parse a manifest JSON string whose gate has `"preview": {"columns": ["date","amount"], "totalField": "amount"}` and assert `manifest.approvalGates().get(0).preview()` equals `new ApprovalGateDecl.Preview(List.of("date","amount"), "amount")`; a gate without `preview` gives `preview() == null`; a `preview` whose `columns` is not an array fails schema validation (use the same validator entry point that `ManifestParser`/`ManifestValidator` tests use in `backend/src/test/java/com/agentstore/platform/manifest/`; copy the setup of the nearest existing test there).

`GatePreviewBuilderTest extends AbstractStorageIntegrationTest`:

```java
    @Autowired GatePreviewBuilder builder;
    @Autowired RunOutputRepository outputs;
    @Autowired ObjectStorage storage;
    @Autowired ExecutionFixtures fixtures;   // lives in execution test package: make it public (it is) and import it

    private UUID stage(UUID runId, String resource, String... jsonLines) {
        String key = "outputs/" + runId + "/" + resource + ".jsonl";
        byte[] bytes = (String.join("\n", jsonLines) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        storage.put(key, bytes);
        return outputs.save(new RunOutput(null, runId, resource, OutputStatus.STAGED, jsonLines.length, bytes.length, key)).getId();
    }

    @Test
    void firstTwentyRowsCutToColumnsWithTotals() throws Exception {
        UUID runId = newRun();
        String[] lines = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "{\"date\":\"2026-01-%02d\",\"account\":\"A%d\",\"amount\":%d.50}".formatted(i, i, i))
                .toArray(String[]::new);
        stage(runId, "journal_entries", lines);
        String json = builder.build(runId, List.of("journal_entries"),
                new ApprovalGateDecl.Preview(List.of("date", "amount"), "amount"));
        JsonNode p = Jsonl.MAPPER.readTree(json);
        assertThat(p.get("rows")).hasSize(20);
        assertThat(p.get("rows").get(0).fieldNames()).toIterable().containsExactly("date", "amount");
        assertThat(p.get("totalRows").asInt()).isEqualTo(30);
        assertThat(p.get("total").decimalValue()).isEqualByComparingTo("480.00");   // 1.50..30.50 => sum(i)+30*0.5 = 465+15
    }

    @Test
    void noConfigMeansAllColumnsAndNoTotal() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"date\":\"2026-01-01\",\"amount\":5}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("journal_entries"), null));
        assertThat(p.has("total")).isFalse();
        assertThat(p.has("columns")).isFalse();
        assertThat(p.get("rows").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("date", "amount");
    }

    @Test
    void nothingStagedIsNull() {
        assertThat(builder.build(newRun(), List.of("journal_entries"), null)).isNull();
    }

    @Test
    void nonNumericAndMissingTotalFieldValuesAreSkipped() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"amount\":\"n/a\"}", "{\"other\":1}", "{\"amount\":2}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("journal_entries"),
                new ApprovalGateDecl.Preview(null, "amount")));
        assertThat(p.get("total").decimalValue()).isEqualByComparingTo("2");
        assertThat(p.get("totalRows").asInt()).isEqualTo(3);
    }

    @Test
    void unknownColumnIsIgnoredNotAnError() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"date\":\"2026-01-01\",\"amount\":5}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("journal_entries"),
                new ApprovalGateDecl.Preview(List.of("nope", "date"), null)));
        assertThat(p.get("rows").get(0).fieldNames()).toIterable().containsExactly("date");
    }

    @Test
    void discardedAndCommittedOutputsAreNotPreviewed() {
        UUID runId = newRun();
        String key = "outputs/" + runId + "/x.jsonl";
        storage.put(key, "{\"a\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outputs.save(new RunOutput(null, runId, "journal_entries", OutputStatus.DISCARDED, 1, 8, key));
        outputs.save(new RunOutput(null, runId, "journal_entries", OutputStatus.COMMITTED, 1, 8, key));
        assertThat(builder.build(runId, List.of("journal_entries"), null)).isNull();
    }

    @Test
    void onlyOutputsForAffectedResourcesAreUsed() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"a\":1}", "{\"a\":2}");
        stage(runId, "vendors", "{\"a\":9}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("vendors"), null));
        assertThat(p.get("totalRows").asInt()).isEqualTo(1);
    }
```

`newRun()` is a private helper: `var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00")); return fixtures.run(s, RunStatus.RUNNING, "sbx-" + System.nanoTime(), "tok-" + System.nanoTime()).getId();`

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='GatePreviewBuilderTest,ApprovalGatePreviewManifestTest'`. Expected: compile errors.

- [ ] **Step 3: Manifest change**

`ApprovalGateDecl`:

```java
public record ApprovalGateDecl(String trigger, String description, int timeoutMinutes, Preview preview) {
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;

    /** Optional: which columns a gate preview shows and which numeric column it totals. */
    public record Preview(List<String> columns, String totalField) {
    }

    public ApprovalGateDecl(String trigger, String description, int timeoutMinutes) {
        this(trigger, description, timeoutMinutes, null);
    }

    public ApprovalGateDecl(String trigger, String description) {
        this(trigger, description, DEFAULT_TIMEOUT_MINUTES, null);
    }
}
```

(import `java.util.List`). In `manifest-schema.json` `approvalGates.items.properties` add:

```json
          "preview": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "columns": { "type": "array", "items": { "type": "string", "minLength": 1 }, "maxItems": 50 },
              "totalField": { "type": "string", "minLength": 1 }
            }
          }
```

In `ManifestParser` build the gate with the preview:

```java
            JsonNode p = g.path("preview");
            ApprovalGateDecl.Preview preview = null;
            if (p.isObject()) {
                List<String> cols = new ArrayList<>();
                p.path("columns").forEach(c -> cols.add(c.asText()));
                preview = new ApprovalGateDecl.Preview(cols, p.hasNonNull("totalField") ? p.get("totalField").asText() : null);
            }
            gates.add(new ApprovalGateDecl(g.get("trigger").asText(), g.path("description").asText(""),
                    g.path("timeoutMinutes").asInt(ApprovalGateDecl.DEFAULT_TIMEOUT_MINUTES), preview));
```

- [ ] **Step 4: `GatePreviewBuilder`**

```java
package com.agentstore.platform.data;

import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MappingIterator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the preview the buyer sees at an approval gate, from the run's staged outputs. The platform builds it so the
 * agent cannot misstate what will be posted. Only the first ROWS rows are kept; the optional total streams over every
 * staged row without holding them in memory.
 */
@Component
public class GatePreviewBuilder {

    static final int ROWS = 20;

    private final RunOutputRepository outputs;
    private final ObjectStorage storage;

    public GatePreviewBuilder(RunOutputRepository outputs, ObjectStorage storage) {
        this.outputs = outputs;
        this.storage = storage;
    }

    /** JSON {columns?, rows, totalRows, total?}, or null when nothing is staged for the affected resources. */
    public String build(UUID runId, List<String> affectedResources, ApprovalGateDecl.Preview config) {
        List<RunOutput> staged = outputs.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(o -> o.getStatus() == OutputStatus.STAGED && o.getStorageKey() != null)
                .filter(o -> affectedResources == null || affectedResources.isEmpty()
                        || affectedResources.contains(o.getResourceName()))
                .toList();
        if (staged.isEmpty()) {
            return null;
        }
        List<String> columns = config == null || config.columns() == null || config.columns().isEmpty()
                ? null : config.columns();
        String totalField = config == null ? null : config.totalField();

        List<Map<String, Object>> rows = new ArrayList<>();
        int totalRows = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (RunOutput output : staged) {
            totalRows += output.getRecordCount();
            if (rows.size() >= ROWS && totalField == null) {
                continue;   // rows are full and no total is wanted: no need to read the file again
            }
            try (InputStream in = storage.open(output.getStorageKey());
                 MappingIterator<Map<String, Object>> it = Jsonl.readRows(in)) {
                while (it.hasNext()) {
                    Map<String, Object> row = it.next();
                    if (rows.size() < ROWS) {
                        rows.add(cut(row, columns));
                    }
                    if (totalField != null) {
                        total = total.add(numeric(row.get(totalField)));
                    } else if (rows.size() >= ROWS) {
                        break;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        if (columns != null) {
            preview.put("columns", columns);
        }
        preview.put("rows", rows);
        preview.put("totalRows", totalRows);
        if (totalField != null) {
            preview.put("total", total);
        }
        try {
            return Jsonl.MAPPER.writeValueAsString(preview);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> cut(Map<String, Object> row, List<String> columns) {
        if (columns == null) {
            return row;
        }
        Map<String, Object> cut = new LinkedHashMap<>();
        for (String column : columns) {
            if (row.containsKey(column)) {
                cut.put(column, row.get(column));
            }
        }
        return cut;
    }

    private static BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return BigDecimal.ZERO;   // missing or non-numeric: not part of the total
    }
}
```

Note the streaming loop: when a `totalField` is set every row of every staged output is read; when it is not, reading stops after 20 rows.

- [ ] **Step 5: Wire it into the runtime gate controller**

In `RuntimeGateController` inject `GatePreviewBuilder` and `RunRepository`; in `create`, before `gateService.open`:

```java
        String previewJson = previewBuilder.build(principal.runId(), affects, declared.preview());
```

and call the eight-argument `open(..., request.estimatedUnits(), previewJson)`. Sandbox runs get previews too.

- [ ] **Step 6: Run tests, sync the CLI schema and frontend type**

Run: `mvn -q test -Dtest='GatePreviewBuilderTest,ApprovalGatePreviewManifestTest,Manifest*Test,RuntimeGate*Test'`. Expected: PASS.

Run: `cd cli && npm run sync-schema && npx vitest run` (the bundled-schema drift test must pass). In `frontend/src/lib/types.ts` add to `ApprovalGateDecl`: `preview?: { columns?: string[]; totalField?: string };` and run `cd frontend && npx tsc --noEmit`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(gates): platform-built gate preview and optional manifest preview block"
```

---

### Task 6: Buyer gate view and gate details in the flow

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/GateDetailResponse.java`
- Modify: `RunDetailController.java` (execution), `RunFlowService.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunGatesViewTest.java`

**Interfaces:**
- Consumes: `GateCosts` (Task 4), `GateRequest.getPreview()/getEstimatedUnits()/getDecidedVia()`.
- Produces: `GateDetailResponse(UUID gateId, String action, String summary, Object payload, GateStatus status, Instant expiresAt, BigDecimal estimatedUnits, BigDecimal costIfApproved, Object preview, DecidedVia decidedVia, UUID decidedByUserId, Instant decidedAt, String note)`; `GET /api/v1/runs/{ref}/gates` returning `List<GateDetailResponse>` newest first; a WAITING gate block in `FlowView` now carries `metrics {expiresAt, costIfApproved?}`.

- [ ] **Step 1: Write the failing test**

`RunGatesViewTest` (integration, `AbstractIntegrationTest`): open a gate with `estimatedUnits` and a `previewJson` of `{"rows":[],"totalRows":0}` via the eight-argument `open`; as an authenticated buyer of the run's org call `GET /api/v1/runs/{displayId}/gates` and assert one element with `gateId`, `action`, `status == PENDING`, `costIfApproved`, `preview.totalRows == 0`, `payload` an object; assert another org's buyer gets 403 and an unknown reference 404; open a second gate after deciding the first and assert the list is newest first. Then call `GET /api/v1/runs/{id}/flow`, find the block named `gate:post_journal_entries` and assert `state == WAITING`, `gateId` set, and `metrics.expiresAt` present (and `metrics.costIfApproved` present when an estimate was given, absent otherwise). Use the JWT and `TestRestTemplate` helpers exactly as `GateDecisionEndpointTest` does.


```java
class RunGatesViewTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ExecutionFixtures fixtures;
    @Autowired GateService gateService;

    private HttpEntity<Void> buyer(UUID orgId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_APPROVER));
        return new HttpEntity<>(h);
    }

    @Test
    void gateViewShowsCostPreviewAndPayload() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-gv", "tok-gv");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post 3 entries", "{\"n\":3}", "[]",
                new BigDecimal("10"), "{\"rows\":[],\"totalRows\":0}");

        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + run.getDisplayId() + "/gates",
                HttpMethod.GET, buyer(s.buyer().getId()), com.fasterxml.jackson.databind.JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var gate = response.getBody().get(0);
        assertThat(gate.get("status").asText()).isEqualTo("PENDING");
        assertThat(gate.get("costIfApproved").decimalValue()).isPositive();
        assertThat(gate.get("preview").get("totalRows").asInt()).isZero();
        assertThat(gate.get("payload").get("n").asInt()).isEqualTo(3);
    }

    @Test
    void flowGateBlockCarriesExpiryAndCost() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-gf", "tok-gf");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]", new BigDecimal("10"), null);

        var flow = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + run.getId() + "/flow",
                HttpMethod.GET, buyer(s.buyer().getId()), com.fasterxml.jackson.databind.JsonNode.class).getBody();

        var block = java.util.stream.StreamSupport.stream(flow.get("blocks").spliterator(), false)
                .filter(b -> b.get("block").asText().equals("gate:post_journal_entries")).findFirst().orElseThrow();
        assertThat(block.get("state").asText()).isEqualTo("WAITING");
        assertThat(block.get("metrics").has("expiresAt")).isTrue();
        assertThat(block.get("metrics").has("costIfApproved")).isTrue();
    }

    @Test
    void anotherOrgIsRefusedAndAnUnknownReferenceIs404() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-go", "tok-go");
        String base = "http://localhost:" + port + "/api/v1/runs/";
        assertThat(rest.exchange(base + run.getId() + "/gates", HttpMethod.GET, buyer(UUID.randomUUID()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(base + "R-99999999/gates", HttpMethod.GET, buyer(s.buyer().getId()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

Additional case in the same class, with the same helpers: open a gate, decide it (`gateService.decide(run.getId(), null, true, UUID.randomUUID(), "ok")`), open a second gate, and assert the list has two elements with the newest first.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=RunGatesViewTest`. Expected: 404 on `/gates` and no `metrics` on the gate block.

- [ ] **Step 3: Implement**

```java
package com.agentstore.platform.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What a buyer sees at an approval gate. payload and preview are parsed JSON, so clients get objects, not strings. */
public record GateDetailResponse(UUID gateId, String action, String summary, Object payload, GateStatus status,
                                 Instant expiresAt, BigDecimal estimatedUnits, BigDecimal costIfApproved,
                                 Object preview, DecidedVia decidedVia, UUID decidedByUserId, Instant decidedAt,
                                 String note) {

    static GateDetailResponse from(GateRequest gate, BigDecimal costIfApproved, ObjectMapper mapper) {
        return new GateDetailResponse(gate.getId(), gate.getAction(), gate.getSummary(), parse(gate.getPayload(), mapper),
                gate.getStatus(), gate.getExpiresAt(), gate.getEstimatedUnits(), costIfApproved,
                parse(gate.getPreview(), mapper), gate.getDecidedVia(), gate.getDecidedByUserId(), gate.getDecidedAt(),
                gate.getDecisionNote());
    }

    private static Object parse(String json, ObjectMapper mapper) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
```

`GateRequestRepository` needs `List<GateRequest> findByRunIdOrderByCreatedAtDesc(UUID runId);` (add it). In `RunDetailController` inject `GateRequestRepository`, `ManifestParser`, `ObjectMapper` (constructor) and add:

```java
    @GetMapping("/{runRef}/gates")
    @PreAuthorize("isAuthenticated()")
    public java.util.List<GateDetailResponse> gates(@PathVariable("runRef") String runRef) {
        Run run = runLookup.find(runRef);
        requireViewable(run);
        return gateRequestRepository.findByRunIdOrderByCreatedAtDesc(run.getId()).stream()
                .map(g -> GateDetailResponse.from(g, GateCosts.costIfApproved(run, g, manifestParser), mapper)).toList();
    }
```

(If Task 2 named the path variable `runRef` on this controller's other methods, keep it consistent; otherwise use `runId` as the string.) In `RunFlowService` inject `GateRequestRepository`; change the WAITING branch of `declaredBlock(...)` (it currently returns `FlowBlock.declared(block, BlockState.WAITING, null, stringField(last, "gateId"))`) to pass `waitingMetrics(run, gateId)` where:

```java
    private Map<String, Object> waitingMetrics(Run run, String gateId) {
        if (gateId == null) {
            return null;
        }
        return gateRequestRepository.findById(UUID.fromString(gateId)).map(gate -> {
            Map<String, Object> m = new LinkedHashMap<>();
            if (gate.getExpiresAt() != null) {
                m.put("expiresAt", gate.getExpiresAt().toString());
            }
            BigDecimal cost = GateCosts.costIfApproved(run, gate, manifestParser);
            if (cost != null) {
                m.put("costIfApproved", cost);
            }
            return m;
        }).orElse(null);
    }
```

`declaredBlock` therefore needs the `Run run` parameter; pass it from `compute`. Fix the constructor call sites of `RunFlowService` in tests (`RunFlowServiceTest`) by adding the new repository argument.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest='RunGatesViewTest,RunFlowServiceTest,RunFlowControllerTest,RunFlowScopeViolationTest,RunReportServiceTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(runs): buyer gate view and gate details in the flow"
```

---

### Task 7: Run list with groups and counts

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunGroup.java`, `RunListQuery.java`, `RunListItem.java`, `RunListResponse.java`, `RunListService.java`
- Modify: `RunDetailController.java` (add `GET /api/v1/runs`)
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunListTest.java`

**Interfaces:**
- Consumes: `Run.displayId/terminalReason/reachedBlock/summary` (Tasks 1 and 3), `NamedParameterJdbcTemplate`.
- Produces: `GET /api/v1/runs?group&agentId&q&from&to&page&size` returning `RunListResponse(List<RunListItem> items, Map<String, Long> counts, int page, int size, long total)`; `RunListItem(UUID id, String displayId, String agent, String version, RunStatus status, TerminalReason terminalReason, String reachedBlock, Instant startedAt, Instant completedAt, BigDecimal charged, BigDecimal held, String decidedByName)` where the API exposes `decidedBy` as `{name}` via `RunListItem.DecidedBy`.

- [ ] **Step 1: Write the failing test**

`RunListTest` (integration, JWT as the buyer): create one run each in `AWAITING_APPROVAL`, `RUNNING`, `COMPLETED`, `FAILED` using `fixtures.run(...)`; then assert

- `GET /api/v1/runs` returns four items and `counts == {needs_you:1, running:1, finished:1, stopped:1}`.
- `?group=stopped` returns one item (the FAILED one) and `counts` is unchanged.
- `?agentId=<other uuid>` returns none and every count is 0; `?q=<displayId lower case>` returns that run; `?q=%` returns none (literal match); a run whose `summary` contains `ledger` is found by `?q=Ledger`.
- `?from=<future>` returns none; `?from=<future>&to=<past>` returns an empty page with 200.
- With 30 runs, `?size=10&page=2` returns the last 10 and `total == 30`; `?page=9` returns an empty `items` with `counts` still correct; `?size=1000` is clamped to 100.
- another org's runs never appear; sandbox runs never appear; a `DEVELOPER` role gets 403.
- `decidedBy.name` is the full name of the user who decided the latest decided gate (save a `User`, open and decide a gate with `gateService`), otherwise null.


```java
class RunListTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ExecutionFixtures fixtures;
    @Autowired RunRepository runs;

    private JsonNode get(ExecutionFixtures.Scenario s, String query) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), s.buyer().getId(), UserRole.BUYER_ADMIN));
        return rest.exchange("http://localhost:" + port + "/api/v1/runs" + query, HttpMethod.GET, new HttpEntity<>(h),
                JsonNode.class).getBody();
    }

    private ExecutionFixtures.Scenario fourRuns() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        fixtures.run(s, RunStatus.AWAITING_APPROVAL, "sbx-l1", "tok-l1");
        fixtures.run(s, RunStatus.RUNNING, "sbx-l2", "tok-l2");
        fixtures.run(s, RunStatus.COMPLETED, "sbx-l3", "tok-l3");
        Run failed = fixtures.run(s, RunStatus.FAILED, "sbx-l4", "tok-l4");
        failed.setSummary("Ledger export failed");
        runs.save(failed);
        return s;
    }

    @Test
    void countsCoverEveryGroupAndIgnoreTheGroupFilter() {
        var s = fourRuns();
        JsonNode all = get(s, "");
        assertThat(all.get("items")).hasSize(4);
        JsonNode counts = all.get("counts");
        assertThat(counts.get("needs_you").asInt()).isEqualTo(1);
        assertThat(counts.get("running").asInt()).isEqualTo(1);
        assertThat(counts.get("finished").asInt()).isEqualTo(1);
        assertThat(counts.get("stopped").asInt()).isEqualTo(1);

        JsonNode stopped = get(s, "?group=stopped");
        assertThat(stopped.get("items")).hasSize(1);
        assertThat(stopped.get("counts")).isEqualTo(counts);
    }

    @Test
    void searchMatchesDisplayIdAndSummaryAndTreatsWildcardsLiterally() {
        var s = fourRuns();
        assertThat(get(s, "?q=ledger").get("items")).hasSize(1);
        assertThat(get(s, "?q=%25").get("items")).isEmpty();          // a literal % matches nothing
        String someId = get(s, "").get("items").get(0).get("displayId").asText().toLowerCase();
        assertThat(get(s, "?q=" + someId).get("items")).hasSize(1);
    }

    @Test
    void dateRangeAndPagingEdges() {
        var s = fourRuns();
        assertThat(get(s, "?from=2999-01-01T00:00:00Z").get("items")).isEmpty();
        assertThat(get(s, "?from=2999-01-01T00:00:00Z&to=2000-01-01T00:00:00Z").get("items")).isEmpty();
        JsonNode beyond = get(s, "?page=9&size=2");
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("counts").get("running").asInt()).isEqualTo(1);
        assertThat(get(s, "?size=1000").get("size").asInt()).isEqualTo(100);
    }

    @Test
    void otherOrgsSandboxRunsAndDevelopersAreExcluded() {
        var mine = fourRuns();
        var theirs = fourRuns();
        assertThat(get(mine, "").get("items")).hasSize(4);
        HttpHeaders dev = new HttpHeaders();
        dev.setBearerAuth(jwtService.issue(UUID.randomUUID(), mine.buyer().getId(), UserRole.DEVELOPER));
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/runs", HttpMethod.GET, new HttpEntity<>(dev),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

Additional cases in the same class: with 30 runs `?size=10&page=2` returns the last 10 and `total == 30`; `?agentId=<random uuid>` returns no items and all-zero counts; `decidedBy.name` equals the full name of the user who decided the latest decided gate (save a `User` via `UserRepository`, then `gateService.open` and `gateService.decide(..., user.getId(), ...)`) and is null otherwise; a sandbox run created directly (`mode = SANDBOX`, no connection) never appears.

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=RunListTest`. Expected: FAIL (405/404 on `GET /api/v1/runs`).

- [ ] **Step 3: Implement**

```java
package com.agentstore.platform.execution;

import java.util.List;

public enum RunGroup {
    NEEDS_YOU("needs_you", List.of(RunStatus.AWAITING_APPROVAL)),
    RUNNING("running", List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING)),
    FINISHED("finished", List.of(RunStatus.COMPLETED)),
    STOPPED("stopped", List.of(RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT, RunStatus.EXPIRED));

    private final String key;
    private final List<RunStatus> statuses;

    RunGroup(String key, List<RunStatus> statuses) {
        this.key = key;
        this.statuses = statuses;
    }

    public String key() {
        return key;
    }

    public List<RunStatus> statuses() {
        return statuses;
    }

    public static RunGroup fromKey(String key) {
        for (RunGroup g : values()) {
            if (g.key.equals(key)) {
                return g;
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown group: " + key);
    }
}
```

`RunListService` builds SQL from the fixed filter fragments below (only `?`-free, named parameters; no user text is concatenated into SQL):

```java
    private static final String FROM = """
            FROM run r
            JOIN connection c ON c.id = r.connection_id
            JOIN agent a ON a.id = c.agent_id
            JOIN agent_version v ON v.id = r.agent_version_id
            LEFT JOIN LATERAL (
                SELECT u.full_name FROM gate_request g JOIN app_user u ON u.id = g.decided_by_user_id
                WHERE g.run_id = r.id AND g.decided_by_user_id IS NOT NULL ORDER BY g.decided_at DESC LIMIT 1) d ON true
            WHERE c.buyer_org_id = :orgId
            """;
```

Filters appended when present: `AND a.id = :agentId`; `AND r.started_at >= :from`; `AND r.started_at <= :to`; `AND (r.display_id ILIKE :q ESCAPE '\' OR a.name ILIKE :q ESCAPE '\' OR r.summary ILIKE :q ESCAPE '\')` with `:q` = `"%" + escapeLike(q) + "%"` where `escapeLike` prefixes `\`, `%` and `_` with `\`. Items query: `SELECT r.id, r.display_id, a.name AS agent, v.version_number, r.status, r.terminal_reason, r.reached_block, r.started_at, r.completed_at, r.total_charged, r.reserved_amount, d.full_name ` + FROM + filters + ` AND r.status IN (:statuses) ORDER BY r.started_at DESC, r.id LIMIT :size OFFSET :offset` (statuses = the selected group's, or all when no group). Counts query: `SELECT r.status, count(*) ` + FROM + filters (all except group) + ` GROUP BY r.status`, then folded into the four group keys (missing groups are 0). `total` is the count for the selected group (or all groups). `size` is clamped to `[1, 100]` (default 25), `page` to `>= 0`. `held` is `reserved_amount`, `charged` is `total_charged`. Map rows to `RunListItem` with `status` and `terminalReason` parsed via `valueOf` (null-safe for the reason).

Controller method on `RunDetailController`:

```java
    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunListResponse list(@RequestParam(required = false) String group,
                                @RequestParam(required = false) UUID agentId,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) Instant from,
                                @RequestParam(required = false) Instant to,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "25") int size) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return runListService.list(claims.orgId(), new RunListQuery(group == null ? null : RunGroup.fromKey(group),
                agentId, q, from, to, page, size));
    }
```

(`Instant` request params bind ISO-8601 by default in Spring; `/mine` stays unchanged and Spring matches `GET /api/v1/runs` to this method because `/mine` and `/{ref}` are more specific paths.)

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest='RunListTest,RunDetailControllerTest,RunSummaryControllerTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(runs): grouped run list with counts, search and pagination"
```

---

### Task 8: Network events

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunStepKind.java`, `FlowView.java`, `RunFlowService.java`, `RunReportService.java`
- Create: `backend/src/main/java/com/agentstore/platform/runtime/NetworkEventBatch.java`
- Modify: `backend/src/main/java/com/agentstore/platform/runtime/RuntimeLifecycleController.java`
- Modify: `RunLifecycleService.java` (new method)
- Test: `backend/src/test/java/com/agentstore/platform/runtime/NetworkEventsTest.java`

**Interfaces:**
- Consumes: `RunLifecycleService.recordStep(UUID, RunStepKind, RunStepSource, Map)`.
- Produces: `RunStepKind.NETWORK_BLOCKED`; `POST /runtime/v1/network-events`; `FlowView(List<FlowBlock> blocks, String reachedBlock, Spend spend, Instant deadlineAt, long blockedAttempts)`; report `network.blocked: [{host, count}]`.

- [ ] **Step 1: Write the failing tests**

`NetworkEventsTest` (copy the run-token request helpers from `RuntimeLifecycleControllerTest` in the same package): 

- a valid batch of two events returns 204, records two `NETWORK_BLOCKED` steps with `source == AGENT` and `block == null`, and `GET /runs/{id}/flow` shows `blockedAttempts == sum of counts`;
- `{"events": []}` is 400; an event with `count` 0 or -3 is 400; 101 events is 400; a `host` longer than 255 characters is 400; a missing `host` is 400;
- a terminal run returns 409 `RUN_TERMINATED`; a missing or bad token returns 401;
- after a batch, `RunReportService`'s report `network.blocked` lists `{host, count}` aggregated per host (two events for one host sum to one entry).


```java
class NetworkEventsTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ExecutionFixtures fixtures;
    @Autowired RunStepRepository steps;
    @Autowired RunRepository runs;

    private ResponseEntity<String> post(String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/runtime/v1/network-events", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private Run runWithToken(String token) {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
    }

    @Test
    void recordsOneAgentSourcedStepPerEvent() {
        Run run = runWithToken("tok-ne-1");
        var response = post("tok-ne-1", Map.of("events", List.of(
                Map.of("host", "evil.example", "count", 3), Map.of("host", "evil.example", "count", 2, "callsite", "main.py:9"))));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var recorded = steps.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.NETWORK_BLOCKED).toList();
        assertThat(recorded).hasSize(2);
        assertThat(recorded).allMatch(s -> s.getSource() == RunStepSource.AGENT && s.getBlock() == null);
    }

    @Test
    void invalidBatchesAreRejected() {
        runWithToken("tok-ne-2");
        assertThat(post("tok-ne-2", Map.of("events", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("tok-ne-2", Map.of("events", List.of(Map.of("host", "a.example", "count", 0)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("tok-ne-2", Map.of("events", List.of(Map.of("host", "a.example", "count", -3)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("tok-ne-2", Map.of("events", List.of(Map.of("host", "h".repeat(256), "count", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("tok-ne-2", Map.of("events", List.of(Map.of("count", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> Map.of("host", "h" + i + ".example", "count", 1)).toList();
        assertThat(post("tok-ne-2", Map.of("events", tooMany)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void terminalRunIs409AndBadTokenIs401() {
        Run run = runWithToken("tok-ne-3");
        run.setStatus(RunStatus.COMPLETED);
        runs.save(run);
        assertThat(post("tok-ne-3", Map.of("events", List.of(Map.of("host", "a.example", "count", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("wrong", Map.of("events", List.of(Map.of("host", "a.example", "count", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(null, Map.of("events", List.of(Map.of("host", "a.example", "count", 1)))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

Additional cases: after a batch, `GET /api/v1/runs/{id}/flow` (buyer JWT, as in Task 6) has `blockedAttempts == 5` for counts 3 and 2; `RunReportService`'s report `network.blocked` holds one entry `{host: "evil.example", count: 5}` for the two events above (call the report service the way `RunReportServiceTest` does).

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=NetworkEventsTest`. Expected: 404 on the endpoint / compile errors.

- [ ] **Step 3: Implement**

Add `NETWORK_BLOCKED` to `RunStepKind` (keep `defaultSource()` returning `AGENT` for it, which is the enum default branch). Request record:

```java
package com.agentstore.platform.runtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Blocked outbound connection attempts reported by the in-sandbox logging proxy. Best-effort: the VM deny is the control. */
public record NetworkEventBatch(@NotEmpty @Size(max = 100) List<@Valid NetworkEvent> events) {

    public record NetworkEvent(@NotBlank @Size(max = 255) String host, @Min(1) @Max(1000000) int count,
                               @Size(max = 500) String callsite) {
    }
}
```

In `RuntimeLifecycleController` add:

```java
    @PostMapping("/network-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void networkEvents(@Valid @RequestBody NetworkEventBatch batch) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (NetworkEventBatch.NetworkEvent event : batch.events()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("host", event.host());
            detail.put("count", event.count());
            if (event.callsite() != null) {
                detail.put("callsite", event.callsite());
            }
            entries.add(detail);
        }
        runLifecycleService.recordNetworkEvents(RunPrincipal.current().runId(), entries);
    }
```

`RunLifecycleService`:

```java
    /** Stores blocked-connection reports, one NETWORK_BLOCKED step per event; agent-sourced because the proxy is untrusted. */
    @Transactional
    public void recordNetworkEvents(UUID runId, List<Map<String, Object>> events) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        for (Map<String, Object> event : events) {
            step(run, RunStepKind.NETWORK_BLOCKED, RunStepSource.AGENT, event);
        }
        runRepository.save(run);
    }
```

`RunStepBlocks.of` already returns null for unlisted kinds (the `default` branch), so the step carries no block. In `RunFlowService.compute` compute `long blockedAttempts = allSteps.stream().filter(s -> s.getKind() == RunStepKind.NETWORK_BLOCKED).mapToLong(s -> fieldOrDefault(s, "count", 1)).sum();` and return it as the new last `FlowView` component; fix every `new FlowView(...)` in code and tests. In `RunReportService.network(Run run)` replace the note with:

```java
        Map<String, Long> blocked = new java.util.TreeMap<>();
        for (RunStep s : stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())) {
            if (s.getKind() == RunStepKind.NETWORK_BLOCKED) {
                JsonNode d = parseDetail(s);
                blocked.merge(d.path("host").asText(), d.path("count").asLong(1), Long::sum);
            }
        }
        List<Map<String, Object>> blockedList = blocked.entrySet().stream()
                .map(e -> Map.<String, Object>of("host", e.getKey(), "count", e.getValue())).toList();
        return Map.of("declaredAllowlist", manifest.networkAllowlist(), "blocked", blockedList);
```

(use the report service's existing step-detail parsing helper in place of `parseDetail` if it has one; otherwise add a private one mirroring `RunFlowService.parse`). Update `RunReportServiceTest` expectations that referenced the old `note`.

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest='NetworkEventsTest,RunReportServiceTest,RunFlowServiceTest,RunFlowControllerTest,RunLifecycleRuntimeTest'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(runtime): network-events intake, blocked attempts in the flow and report"
```

---

### Task 9: Contract, SDKs, CLI, frontend types, end-to-end and docs

**Files:**
- Modify: `contracts/runtime-v1.yaml` (regenerated)
- Modify: `sdk/python/agentstore/_generated/**`, `sdk/typescript/src/generated/schema.d.ts` (regenerated), `sdk/python/agentstore/gates.py`, `sdk/typescript/src/gates.ts`, `sdk/python/agentstore/_api.py`, `sdk/typescript/src/api.ts`, and their tests
- Modify: `frontend/src/lib/types.ts`
- Modify: `cli/src/commands/*.ts` only if a run reference is validated as a UUID
- Modify: `backend/src/test/java/com/agentstore/platform/sdk/AbstractSdkEndToEndTest.java` (extend) or add `Ws1bEndToEndTest.java`
- Modify: `docs/known-issues.md`, `docs/superpowers/specs/2026-09-29-ws1b-buyer-run-api-design.md` (correct two facts)

**Interfaces:**
- Consumes: everything above.
- Produces: SDK `gates.request(..., estimated_units=None)` / `estimatedUnits?`, regenerated clients, frontend types.

- [ ] **Step 1: Regenerate the runtime contract**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true`, then `git diff contracts/runtime-v1.yaml` and confirm it adds `estimatedUnits` on `CreateGateRequest`, the `/runtime/v1/network-events` path and `NetworkEventBatch`. Re-run without `-Dcontract.update=true`; expect PASS.

- [ ] **Step 2: Regenerate both SDK clients**

Run: `sdk/python/regenerate.sh` and `sdk/typescript/regenerate.sh`, then `scripts/check-sdk-drift.sh`. Expected: no output, exit 0.

- [ ] **Step 3: SDK `estimated_units` (test first)**

Python: add to `sdk/python/tests/test_gates_logs_heartbeat.py` a test that `ctx.gates.request(action, summary, payload, estimated_units=142)` sends `estimatedUnits: 142` in the JSON body (use the scripted fake `Api` the file already uses and assert on the recorded `create_gate` body), and that omitting it sends no `estimatedUnits` key. Then extend `Gates.request` in `sdk/python/agentstore/gates.py` with `estimated_units: float | None = None`, put `body["estimatedUnits"] = estimated_units` only when not None. TypeScript: the same two tests in `sdk/typescript/tests/gates-logs-heartbeat.test.ts`, and an `estimatedUnits?: number` option on `Gates.request` in `sdk/typescript/src/gates.ts`, sent only when defined. Run `cd sdk/python && . <venv>/bin/activate && pytest -q` and `cd sdk/typescript && npm run -s typecheck && npm test --silent`. Expected: PASS.

- [ ] **Step 4: Frontend types**

In `frontend/src/lib/types.ts` add `displayId: string; terminalReason: TerminalReason | null` to `RunResponse`, `RunSummaryResponse` and `RunDetailResponse`; a `TerminalReason` union of the nine values; `GateDetail` mirroring `GateDetailResponse` (with `costIfApproved`, `preview`, `decidedVia`); `RunListItem` and `RunListResponse`; `blockedAttempts: number` on the flow view type. Run `cd frontend && npx tsc --noEmit && npx vitest run`. Expected: PASS (fix any test fixture that now lacks `displayId`).

- [ ] **Step 5: CLI**

Run `grep -n -i "uuid\|isUuid\|[0-9a-f]\{8\}-" cli/src -r`. If any command rejects a non-UUID run reference, relax it and add a test in `cli/test/` that passes `R-12`; otherwise add a `cli/test/` test that `sandboxLogs(ctx, "S-4")` requests `/api/v1/sandbox/runs/S-4`. Run `cd cli && npx vitest run`. Expected: PASS.

- [ ] **Step 6: End-to-end test**

Add `Ws1bEndToEndTest` in `backend/src/test/java/com/agentstore/platform/sdk/` (reuse `AbstractSdkEndToEndTest` scaffolding): a scripted agent requests a gate with `estimatedUnits`; assert the buyer's `GET /api/v1/runs/{displayId}/gates` shows `costIfApproved` and a `preview` (stage rows first through `POST /runtime/v1/resources/.../records`), an off-list user is refused, an eligible approver approves, the run completes, `GET /api/v1/runs?group=finished` lists it with `terminalReason == FINISHED`, and `counts.finished` is 1. Run it: `mvn -q test -Dtest=Ws1bEndToEndTest`. Expected: PASS.

- [ ] **Step 7: Docs**

In `docs/known-issues.md` add: `eligible_approver_ids` is the buyer org's `BUYER_APPROVER` members, so a `BUYER_ADMIN` still cannot decide a gate (WS15 changes the source); gate preview is a snapshot at gate-open time; the in-sandbox proxy that posts `network-events` does not exist yet, so `blockedAttempts` stays 0 in real runs. In the WS1b spec, correct section 2 (`CANCELLED` is set by the sandbox cancel path, `SandboxRunService.cancel`) and section 1 (display IDs are assigned by a database trigger, not a `DisplayIds` component).

- [ ] **Step 8: Full verification and commit**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test` (full suite), `cd cli && npx vitest run`, `cd frontend && npx tsc --noEmit && npx vitest run`, `scripts/check-sdk-drift.sh`. Expected: all green. Then:

```bash
git add -A
git commit -m "feat(ws1b): contract, SDK estimated units, client types, end-to-end test and docs"
```

---

## Self-review notes

- **Spec coverage:** section 1 (IDs, lookup, DTOs) is Tasks 1 and 2; section 2 (stop reason) is Tasks 1 and 3; section 3 (gate extras) is Tasks 4, 5 and 6; section 4 (run list) is Task 7; section 5 (network events) is Task 8; section 6 (clients) and section 7 (testing, drift, end-to-end) are Task 9. Follow-ups in section 9 need no task.
- **Deviations from the spec, to be recorded in Task 9:** display IDs via a database trigger with `@Generated` rather than a `DisplayIds` component; `CANCELLED` has a real path (sandbox cancel); an org with no approver members snapshots as unrestricted; a `GET /api/v1/runs/{ref}/gates` endpoint is added as the concrete "buyer-facing gate view".
- **Type consistency:** `terminate` (both overloads) and `GateService.expire` take `TerminalReason` in Task 3 and are used with those signatures in Tasks 4 to 8; `GateService.open` gains the eight-argument form in Task 4 and Task 5 calls it; `FlowView` gains `blockedAttempts` only in Task 8, after Task 6 changed the flow service.
