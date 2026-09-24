# WS0 — Execution Defect Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing production run path correct and safe before any new developer-platform work builds on it. That means hashed run tokens compared in constant time, a single locked run-lifecycle service, cost-cap clamping, wallet reservations, runs pinned to a connection's version, a run reaper, and deny-all egress.

**Architecture:** All run state transitions move out of controllers into one `RunLifecycleService`. It takes a Postgres row lock on the run (`SELECT … FOR UPDATE`), so the agent callback, buyer approve/reject, trigger and the new `RunReaper` can't overwrite each other. Terminal transitions go through one `finish()` method that sets status, releases the wallet reservation and kills the sandbox exactly once. Wallet reservations live in a new `WalletReservationService` in the `ledger` module. The reaper is a plain Spring `@Scheduled` job guarded by ShedLock (Postgres-backed), so it runs on one instance at a time.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Maven, Postgres 16 + Flyway, Hibernate 6, Testcontainers, ShedLock 5.16.0 (`shedlock-spring`, `shedlock-provider-jdbc-template`), Next.js 14 + Vitest (frontend status labels only).

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md`. This plan implements **§5 WS0** (defects B1, B3, B4, B5, B9, B10 from §2.2). Read §2.2 and §5 WS0 before starting.

**Where this plan sits in the series:** the spec defines WS0–WS12, each with its own plan. The critical path is **WS0 → WS5 (sandbox-bridge) → WS1 (Runtime API) → WS2 (data plane) → WS6 (sandbox)**. WS7 (verification) can be planned and built in parallel at any time.

## Global Constraints

- Java/Spring Boot monolith stays the single source of truth (spec D6). No new services in this plan.
- Flyway migrations continue from `V12` (spec §3.2). This plan uses **V12–V16**. The spec's WS1 data model also lists `deadline_at`, `last_heartbeat_at` and the `callback_token → callback_token_hash` rename. **This plan delivers them**, so the WS1 plan must not re-add them and must renumber its migrations from V17.
- Background jobs use Spring `@Scheduled` + ShedLock (Postgres). No Kafka/Redis (spec §3.2).
- Run token: stored as SHA-256 hash, compared with `MessageDigest.isEqual` (spec B5). The migration invalidates in-flight tokens, which is acceptable pre-launch.
- `gateTimeout` defaults to **30 min** (spec WS0 B3). Configurable via `agentstore.runs.gate-timeout`.
- New `RunStatus` values: exactly `TIMED_OUT`, `EXPIRED`. New `RunStepKind` values in this plan: `TIMEOUT`, `COST_CAP_REACHED` (the rest arrive in WS1).
- Egress: `denyOut: ["0.0.0.0/0"]` alongside `allowOut` (E2B docs: allow rules take precedence over deny; domains are not allowed in `denyOut`).
- Money is `BigDecimal`, scale 2, `RoundingMode.HALF_UP` (existing convention).
- Integration tests extend `AbstractIntegrationTest` (Testcontainers Postgres, `test` profile, `FakeSandboxProvider`). Docker must be running.
- Commit messages: conventional-commit style (`fix(execution): …`). **Never add any Claude/Anthropic/AI attribution or `Co-Authored-By` trailer** (user's global instruction).
- **Out of scope** (owned by later workstreams): B2 agent process start (WS5 sandbox-bridge, which just gets a documented limitation here); B11 `/internal` → `/runtime/v1` + rate limiting (WS1); B6/B7 scope mediation and datasets (WS1/WS2); B8 double-entry ledger (WS10).

## Review Focus

Things the spec implies but doesn't spell out, and that are most likely to hurt a real user. Each has a test in the task named in brackets.

1. **A `COST` event with a negative, zero or non-numeric `quantity`** must be rejected with 400 and charge nothing. Today a negative quantity would credit the buyer's wallet. [Task 3]
2. **An agent that calls `COMPLETE` before the trigger has recorded its sandbox id** (a fast agent on real E2B) must leave the run `COMPLETED` and still have its sandbox killed, not flipped back to `RUNNING`. [Task 2]
3. **`gate-status` polled by an agent whose run was `EXPIRED`, `TIMED_OUT` or `FAILED`** must answer `REJECTED`. Today anything other than pending/cancelled answers `APPROVED`, so a timed-out agent would go ahead. [Task 2]
4. **Sandbox creation failing at trigger time** must mark the run `FAILED`, return 502 and release the wallet reservation, so the buyer's balance isn't locked forever. [Task 2, Task 4]
5. **A buyer clicking Approve just after the reaper expired the gate** must get 409, and the run must stay `EXPIRED`. [Task 6]

---

## File Structure

```
backend/
  pom.xml                                                     (modify: ShedLock deps, surefire tag exclusion)
  src/main/resources/application.yml                          (modify: jobs/runs config)
  src/main/resources/db/migration/
    V12__run_callback_token_hash.sql                          (create)
    V13__run_lifecycle_columns.sql                            (create)
    V14__wallet_reservation.sql                               (create)
    V15__connection_version_pin.sql                           (create)
    V16__shedlock.sql                                         (create)
  src/main/java/com/agentstore/platform/
    common/SchedulingConfig.java                              (create: @EnableScheduling + ShedLock, conditional)
    execution/
      RunTokenService.java            (modify: hash + constant-time matches)
      Run.java                        (modify: callbackTokenHash, deadlineAt, lastHeartbeatAt, awaitingSince, reservedAmount)
      RunStatus.java                  (modify: TIMED_OUT, EXPIRED, isTerminal())
      RunStepKind.java                (modify: TIMEOUT, COST_CAP_REACHED)
      RunRepository.java              (modify: findByIdForUpdate, findByStatusIn)
      RunLifecycleService.java        (create: every run state transition, under a row lock)
      RunController.java              (modify: delegates to lifecycle; reservation; pinned version)
      AgentCallbackController.java    (modify: thin, delegates to lifecycle)
      GateStatusResponse.java         (modify: terminal-but-not-approved → REJECTED)
      Connection.java                 (modify: agentVersion pin)
      ConnectionController.java       (modify: pin LIVE version at connect time)
      SandboxProvider.java            (modify: status())
      SandboxStatus.java              (create)
      FakeSandboxProvider.java        (modify: status, markExited, failNextCreate, specForRun)
      E2bSandboxProvider.java         (modify: denyOut, status(), B2 limitation doc)
      RunReaper.java                  (create: reap logic, testable with an explicit clock instant)
      RunReaperJob.java               (create: @Scheduled + @SchedulerLock wrapper)
    ledger/
      Wallet.java                     (modify: reserved)
      WalletResponse.java             (modify: reserved)
      WalletReservationService.java   (create)
      InsufficientFundsException.java (create)
  src/test/resources/application-test.yml                     (modify: jobs disabled)
  src/test/java/com/agentstore/platform/
    execution/ExecutionFixtures.java                          (create: shared test fixture @Component)
    execution/RunTokenServiceTest.java                        (create)
    execution/GateStatusResponseTest.java                     (create)
    execution/RunLifecycleServiceTest.java                    (create)
    execution/RunVersionPinningTest.java                      (create)
    execution/RunReaperTest.java                              (create)
    execution/E2bSandboxProviderLiveTest.java                 (create, @Tag("e2b"))
    common/SchedulingWiringTest.java                          (create)
    (modify) execution/AgentCallbackControllerTest.java, AgentCallbackCompletionTest.java, ApprovalGateTest.java,
             RunControllerTest.java, RunControllerWalletTest.java (rewritten), RunDetailControllerTest.java,
             RunSummaryControllerTest.java, E2bSandboxProviderTest.java, ledger/StatementControllerTest.java,
             EndToEndTierATest.java, EndToEndTierBTest.java
frontend/src/
  lib/types.ts, components/StatusPill.tsx, components/useRunPolling.ts, components/RunTraceStep.tsx,
  app/(buyer)/runs/[runId]/RunConsole.tsx  (+ their tests)
```

All backend commands run from `backend/`; frontend commands from `frontend/`.

---

### Task 1: Hash run tokens and compare in constant time (B5)

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__run_callback_token_hash.sql`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunTokenService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/Run.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunController.java:72-78` (token creation + env var)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java` (`authorize`)
- Modify: `backend/src/main/java/com/agentstore/platform/execution/FakeSandboxProvider.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunTokenServiceTest.java` (create)
- Modify tests: every `new Run(...)` call site and every `getCallbackToken()` use (listed in Step 6)

**Interfaces:**
- Produces: `RunTokenService.generate(): String` (raw token, unchanged), `static RunTokenService.hash(String raw): String` (64-char lowercase hex SHA-256), `static RunTokenService.matches(String raw, String storedHash): boolean`.
- Produces: `Run.getCallbackTokenHash()/setCallbackTokenHash(String)`. The `Run` constructor's last parameter is now the **hash**.
- Produces: `FakeSandboxProvider.specForRun(UUID runId): SandboxSpec` (the spec whose `AGENT_RUN_ID` env var matches; latest wins).

- [ ] **Step 1: Write the failing unit test**

`backend/src/test/java/com/agentstore/platform/execution/RunTokenServiceTest.java`:

```java
package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunTokenServiceTest {

    RunTokenService service = new RunTokenService();

    @Test
    void generatedTokensAreUniqueAndUrlSafe() {
        String a = service.generate();
        String b = service.generate();
        assertThat(a).isNotEqualTo(b).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void hashIsLowercaseHexSha256() {
        assertThat(RunTokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesAcceptsOnlyTheOriginalToken() {
        String raw = service.generate();
        String stored = RunTokenService.hash(raw);
        assertThat(RunTokenService.matches(raw, stored)).isTrue();
        assertThat(RunTokenService.matches(raw + "x", stored)).isFalse();
        assertThat(RunTokenService.matches(stored, stored)).as("presenting the hash itself must not work").isFalse();
    }

    @Test
    void matchesRejectsMissingValues() {
        assertThat(RunTokenService.matches(null, RunTokenService.hash("a"))).isFalse();
        assertThat(RunTokenService.matches("", RunTokenService.hash(""))).isFalse();
        assertThat(RunTokenService.matches("a", null)).isFalse();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn -q test -Dtest=RunTokenServiceTest`
Expected: compilation failure, `cannot find symbol: method hash(java.lang.String)`.

- [ ] **Step 3: Implement `RunTokenService`**

Replace the whole file:

```java
package com.agentstore.platform.execution;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RunTokenService {

    private final SecureRandom random = new SecureRandom();

    /** A fresh raw token. Hand it to the sandbox; persist only {@link #hash(String)}. */
    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of a presented raw token against a stored hash. */
    public static boolean matches(String rawToken, String storedHash) {
        if (rawToken == null || rawToken.isEmpty() || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(rawToken).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
```

- [ ] **Step 4: Run the unit test**

Run: `mvn -q test -Dtest=RunTokenServiceTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Migration, entity, controllers, fake provider**

`backend/src/main/resources/db/migration/V12__run_callback_token_hash.sql`:

```sql
ALTER TABLE run RENAME COLUMN callback_token TO callback_token_hash;
-- Existing values are plaintext tokens. Replace them with random 64-hex strings nobody knows the preimage of,
-- which invalidates every in-flight token (acceptable pre-launch, spec WS0 B5).
UPDATE run SET callback_token_hash = md5(random()::text) || md5(random()::text);
```

In `Run.java`, rename the field and constructor parameter `callbackToken` → `callbackTokenHash` (field, constructor parameter and the constructor assignment `this.callbackTokenHash = callbackTokenHash;`).

In `RunController.trigger`, replace:

```java
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.PENDING, connection.getSpendCap(),
                BigDecimal.ZERO, null, runTokenService.generate()));
```

with:

```java
        String rawToken = runTokenService.generate();
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.PENDING, connection.getSpendCap(),
                BigDecimal.ZERO, null, RunTokenService.hash(rawToken)));
```

and replace `envVars.put("AGENT_CALLBACK_TOKEN", run.getCallbackToken());` with `envVars.put("AGENT_CALLBACK_TOKEN", rawToken);`.

In `AgentCallbackController.authorize`, replace `if (!run.getCallbackToken().equals(token)) {` with:

```java
        if (!RunTokenService.matches(token, run.getCallbackTokenHash())) {
```

In `FakeSandboxProvider`, add (`java.util.UUID` is already imported):

```java
    /** The spec used for the given run's sandbox (latest if created more than once). Tests read the raw token from it. */
    public SandboxSpec specForRun(UUID runId) {
        return created.stream()
                .filter(spec -> runId.toString().equals(spec.envVars().get("AGENT_RUN_ID")))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("No sandbox created for run " + runId));
    }
```

- [ ] **Step 6: Update the existing tests for hashed tokens**

Wrap the last constructor argument of every test `new Run(...)` in `RunTokenService.hash(...)`, keeping the literal. The raw literal is still what the tests send as the bearer token:

| File | Change |
|---|---|
| `execution/AgentCallbackControllerTest.java:67` | `sandboxId, "tok-123")` → `sandboxId, RunTokenService.hash("tok-123"))` |
| `execution/AgentCallbackCompletionTest.java:69` | `"tok-complete")` → `RunTokenService.hash("tok-complete"))` |
| `execution/ApprovalGateTest.java:61` | `"tok-gate")` → `RunTokenService.hash("tok-gate"))` |
| `execution/RunDetailControllerTest.java:59` | `"tok-trace")` → `RunTokenService.hash("tok-trace"))` |
| `execution/RunSummaryControllerTest.java:54,59` | `"token-1")` / `"token-2")` → `RunTokenService.hash("token-1"))` / `RunTokenService.hash("token-2"))` |
| `ledger/StatementControllerTest.java:61` | `"tok")` → `RunTokenService.hash("tok"))` and add `import com.agentstore.platform.execution.RunTokenService;` |

`execution/RunControllerTest.java`: replace lines 78 and 84 (`saved.getCallbackToken()` assertions) with:

```java
        String rawToken = fakeSandboxProvider.specForRun(saved.getId()).envVars().get("AGENT_CALLBACK_TOKEN");
        assertThat(rawToken).isNotBlank();
        assertThat(saved.getCallbackTokenHash()).isEqualTo(RunTokenService.hash(rawToken)).isNotEqualTo(rawToken);
```

(keep the `templateId`, `allowOut` and `AGENT_RUN_ID` assertions on `lastSpec`).

`EndToEndTierATest.java:148`: replace `return runRepository.findById(runId).orElseThrow().getCallbackToken();` with `return fakeSandboxProvider.specForRun(runId).envVars().get("AGENT_CALLBACK_TOKEN");` and add the field `@Autowired FakeSandboxProvider fakeSandboxProvider;`.

`EndToEndTierBTest.java:101`: replace the line with `String callbackToken = fakeSandboxProvider.specForRun(run.id()).envVars().get("AGENT_CALLBACK_TOKEN");` and add `@Autowired FakeSandboxProvider fakeSandboxProvider;`.

- [ ] **Step 7: Run the affected suites**

Run: `mvn -q test -Dtest='RunTokenServiceTest,AgentCallback*Test,ApprovalGateTest,Run*ControllerTest,StatementControllerTest,EndToEnd*Test,PlatformApplicationTest'`
Expected: all PASS. (`PlatformApplicationTest` proves Hibernate `validate` accepts the renamed column.)

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "fix(execution): store run callback tokens hashed and compare in constant time"
```

---

### Task 2: Single locked run-lifecycle service, terminal statuses, deadlines

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__run_lifecycle_columns.sql`
- Modify: `execution/RunStatus.java`, `execution/RunStepKind.java`, `execution/Run.java`, `execution/RunRepository.java`, `execution/GateStatusResponse.java`, `execution/FakeSandboxProvider.java`
- Create: `execution/RunLifecycleService.java`
- Modify (rewrite): `execution/AgentCallbackController.java`, `execution/RunController.java`
- Test: create `execution/ExecutionFixtures.java`, `execution/RunLifecycleServiceTest.java`, `execution/GateStatusResponseTest.java`; add tests to `execution/RunControllerTest.java`

**Interfaces:**
- Consumes: `RunTokenService.hash`, `FakeSandboxProvider.specForRun` (Task 1).
- Produces:
  - `RunStatus.isTerminal(): boolean`. Terminal = `COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED`.
  - `RunStepKind.TIMEOUT`, `RunStepKind.COST_CAP_REACHED`.
  - `Run` fields `deadlineAt`, `lastHeartbeatAt`, `awaitingSince` (all `Instant`, nullable).
  - `RunRepository.findByIdForUpdate(UUID): Optional<Run>` (PESSIMISTIC_WRITE), `RunRepository.findByStatusIn(Collection<RunStatus>): List<Run>`.
  - `RunLifecycleService`:
    - `void markStarted(UUID runId, String sandboxId)`
    - `void recordEvent(UUID runId, RunStepKind kind, Map<String, Object> detail)`: agent-reported events; 400 for platform-only kinds, 409 for terminal runs
    - `Run approveGate(UUID runId)` / `Run rejectGate(UUID runId)`: 409 unless `AWAITING_APPROVAL`
    - `boolean terminate(UUID runId, RunStatus terminalStatus, RunStepKind stepKind /*nullable*/, Map<String, Object> detail)`: returns false if the run was already terminal
    - private `finish(Run, RunStatus)`: the **only** place a run becomes terminal (Tasks 3, 4 extend it)
  - `RunController.SANDBOX_TIMEOUT_SECONDS = 900` (package-private constant).
  - `FakeSandboxProvider.failNextCreate()`: the next `create` throws `IllegalStateException`.
  - Test fixture `ExecutionFixtures` (`@Component` in test sources, picked up by component scan): `Scenario liveTierA(BigDecimal spendCap, BigDecimal walletBalance)`, `Run run(Scenario, RunStatus, String sandboxId, String rawToken)`, `record Scenario(Org buyer, AgentVersion version, Connection connection)`.

- [ ] **Step 1: Write the test fixture**

`backend/src/test/java/com/agentstore/platform/execution/ExecutionFixtures.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Shared setup for execution tests: a LIVE Tier A agent, a buyer with a wallet, and an ACTIVE connection. */
@Component
public class ExecutionFixtures {

    private final OrgRepository orgRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final ConnectionRepository connectionRepository;
    private final WalletRepository walletRepository;
    private final RunRepository runRepository;

    public ExecutionFixtures(OrgRepository orgRepository, AgentRepository agentRepository,
                             AgentVersionRepository agentVersionRepository, ConnectionRepository connectionRepository,
                             WalletRepository walletRepository, RunRepository runRepository) {
        this.orgRepository = orgRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.connectionRepository = connectionRepository;
        this.walletRepository = walletRepository;
        this.runRepository = runRepository;
    }

    public record Scenario(Org buyer, AgentVersion version, Connection connection) {}

    public Scenario liveTierA(BigDecimal spendCap, BigDecimal walletBalance) {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", readManifest("valid-tier-a.json"),
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        version = agentVersionRepository.save(version);
        Connection connection = new Connection(null, buyer, agent, List.of("bank_statement"), spendCap,
                ConnectionStatus.ACTIVE);
        connection = connectionRepository.save(connection);
        Wallet wallet = new Wallet(null, buyer.getId(), walletBalance, null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return new Scenario(buyer, version, connection);
    }

    public Run run(Scenario scenario, RunStatus status, String sandboxId, String rawToken) {
        Run run = new Run(null, scenario.connection(), scenario.version(), status,
                scenario.connection().getSpendCap(), BigDecimal.ZERO, sandboxId, RunTokenService.hash(rawToken));
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    private static String readManifest(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/manifest/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/GateStatusResponseTest.java`:

```java
package com.agentstore.platform.execution;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class GateStatusResponseTest {

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"CANCELLED", "FAILED", "TIMED_OUT", "EXPIRED"})
    void runsStoppedWithoutApprovalReportRejected(RunStatus status) {
        assertThat(GateStatusResponse.from(status).decision()).isEqualTo("REJECTED");
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"RUNNING", "COMPLETED"})
    void resumedRunsReportApproved(RunStatus status) {
        assertThat(GateStatusResponse.from(status).decision()).isEqualTo("APPROVED");
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"AWAITING_APPROVAL"})
    void waitingRunsReportPending(RunStatus status) {
        assertThat(GateStatusResponse.from(status).decision()).isEqualTo("PENDING");
    }
}
```

`backend/src/test/java/com/agentstore/platform/execution/RunLifecycleServiceTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecycleServiceTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    String sandbox() {
        return "fake-sandbox-" + UUID.randomUUID();
    }

    @Test
    void markStartedMovesPendingRunToRunning() {
        Run run = fixtures.run(scenario(), RunStatus.PENDING, null, "tok-" + UUID.randomUUID());
        String sandboxId = sandbox();

        lifecycle.markStarted(run.getId(), sandboxId);

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(after.getSandboxId()).isEqualTo(sandboxId);
    }

    @Test
    void agentFinishingBeforeSandboxIsRecordedStillGetsItsSandboxKilled() {
        Run run = fixtures.run(scenario(), RunStatus.PENDING, null, "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.COMPLETE, Map.of());
        String sandboxId = sandbox();

        lifecycle.markStarted(run.getId(), sandboxId);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
    }

    @Test
    void terminateIsIdempotentAndRecordsOneStep() {
        String sandboxId = sandbox();
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandboxId, "tok-" + UUID.randomUUID());

        assertThat(lifecycle.terminate(run.getId(), RunStatus.TIMED_OUT, RunStepKind.TIMEOUT,
                Map.of("summary", "Run exceeded its time limit"))).isTrue();
        assertThat(lifecycle.terminate(run.getId(), RunStatus.FAILED, RunStepKind.FAILED, Map.of())).isFalse();

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(after.getCompletedAt()).isNotNull();
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting(RunStep::getKind).containsExactly(RunStepKind.TIMEOUT);
    }

    @Test
    void eventsForTerminalRunsAreRejected() {
        Run run = fixtures.run(scenario(), RunStatus.COMPLETED, sandbox(), "tok-" + UUID.randomUUID());

        assertThatThrownBy(() -> lifecycle.recordEvent(run.getId(), RunStepKind.READ, Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void agentCannotReportPlatformOnlyStepKinds() {
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandbox(), "tok-" + UUID.randomUUID());

        assertThatThrownBy(() -> lifecycle.recordEvent(run.getId(), RunStepKind.TIMEOUT, Map.of()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void gateEventRecordsWhenTheApprovalWaitStarted() {
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandbox(), "tok-" + UUID.randomUUID());

        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of("trigger", "X"));

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(after.getAwaitingSince()).isNotNull();
        assertThat(after.getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void approvingClearsTheWaitAndRejectingKills() {
        String sandboxId = sandbox();
        Run run = fixtures.run(scenario(), RunStatus.RUNNING, sandboxId, "tok-" + UUID.randomUUID());
        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of());

        assertThat(lifecycle.approveGate(run.getId()).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(runRepository.findById(run.getId()).orElseThrow().getAwaitingSince()).isNull();

        lifecycle.recordEvent(run.getId(), RunStepKind.GATE, Map.of());
        assertThat(lifecycle.rejectGate(run.getId()).getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
    }
}
```

Append to `RunControllerTest.java` (add imports `com.agentstore.platform.execution.ExecutionFixtures` is same package; add `java.time.Duration`, and autowire `ExecutionFixtures fixtures;`):

```java
    @Autowired
    ExecutionFixtures fixtures;

    HttpHeaders buyerAdmin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void triggeredRunGetsADeadlineMatchingTheSandboxTimeout() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));

        var response = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
                RunResponse.class);

        Run saved = runRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(Duration.between(saved.getStartedAt(), saved.getDeadlineAt()))
                .isEqualTo(Duration.ofSeconds(RunController.SANDBOX_TIMEOUT_SECONDS));
    }

    @Test
    void sandboxCreationFailureFailsTheRunAndReturnsBadGateway() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        fakeSandboxProvider.failNextCreate();

        var response = rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        var runs = runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId());
        assertThat(runs).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(r.getCompletedAt()).isNotNull();
        });
    }
```

- [ ] **Step 3: Run them and confirm they fail**

Run: `mvn -q test -Dtest='GateStatusResponseTest,RunLifecycleServiceTest,RunControllerTest'`
Expected: compilation failure (`RunLifecycleService`, `TIMED_OUT`, `getDeadlineAt`, `failNextCreate` not found).

- [ ] **Step 4: Schema, enums, entity, repository**

`backend/src/main/resources/db/migration/V13__run_lifecycle_columns.sql`:

```sql
ALTER TABLE run ADD COLUMN deadline_at TIMESTAMPTZ;
ALTER TABLE run ADD COLUMN last_heartbeat_at TIMESTAMPTZ;
ALTER TABLE run ADD COLUMN awaiting_since TIMESTAMPTZ;
CREATE INDEX run_active_status_idx ON run (status) WHERE status IN ('PENDING', 'RUNNING', 'AWAITING_APPROVAL');
```

`RunStatus.java`:

```java
package com.agentstore.platform.execution;

public enum RunStatus {
    PENDING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED -> true;
            case PENDING, RUNNING, AWAITING_APPROVAL -> false;
        };
    }
}
```

`RunStepKind.java`:

```java
package com.agentstore.platform.execution;

/** READ..FAILED are reported by the agent; TIMEOUT and COST_CAP_REACHED are recorded by the platform only. */
public enum RunStepKind { READ, WRITE, GATE, COST, COMPLETE, FAILED, TIMEOUT, COST_CAP_REACHED }
```

In `Run.java` add after `completedAt`:

```java
    private Instant deadlineAt;
    private Instant lastHeartbeatAt;
    private Instant awaitingSince;
```

`RunRepository.java`:

```java
package com.agentstore.platform.execution;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, UUID> {
    List<Run> findByConnectionBuyerOrgIdOrderByStartedAtDesc(UUID buyerOrgId);

    List<Run> findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(UUID buyerOrgId, RunStatus status);

    List<Run> findByStatusIn(Collection<RunStatus> statuses);

    /** Row-locks the run for the rest of the caller's transaction. Every state transition goes through this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id")
    Optional<Run> findByIdForUpdate(@Param("id") UUID id);
}
```

`GateStatusResponse.java`:

```java
package com.agentstore.platform.execution;

public record GateStatusResponse(String decision) {
    static GateStatusResponse from(RunStatus status) {
        return switch (status) {
            case AWAITING_APPROVAL -> new GateStatusResponse("PENDING");
            case RUNNING, COMPLETED -> new GateStatusResponse("APPROVED");
            // Cancelled, failed, timed out or expired: the agent must not proceed.
            default -> new GateStatusResponse("REJECTED");
        };
    }
}
```

In `FakeSandboxProvider.java` add a field and method, and make `create` honour it (import `java.util.concurrent.atomic.AtomicBoolean`):

```java
    private final AtomicBoolean failNextCreate = new AtomicBoolean(false);

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        if (failNextCreate.getAndSet(false)) {
            throw new IllegalStateException("Simulated sandbox provider failure");
        }
        created.add(spec);
        return new SandboxHandle("fake-sandbox-" + UUID.randomUUID());
    }

    public void failNextCreate() {
        failNextCreate.set(true);
    }
```

- [ ] **Step 5: Create `RunLifecycleService`**

`backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.UsageEvent;
import com.agentstore.platform.ledger.UsageEventRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every run state transition, each under a row lock on the run, so callbacks, buyer decisions, trigger and the
 * reaper cannot overwrite each other. {@link #finish} is the only way a run becomes terminal.
 */
@Service
public class RunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);
    private static final Set<RunStepKind> AGENT_REPORTABLE = EnumSet.of(
            RunStepKind.READ, RunStepKind.WRITE, RunStepKind.GATE, RunStepKind.COST,
            RunStepKind.COMPLETE, RunStepKind.FAILED);

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final UsageEventRepository usageEventRepository;
    private final LedgerService ledgerService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunLifecycleService(RunRepository runRepository, RunStepRepository runStepRepository,
                               UsageEventRepository usageEventRepository, LedgerService ledgerService,
                               ManifestParser manifestParser, SandboxProvider sandboxProvider) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.usageEventRepository = usageEventRepository;
        this.ledgerService = ledgerService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
    }

    @Transactional
    public void markStarted(UUID runId, String sandboxId) {
        Run run = lock(runId);
        run.setSandboxId(sandboxId);
        if (run.getStatus().isTerminal()) {
            // The agent finished (or the run was stopped) before we recorded its sandbox; don't leave it running.
            killQuietly(sandboxId);
        } else if (run.getStatus() == RunStatus.PENDING) {
            run.setStatus(RunStatus.RUNNING);
        }
        runRepository.save(run);
    }

    @Transactional
    public void recordEvent(UUID runId, RunStepKind kind, Map<String, Object> detail) {
        if (!AGENT_REPORTABLE.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Step kind is platform-only: " + kind);
        }
        Map<String, Object> safeDetail = detail == null ? Map.of() : detail;
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
        }
        run.setLastHeartbeatAt(Instant.now());

        switch (kind) {
            case GATE -> {
                step(run, kind, safeDetail);
                run.setStatus(RunStatus.AWAITING_APPROVAL);
                run.setAwaitingSince(Instant.now());
                runRepository.save(run);
            }
            case COST -> recordCost(run, safeDetail);
            case COMPLETE -> {
                step(run, kind, safeDetail);
                finish(run, RunStatus.COMPLETED);
            }
            case FAILED -> {
                step(run, kind, safeDetail);
                finish(run, RunStatus.FAILED);
            }
            default -> {
                step(run, kind, safeDetail);
                runRepository.save(run);
            }
        }
    }

    @Transactional
    public Run approveGate(UUID runId) {
        Run run = lock(runId);
        requireAwaitingApproval(run);
        run.setStatus(RunStatus.RUNNING);
        run.setAwaitingSince(null);
        return runRepository.save(run);
    }

    @Transactional
    public Run rejectGate(UUID runId) {
        Run run = lock(runId);
        requireAwaitingApproval(run);
        finish(run, RunStatus.CANCELLED);
        return run;
    }

    /** Moves a run to a terminal status. Returns false (and changes nothing) if it was already terminal. */
    @Transactional
    public boolean terminate(UUID runId, RunStatus terminalStatus, RunStepKind stepKind, Map<String, Object> detail) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal status: " + terminalStatus);
        }
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            return false;
        }
        if (stepKind != null) {
            step(run, stepKind, detail == null ? Map.of() : detail);
        }
        finish(run, terminalStatus);
        return true;
    }

    private void recordCost(Run run, Map<String, Object> detail) {
        step(run, RunStepKind.COST, detail);
        var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        BigDecimal quantity = new BigDecimal(String.valueOf(detail.getOrDefault("quantity", "0")));
        BigDecimal unitCost = manifest.costEnvelope().unitPrice();
        BigDecimal amount = quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

        UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(),
                run.getConnection().getBuyerOrg().getId(), quantity, unitCost, amount));
        ledgerService.chargeUsageEvent(usageEvent.getId());

        run.setTotalCharged(run.getTotalCharged().add(amount));
        if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
            finish(run, RunStatus.FAILED);
        } else {
            runRepository.save(run);
        }
    }

    private void finish(Run run, RunStatus status) {
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        run.setAwaitingSince(null);
        runRepository.save(run);
        if (run.getSandboxId() != null) {
            killQuietly(run.getSandboxId());
        }
    }

    private void killQuietly(String sandboxId) {
        try {
            sandboxProvider.kill(sandboxId);
        } catch (RuntimeException e) {
            // The provider's own timeout reclaims it; the run's terminal state must not roll back over this.
            log.warn("Failed to kill sandbox {}", sandboxId, e);
        }
    }

    private void requireAwaitingApproval(Run run) {
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is not awaiting approval: " + run.getStatus());
        }
    }

    private Run lock(UUID runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        try {
            runStepRepository.save(new RunStep(null, run, kind, objectMapper.writeValueAsString(detail)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
```

- [ ] **Step 6: Make the controllers thin**

Replace `AgentCallbackController.java`:

```java
package com.agentstore.platform.execution;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/internal/agent-callback/runs/{runId}")
public class AgentCallbackController {

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;

    public AgentCallbackController(RunRepository runRepository, RunLifecycleService runLifecycleService) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@PathVariable UUID runId,
                                       @RequestHeader("Authorization") String authorization,
                                       @Valid @RequestBody AgentEventRequest request) {
        authorize(runId, authorization);
        runLifecycleService.recordEvent(runId, request.kind(), request.detail());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/gate-status")
    public GateStatusResponse gateStatus(@PathVariable UUID runId, @RequestHeader("Authorization") String authorization) {
        return GateStatusResponse.from(authorize(runId, authorization).getStatus());
    }

    private Run authorize(UUID runId, String authorizationHeader) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7) : "";
        if (!RunTokenService.matches(token, run.getCallbackTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback token");
        }
        return run;
    }
}
```

Replace `RunController.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.ledger.WalletRepository;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    static final int SANDBOX_TIMEOUT_SECONDS = 900;

    private final ConnectionRepository connectionRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final String callbackBaseUrl;
    private final WalletRepository walletRepository;
    private final RunLifecycleService runLifecycleService;

    public RunController(ConnectionRepository connectionRepository, AgentVersionRepository agentVersionRepository,
                         RunRepository runRepository, SecretRepository secretRepository, SecretService secretService,
                         ManifestParser manifestParser, SandboxProvider sandboxProvider, RunTokenService runTokenService,
                         @Value("${agentstore.callback.base-url}") String callbackBaseUrl,
                         WalletRepository walletRepository, RunLifecycleService runLifecycleService) {
        this.connectionRepository = connectionRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.callbackBaseUrl = callbackBaseUrl;
        this.walletRepository = walletRepository;
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        var wallet = walletRepository.findByOrgId(connection.getBuyerOrg().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "No wallet for org"));
        if (wallet.getBalance().compareTo(connection.getSpendCap()) < 0) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Insufficient wallet balance for spend cap");
        }
        AgentVersion version = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(connection.getAgent().getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));
        var manifest = manifestParser.parse(version.getManifestJson());

        String rawToken = runTokenService.generate();
        Instant now = Instant.now();
        Run pending = new Run(null, connection, version, RunStatus.PENDING, connection.getSpendCap(),
                BigDecimal.ZERO, null, RunTokenService.hash(rawToken));
        pending.setStartedAt(now);
        pending.setDeadlineAt(now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        Run run = runRepository.save(pending);

        return launch(run, connection, version, manifest.networkAllowlist(), rawToken);
    }

    private RunResponse launch(Run run, Connection connection, AgentVersion version, List<String> manifestAllowlist,
                               String rawToken) {
        Map<String, String> envVars = new HashMap<>();
        envVars.put("AGENT_RUN_ID", run.getId().toString());
        envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + run.getId());
        envVars.put("AGENT_CALLBACK_TOKEN", rawToken);
        for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
            envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
        }

        List<String> allowOut = new ArrayList<>();
        allowOut.add(URI.create(callbackBaseUrl).getHost());
        allowOut.addAll(manifestAllowlist);

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
        return RunResponse.from(runRepository.findById(run.getId()).orElseThrow());
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse approve(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(runLifecycleService.approveGate(runId));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse reject(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(runLifecycleService.rejectGate(runId));
    }

    private void requireOwnedByCaller(UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!run.getConnection().getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
    }
}
```

- [ ] **Step 7: Run the new and existing execution tests**

Run: `mvn -q test -Dtest='GateStatusResponseTest,RunLifecycleServiceTest,RunControllerTest,AgentCallback*Test,ApprovalGateTest,RunDetailControllerTest,EndToEnd*Test,PlatformApplicationTest'`
Expected: all PASS. `ApprovalGateTest.rejectingCancelsAndKillsTheRun` still sees `REJECTED` from gate-status.

- [ ] **Step 8: Commit**

```bash
git add backend/src
git commit -m "fix(execution): route all run transitions through a locked lifecycle service with deadlines"
```

---

### Task 3: Clamp charges to the cost cap and validate quantities (B4)

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java` (`recordCost`)
- Test: `backend/src/test/java/com/agentstore/platform/execution/AgentCallbackControllerTest.java`

**Interfaces:**
- Consumes: `RunLifecycleService.finish`, `step`, `RunStepKind.COST_CAP_REACHED` (Task 2).
- Produces: behaviour. `total_charged` never exceeds `cost_cap`; a request that would exceed it is charged `remaining`, records a `COST_CAP_REACHED` step `{summary, requested, charged, costCap}` and ends the run `FAILED`. A non-positive or non-numeric `quantity` → 400 with nothing recorded.

- [ ] **Step 1: Write the failing tests**

In `AgentCallbackControllerTest.java`, add `@Autowired UsageEventRepository usageEventRepository;` (import `com.agentstore.platform.ledger.UsageEventRepository`), plus imports `org.junit.jupiter.params.ParameterizedTest` and `org.junit.jupiter.params.provider.ValueSource`. **Replace** `costEventExceedingCapKillsSandboxAndFailsRun` with these tests:

```java
    @Test
    void costEventCrossingTheCapChargesOnlyUpToTheCapThenStopsTheRun() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"), "fake-sandbox-over-cap");

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);   // 6.00
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);   // 6.00 requested, 4.00 left

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-over-cap")).isTrue();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.COST, RunStepKind.COST, RunStepKind.COST_CAP_REACHED);
    }

    @Test
    void singleOversizedCostEventNeverChargesBeyondTheCap() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"), "fake-sandbox-oversized");
        var buyerOrgId = run.getConnection().getBuyerOrg().getId();

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 50)), Void.class);  // 100.00 requested

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(walletRepository.findByOrgId(buyerOrgId).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("990.00"));
        assertThat(usageEventRepository.findByRunId(run.getId())).singleElement()
                .satisfies(e -> assertThat(e.getAmount()).isEqualByComparingTo(new BigDecimal("10.00")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-5", "0", "abc"})
    void nonPositiveOrNonNumericQuantityIsRejectedWithoutCharging(String quantity) throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"), "fake-sandbox-bad-qty-" + quantity);

        var response = rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", quantity)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(usageEventRepository.findByRunId(run.getId())).isEmpty();
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=AgentCallbackControllerTest`
Expected: FAIL. The crossing test sees `12.00` and no `COST_CAP_REACHED`; the oversized test sees `100.00`; `-5` returns 200.

- [ ] **Step 3: Replace `recordCost` and add `parseQuantity`**

In `RunLifecycleService.java`:

```java
    private void recordCost(Run run, Map<String, Object> detail) {
        BigDecimal quantity = parseQuantity(detail.get("quantity"));
        var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        BigDecimal unitCost = manifest.costEnvelope().unitPrice();
        BigDecimal requested = quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = run.getCostCap().subtract(run.getTotalCharged()).max(BigDecimal.ZERO);
        BigDecimal charged = requested.min(remaining);

        step(run, RunStepKind.COST, detail);
        if (charged.signum() > 0) {
            UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(),
                    run.getConnection().getBuyerOrg().getId(), quantity, unitCost, charged));
            ledgerService.chargeUsageEvent(usageEvent.getId());
            run.setTotalCharged(run.getTotalCharged().add(charged));
        }

        if (requested.compareTo(remaining) > 0) {
            step(run, RunStepKind.COST_CAP_REACHED, Map.of(
                    "summary", "Spend limit reached; run stopped",
                    "requested", requested, "charged", charged, "costCap", run.getCostCap()));
            finish(run, RunStatus.FAILED);
        } else {
            runRepository.save(run);
        }
    }

    private BigDecimal parseQuantity(Object raw) {
        try {
            BigDecimal quantity = new BigDecimal(String.valueOf(raw));
            if (quantity.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
            }
            return quantity;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be a number");
        }
    }
```

(When clamped, the usage event keeps the agent's reported `quantity` with the clamped `amount`. The statement bills `amount`.)

- [ ] **Step 4: Run the callback suites**

Run: `mvn -q test -Dtest='AgentCallback*Test,EndToEnd*Test,RunLifecycleServiceTest'`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "fix(execution): clamp usage charges to the run cost cap and reject non-positive quantities"
```

---

### Task 4: Wallet reservations (B9)

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__wallet_reservation.sql`
- Create: `backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java`, `ledger/InsufficientFundsException.java`
- Modify: `ledger/Wallet.java`, `ledger/WalletResponse.java`, `execution/Run.java`, `execution/RunLifecycleService.java`, `execution/RunController.java`
- Test: rewrite `backend/src/test/java/com/agentstore/platform/execution/RunControllerWalletTest.java`

**Interfaces:**
- Consumes: `RunLifecycleService.finish/recordCost` (Tasks 2–3), `ExecutionFixtures`, `FakeSandboxProvider.failNextCreate/specForRun`.
- Produces:
  - `Wallet.getReserved()/setReserved(BigDecimal)` (never negative, DB check). `WalletResponse(balance, cap, reserved)`.
  - `Run.getReservedAmount()/setReservedAmount(BigDecimal)`: the part of this run's reservation not yet consumed.
  - `WalletReservationService.reserve(UUID orgId, BigDecimal amount)` throws `InsufficientFundsException` when `balance − reserved < amount` or no wallet. `release(UUID orgId, BigDecimal amount)` is a no-op for amount ≤ 0 and floors at zero.
  - Invariant: `wallet.reserved = Σ run.reserved_amount` over the org's non-terminal runs. Charges consume reservation; `finish` releases the rest.

- [ ] **Step 1: Write the failing tests**

Replace `RunControllerWalletTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.Wallet;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class RunControllerWalletTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders buyerAdmin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    <T> ResponseEntity<T> trigger(ExecutionFixtures.Scenario s, Class<T> type) {
        return rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())), type);
    }

    Wallet wallet(ExecutionFixtures.Scenario s) {
        return walletRepository.findByOrgId(s.buyer().getId()).orElseThrow();
    }

    void agentEvent(UUID runId, String kind, Map<String, Object> detail) {
        String token = fakeSandboxProvider.specForRun(runId).envVars().get("AGENT_CALLBACK_TOKEN");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity(url("/internal/agent-callback/runs/" + runId + "/events"),
                new HttpEntity<>(Map.of("kind", kind, "detail", detail), headers), Void.class);
    }

    @Test
    void triggeringWithInsufficientWalletBalanceIsRejected() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("10.00"));

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void triggerReservesTheSpendCap() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));

        var run = trigger(s, RunResponse.class).getBody();

        assertThat(wallet(s).getReserved()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(runRepository.findById(run.id()).orElseThrow().getReservedAmount())
                .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void concurrentTriggersCannotReserveMoreThanTheWalletHolds() throws Exception {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("300.00"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<HttpStatusCode> call = () -> {
                start.await();
                return trigger(s, String.class).getStatusCode();
            };
            Future<HttpStatusCode> a = pool.submit(call);
            Future<HttpStatusCode> b = pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.PAYMENT_REQUIRED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void chargesConsumeTheReservationAndCompletionReleasesTheRest() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        UUID runId = trigger(s, RunResponse.class).getBody().id();

        agentEvent(runId, "COST", Map.of("quantity", 5));   // 5 × 2.00 = 10.00
        assertThat(wallet(s).getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(new BigDecimal("190.00"));

        agentEvent(runId, "COMPLETE", Map.of());
        assertThat(wallet(s).getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(runRepository.findById(runId).orElseThrow().getReservedAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sandboxCreationFailureReleasesTheReservation() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        fakeSandboxProvider.failNextCreate();

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=RunControllerWalletTest`
Expected: compilation failure (`getReserved`, `getReservedAmount` not found).

- [ ] **Step 3: Schema and entities**

`backend/src/main/resources/db/migration/V14__wallet_reservation.sql`:

```sql
ALTER TABLE wallet ADD COLUMN reserved NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE wallet ADD CONSTRAINT wallet_reserved_non_negative CHECK (reserved >= 0);
ALTER TABLE run ADD COLUMN reserved_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
```

`Wallet.java`: add field `private BigDecimal reserved = BigDecimal.ZERO;` after `cap`.
`Run.java`: add field `private BigDecimal reservedAmount = BigDecimal.ZERO;` after `totalCharged`.
`WalletResponse.java`:

```java
package com.agentstore.platform.ledger;

import java.math.BigDecimal;

public record WalletResponse(BigDecimal balance, BigDecimal cap, BigDecimal reserved) {
    static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getBalance(), wallet.getCap(), wallet.getReserved());
    }
}
```

- [ ] **Step 4: Reservation service**

`backend/src/main/java/com/agentstore/platform/ledger/InsufficientFundsException.java`:

```java
package com.agentstore.platform.ledger;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java`:

```java
package com.agentstore.platform.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Holds a run's spend cap against the wallet so concurrent runs cannot overspend (spec WS0 B9). */
@Service
public class WalletReservationService {

    private final WalletRepository walletRepository;

    public WalletReservationService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public void reserve(UUID orgId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByOrgId(orgId)   // PESSIMISTIC_WRITE row lock
                .orElseThrow(() -> new InsufficientFundsException("No wallet for org"));
        BigDecimal available = wallet.getBalance().subtract(wallet.getReserved());
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient wallet balance for spend cap");
        }
        wallet.setReserved(wallet.getReserved().add(amount));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }

    @Transactional
    public void release(UUID orgId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        Wallet wallet = walletRepository.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalStateException("No wallet for org " + orgId));
        wallet.setReserved(wallet.getReserved().subtract(amount).max(BigDecimal.ZERO));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }
}
```

- [ ] **Step 5: Wire it into the lifecycle and the trigger**

`RunLifecycleService.java`: add the constructor dependency `WalletReservationService walletReservationService` (field + assignment; import `com.agentstore.platform.ledger.WalletReservationService`). Replace the `if (charged.signum() > 0) { … }` block in `recordCost` with:

```java
        if (charged.signum() > 0) {
            UUID buyerOrgId = run.getConnection().getBuyerOrg().getId();
            UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(), buyerOrgId,
                    quantity, unitCost, charged));
            ledgerService.chargeUsageEvent(usageEvent.getId());
            run.setTotalCharged(run.getTotalCharged().add(charged));
            BigDecimal consumed = charged.min(run.getReservedAmount());
            walletReservationService.release(buyerOrgId, consumed);
            run.setReservedAmount(run.getReservedAmount().subtract(consumed));
        }
```

Replace `finish` with:

```java
    private void finish(Run run, RunStatus status) {
        if (run.getReservedAmount().signum() > 0) {
            walletReservationService.release(run.getConnection().getBuyerOrg().getId(), run.getReservedAmount());
            run.setReservedAmount(BigDecimal.ZERO);
        }
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        run.setAwaitingSince(null);
        runRepository.save(run);
        if (run.getSandboxId() != null) {
            killQuietly(run.getSandboxId());
        }
    }
```

Lock order is always run row → wallet row. `reserve` runs in its own transaction before the run row exists, so no deadlock cycle is possible.

`RunController.java`: swap the `WalletRepository walletRepository` dependency for `WalletReservationService walletReservationService` (constructor, field, imports `com.agentstore.platform.ledger.InsufficientFundsException` and `com.agentstore.platform.ledger.WalletReservationService`; drop the `WalletRepository` import). Replace the body of `trigger` with:

```java
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        AgentVersion version = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(connection.getAgent().getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));
        var manifest = manifestParser.parse(version.getManifestJson());

        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();
        try {
            walletReservationService.reserve(buyerOrgId, spendCap);
        } catch (InsufficientFundsException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        }

        String rawToken = runTokenService.generate();
        Instant now = Instant.now();
        Run pending = new Run(null, connection, version, RunStatus.PENDING, spendCap, BigDecimal.ZERO, null,
                RunTokenService.hash(rawToken));
        pending.setReservedAmount(spendCap);
        pending.setStartedAt(now);
        pending.setDeadlineAt(now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        Run run;
        try {
            run = runRepository.save(pending);
        } catch (RuntimeException e) {
            walletReservationService.release(buyerOrgId, spendCap);
            throw e;
        }

        return launch(run, connection, version, manifest.networkAllowlist(), rawToken);
```

(`launch` is unchanged. On sandbox failure it calls `terminate`, whose `finish` releases the reservation.)

- [ ] **Step 6: Run the money-related suites**

Run: `mvn -q test -Dtest='RunControllerWalletTest,RunControllerTest,AgentCallback*Test,ledger.*Test,EndToEnd*Test,RunLifecycleServiceTest'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "fix(ledger): reserve the spend cap on trigger so concurrent runs cannot overspend a wallet"
```

---

### Task 5: Pin connections to the version the buyer consented to (B10)

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__connection_version_pin.sql`
- Modify: `execution/Connection.java`, `execution/ConnectionController.java`, `execution/RunController.java`
- Modify tests: `execution/ExecutionFixtures.java`, `execution/RunControllerTest.java` (existing test's connection)
- Test: create `execution/RunVersionPinningTest.java`

**Interfaces:**
- Consumes: `ExecutionFixtures`, `FakeSandboxProvider.specForRun`.
- Produces: `Connection.getAgentVersion()/setAgentVersion(AgentVersion)` (nullable only for legacy rows). The trigger uses the pinned version, and 409 if it's missing or no longer `LIVE`. `ConnectionController.create` pins the current LIVE version. `RunController` no longer depends on `AgentVersionRepository`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/RunVersionPinningTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunVersionPinningTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders buyerAdmin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    <T> ResponseEntity<T> trigger(ExecutionFixtures.Scenario s, Class<T> type) {
        return rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())), type);
    }

    @Test
    void creatingAConnectionPinsTheCurrentLiveVersion() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));

        var created = rest.postForEntity(url("/api/v1/connections"),
                new HttpEntity<>(new CreateConnectionRequest(s.version().getAgent().getId(), List.of("bank_statement"),
                        new BigDecimal("200.00")), buyerAdmin(s.buyer().getId())),
                ConnectionResponse.class).getBody();

        assertThat(connectionRepository.findById(created.id()).orElseThrow().getAgentVersion().getId())
                .isEqualTo(s.version().getId());
    }

    @Test
    void runsUseThePinnedVersionEvenWhenANewerOneIsLive() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        AgentVersion v2 = new AgentVersion(null, s.version().getAgent(), "2.0.0", s.version().getManifestJson(),
                "template-v2", VersionStatus.LIVE, Tier.TIER_A, "adds things");
        v2.setApprovedAt(Instant.now().plusSeconds(60));
        agentVersionRepository.save(v2);

        UUID runId = trigger(s, RunResponse.class).getBody().id();

        assertThat(runRepository.findById(runId).orElseThrow().getAgentVersion().getId()).isEqualTo(s.version().getId());
        assertThat(fakeSandboxProvider.specForRun(runId).templateId()).isEqualTo("template-v1");
    }

    @Test
    void connectionWithoutAPinnedVersionCannotRun() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        Connection connection = s.connection();
        connection.setAgentVersion(null);
        connectionRepository.save(connection);

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void pinnedVersionThatIsNoLongerLiveCannotRun() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        AgentVersion pinned = s.version();
        pinned.setStatus(VersionStatus.REJECTED);
        agentVersionRepository.save(pinned);

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

(Only the status is asserted, because `server.error.include-message` is not enabled, so error bodies don't carry the reason.)

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=RunVersionPinningTest`
Expected: compilation failure (`setAgentVersion` not found).

- [ ] **Step 3: Schema, entity, connect-time pinning**

`backend/src/main/resources/db/migration/V15__connection_version_pin.sql`:

```sql
ALTER TABLE connection ADD COLUMN agent_version_id UUID REFERENCES agent_version(id);
-- Pin existing connections to the version they were effectively running: the agent's latest LIVE version.
UPDATE connection c SET agent_version_id = (
    SELECT v.id FROM agent_version v
    WHERE v.agent_id = c.agent_id AND v.status = 'LIVE'
    ORDER BY v.approved_at DESC NULLS LAST
    LIMIT 1);
```

`Connection.java`: add (import `com.agentstore.platform.catalog.AgentVersion`):

```java
    /** The version the buyer consented to. Runs always use this; upgrading is an explicit buyer action (WS11). */
    @ManyToOne
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;
```

`ConnectionController.create`: replace the final save with:

```java
        Connection connection = new Connection(null, buyerOrg, agent, request.grantedScopes(), request.spendCap(),
                ConnectionStatus.ACTIVE);
        connection.setAgentVersion(liveVersion);
        return ConnectionResponse.from(connectionRepository.save(connection));
```

- [ ] **Step 4: Trigger uses the pin**

In `RunController.trigger`, replace the `AgentVersion version = agentVersionRepository…orElseThrow(…);` statement with:

```java
        AgentVersion version = connection.getAgentVersion();
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Connection is not pinned to an agent version; reconnect to the agent");
        }
        if (version.getStatus() != VersionStatus.LIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned agent version is no longer LIVE");
        }
```

Remove the `AgentVersionRepository` constructor parameter, field and import from `RunController`.

- [ ] **Step 5: Pin in test fixtures that trigger runs**

`ExecutionFixtures.liveTierA`: between `new Connection(...)` and `connectionRepository.save(connection)` add `connection.setAgentVersion(version);`.

`RunControllerTest.triggeringARunCreatesASandboxWithManifestAllowlist`: replace

```java
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("invoice", "whatsapp_message"), new BigDecimal("200.00"), ConnectionStatus.ACTIVE));
```

with

```java
        Connection connection = new Connection(null, buyer, agent,
                List.of("invoice", "whatsapp_message"), new BigDecimal("200.00"), ConnectionStatus.ACTIVE);
        connection.setAgentVersion(version);
        connection = connectionRepository.save(connection);
```

- [ ] **Step 6: Run the execution suites**

Run: `mvn -q test -Dtest='RunVersionPinningTest,RunController*Test,Connection*Test,EndToEnd*Test,PlatformApplicationTest'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "fix(execution): pin connections to the consented agent version and run only that version"
```

---

### Task 6: Run reaper with ShedLock (B3)

**Files:**
- Modify: `backend/pom.xml` (ShedLock deps)
- Create: `backend/src/main/resources/db/migration/V16__shedlock.sql`
- Modify: `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml`
- Create: `execution/SandboxStatus.java`, `execution/RunReaper.java`, `execution/RunReaperJob.java`, `common/SchedulingConfig.java`
- Modify: `execution/SandboxProvider.java`, `execution/FakeSandboxProvider.java`, `execution/E2bSandboxProvider.java`
- Test: create `execution/RunReaperTest.java`, `common/SchedulingWiringTest.java`; add to `execution/E2bSandboxProviderTest.java`

**Interfaces:**
- Consumes: `RunLifecycleService.terminate`, `RunRepository.findByStatusIn`, `Run.deadlineAt/awaitingSince` (Task 2).
- Produces:
  - `enum SandboxStatus { RUNNING, EXITED, UNKNOWN }`. `SandboxProvider.status(String sandboxId): SandboxStatus`.
  - `FakeSandboxProvider.markExited(String sandboxId)`. Killed or marked sandboxes report `EXITED`.
  - `RunReaper.reapOnce(): int` and `RunReaper.reapAt(Instant now): int` (number of runs moved to terminal).
  - Config: `agentstore.jobs.enabled` (default true; false in tests), `agentstore.runs.gate-timeout` (default `PT30M`), `agentstore.runs.reaper-interval` (default `PT30S`).

**Reaper rules** (spec WS0 B3). For each run in `PENDING/RUNNING/AWAITING_APPROVAL`:
1. `AWAITING_APPROVAL` and (`awaiting_since + gateTimeout < now` or `deadline_at < now`) → `EXPIRED` + `TIMEOUT` step. A run blocked on a human that runs out of time counts as "expired", not "timed out".
2. Otherwise `deadline_at < now` → `TIMED_OUT` + `TIMEOUT` step.
3. Otherwise `RUNNING` with a sandbox the provider reports `EXITED` → `FAILED` + `FAILED` step. `UNKNOWN` never reaps; the deadline will catch it.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/RunReaperTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunReaperTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunReaper reaper;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    Instant now = Instant.now();

    ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
    }

    Run run(ExecutionFixtures.Scenario s, RunStatus status, String sandboxId, Instant deadline, Instant awaitingSince) {
        Run run = fixtures.run(s, status, sandboxId, "tok-" + UUID.randomUUID());
        run.setDeadlineAt(deadline);
        run.setAwaitingSince(awaitingSince);
        return runRepository.save(run);
    }

    Run reload(Run run) {
        return runRepository.findById(run.getId()).orElseThrow();
    }

    String sandbox() {
        return "fake-sandbox-" + UUID.randomUUID();
    }

    @Test
    void runPastItsDeadlineIsTimedOutAndKilled() {
        String sandboxId = sandbox();
        Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.minusSeconds(1), null);

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(fakeSandboxProvider.wasKilled(sandboxId)).isTrue();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting(RunStep::getKind).containsExactly(RunStepKind.TIMEOUT);
    }

    @Test
    void approvalWaitLongerThanTheGateTimeoutExpires() {
        Run run = run(scenario(), RunStatus.AWAITING_APPROVAL, sandbox(), now.plusSeconds(600),
                now.minus(Duration.ofMinutes(31)));

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    @Test
    void approvalWaitPastTheRunDeadlineExpiresRatherThanTimesOut() {
        Run run = run(scenario(), RunStatus.AWAITING_APPROVAL, sandbox(), now.minusSeconds(1), now.minusSeconds(60));

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    @Test
    void runningRunWhoseSandboxExitedWithoutReportingIsFailed() {
        String sandboxId = sandbox();
        Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.plusSeconds(600), null);
        fakeSandboxProvider.markExited(sandboxId);

        reaper.reapAt(now);

        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void healthyRunsAreLeftAlone() {
        Run running = run(scenario(), RunStatus.RUNNING, sandbox(), now.plusSeconds(600), null);
        Run waiting = run(scenario(), RunStatus.AWAITING_APPROVAL, sandbox(), now.plusSeconds(600),
                now.minusSeconds(60));

        reaper.reapAt(now);

        assertThat(reload(running).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(reload(waiting).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void reapingReleasesTheWalletReservation() {
        var s = scenario();
        Wallet wallet = walletRepository.findByOrgId(s.buyer().getId()).orElseThrow();
        wallet.setReserved(new BigDecimal("200.00"));
        walletRepository.save(wallet);
        Run run = run(s, RunStatus.RUNNING, sandbox(), now.minusSeconds(1), null);
        run.setReservedAmount(new BigDecimal("200.00"));
        runRepository.save(run);

        reaper.reapAt(now);

        assertThat(walletRepository.findByOrgId(s.buyer().getId()).orElseThrow().getReserved())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void approvingAfterTheReaperExpiredTheGateConflicts() {
        var s = scenario();
        Run run = run(s, RunStatus.AWAITING_APPROVAL, sandbox(), now.minusSeconds(1), now.minusSeconds(60));
        reaper.reapAt(now);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), s.buyer().getId(), UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("late"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.EXPIRED);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }
}
```

`backend/src/test/java/com/agentstore/platform/common/SchedulingWiringTest.java`:

```java
package com.agentstore.platform.common;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunReaperJob;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.jobs.enabled=true")
class SchedulingWiringTest extends AbstractIntegrationTest {

    @Autowired
    LockProvider lockProvider;
    @Autowired
    RunReaperJob runReaperJob;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void reaperJobRunsUnderAShedLock() {
        runReaperJob.run();

        Integer locks = jdbcTemplate.queryForObject("select count(*) from shedlock where name = 'runReaper'",
                Integer.class);
        assertThat(locks).isEqualTo(1);
    }
}
```

Add to `E2bSandboxProviderTest.java` (imports `org.springframework.http.HttpStatus` and `static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus`):

```java
    @Test
    void statusReportsRunningForALiveSandbox() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"sandboxID\":\"sbx-1\"}", MediaType.APPLICATION_JSON));
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.RUNNING);
    }

    @Test
    void statusReportsExitedWhenE2bNoLongerKnowsTheSandbox() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-2")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(provider.status("sbx-2")).isEqualTo(SandboxStatus.EXITED);
    }

    @Test
    void statusReportsUnknownOnProviderErrors() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-3")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(provider.status("sbx-3")).isEqualTo(SandboxStatus.UNKNOWN);
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest='RunReaperTest,SchedulingWiringTest,E2bSandboxProviderTest'`
Expected: compilation failure (`RunReaper`, `SandboxStatus`, ShedLock classes not found).

- [ ] **Step 3: Dependencies, migration, config**

`pom.xml`: add inside `<properties>` `<shedlock.version>5.16.0</shedlock.version>` and inside `<dependencies>`:

```xml
    <dependency>
      <groupId>net.javacrumbs.shedlock</groupId>
      <artifactId>shedlock-spring</artifactId>
      <version>${shedlock.version}</version>
    </dependency>
    <dependency>
      <groupId>net.javacrumbs.shedlock</groupId>
      <artifactId>shedlock-provider-jdbc-template</artifactId>
      <version>${shedlock.version}</version>
    </dependency>
```

`backend/src/main/resources/db/migration/V16__shedlock.sql`:

```sql
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

`application.yml`: under the existing top-level `agentstore:` key add:

```yaml
  jobs:
    enabled: ${AGENTSTORE_JOBS_ENABLED:true}
  runs:
    gate-timeout: PT30M
    reaper-interval: PT30S
```

`application-test.yml`: under its `agentstore:` key add:

```yaml
  jobs:
    enabled: false
```

- [ ] **Step 4: Sandbox status across providers**

`execution/SandboxStatus.java`:

```java
package com.agentstore.platform.execution;

public enum SandboxStatus { RUNNING, EXITED, UNKNOWN }
```

`SandboxProvider.java`:

```java
package com.agentstore.platform.execution;

public interface SandboxProvider {
    SandboxHandle create(SandboxSpec spec);

    void kill(String sandboxId);

    /** UNKNOWN means the provider could not be asked; callers must not treat it as exited. */
    SandboxStatus status(String sandboxId);
}
```

`FakeSandboxProvider.java`: add

```java
    private final Set<String> exited = ConcurrentHashMap.newKeySet();

    @Override
    public SandboxStatus status(String sandboxId) {
        return killed.contains(sandboxId) || exited.contains(sandboxId) ? SandboxStatus.EXITED : SandboxStatus.RUNNING;
    }

    public void markExited(String sandboxId) {
        exited.add(sandboxId);
    }
```

`E2bSandboxProvider.java`: add (imports `org.springframework.web.client.HttpClientErrorException`, `org.springframework.web.client.RestClientException`):

```java
    @Override
    public SandboxStatus status(String sandboxId) {
        try {
            restClient.get()
                    .uri("/sandboxes/{id}", sandboxId)
                    .header("X-API-Key", apiKey)
                    .retrieve()
                    .toBodilessEntity();
            return SandboxStatus.RUNNING;
        } catch (HttpClientErrorException.NotFound e) {
            return SandboxStatus.EXITED;
        } catch (RestClientException e) {
            return SandboxStatus.UNKNOWN;
        }
    }
```

- [ ] **Step 5: Reaper, job, scheduling config**

`execution/RunReaper.java`:

```java
package com.agentstore.platform.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Moves runs that can no longer make progress to a terminal state (spec WS0 B3). */
@Component
public class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);
    private static final List<RunStatus> ACTIVE =
            List.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;
    private final SandboxProvider sandboxProvider;
    private final Duration gateTimeout;

    public RunReaper(RunRepository runRepository, RunLifecycleService runLifecycleService,
                     SandboxProvider sandboxProvider,
                     @Value("${agentstore.runs.gate-timeout:PT30M}") Duration gateTimeout) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
        this.sandboxProvider = sandboxProvider;
        this.gateTimeout = gateTimeout;
    }

    public int reapOnce() {
        return reapAt(Instant.now());
    }

    public int reapAt(Instant now) {
        int reaped = 0;
        for (Run run : runRepository.findByStatusIn(ACTIVE)) {
            try {
                if (reap(run, now)) {
                    reaped++;
                }
            } catch (RuntimeException e) {
                log.error("Reaper failed for run {}", run.getId(), e);
            }
        }
        return reaped;
    }

    private boolean reap(Run run, Instant now) {
        boolean deadlinePassed = run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(now);
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            boolean gateExpired = run.getAwaitingSince() != null
                    && run.getAwaitingSince().plus(gateTimeout).isBefore(now);
            if (!gateExpired && !deadlinePassed) {
                return false;
            }
            return runLifecycleService.terminate(run.getId(), RunStatus.EXPIRED, RunStepKind.TIMEOUT,
                    Map.of("summary", "No approval decision in time; run stopped"));
        }
        if (deadlinePassed) {
            return runLifecycleService.terminate(run.getId(), RunStatus.TIMED_OUT, RunStepKind.TIMEOUT,
                    Map.of("summary", "Run exceeded its time limit"));
        }
        if (run.getStatus() == RunStatus.RUNNING && run.getSandboxId() != null
                && sandboxProvider.status(run.getSandboxId()) == SandboxStatus.EXITED) {
            return runLifecycleService.terminate(run.getId(), RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Agent stopped without reporting completion"));
        }
        return false;
    }
}
```

`execution/RunReaperJob.java`:

```java
package com.agentstore.platform.execution;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RunReaperJob {

    private final RunReaper runReaper;

    public RunReaperJob(RunReaper runReaper) {
        this.runReaper = runReaper;
    }

    @Scheduled(fixedDelayString = "${agentstore.runs.reaper-interval:PT30S}")
    @SchedulerLock(name = "runReaper", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        runReaper.reapOnce();
    }
}
```

`common/SchedulingConfig.java`:

```java
package com.agentstore.platform.common;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/** Background jobs; ShedLock makes each job run on one instance at a time. Disabled in tests. */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
```

- [ ] **Step 6: Run the reaper suites and the full execution package**

Run: `mvn -q test -Dtest='RunReaperTest,SchedulingWiringTest,E2bSandboxProviderTest,com.agentstore.platform.execution.*Test'`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "fix(execution): add a ShedLock-guarded reaper that times out, expires and fails stuck runs"
```

---

### Task 7: Deny-all egress on E2B and a live contract test (B1, B2 note)

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/E2bSandboxProvider.java`
- Modify: `backend/pom.xml` (surefire tag exclusion)
- Test: modify `execution/E2bSandboxProviderTest.java`; create `execution/E2bSandboxProviderLiveTest.java`

**Interfaces:**
- Consumes: `SandboxProvider.status` (Task 6).
- Produces: E2B create body `network: {allowOut: [...], denyOut: ["0.0.0.0/0"]}`. JUnit tag `e2b` excluded by default; run with `-Dsurefire.excludedGroups=none -Dgroups=e2b`.

- [ ] **Step 1: Tighten the unit test**

In `E2bSandboxProviderTest.createPostsTemplateAndReturnsSandboxId`, add after the `allowOut[0]` expectation:

```java
                .andExpect(jsonPath("$.network.denyOut[0]").value("0.0.0.0/0"))
                .andExpect(jsonPath("$.network.denyOut.length()").value(1))
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -q test -Dtest=E2bSandboxProviderTest`
Expected: FAIL, `No value at JSON path "$.network.denyOut[0]"`.

- [ ] **Step 3: Send deny-all and document the B2 limitation**

In `E2bSandboxProvider.java` (import `java.util.List`), add the constant and change the `network` entry of the create body:

```java
    /** E2B: allow rules take precedence over deny rules, so deny-all + allowOut = allowlist only. */
    static final String ALL_TRAFFIC = "0.0.0.0/0";
```

```java
                "network", Map.of("allowOut", spec.allowOut(), "denyOut", List.of(ALL_TRAFFIC)));
```

Add this class Javadoc above `@Component`:

```java
/**
 * Direct E2B REST client.
 *
 * <p>Known limitation (spec §2.2 B2): this only creates the sandbox. It cannot start the agent process, because E2B
 * exposes in-sandbox command execution only through its JS/Python SDKs. Real runs therefore never call back and are
 * reclaimed by {@link RunReaper} at their deadline. WS5 replaces this with a sandbox-bridge provider; until then only
 * {@link FakeSandboxProvider} runs exercise the full loop.
 */
```

- [ ] **Step 4: Live contract test (skipped without a key)**

`backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderLiveTest.java`:

```java
package com.agentstore.platform.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against the real E2B API (nightly). Proves E2B accepts the deny-all network shape and that status/kill work.
 * Proving a non-allowlisted host is actually unreachable needs in-sandbox command execution, which arrives with
 * sandbox-bridge in WS5; that assertion is added there.
 */
@Tag("e2b")
class E2bSandboxProviderLiveTest {

    @Test
    void e2bAcceptsDenyAllEgressAndReportsLifecycle() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        String template = System.getenv().getOrDefault("E2B_SMOKE_TEMPLATE", "base");
        var provider = new E2bSandboxProvider(RestClient.builder(), "https://api.e2b.app", apiKey);

        SandboxHandle handle = provider.create(new SandboxSpec(template, 60, Map.of(), List.of("example.com")));
        try {
            assertThat(handle.sandboxId()).isNotBlank();
            assertThat(provider.status(handle.sandboxId())).isEqualTo(SandboxStatus.RUNNING);
        } finally {
            provider.kill(handle.sandboxId());
        }
    }
}
```

`pom.xml`: add `<surefire.excludedGroups>e2b</surefire.excludedGroups>` to `<properties>` and this plugin inside `<build><plugins>`:

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludedGroups>${surefire.excludedGroups}</excludedGroups>
        </configuration>
      </plugin>
```

- [ ] **Step 5: Verify both paths**

Run: `mvn -q test -Dtest='E2bSandboxProvider*'`
Expected: `E2bSandboxProviderTest` PASS; the live test is **not run** (excluded tag).

Run: `mvn test -Dsurefire.excludedGroups=none -Dgroups=e2b`
Expected without `E2B_API_KEY`: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1`. With a key it runs for real. If E2B rejects the body (HTTP 400), look up the current REST field names in E2B's API reference and fix `create` before merging.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src
git commit -m "fix(execution): deny all sandbox egress except the allowlist and add a live E2B contract test"
```

---

### Task 8: Frontend shows the new statuses and step kinds, then full verification

**Files:**
- Modify: `frontend/src/lib/types.ts`, `frontend/src/components/StatusPill.tsx`, `frontend/src/components/useRunPolling.ts`, `frontend/src/components/RunTraceStep.tsx`, `frontend/src/app/(buyer)/runs/[runId]/RunConsole.tsx`
- Test: `frontend/src/components/useRunPolling.test.tsx`, `frontend/src/components/RunTraceStep.test.tsx`, `frontend/src/components/StatusPill.test.tsx`, `frontend/src/app/(buyer)/runs/[runId]/RunConsole.test.tsx`

**Interfaces:**
- Consumes: backend `RunStatus` values `TIMED_OUT`, `EXPIRED`; `RunStepKind` values `TIMEOUT`, `COST_CAP_REACHED`; step `detail.summary` (Tasks 2, 3, 6).
- Produces: `export function isTerminalRunStatus(status: RunStatus): boolean` from `useRunPolling.ts`.

- [ ] **Step 1: Write the failing tests**

Append to `useRunPolling.test.tsx` (extend the import to `import { isTerminalRunStatus, useRunPolling } from "./useRunPolling";`):

```tsx
describe("isTerminalRunStatus", () => {
  it.each(["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"] as const)("treats %s as terminal", (s) => {
    expect(isTerminalRunStatus(s)).toBe(true);
  });
  it.each(["PENDING", "RUNNING", "AWAITING_APPROVAL"] as const)("keeps polling for %s", (s) => {
    expect(isTerminalRunStatus(s)).toBe(false);
  });
});
```

Append inside the `describe("RunConsole", …)` block of `RunConsole.test.tsx`:

```tsx
  it("labels a timed-out run", () => {
    const run: RunDetailResponse = { ...awaitingRun, status: "TIMED_OUT" };
    renderWithClient(<RunConsole runId="r1" initial={run} role="BUYER_APPROVER" />);
    expect(screen.getByText("Stopped — ran past its time limit")).toBeInTheDocument();
  });

  it("labels a run whose approval window expired", () => {
    const run: RunDetailResponse = { ...awaitingRun, status: "EXPIRED" };
    renderWithClient(<RunConsole runId="r1" initial={run} role="BUYER_APPROVER" />);
    expect(screen.getByText("Expired — no approval in time")).toBeInTheDocument();
  });
```

Append to `RunTraceStep.test.tsx`, inside its existing `describe` (reuse its existing imports of `render`, `screen`, `RunTraceStep`):

```tsx
  it("labels platform-recorded stops", () => {
    render(<RunTraceStep step={{ kind: "COST_CAP_REACHED", detail: '{"summary":"Spend limit reached; run stopped"}', createdAt: "2026-09-23T10:00:00Z" }} />);
    expect(screen.getByText("Spend limit reached")).toBeInTheDocument();
    expect(screen.getByText("Spend limit reached; run stopped")).toBeInTheDocument();
  });
```

Append to `StatusPill.test.tsx` inside its `describe`:

```tsx
  it("styles expired runs as a stop, not neutral", () => {
    render(<StatusPill status="EXPIRED" />);
    expect(screen.getByText("expired").className).toContain("text-red");
  });
```

- [ ] **Step 2: Run and confirm failure**

Run: `npx vitest run src/components src/app`
Expected: FAIL. `isTerminalRunStatus` is not exported, the labels are missing, and `EXPIRED` has the neutral style.

- [ ] **Step 3: Implement**

`types.ts`:

```ts
export type RunStatus =
  | "PENDING" | "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "EXPIRED";
export type RunStepKind = "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED" | "TIMEOUT" | "COST_CAP_REACHED";
```

and add `reserved: number;` to `WalletResponse`.

`StatusPill.tsx`: add to `STYLES`:

```ts
  TIMED_OUT: "bg-redSoft text-red",
  EXPIRED: "bg-redSoft text-red",
```

`useRunPolling.ts`:

```ts
"use client";

import { useQuery } from "@tanstack/react-query";
import type { RunDetailResponse, RunStatus } from "@/lib/types";

const TERMINAL_STATUSES: RunStatus[] = ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"];

export function isTerminalRunStatus(status: RunStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}

export function useRunPolling(runId: string, initialData: RunDetailResponse) {
  return useQuery({
    queryKey: ["run", runId],
    initialData,
    refetchInterval: (query) => (isTerminalRunStatus(query.state.data?.status ?? "PENDING") ? false : 2000),
    queryFn: async () => {
      const res = await fetch(`/api/runs/${runId}`);
      if (!res.ok) throw new Error("Failed to load run");
      return (await res.json()) as RunDetailResponse;
    },
  });
}
```

`RunTraceStep.tsx`: add to `KIND_LABEL`:

```ts
  TIMEOUT: "Stopped by platform",
  COST_CAP_REACHED: "Spend limit reached",
```

`RunConsole.tsx` `terminalLabel`: add before `return run.status;`:

```ts
  if (run.status === "TIMED_OUT") return "Stopped — ran past its time limit";
  if (run.status === "EXPIRED") return "Expired — no approval in time";
```

- [ ] **Step 4: Frontend checks**

Run: `npx vitest run && npx tsc --noEmit`
Expected: all tests PASS; no type errors (every `Record<RunStepKind, …>` now has all eight keys).

- [ ] **Step 5: Full backend verification (spec WS0 acceptance: "all existing tests pass")**

Run (from `backend/`, Docker running): `mvn test`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors; `E2bSandboxProviderLiveTest` not executed.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): show timed-out, expired and spend-limit stops in the run console"
```
