# WS6a Developer Sandbox Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A developer can run any of their built versions (DRAFT included) against fixture data in the real runner with no buyer, no connection and no money moving, and can watch it from a thin CLI.

**Architecture:** A SANDBOX run is an ordinary `run` row with `connection_id = NULL` and `initiated_by_org_id` set, owned by the developer org. It goes through the same launcher, Runtime API, LLM gateway and trace as production. Every place that read `run.connection` gets a branch; sandbox billing writes only `run.simulated_charged` / `requested_charge`, never `usage_event`, the ledger or a wallet. Profiles, fixtures, gate policy, quotas and rerun-with-replay are new code in a new `sandbox` package; quota usage is computed from `run` and `llm_call`, not stored.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate (`ddl-auto: validate`), Flyway (V31), Postgres, Testcontainers (Postgres, Redis, SeaweedFS), TypeScript CLI with vitest.

**Spec:** `docs/superpowers/specs/2026-09-27-ws6a-sandbox-core-design.md`

## Global Constraints

- Backend, contract and a thin CLI only. No UI, no `run_report`, no PII scan, no trial attestation, no REPO sync, no local emulator (spec §1).
- Migration is **V31** (last existing is V30). `usage_event.simulated`, `developer_quota`, `run_report` are NOT created (spec §2 deviations).
- A sandbox run writes **no** `usage_event`, `ledger_entry`, wallet reservation or wallet release (spec §4).
- Quotas (spec §5), config prefix `agentstore.sandbox.quota.{unverified|verified}.*`:
  unverified 7,200 run-seconds / 1 concurrent / 200 LLM spend / 500 MB fixtures / 20 builds per day; verified 60,000 / 5 / 2,000 / 5 GB / 100. UTC calendar months. Every org is unverified until WS7 sets `org.developer_verified_at`.
- Error codes: `SANDBOX_QUOTA_EXCEEDED` 429 `{limit, used, max}`; `MISSING_BINDING` 422; `REPLAY_SOURCE_INVALID` 403 (foreign / other-agent / non-sandbox / missing source) or 422 (no recorded calls); `VERSION_NOT_BUILT` 409; `RESOURCE_NOT_BINDABLE` 422.
- Policy values `MANUAL | AUTO_APPROVE | AUTO_REJECT`; auto decisions carry the note `auto policy`.
- Cost cap = `profile.spend_cap`, else the manifest's `costEnvelope.maxPerRun` (I1). Any `VersionStatus` may run (I2). Replay source = SANDBOX run, same agent, same org; cross-version allowed (I3).
- **No existing test file may be edited** (spec §8: the whole existing suite passes unchanged). New tests only.
- `/runtime/v1` does not change; `contracts/runtime-v1.yaml` must not drift.
- Commit messages carry **no** AI/Claude/co-author attribution (user's global rule, overrides the harness trailer).
- Run backend tests from `backend/` with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=<Class>`; CLI tests from `cli/` with `npx vitest run`.

## Review Focus

Failure modes the spec implies but a happy-path test would not catch, most likely first. Each has a test in the named task.

1. Two triggers racing on an unverified org must not both pass the concurrency check (Task 7, `SandboxRunStartTest.concurrentTriggersOnlyOnePasses`).
2. A replay source that is another developer's run, another agent's run, a PRODUCTION run, or simply a nonexistent id is a 403 `REPLAY_SOURCE_INVALID`, never a 500 or a leak of existence (Task 7).
3. An inline profile that names another org's fixture must fail with 404 and leave no run **and no profile row** behind (Task 7, rolled back with the run transaction).
4. Deleting a profile that runs already used must not break the run list/detail (profile name shows as null) and rerun of such a run is a clean 409 (Task 7).
5. Fake secret values never appear in any API response, in `SandboxSpec.toString`, or in the steps of the run (Task 3).

---

## File Structure

New package `backend/src/main/java/com/agentstore/platform/sandbox/`:

| File | Responsibility |
|---|---|
| `SandboxProfile.java`, `SandboxProfileRepository.java` | Entity and repo for `sandbox_profile` |
| `GatePolicy.java` | `{default, actions}` record + `GateMode` enum, JSON (de)serialisation |
| `FakeSecrets.java` | Encrypts/decrypts the profile's fake-secret map with `AesGcmCipher`; names only on read |
| `SandboxApiException.java`, `SandboxApiErrors.java` | `{code, message, ...extra}` errors (like `LlmApiException`) |
| `ProfileInput.java` | Request record shared by saved and inline profiles |
| `SandboxProfileService.java`, `SandboxProfileController.java` | Profile CRUD, inline profiles, ownership |
| `SandboxQuotaProperties.java`, `SandboxQuotaService.java`, `DeveloperQuotaController.java` | Limits, computed usage, checks, `GET /developer/quota` |
| `SandboxRunService.java`, `SandboxRunController.java`, `SandboxRunViews.java` | Start, cancel, rerun, list, detail, gate decision |
| `SandboxGatePolicy.java` | Auto-approve / auto-reject after a gate opens |

New in `data/`: `SandboxBindingService`, `SandboxBindingController`, `DeveloperFixtureController` (they need package-private `ResourceLookup` and the dataset internals).

Modified: `Run`, `RunRepository`, `RunLifecycleService`, `RunLauncher`, `RunController`, `RunDetailController`, `RunStepKind`, `RunStepResponse`, `RunPrincipal`, `OutputsController`, `BindingResolver`, `DatasetService`, `DatasetResponse`, `DatasetRepository`, `BundleService`, `TemplateBuildRepository`, `LlmCallRepository`, `GatewayLlmBackend`, `RuntimeGateController`, `ApiKeyScope`, `application.yml`, `ONTOLOGY.md`, and under `cli/`: `cli.ts`, new `commands/sandbox.ts`.

Tests: new package `backend/src/test/java/com/agentstore/platform/sandbox/` with a shared `SandboxFixtures`, plus `cli/test/sandbox.test.ts`.

---

### Task 1: Migration V31, entities and test fixtures

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__sandbox_core.sql`
- Create: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxProfile.java`, `SandboxProfileRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/Run.java`
- Create: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxFixtures.java`, `SandboxDataModelTest.java`

**Interfaces:**
- Produces: `Run` gains `initiatedByOrgId: UUID`, `sandboxProfileId: UUID`, `simulatedCharged: BigDecimal`, `requestedCharge: BigDecimal`; `connection` becomes optional. `SandboxProfile` fields per V31. `SandboxFixtures` (test) with `Dev developer(String manifestFile)`, `HttpHeaders bearer(Org, UserRole)`, `SandboxProfile profile(Dev, String name)`, `Run sandboxRun(Dev, SandboxProfile, RunStatus)`, `void verify(Org)`.

- [ ] **Step 1: Write the failing test**

```java
package com.agentstore.platform.sandbox;

class SandboxDataModelTest extends AbstractIntegrationTest {
    @Autowired SandboxFixtures fx;
    @Autowired RunRepository runs;
    @Autowired SandboxProfileRepository profiles;
    @Autowired ExecutionFixtures exec;

    @Test
    void aSandboxRunHasNoConnectionAndKeepsItsSandboxFields() {
        var dev = fx.developer("valid-tier-a.json");
        var profile = fx.profile(dev, "default");
        Run saved = fx.sandboxRun(dev, profile, RunStatus.RUNNING);
        Run loaded = runs.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getConnection()).isNull();
        assertThat(loaded.getMode()).isEqualTo(RunMode.SANDBOX);
        assertThat(loaded.getInitiatedByOrgId()).isEqualTo(dev.org().getId());
        assertThat(loaded.getSandboxProfileId()).isEqualTo(profile.getId());
        assertThat(loaded.getSimulatedCharged()).isEqualByComparingTo("0");
        assertThat(loaded.getRequestedCharge()).isEqualByComparingTo("0");
    }

    @Test
    void theDatabaseRejectsARunWhoseModeAndOwnerDisagree() {
        var dev = fx.developer("valid-tier-a.json");
        var scenario = exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000"));
        Run sandboxWithConnection = exec.run(scenario, RunStatus.RUNNING, "s1", "t1");
        sandboxWithConnection.setMode(RunMode.SANDBOX);
        sandboxWithConnection.setInitiatedByOrgId(dev.org().getId());
        assertThatThrownBy(() -> runs.saveAndFlush(sandboxWithConnection)).isInstanceOf(DataIntegrityViolationException.class);

        Run productionWithoutConnection = new Run(null, null, dev.version(), RunStatus.RUNNING, new BigDecimal("5"),
                BigDecimal.ZERO, null, RunTokenService.hash("t2"));
        assertThatThrownBy(() -> runs.saveAndFlush(productionWithoutConnection)).isInstanceOf(DataIntegrityViolationException.class);

        Run sandboxWithoutInitiator = new Run(null, null, dev.version(), RunStatus.RUNNING, new BigDecimal("5"),
                BigDecimal.ZERO, null, RunTokenService.hash("t3"));
        sandboxWithoutInitiator.setMode(RunMode.SANDBOX);
        assertThatThrownBy(() -> runs.saveAndFlush(sandboxWithoutInitiator)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void profileNamesAreUniquePerAgentAndDefaultsAreSafe() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "happy-path");
        assertThat(profile.getGatePolicy()).contains("\"default\":\"MANUAL\"");
        assertThat(profile.getInputJson()).isEqualTo("{}");
        assertThat(profile.isInline()).isFalse();
        assertThat(profile.getLlmMode()).isNull();
        SandboxProfile dup = new SandboxProfile(dev.agent().getId(), "happy-path");
        assertThatThrownBy(() -> profiles.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn test -Dtest=SandboxDataModelTest` → compile FAIL (`SandboxProfile`, `SandboxFixtures`, `Run.getInitiatedByOrgId` do not exist).

- [ ] **Step 3: Write the migration** `V31__sandbox_core.sql` — exactly the spec §2 SQL, **with one departure**: `sandbox_profile_id UUID REFERENCES sandbox_profile(id) ON DELETE SET NULL` (a profile that runs used must stay deletable; the run keeps its own copy of every setting).

```sql
CREATE TABLE sandbox_profile (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    name VARCHAR(100) NOT NULL,
    inline BOOLEAN NOT NULL DEFAULT FALSE,
    input_json JSONB NOT NULL DEFAULT '{}',
    gate_policy JSONB NOT NULL DEFAULT '{"default":"MANUAL","actions":{}}',
    llm_mode VARCHAR(10),
    replay_run_id UUID REFERENCES run(id),
    llm_mock_json JSONB,
    spend_cap NUMERIC(12,2),
    fake_secrets_encrypted BYTEA,
    fake_secrets_nonce BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, name)
);
ALTER TABLE run ALTER COLUMN connection_id DROP NOT NULL;
ALTER TABLE run ADD COLUMN initiated_by_org_id UUID REFERENCES org(id);
ALTER TABLE run ADD COLUMN sandbox_profile_id UUID REFERENCES sandbox_profile(id) ON DELETE SET NULL;
ALTER TABLE run ADD COLUMN simulated_charged NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE run ADD COLUMN requested_charge   NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE run ADD CONSTRAINT run_mode_owner_chk CHECK (
    (mode = 'PRODUCTION' AND connection_id IS NOT NULL)
 OR (mode = 'SANDBOX'    AND connection_id IS NULL AND initiated_by_org_id IS NOT NULL)
 OR (mode NOT IN ('PRODUCTION','SANDBOX')));
CREATE INDEX run_sandbox_org_idx ON run (initiated_by_org_id, started_at) WHERE mode = 'SANDBOX';
```

- [ ] **Step 4: Entities.** `Run.java`: change `@ManyToOne(optional = false) @JoinColumn(name = "connection_id") private Connection connection;` to `@ManyToOne @JoinColumn(name = "connection_id")` (no `optional = false`; comment: "null for a sandbox run") and add:

```java
    /** The developer org that started a SANDBOX run; null for every other mode. */
    private UUID initiatedByOrgId;
    private UUID sandboxProfileId;
    /** What a SANDBOX run's usage reports would have cost, after the cap. No money moves. */
    private BigDecimal simulatedCharged = BigDecimal.ZERO;
    /** The un-clipped amount those reports asked for. */
    private BigDecimal requestedCharge = BigDecimal.ZERO;
```

`SandboxProfile.java` (`@Entity @Table(name = "sandbox_profile") @Getter @Setter @NoArgsConstructor`): `@Id @GeneratedValue(strategy = GenerationType.UUID) UUID id; UUID agentId; String name; boolean inline;` `input_json` / `gate_policy` / `llm_mock_json` as `@JdbcTypeCode(SqlTypes.JSON) @Column(name = ..., columnDefinition = "jsonb") String` (defaults `"{}"` and `"{\"default\":\"MANUAL\",\"actions\":{}}"`), `@Enumerated(EnumType.STRING) @Column(name = "llm_mode") RunLlmMode llmMode; UUID replayRunId; BigDecimal spendCap; byte[] fakeSecretsEncrypted; byte[] fakeSecretsNonce; Instant createdAt; Instant updatedAt;` with `SandboxProfile(UUID agentId, String name)` constructor and `@PrePersist` (sets both timestamps) / `@PreUpdate` (updatedAt).

```java
public interface SandboxProfileRepository extends JpaRepository<SandboxProfile, UUID> {
    List<SandboxProfile> findByAgentIdAndInlineFalseOrderByNameAsc(UUID agentId);
    boolean existsByAgentIdAndName(UUID agentId, String name);
}
```

- [ ] **Step 5: `SandboxFixtures`** (test `@Component`). Builds a DEVELOPER org, an `Agent`, one DRAFT `AgentVersion` (manifest read from `src/test/resources/manifest/<file>`, `imageRef = "template-v1"`, `Tier.TIER_A`), and issues JWTs through `JwtService.issue(UUID.randomUUID(), org.getId(), role)` exactly like `DataFixtures.bearer`. `profile(dev, name)` saves `new SandboxProfile(agentId, name)`. `sandboxRun(dev, profile, status)` saves a `Run` via `new Run(null, null, version, status, maxPerRun, ZERO, "sbx-"+uuid, RunTokenService.hash("tok-"+uuid))` then sets mode SANDBOX, initiatedByOrgId, sandboxProfileId, `startedAt = now`, `deadlineAt = now+600s`. `verify(org)` runs `UPDATE org SET developer_verified_at = now()`.

- [ ] **Step 6: Run** `mvn test -Dtest=SandboxDataModelTest` → PASS. Then run `mvn test -Dtest='RunControllerTest,RunLifecycleServiceTest,RunReaperTest'` to prove the nullable-connection mapping did not break production paths.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V31__sandbox_core.sql backend/src/main/java/com/agentstore/platform/sandbox backend/src/main/java/com/agentstore/platform/execution/Run.java backend/src/test/java/com/agentstore/platform/sandbox
git commit -m "feat(sandbox): V31 schema, sandbox profile entity, connection-less run"
```

---

### Task 2: Connection-less run plumbing: principal, simulated billing, isolation guards

**Files:**
- Modify: `runtime/RunPrincipal.java`, `execution/RunLifecycleService.java`, `execution/RunController.java`, `execution/RunDetailController.java`, `execution/RunStepKind.java`, `data/OutputsController.java`
- Test: `sandbox/SandboxRunPlumbingTest.java`

**Interfaces:**
- Consumes: Task 1 `Run` fields, `SandboxFixtures`.
- Produces: `RunPrincipal.forRun` works for a connection-less run (`orgId` = initiator, every manifest scope granted). `RunLifecycleService.recordUsage` on a sandbox run updates `simulatedCharged` / `requestedCharge`. `RunStepKind.CANCELLED` (platform source). Buyer run endpoints answer 404 for a sandbox run.

- [ ] **Step 1: Write failing tests** in `SandboxRunPlumbingTest extends AbstractStorageIntegrationTest` (uses `@LocalServerPort`, `TestRestTemplate`, `RunLifecycleService`, `JdbcTemplate`):

```java
@Test
void usageOnASandboxRunTouchesNoLedgerWalletOrUsageEvent() {
    var dev = fx.developer("valid-tier-a.json");                       // unitPrice 2.00, maxPerRun 500
    Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
    long ledger = count("ledger_entry"), events = count("usage_event"), wallets = sumWalletBalances();
    UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("3"), Map.of("unit", "u"));
    assertThat(outcome.charged()).isEqualByComparingTo("6.00");
    Run after = runs.findById(run.getId()).orElseThrow();
    assertThat(after.getSimulatedCharged()).isEqualByComparingTo("6.00");
    assertThat(after.getRequestedCharge()).isEqualByComparingTo("6.00");
    assertThat(after.getTotalCharged()).isEqualByComparingTo("0");
    assertThat(count("ledger_entry")).isEqualTo(ledger);
    assertThat(count("usage_event")).isEqualTo(events);
    assertThat(sumWalletBalances()).isEqualTo(wallets);
}

@Test
void theCapClipsTheSimulatedChargeButRequestedKeepsTheAsk() {
    var dev = fx.developer("valid-tier-a.json");
    var profile = fx.profile(dev, "capped");
    Run run = fx.sandboxRun(dev, profile, RunStatus.RUNNING);
    run.setCostCap(new BigDecimal("10.00")); runs.save(run);
    UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("8"), Map.of());   // asks 16.00, cap 10.00
    assertThat(outcome.capReached()).isTrue();
    Run after = runs.findById(run.getId()).orElseThrow();
    assertThat(after.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(after.getSimulatedCharged()).isEqualByComparingTo("10.00");
    assertThat(after.getRequestedCharge()).isEqualByComparingTo("16.00");
    assertThat(steps(run)).contains(RunStepKind.COST_CAP_REACHED);
}

@Test
void aScopeViolationDoesNotKillASandboxRunButStillKillsAProductionRun() {
    var dev = fx.developer("valid-tier-a.json");
    Run sandbox = fx.sandboxRun(dev, fx.profile(dev, "v"), RunStatus.RUNNING);
    for (int i = 0; i < 4; i++) { assertThat(lifecycle.recordViolation(sandbox.getId(), Map.of("summary", "x"))).isFalse(); }
    assertThat(runs.findById(sandbox.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    var scenario = exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000"));
    Run prod = exec.run(scenario, RunStatus.RUNNING, "sbx", "tok");
    lifecycle.recordViolation(prod.getId(), Map.of("summary", "x")); lifecycle.recordViolation(prod.getId(), Map.of("summary", "x"));
    assertThat(lifecycle.recordViolation(prod.getId(), Map.of("summary", "x"))).isTrue();
}

@Test
void aSandboxRunPrincipalIsGrantedEveryDeclaredScopeInTheInitiatorsOrg() {
    var dev = fx.developer("valid-runtime-agent.json");
    Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
    RunPrincipal principal = RunPrincipal.forRun(run, parser);
    assertThat(principal.orgId()).isEqualTo(dev.org().getId());
    assertThat(principal.mode()).isEqualTo(RunMode.SANDBOX);
    assertThat(principal.granted(ScopeType.READ, "bank_statement")).isTrue();
    assertThat(principal.granted(ScopeType.READ, "invoices")).isTrue();
    assertThat(principal.granted(ScopeType.WRITE, "journal_entries")).isTrue();
    assertThat(principal.granted(ScopeType.WRITE, "undeclared")).isFalse();
}

@Test
void buyerRunEndpointsAnswer404ForASandboxRunAndListsExcludeIt() {
    var dev = fx.developer("valid-tier-a.json");
    Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
    var buyer = exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000")).buyer();
    for (String path : List.of("/api/v1/runs/" + run.getId(), "/api/v1/runs/" + run.getId() + "/outputs")) {
        assertThat(get(path, buyer, UserRole.BUYER_ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    assertThat(post("/api/v1/runs/" + run.getId() + "/approve", Map.of("notes", "x"), buyer, UserRole.BUYER_APPROVER).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    var mine = rest.exchange(url("/api/v1/runs/mine"), HttpMethod.GET, new HttpEntity<>(null, fx.bearer(buyer, UserRole.BUYER_ADMIN)), List.class);
    assertThat(mine.getBody()).isEmpty();
}

@Test
void theInitiatingDeveloperCanReadOutputsButAnotherDeveloperAndAProductionRunAreRefused() {
    var dev = fx.developer("valid-tier-a.json"); var other = fx.developer("valid-tier-a.json");
    Run run = fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);
    assertThat(get("/api/v1/runs/" + run.getId() + "/outputs", dev.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/runs/" + run.getId() + "/outputs", other.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var prod = exec.run(exec.liveTierA(new BigDecimal("500"), new BigDecimal("1000")), RunStatus.RUNNING, "s", "t");
    assertThat(get("/api/v1/runs/" + prod.getId() + "/outputs", dev.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}
```

- [ ] **Step 2: Run** `mvn test -Dtest=SandboxRunPlumbingTest` → FAIL.

- [ ] **Step 3: `RunPrincipal.forRun`**

```java
public static RunPrincipal forRun(Run run, ManifestParser parser) {
    Manifest manifest = parser.parse(run.getAgentVersion().getManifestJson());
    if (run.getConnection() == null) {
        // A sandbox run has no buyer to grant scopes: the developer gets everything the manifest declares, so an
        // undeclared call is the only violation.
        Set<String> all = manifest.scopes().stream().map(s -> scopeKey(s.type(), s.resource())).collect(Collectors.toSet());
        return new RunPrincipal(run.getId(), run.getInitiatedByOrgId(), run.getMode(), manifest, all);
    }
    ... existing body unchanged ...
}
```

- [ ] **Step 4: `RunLifecycleService`.** In `charge(...)`:

```java
boolean sandbox = run.getConnection() == null;
BigDecimal spent = sandbox ? run.getSimulatedCharged() : run.getTotalCharged();
BigDecimal remaining = run.getCostCap().subtract(spent).max(BigDecimal.ZERO);
BigDecimal charged = requested.min(remaining);
step(run, stepKind, source, detail);
if (sandbox) {
    run.setSimulatedCharged(run.getSimulatedCharged().add(charged));
    run.setRequestedCharge(run.getRequestedCharge().add(requested));
} else if (charged.signum() > 0) {
    ... existing usage event / ledger / totalCharged / reservation release ...
}
```
and the returned `UsageOutcome` uses `sandbox ? run.getSimulatedCharged() : run.getTotalCharged()` for both `totalCharged` and the remaining figure. In `finish(...)` guard the release: `if (run.getConnection() != null && run.getReservedAmount().signum() > 0)`. `recordViolation` needs no change (the kill is already PRODUCTION-only; the test pins it).

- [ ] **Step 5: `RunStepKind`** add `CANCELLED` to the enum and to the platform-source case of `defaultSource()` (`this == TIMEOUT || this == COST_CAP_REACHED || this == CANCELLED`).

- [ ] **Step 6: Buyer endpoints.** `RunController.requireOwnedByCaller` and `RunDetailController.get`: right after loading the run, `if (run.getConnection() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");`. `RunDetailController.mine` already excludes sandbox runs (the JPQL path through `connection.buyerOrg` is an inner join); the test pins it. Make `RunController.SANDBOX_TIMEOUT_SECONDS` and `PENDING_BUILD_TIMEOUT` `public` (Task 7 needs them). Make `RunStepResponse.from` `public`.

- [ ] **Step 7: `OutputsController`.** Class-level `@PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER','DEVELOPER')")`; replace `requireOwnRun`:

```java
private void requireOwnRun(UUID runId) {
    Run run = runs.findById(runId).orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found"));
    var caller = Caller.current();
    if (run.getConnection() == null) {   // sandbox: only the initiating developer org
        if (caller.role() != UserRole.DEVELOPER || !caller.orgId().equals(run.getInitiatedByOrgId())) {
            throw new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Run not found");
        }
        return;
    }
    if (caller.role() == UserRole.DEVELOPER || !run.getConnection().getBuyerOrg().getId().equals(caller.orgId())) {
        throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Run not owned by caller's org");
    }
}
```

- [ ] **Step 8: Run** `mvn test -Dtest='SandboxRunPlumbingTest,RunLifecycleServiceTest,RunLifecycleRuntimeTest,OutputsApiTest,RunControllerTest,RunDetailControllerTest,RunSummaryControllerTest,RuntimeUsageEndpointTest'` → PASS.

- [ ] **Step 9: Commit** — `git commit -m "feat(sandbox): sandbox run principal, simulated billing and buyer-endpoint isolation"`.

---

### Task 3: Profiles, gate policy, fake secrets and launcher wiring

**Files:**
- Create: `sandbox/GatePolicy.java`, `FakeSecrets.java`, `SandboxApiException.java`, `SandboxApiErrors.java`, `ProfileInput.java`, `SandboxProfileService.java`, `SandboxProfileController.java`
- Modify: `execution/RunLauncher.java`
- Test: `sandbox/SandboxProfileApiTest.java`, `sandbox/SandboxLauncherSecretsTest.java`, `sandbox/GatePolicyTest.java`

**Interfaces:**
- Produces:
  - `GatePolicy(GateMode defaultMode, Map<String,GateMode> actions)`, `GatePolicy.parse(String json)`, `String toJson()`, `GateMode modeFor(String action)`; `enum GateMode { MANUAL, AUTO_APPROVE, AUTO_REJECT }`; JSON keys `default` and `actions`.
  - `FakeSecrets`: `void store(SandboxProfile, Map<String,String>)`, `Map<String,String> reveal(SandboxProfile)`, `List<String> names(SandboxProfile)`.
  - `SandboxApiException(HttpStatus, String code, String message[, Map<String,Object> extra])` with statics `quota(String limit, Object used, Object max)` and `notFound(String what)`.
  - `ProfileInput(String name, JsonNode input, GatePolicy gatePolicy, RunLlmMode llmMode, UUID replayRunId, JsonNode llmMock, BigDecimal spendCap, Map<String,String> fakeSecrets, Map<String,UUID> bindings)` (`bindings` is honoured only for inline profiles, Task 7).
  - `SandboxProfileService`: `SandboxProfile create(UUID orgId, UUID agentId, ProfileInput)`, `SandboxProfile createInline(UUID orgId, UUID agentId, ProfileInput)`, `SandboxProfile update(UUID orgId, UUID agentId, UUID profileId, ProfileInput)`, `void delete(UUID orgId, UUID agentId, UUID profileId)`, `List<ProfileView> list(UUID orgId, UUID agentId)`, `ProfileView get(...)`, `ProfileView view(SandboxProfile)`.
  - Endpoints: `POST/GET /api/v1/agents/{agentId}/sandbox-profiles`, `GET/PUT/DELETE /api/v1/agents/{agentId}/sandbox-profiles/{profileId}`, all `hasRole('DEVELOPER')`.

- [ ] **Step 1: Failing tests.**

`GatePolicyTest` (plain JUnit): parses `{"default":"AUTO_APPROVE","actions":{"post":"AUTO_REJECT"}}`; `modeFor("post")` = AUTO_REJECT, `modeFor("other")` = AUTO_APPROVE; parsing `null`/`""`/`{}` gives MANUAL; an unknown mode string throws `IllegalArgumentException`; `toJson` round-trips.

`SandboxProfileApiTest extends AbstractStorageIntegrationTest`:
- create → 201 body has `id`, `name`, `fakeSecretNames: ["API_KEY"]`, and **no** `fakeSecrets`/value anywhere: `assertThat(body.toString()).doesNotContain("s3cret")`.
- second create with the same name → 409 `PROFILE_NAME_TAKEN`.
- create with `llmMode: "REPLAY"` and no `replayRunId` → 400 `VALIDATION_FAILED`; bad secret name `"bad name"` → 400; `spendCap: -1` → 400; `gatePolicy.actions: {"x":"NOPE"}` → 400.
- list returns only saved profiles (an inline one made via `service.createInline` is absent).
- another developer's org gets 404 on get/put/delete; a BUYER_ADMIN gets 403.
- PUT without `fakeSecrets` keeps the stored secrets (`names` unchanged); PUT with `fakeSecrets: {}` clears them.
- DELETE removes the profile and its `SANDBOX_PROFILE` bindings (insert a binding row first through `DatasetBindingRepository`).

`SandboxLauncherSecretsTest extends AbstractStorageIntegrationTest` (`FakeSandboxProvider` is the provider bean): create a profile with `FakeSecrets.store(profile, Map.of("ACME_TOKEN","fake-123"))`, save a PENDING sandbox run for it (`fx.sandboxRun(..., PENDING)`), `launcher.launch(runId)`; the last `fake.createdSpecs()` entry has `envVars["AGENT_SECRET_ACME_TOKEN"] = "fake-123"`, `AGENTSTORE_MODE = SANDBOX`, and the run is RUNNING. Also assert `spec.toString()` does not contain `fake-123`, and that the run's steps contain no `fake-123`. Second case: a `valid-llm-byok-direct.json` sandbox run gets the developer's real key under `AGENT_SECRET_<VENDOR>_API_KEY` (store one with `ByokKeyService`, see `ByokDirectLaunchTest` for the setup), unless the fake secrets supply the same name, in which case the fake value wins and no real key is needed (run still launches with no stored key).

- [ ] **Step 2: Run** the three classes → FAIL (compile).

- [ ] **Step 3: `GatePolicy`**

```java
public record GatePolicy(@JsonProperty("default") GateMode defaultMode, Map<String, GateMode> actions) {
    public enum GateMode { MANUAL, AUTO_APPROVE, AUTO_REJECT }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GatePolicy {
        defaultMode = defaultMode == null ? GateMode.MANUAL : defaultMode;
        actions = actions == null ? Map.of() : Map.copyOf(actions);
    }
    public static GatePolicy manual() { return new GatePolicy(GateMode.MANUAL, Map.of()); }
    public GateMode modeFor(String action) { return actions.getOrDefault(action, defaultMode); }
    public static GatePolicy parse(String json) {
        if (json == null || json.isBlank()) return manual();
        try { return MAPPER.readValue(json, GatePolicy.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid gate policy: " + e.getOriginalMessage(), e); }
    }
    public String toJson() { try { return MAPPER.writeValueAsString(this); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
}
```

- [ ] **Step 4: `SandboxApiException` / `SandboxApiErrors`** — same shape as `DataApiException` / `DataApiErrors` (`{code, message, ...extra}` body via a `@RestControllerAdvice`), plus `MethodArgumentNotValidException` is left to the app default. Statics:

```java
public static SandboxApiException quota(String limit, Object used, Object max) {
    return new SandboxApiException(HttpStatus.TOO_MANY_REQUESTS, "SANDBOX_QUOTA_EXCEEDED",
            "Sandbox limit reached: " + limit, Map.of("limit", limit, "used", used, "max", max));
}
public static SandboxApiException notFound(String what) {
    return new SandboxApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
}
```
`SandboxApiErrors` also maps `IllegalArgumentException` thrown by `GatePolicy.parse` **only within the sandbox package's controllers** — simpler: `SandboxProfileService` catches nothing; the controller binds `GatePolicy` via Jackson, so an unknown mode is a 400 from Spring's message-conversion error. Test 400 accordingly.

- [ ] **Step 5: `FakeSecrets`**

```java
@Service
public class FakeSecrets {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};
    private final AesGcmCipher cipher;
    public FakeSecrets(AesGcmCipher cipher) { this.cipher = cipher; }

    public void store(SandboxProfile profile, Map<String, String> secrets) {
        if (secrets == null || secrets.isEmpty()) { profile.setFakeSecretsEncrypted(null); profile.setFakeSecretsNonce(null); return; }
        var enc = cipher.encrypt(json(secrets).getBytes(StandardCharsets.UTF_8));
        profile.setFakeSecretsEncrypted(enc.ciphertext()); profile.setFakeSecretsNonce(enc.nonce());
    }
    public Map<String, String> reveal(SandboxProfile profile) {
        if (profile.getFakeSecretsEncrypted() == null) return Map.of();
        try { return MAPPER.readValue(new String(cipher.decrypt(profile.getFakeSecretsEncrypted(), profile.getFakeSecretsNonce()), StandardCharsets.UTF_8), MAP); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public List<String> names(SandboxProfile profile) { return reveal(profile).keySet().stream().sorted().toList(); }
    private static String json(Map<String, String> m) { try { return MAPPER.writeValueAsString(m); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
}
```

- [ ] **Step 6: `SandboxProfileService`.** Validation constants: name 1–100 chars; `spendCap` > 0 and ≤ 999,999,999.99 if present; fake-secret names match `[A-Za-z0-9_]{1,64}`, ≤ 50 entries, values ≤ 4096 chars; `input` (when present) must be a JSON object ≤ 64 KB; `llmMode == REPLAY` requires `replayRunId`. Validation failures are `SandboxApiException(BAD_REQUEST, "VALIDATION_FAILED", msg)`. Ownership: `agents.findById(agentId).filter(a -> a.getDeveloperOrg().getId().equals(orgId)).orElseThrow(() -> SandboxApiException.notFound("Agent"))`; a profile is fetched with `profiles.findById(id).filter(p -> p.getAgentId().equals(agentId))` else 404. Name uniqueness → `409 PROFILE_NAME_TAKEN` (check `existsByAgentIdAndName` first, and catch `DataIntegrityViolationException` on save for the race). `createInline` names the row `"inline-" + first 8 hex of a random UUID` and sets `inline = true`. `update` replaces every field, except `fakeSecrets == null` keeps the stored secrets. `delete` also deletes `dataset_binding` rows for `(SANDBOX_PROFILE, profileId)` (inject `DatasetBindingRepository`; `findByOwnerTypeAndOwnerId` then `deleteAll`) in the same `@Transactional`. `ProfileView` record: `(UUID id, UUID agentId, String name, JsonNode input, GatePolicy gatePolicy, RunLlmMode llmMode, UUID replayRunId, JsonNode llmMock, BigDecimal spendCap, List<String> fakeSecretNames, List<BindingView> bindings, Instant createdAt, Instant updatedAt)`; `BindingView(String resource, UUID datasetId, String displayName)` built from `bindings.findByOwnerTypeAndOwnerId(SANDBOX_PROFILE, id)` + `DatasetRepository`. JSON columns are stored as strings (`input.toString()`), read back with `ObjectMapper.readTree`.

- [ ] **Step 7: `SandboxProfileController`** — `@RestController @RequestMapping("/api/v1/agents/{agentId}/sandbox-profiles") @PreAuthorize("hasRole('DEVELOPER')")`; `Caller.current()` gives the org; POST is `@ResponseStatus(CREATED)`, DELETE is 204; request bodies are `@Valid @RequestBody ProfileInput`.

- [ ] **Step 8: `RunLauncher`.** Inject `FakeSecrets` and `SandboxProfileRepository`. Replace the connection-secret loop and the BYOK block:

```java
Map<String, String> fake = Map.of();
if (connection != null) {
    for (Secret secret : secretRepository.findByConnectionId(connection.getId())) { ...unchanged... }
} else if (run.getSandboxProfileId() != null) {
    fake = profiles.findById(run.getSandboxProfileId()).map(fakeSecrets::reveal).orElse(Map.of());
    fake.forEach((name, value) -> envVars.put("AGENT_SECRET_" + name, value));
}
if (manifest.llm() != null && manifest.llm().mode() == LlmMode.BYOK_DIRECT) {
    LlmVendor vendor = manifest.llm().provider();
    String name = "AGENT_SECRET_" + vendor.name() + "_API_KEY";
    if (connection != null || !fake.containsKey(vendor.name() + "_API_KEY")) {
        UUID developerOrgId = version.getAgent().getDeveloperOrg().getId();
        envVars.put(name, byokKeys.reveal(developerOrgId, vendor).orElseThrow(() -> new LlmKeyMissingException(vendor)));
    }
}
```

- [ ] **Step 9: Run** `mvn test -Dtest='GatePolicyTest,SandboxProfileApiTest,SandboxLauncherSecretsTest,ByokDirectLaunchTest,LegacyEnvVarsTest,RunLaunchFromBuildTest'` → PASS.

- [ ] **Step 10: Commit** — `git commit -m "feat(sandbox): sandbox profiles, gate policy, write-only fake secrets"`.

---

### Task 4: Quotas: limits, computed usage, checks, quota endpoint

**Files:**
- Create: `sandbox/SandboxQuotaProperties.java`, `SandboxQuotaService.java`, `DeveloperQuotaController.java`
- Modify: `execution/RunRepository.java`, `data/DatasetRepository.java`, `build/TemplateBuildRepository.java`, `llm/LlmCallRepository.java`, `build/BundleService.java`, `application.yml`
- Test: `sandbox/SandboxQuotaServiceTest.java`, `sandbox/SandboxQuotaEndpointTest.java`, `sandbox/SandboxBuildQuotaTest.java`

**Interfaces:**
- Produces:
  - `SandboxQuotaProperties.Limits(long runSeconds, int concurrentRuns, BigDecimal llmSpend, long fixtureBytes, int buildsPerDay)`; `Limits forOrg(Org)`; `unverified()`, `verified()`.
  - `SandboxQuotaService`: `void checkStart(UUID orgId)` (caller holds the org row lock); `void checkFixtureStorage(UUID orgId, long incomingBytes)`; `void checkBuild(UUID orgId)`; `boolean llmAllows(UUID orgId, BigDecimal estimatedMaxCost)`; `QuotaView view(UUID orgId)`.
  - `record Entry(BigDecimal used, BigDecimal limit, BigDecimal verifiedLimit)`; `record QuotaView(Entry runSeconds, Entry concurrentRuns, Entry llmSpend, Entry fixtureBytes, Entry buildsToday)`.
  - `GET /api/v1/developer/quota` (DEVELOPER) returns `QuotaView`.
  - Repository methods: `RunRepository.countByModeAndInitiatedByOrgIdAndStatusIn(RunMode, UUID, Collection<RunStatus>)`, `RunRepository.sandboxRunSecondsSince(UUID orgId, Instant since)` (BigDecimal), `DatasetRepository.usedBytesByPurpose(UUID orgId, DatasetPurpose)`, `TemplateBuildRepository.countQueuedSince(UUID orgId, Instant since)`, `LlmCallRepository.sandboxSpendSince(UUID orgId, Instant since)` (BigDecimal).

- [ ] **Step 1: Failing tests.**

`SandboxQuotaServiceTest extends AbstractStorageIntegrationTest`:
- `unverifiedOrgGetsTheUnverifiedLimitsAndVerifiedGetsTheOthers`: `props.forOrg(org)` with `developerVerifiedAt == null` → `(7200, 1, 200, 524288000, 20)`; after `fx.verify(org)` → `(60000, 5, 2000, 5368709120, 100)`.
- `concurrencyCountsNonTerminalSandboxRunsIncludingPendingBuild`: unverified org, one `PENDING_BUILD` sandbox run → `checkStart` throws `SandboxApiException` with `code=SANDBOX_QUOTA_EXCEEDED`, `extra` `limit=concurrentRuns, used=1, max=1`; a COMPLETED run does not count; after `verify`, four open runs still pass, the fifth fails.
- `runSecondsSumThisMonthOnlyAndExcludePendingBuild`: insert (via JDBC `UPDATE run SET started_at=?, completed_at=?`) a COMPLETED run of 7200 s this month → `checkStart` fails with `limit=runSeconds`; a run of 7200 s from the previous UTC month does not count; a `PENDING_BUILD` run's seconds do not count.
- `otherOrgsAndProductionRunsAreIgnored`.

`SandboxQuotaEndpointTest`: `GET /api/v1/developer/quota` as the developer returns the five entries with `limit`/`verifiedLimit` and correct `used` after a fixture-free setup (one open run → `concurrentRuns.used = 1`); a BUYER_ADMIN gets 403.

`SandboxBuildQuotaTest` (with `@TestPropertySource(properties = "agentstore.sandbox.quota.unverified.builds-per-day=2")`): insert two `template_build` rows queued today for the org's agent (use `TemplateBuild` + `TemplateBuildRepository` like `ExecutionFixtures.liveBuiltTierA`); `checkBuild` fails with `limit=buildsToday`. Then drive the real path: finalize a valid bundle through `BundleService.finalizeBundle` (copy the setup of `BundleApiTest`/`BundleServiceTest` in `build/` for producing an UPLOADED-ready zip) and assert the third finalize is 429 and the bundle stays `UPLOADING` (a retry after the day rolls over would succeed). A build queued yesterday (UTC) does not count.

- [ ] **Step 2: Run** → FAIL (compile).

- [ ] **Step 3: `SandboxQuotaProperties`**

```java
@Component
public class SandboxQuotaProperties {
    public record Limits(long runSeconds, int concurrentRuns, BigDecimal llmSpend, long fixtureBytes, int buildsPerDay) {}
    private final Limits unverified, verified;
    public SandboxQuotaProperties(
            @Value("${agentstore.sandbox.quota.unverified.run-seconds:7200}") long uRunSeconds,
            @Value("${agentstore.sandbox.quota.unverified.concurrent-runs:1}") int uConcurrent,
            @Value("${agentstore.sandbox.quota.unverified.llm-spend:200}") BigDecimal uLlm,
            @Value("${agentstore.sandbox.quota.unverified.fixture-bytes:524288000}") long uFixture,
            @Value("${agentstore.sandbox.quota.unverified.builds-per-day:20}") int uBuilds,
            @Value("${agentstore.sandbox.quota.verified.run-seconds:60000}") long vRunSeconds,
            @Value("${agentstore.sandbox.quota.verified.concurrent-runs:5}") int vConcurrent,
            @Value("${agentstore.sandbox.quota.verified.llm-spend:2000}") BigDecimal vLlm,
            @Value("${agentstore.sandbox.quota.verified.fixture-bytes:5368709120}") long vFixture,
            @Value("${agentstore.sandbox.quota.verified.builds-per-day:100}") int vBuilds) {
        unverified = new Limits(uRunSeconds, uConcurrent, uLlm, uFixture, uBuilds);
        verified = new Limits(vRunSeconds, vConcurrent, vLlm, vFixture, vBuilds);
    }
    public Limits unverified() { return unverified; }
    public Limits verified() { return verified; }
    public Limits forOrg(Org org) { return org.getDeveloperVerifiedAt() != null ? verified : unverified; }
}
```
Add the same ten keys with these values to `application.yml` under `agentstore.sandbox.quota`.

- [ ] **Step 4: Repository queries.**

```java
// RunRepository
long countByModeAndInitiatedByOrgIdAndStatusIn(RunMode mode, UUID orgId, Collection<RunStatus> statuses);

@Query(value = "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(completed_at, now()) - started_at))), 0) FROM run "
        + "WHERE mode = 'SANDBOX' AND initiated_by_org_id = :orgId AND started_at >= :since AND status <> 'PENDING_BUILD'",
        nativeQuery = true)
BigDecimal sandboxRunSecondsSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

// DatasetRepository
@Query("select coalesce(sum(d.sizeBytes), 0) from Dataset d where d.ownerOrgId = :orgId and d.purpose = :purpose and d.purgedAt is null")
long usedBytesByPurpose(@Param("orgId") UUID orgId, @Param("purpose") DatasetPurpose purpose);

// TemplateBuildRepository
@Query("select count(b) from TemplateBuild b where b.agentVersion.agent.developerOrg.id = :orgId and b.queuedAt >= :since")
long countQueuedSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

// LlmCallRepository
@Query(value = "SELECT COALESCE(SUM(c.cost), 0) FROM llm_call c JOIN run r ON r.id = c.run_id "
        + "WHERE r.mode = 'SANDBOX' AND r.initiated_by_org_id = :orgId AND c.created_at >= :since", nativeQuery = true)
BigDecimal sandboxSpendSince(@Param("orgId") UUID orgId, @Param("since") Instant since);
```

- [ ] **Step 5: `SandboxQuotaService`**

```java
@Service
public class SandboxQuotaService {
    static final List<RunStatus> OPEN = List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);
    // deps: OrgRepository orgs, RunRepository runs, DatasetRepository datasets, TemplateBuildRepository builds, LlmCallRepository llmCalls, SandboxQuotaProperties props

    static Instant monthStart() { return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant(); }
    static Instant dayStart()   { return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant(); }

    /** Caller holds the org row lock (SandboxRunService), so two triggers cannot both pass. */
    public void checkStart(UUID orgId) {
        Limits limits = limits(orgId);
        long open = runs.countByModeAndInitiatedByOrgIdAndStatusIn(RunMode.SANDBOX, orgId, OPEN);
        if (open >= limits.concurrentRuns()) throw SandboxApiException.quota("concurrentRuns", open, limits.concurrentRuns());
        BigDecimal seconds = runs.sandboxRunSecondsSince(orgId, monthStart());
        if (seconds.compareTo(BigDecimal.valueOf(limits.runSeconds())) >= 0)
            throw SandboxApiException.quota("runSeconds", seconds.setScale(0, RoundingMode.DOWN), limits.runSeconds());
    }
    public void checkFixtureStorage(UUID orgId, long incoming) {
        Limits limits = limits(orgId);
        long used = datasets.usedBytesByPurpose(orgId, DatasetPurpose.FIXTURE);
        if (used + incoming > limits.fixtureBytes()) throw SandboxApiException.quota("fixtureBytes", used, limits.fixtureBytes());
    }
    public void checkBuild(UUID orgId) {
        Limits limits = limits(orgId);
        long used = builds.countQueuedSince(orgId, dayStart());
        if (used >= limits.buildsPerDay()) throw SandboxApiException.quota("buildsToday", used, limits.buildsPerDay());
    }
    public boolean llmAllows(UUID orgId, BigDecimal estimatedMaxCost) {
        return llmCalls.sandboxSpendSince(orgId, monthStart()).add(estimatedMaxCost).compareTo(limits(orgId).llmSpend()) <= 0;
    }
    public QuotaView view(UUID orgId) { ...one Entry per limit with used from the same queries, limit and verifiedLimit from props... }
    private Limits limits(UUID orgId) { return props.forOrg(orgs.findById(orgId).orElseThrow()); }
}
```

- [ ] **Step 6: `DeveloperQuotaController`** — `@GetMapping("/api/v1/developer/quota") @PreAuthorize("hasRole('DEVELOPER')")` returns `quota.view(Caller.current().orgId())`.

- [ ] **Step 7: `BundleService.record`** — inject `SandboxQuotaService`; in `record(...)`, immediately after the `bundle.getStatus() != UPLOADING` guard (so a retried finalize of an accepted bundle still returns its existing result), call `orgs.findByIdForUpdate(...)`-free `quota.checkBuild(bundle.getAgent().getDeveloperOrg().getId());` (lock order stays agent then bundle; the count needs no extra lock beyond the agent row it already holds for one agent, and a per-org race across agents can overshoot by at most one build, which is acceptable for a daily soft limit).

- [ ] **Step 8: Run** `mvn test -Dtest='SandboxQuota*Test,SandboxBuildQuotaTest,Bundle*Test,EndToEndTierBTest'` → PASS.

- [ ] **Step 9: Commit** — `git commit -m "feat(sandbox): computed developer quotas and quota endpoint"`.

---

### Task 5: Fixtures and profile bindings

**Files:**
- Modify: `data/DatasetService.java`, `data/DatasetResponse.java`, `data/BindingResolver.java`, `catalog/AgentVersionRepository.java`
- Create: `data/DeveloperFixtureController.java`, `data/SandboxBindingService.java`, `data/SandboxBindingController.java`
- Test: `sandbox/SandboxFixtureApiTest.java`, `sandbox/SandboxBindingApiTest.java`, `sandbox/SandboxBindingResolverTest.java`

**Interfaces:**
- Consumes: `SandboxQuotaService.checkFixtureStorage(UUID, long)`; `SandboxProfileRepository`.
- Produces:
  - `DatasetService.upload(UUID orgId, UUID userId, String filename, InputStream content, String displayName, String sourceLabel, DatasetPurpose purpose, UploadQuota quota)` where `interface UploadQuota { void check(UUID orgId, long originalBytes); }` (`null` = the buyer's storage-limit rule). The 6-argument `upload` delegates with `BUYER_DATA` and `null`.
  - `DatasetService.listFixtures(UUID orgId)` → `List<DatasetResponse>`; `deleteFixture(UUID orgId, UUID datasetId)`.
  - `BindingResolver.resolveForProfile(UUID profileId, Manifest manifest, RunMode mode)` → `List<ResolvedPin>` over **all** the manifest's READ resources.
  - `SandboxBindingService`: `BindingService.BindingResponse bind(UUID orgId, UUID userId, UUID profileId, String resource, UUID datasetId)`, `void unbind(UUID orgId, UUID profileId, String resource)`, `List<BindingService.BindingResponse> list(UUID orgId, UUID profileId)`.
  - Endpoints: `POST/GET /api/v1/developer/fixtures`, `DELETE /api/v1/developer/fixtures/{id}`, `PUT/DELETE /api/v1/sandbox-profiles/{profileId}/bindings/{resource}` (all `hasRole('DEVELOPER')`).

- [ ] **Step 1: Failing tests.**

`SandboxFixtureApiTest extends AbstractStorageIntegrationTest` (with `@TestPropertySource(properties = "agentstore.sandbox.quota.unverified.fixture-bytes=600")` and a second test class or `fx.verify(org)` for the roomy case; CSV from `DataFixtures.CSV` is about 90 bytes):
- upload a CSV → 200, then `datasets.findById` shows `purpose = FIXTURE`, `ownerOrgId` = developer org; `DatasetService.list(orgId, false)` (the buyer library query) does **not** return it.
- displayName omitted → defaults to the file name.
- a `.exe`-style/unsupported file → 415 `UNSUPPORTED_FORMAT`; nothing is stored (`datasets.count()` unchanged).
- the upload that would cross the 600-byte limit → 429 `SANDBOX_QUOTA_EXCEEDED` with `limit=fixtureBytes`; the row count is unchanged **and the S3 keys were removed** (assert `storage` has no object under the org's prefix afterwards, mirroring `DatasetUploadCapTest`).
- `GET` lists only the caller's fixtures; a BUYER_ADMIN gets 403; another developer gets an empty list.
- `DELETE` of own fixture → 204 (row `deletedAt` set, bindings gone); of another org's → 404; of a `BUYER_DATA` dataset of the same org via this endpoint → 404.

`SandboxBindingApiTest`:
- with `valid-data-agent.json`: bind a matching fixture CSV to `bank_statement` → 200 and `GET /agents/{id}/sandbox-profiles/{pid}` lists it under `bindings`; a CSV missing required columns → 422 `DATASET_CHECK_FAILED`; binding to a resource the manifest does not READ (`journal_entries`, a WRITE) → 422 `RESOURCE_NOT_BINDABLE`; a `BUYER_DATA` dataset or another org's fixture → 404; another developer's profile → 404; unbind → 204, then the profile shows no binding; the fixture itself is **not** scheduled for purge by unbinding (`purgeAfter` stays null).

`SandboxBindingResolverTest`: `resolveForProfile(profileId, manifest, SANDBOX)` returns a pin for each bound READ resource; with `valid-data-agent.json` and only `bank_statement` bound it throws `DataApiException` `MISSING_BINDING` naming `statement_pdf`; with the untyped `valid-tier-a.json` and nothing bound it returns an empty list; a bound dataset whose purpose was changed to `BUYER_DATA` is treated as unbound (`DataAccessRules`).

- [ ] **Step 2: Run** → FAIL (compile).

- [ ] **Step 3: `DatasetService`.** Add the overloaded `upload` (rename the existing body to it). The purpose goes on the row: `dataset.setPurpose(purpose)`. In the transaction, replace the storage check with:

```java
Org org = orgs.findByIdForUpdate(orgId).orElseThrow();
if (quota != null) {
    quota.check(orgId, size);
} else if (datasets.usedBytes(orgId) + incoming > org.getStorageLimitBytes()) {
    throw new DataApiException(HttpStatus.PAYLOAD_TOO_LARGE, "QUOTA_EXCEEDED", "This upload would take the workspace over its storage limit");
}
return datasets.save(dataset);
```
Extract the body of `list(orgId, withUsage)` into `private List<DatasetResponse> list(UUID orgId, DatasetPurpose purpose, boolean withUsage)`; `list(orgId, withUsage)` delegates with `BUYER_DATA` (unchanged behaviour); `listFixtures(orgId)` delegates with `FIXTURE, false`. `deleteFixture` is `delete` with an extra `d.getPurpose() == DatasetPurpose.FIXTURE` filter (extract a private `delete(orgId, datasetId, Predicate<Dataset>)`; the public `delete` keeps no purpose filter). Make `DatasetResponse.from` `public`.

- [ ] **Step 4: `DeveloperFixtureController`** (package `data`, `@RequestMapping("/api/v1/developer/fixtures") @PreAuthorize("hasRole('DEVELOPER')")`): POST multipart `file`, optional `displayName` (default `file.getOriginalFilename()`), `sourceLabel`; calls `datasetService.upload(claims.orgId(), claims.userId(), name, in, displayName, sourceLabel, DatasetPurpose.FIXTURE, quotas::checkFixtureStorage)`. Inject `SandboxQuotaService`. GET → `listFixtures`; DELETE → 204 `deleteFixture`.

- [ ] **Step 5: `BindingResolver.resolveForProfile`.** Refactor the existing `resolve(Connection, Manifest, RunMode)` so its resource-collection loop feeds a private `resolveResources(BindingOwnerType ownerType, UUID ownerId, Set<String> resources, Manifest, RunMode)` holding the dataset lookup and the `MISSING_BINDING` throw; `resolve` passes `CONNECTION`, the connection id and the granted READ resources; `resolveForProfile` passes `SANDBOX_PROFILE`, the profile id and **all** READ resources of the manifest:

```java
@Transactional(readOnly = true)
public List<ResolvedPin> resolveForProfile(UUID profileId, Manifest manifest, RunMode mode) {
    Set<String> resources = new LinkedHashSet<>();
    for (Scope scope : manifest.scopes()) {
        if (scope.type() == ScopeType.READ) resources.add(scope.resource());
    }
    return resolveResources(BindingOwnerType.SANDBOX_PROFILE, profileId, resources, manifest, mode);
}
```
Behaviour of `resolve(Connection…)` must stay byte-identical (all `Binding*`/`RunPinning`/`ModeGuard` tests are the proof).

- [ ] **Step 6: `SandboxBindingService`** (package `data`, mirrors `BindingService.bind` but keyed on a profile): load the profile (`SandboxProfileRepository.findById`), its agent (`AgentRepository`) and require `agent.developerOrg.id == orgId` else `DataApiException(NOT_FOUND)`; the dataset via `datasets.findByIdForUpdate` filtered to `ownerOrgId == orgId && deletedAt == null && purgedAt == null && purpose == FIXTURE` else 404; the manifest is that of the agent's newest version: add `Optional<AgentVersion> findFirstByAgentIdOrderByCreatedAtDesc(UUID agentId)` to `AgentVersionRepository` (none → `409 NO_AGENT_VERSION`); `ResourceLookup.requireReadable(manifest, resource)` gives `RESOURCE_NOT_BINDABLE`; `checks.check(dataset, decl)` gives `DATASET_CHECK_FAILED` (422, `checkResult` extra, same as `BindingService`); upsert a `DatasetBinding(SANDBOX_PROFILE, profileId, resource, datasetId, checkJson, userId)`; **no** `release`/`purgeAfter` logic. `unbind` deletes the binding row (404 if absent).

- [ ] **Step 7: `SandboxBindingController`** — `@RequestMapping("/api/v1/sandbox-profiles/{profileId}/bindings")`, `@PreAuthorize("hasRole('DEVELOPER')")`; `PUT /{resource}` with `BindingController.BindRequest`, `DELETE /{resource}` → 204.

- [ ] **Step 8: Run** `mvn test -Dtest='Sandbox*Test,Binding*Test,Dataset*Test,ModeGuardTest,RunPinningTest,DataPlaneEndToEndTest'` → PASS.

- [ ] **Step 9: Commit** — `git commit -m "feat(sandbox): developer fixtures and profile bindings"`.

---

### Task 6: LLM spend quota in the gateway

**Files:**
- Modify: `llm/GatewayLlmBackend.java`
- Test: `sandbox/SandboxLlmQuotaTest.java` (extends `AbstractLlmIntegrationTest`)

**Interfaces:**
- Consumes: `SandboxQuotaService.llmAllows(UUID orgId, BigDecimal estimatedMaxCost)`.
- Produces: platform-key gateway calls of a SANDBOX run with `llmMode == null` are refused with the existing `402 BUDGET_EXCEEDED` when this month's spend plus the call's worst case would pass the org's limit.

- [ ] **Step 1: Failing test.** `SandboxLlmQuotaTest` builds a RUNNING sandbox run of `valid-llm-platform.json` exactly like `GatewayReplayTest` does (`fx.runningAgent(...)` then `fx.configureRun(runId, RunMode.SANDBOX, null, null, null)` and set `initiated_by_org_id` with `UPDATE run SET initiated_by_org_id = ?, connection_id = NULL WHERE id = ?`, a pattern the test can reuse from `SandboxFixtures`). Model `test-claude` costs 15 per Mtok output, so `maxTokens=1000` estimates 0.0150.
  - With `@TestPropertySource(properties = "agentstore.sandbox.quota.unverified.llm-spend=0.02")`: the first call (estimate 0.015) passes and its real cost (10 in / 5 out tokens → 0.000105) is recorded; a second call with `maxTokens=1000` would put spend + estimate at 0.030105 > 0.02 → 402 `BUDGET_EXCEEDED`, the stub provider is **not** called (`STUB.received()` unchanged), and an `llm_call` row with status `BUDGET_REJECTED` is written.
  - MOCK-mode sandbox run: never refused however small the limit (`llm_mode = MOCK` short-circuits before `callProvider`).
  - REPLAY-mode: same.
  - A PRODUCTION run is never affected by the quota (use `fx.runningAgent` unchanged with the same tiny limit → the call succeeds).
  - A BYOK-proxy sandbox run (`valid-llm-byok-proxy.json`) is not checked (source `BYOK_PROXY` has cost zero).
  - Verified org (`fx.verifyDeveloperOrg`-style `UPDATE org SET developer_verified_at`) gets the verified 2000 limit, so the same call passes.

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement.** Inject `SandboxQuotaService` into `GatewayLlmBackend`. In `callProvider`, after the key is resolved and before the token reservation:

```java
if (principal.mode() == RunMode.SANDBOX && source == LlmSource.PLATFORM) {
    BigDecimal worstCase = BigDecimal.valueOf(request.maxTokens()).multiply(model.getOutputPricePerMtok())
            .divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP);
    if (!quota.llmAllows(principal.orgId(), worstCase)) {
        LlmCall rejected = new LlmCall(UUID.randomUUID(), runId, model.getId(), source, LlmCallStatus.BUDGET_REJECTED, normalized.hash());
        rejected.setErrorCode("BUDGET_EXCEEDED");
        rejected.setLatencyMs(millisSince(started));
        callLog.record(principal, rejected);
        throw new RuntimeApiException(HttpStatus.PAYMENT_REQUIRED, "BUDGET_EXCEEDED",
                "This call would take the workspace over its monthly sandbox LLM allowance");
    }
}
```
(`principal.orgId()` is the initiating developer org for sandbox runs, Task 2.)

- [ ] **Step 4: Run** `mvn test -Dtest='SandboxLlmQuotaTest,Gateway*Test,LlmGatewayAcceptanceTest'` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(sandbox): enforce the monthly sandbox LLM allowance in the gateway"`.

---

### Task 7: Starting, listing, cancelling and rerunning sandbox runs

**Files:**
- Create: `sandbox/SandboxRunService.java`, `SandboxRunController.java`, `SandboxRunViews.java`
- Modify: `llm/LlmCallRepository.java`, `identity/ApiKeyScope.java`
- Test: `sandbox/SandboxRunStartTest.java`, `sandbox/SandboxRunApiTest.java`, `sandbox/SandboxReplayOwnershipTest.java`, `identity/…` none (new test `sandbox/SandboxApiKeyScopeTest.java`)

**Interfaces:**
- Consumes: Tasks 1–6 (`SandboxProfileService.createInline`, `SandboxBindingService.bind`, `BindingResolver.resolveForProfile`/`saveWithPins`, `SandboxQuotaService.checkStart`, `RebuildService`, `PendingBuildRuns`, `RunLauncher`).
- Produces:
  - `SandboxRunService.start(UUID orgId, UUID userId, UUID agentVersionId, UUID profileId, ProfileInput inline)` → `Run`; `rerun(UUID orgId, UUID userId, UUID runId)` → `Run`; `cancel(UUID orgId, UUID runId)`; `Run owned(UUID orgId, UUID runId)` (404 unless SANDBOX and initiated by `orgId`).
  - `LlmCallRepository.existsByRunIdAndStatusAndSourceNotAndRecordingExpiresAtIsNotNullAndRecordingPurgedAtIsNull(UUID, LlmCallStatus, LlmSource)`.
  - `SandboxRunSummary(UUID id, String displayId, UUID agentId, UUID agentVersionId, String versionLabel, String profileName, RunLlmMode llmMode, UUID replayOfRunId, RunStatus status, BigDecimal simulatedCharge, BigDecimal requestedCharge, BigDecimal costCap, long durationSeconds, BigDecimal aiCost, Instant startedAt, Instant completedAt)`; `SandboxRunDetail(@JsonUnwrapped SandboxRunSummary run, List<RunStepResponse> steps, PendingGate pendingGate)`; `PendingGate(UUID id, String action, String summary, Instant expiresAt)`.
  - Endpoints (DEVELOPER, owner-checked, 404 for anything else): `POST /api/v1/sandbox/runs` `{agentVersionId, profileId | inlineProfile}` → 201 summary; `GET /api/v1/sandbox/runs?agentId=` (newest first, max 100); `GET /api/v1/sandbox/runs/{id}` detail; `POST /{id}/cancel`; `POST /{id}/rerun`.
  - `ApiKeyScope` allows API keys on `/api/v1/sandbox/**`, `/api/v1/agents/*/sandbox-profiles/**`, `/api/v1/sandbox-profiles/**`, `/api/v1/developer/fixtures/**`, `GET /api/v1/developer/quota`.

- [ ] **Step 1: Failing tests.** Common setup: developer org + `valid-tier-a.json` version (untyped, so no bindings needed) unless stated; `FakeSandboxProvider` records spawns.

`SandboxRunStartTest extends AbstractStorageIntegrationTest` (service level, plus `@LocalServerPort` for the race):
- `startsARunWithNoConnectionAndNoMoney`: `start(org, user, versionId, profileId, null)` → `mode SANDBOX`, `connection null`, `initiatedByOrgId`, `sandboxProfileId`, `costCap = 500.00` (manifest `maxPerRun`), `status RUNNING`, sandbox created with `AGENTSTORE_MODE=SANDBOX`; the profile `spendCap = 40` gives `costCap = 40`; `usage_event`/`ledger_entry`/wallet counts unchanged.
- `aDraftVersionIsRunnable` (the fixture version is DRAFT) and an `IN_REVIEW`/`LIVE` one too.
- `foreignVersionIs404`: another developer's org → `SandboxApiException` 404.
- `versionWithNoImageIs409VersionNotBuilt`: `imageRef = null` → 409 `VERSION_NOT_BUILT`, no run row created.
- `aRebuildEntersPendingBuild`: use `ExecutionFixtures.liveBuiltTierA(..., "other-backend")` version transferred to a developer org (or build the equivalent rows in `SandboxFixtures`) so `RebuildService.needsRebuild` is true → run is `PENDING_BUILD`, `deadlineAt ≈ now + 25 min`, and one QUEUED `template_build` exists (mirror `RunJoinsInFlightRebuildTest`).
- `secondConcurrentRunIsRejectedForUnverifiedAndAllowedForVerified`: first start OK; second → 429 `SANDBOX_QUOTA_EXCEEDED` `limit=concurrentRuns`; after `fx.verify(org)` the second passes; the sixth (fifth open + 1) fails.
- `concurrentTriggersOnlyOnePasses` (**Review Focus 1**): 8 threads call `start` at once for an unverified org behind a `CountDownLatch`; exactly one returns a run, seven get the 429; exactly one sandbox run row exists.
- `missingFixtureBindingIs422`: `valid-data-agent.json` with no bindings → `DataApiException` `MISSING_BINDING`; with both READ resources bound to FIXTURE datasets → run created with two `run_dataset_pin` rows; a bound dataset from another org's fixture is not usable.
- `inlineProfileIsStoredHiddenAndRolledBackOnFailure` (**Review Focus 3**): `start(..., null, inline)` creates a profile row with `inline = true` named `inline-…`, not returned by profile listing; an inline profile whose `bindings` names another org's fixture → 404 and **no** run row and **no** new `sandbox_profile` row; a quota-rejected inline start leaves no profile row either.
- `profileOfAnotherAgentIs404`.
- `deletedProfileKeepsRunReadable` (**Review Focus 4**, service+API): delete the profile after a run used it → `GET /sandbox/runs/{id}` still 200 with `profileName = null`; `rerun` of it → 409 `PROFILE_DELETED`.

`SandboxReplayOwnershipTest extends AbstractLlmIntegrationTest` (**Review Focus 2**):
- helper records real calls into a source SANDBOX run of the same agent (pattern of `GatewayReplayTest.recordedSource`, but built with `SandboxFixtures` so `initiated_by_org_id` is set); a profile with `llmMode REPLAY, replayRunId = source` starts fine (run stores `llmMode REPLAY`, `replayRunId`).
- 403 `REPLAY_SOURCE_INVALID` for: another org's sandbox run; a sandbox run of another agent (same org); a PRODUCTION run (`fx.runningAgent` unchanged); a random UUID; 422 `REPLAY_SOURCE_INVALID` for a source with no recorded OK calls, and for a `MOCK`-only source.
- cross-version replay is allowed (source on version A, start on version B of the same agent).
- `rerun`: creates a new run on the **same version** with `llmMode REPLAY`, `replayRunId = source`, the same `sandboxProfileId`; goes through quota (429 while another run is open for an unverified org) and through the ownership check (rerun of a run whose recordings are all purged → 422).

`SandboxRunApiTest extends AbstractStorageIntegrationTest`:
- POST with `{agentVersionId, profileId}` → 201, body `displayId` matches `S-[0-9A-F]{8}`, `versionLabel` `1.0.0 draft`, `profileName`, `status RUNNING`; POST with `{agentVersionId, inlineProfile:{}}` works; both `profileId` and `inlineProfile` absent → 400; both present → 400.
- list filtered by `agentId`, newest first, only own org; another developer sees `[]` and gets 404 on detail/cancel/rerun; a BUYER_ADMIN gets 403; a **buyer's** `/api/v1/runs/mine` never contains it (Task 2 pin, re-asserted end-to-end).
- detail has `steps`, `costCap`, `simulatedCharge`, `requestedCharge`, `aiCost` (insert two `llm_call` rows with cost 0.5 and 0.25 → `aiCost = 0.75`), `durationSeconds`, and `pendingGate` only while `AWAITING_APPROVAL`.
- after driving `POST /runtime/v1/usage` with the run token taken from `fake.createdSpecs()` (`AGENTSTORE_RUN_TOKEN`), `simulatedCharge` and `requestedCharge` show in the list; with a 10.00 cap a 8×2.00 request shows `simulatedCharge 10.00`, `requestedCharge 16.00`, status FAILED.
- `cancel` → status `CANCELLED`, sandbox killed (`fake.wasKilled`), a `CANCELLED` step is recorded; cancelling a terminal run → 409; cancelling a `PENDING_BUILD` run works and `PendingBuildRuns.settle` later leaves it alone.
- **Fake secrets never leak** (**Review Focus 5**): a run started with `fakeSecrets: {"K":"topsecret-value"}`; none of list, detail, profile GET, or `steps` contains `topsecret-value`.

`SandboxApiKeyScopeTest` (plain JUnit against `ApiKeyScope.allows`, package-private → put the test in `com.agentstore.platform.identity`): allows `POST /api/v1/sandbox/runs`, `GET /api/v1/sandbox/runs/abc`, `POST /api/v1/sandbox/runs/abc/rerun`, `GET /api/v1/agents/x/sandbox-profiles`, `PUT /api/v1/sandbox-profiles/x/bindings/r`, `POST /api/v1/developer/fixtures`, `GET /api/v1/developer/quota`; still denies `POST /api/v1/agents/x/versions/y/submit`, `GET /api/v1/wallet`, `POST /api/v1/runs`, `DELETE /api/v1/developer/api-keys/x`.

- [ ] **Step 2: Run** → FAIL (compile).

- [ ] **Step 3: `SandboxRunService`** (constructor deps: `AgentVersionRepository versions`, `SandboxProfileRepository profiles`, `SandboxProfileService profileService`, `RunRepository runs`, `LlmCallRepository llmCalls`, `OrgRepository orgs`, `SandboxQuotaService quota`, `BindingResolver resolver`, `ManifestParser parser`, `RebuildService rebuild`, `PendingBuildRuns pendingBuildRuns`, `RunLauncher launcher`, `RunLifecycleService lifecycle`, `RunTokenService tokens`, `PlatformTransactionManager` → `TransactionTemplate tx`).

```java
public record LlmOverride(RunLlmMode mode, UUID replayRunId, String mockJson) {}

public Run start(UUID orgId, UUID userId, UUID agentVersionId, UUID profileId, ProfileInput inline) {
    return begin(orgId, userId, ownedVersion(orgId, agentVersionId), profileId, inline, null);
}

public Run rerun(UUID orgId, UUID userId, UUID runId) {
    Run source = owned(orgId, runId);
    if (source.getSandboxProfileId() == null) {
        throw new SandboxApiException(HttpStatus.CONFLICT, "PROFILE_DELETED", "The profile this run used no longer exists");
    }
    return begin(orgId, userId, source.getAgentVersion(), source.getSandboxProfileId(), null,
            new LlmOverride(RunLlmMode.REPLAY, runId, null));
}

private Run begin(UUID orgId, UUID userId, AgentVersion version, UUID profileId, ProfileInput inline, LlmOverride override) {
    if (version.getImageRef() == null || version.getImageRef().isBlank()) {
        throw new SandboxApiException(HttpStatus.CONFLICT, "VERSION_NOT_BUILT",
                "This version has no built image yet; push it and wait for the build to succeed");
    }
    boolean waitForRebuild = rebuild.needsRebuild(version);
    Run run = tx.execute(status -> create(orgId, userId, version, profileId, inline, override, waitForRebuild));
    if (waitForRebuild) {
        try {
            rebuild.queueRebuild(version);
        } catch (RuntimeException e) {
            lifecycle.terminate(run.getId(), RunStatus.PENDING_BUILD, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Could not queue a rebuild for this version"));
            throw e;
        }
        pendingBuildRuns.settle(version.getId(), null);   // the build we joined may already have finished
        return runs.findById(run.getId()).orElseThrow();
    }
    launcher.launch(run.getId());
    return runs.findById(run.getId()).orElseThrow();
}
```
`create(...)` runs inside the transaction, in this order (spec §3.1): `orgs.findByIdForUpdate(orgId)`; `quota.checkStart(orgId)`; resolve the profile (`profileId` → `profiles.findById(...).filter(p -> p.getAgentId().equals(agentId))` else 404, or `profileService.createInline(orgId, agentId, inline)` then, for each `inline.bindings()` entry, `sandboxBindings.bind(orgId, userId, profile.getId(), resource, datasetId)`); `pins = resolver.resolveForProfile(profile.getId(), manifest, RunMode.SANDBOX)`; effective LLM settings (override else profile); `checkReplaySource(orgId, agentId, mode, replayId)`; build the run:

```java
Instant now = Instant.now();
Run pending = new Run(null, null, version, waitForRebuild ? RunStatus.PENDING_BUILD : RunStatus.PENDING,
        profile.getSpendCap() != null ? profile.getSpendCap() : manifest.costEnvelope().maxPerRun(),
        BigDecimal.ZERO, null, RunTokenService.hash(tokens.generate()));   // placeholder hash; the launcher issues the real token
pending.setMode(RunMode.SANDBOX);
pending.setInitiatedByOrgId(orgId);
pending.setSandboxProfileId(profile.getId());
pending.setInputJson(profile.getInputJson());
pending.setLlmMode(llmMode); pending.setReplayRunId(replayId); pending.setLlmMockJson(mock);
pending.setStartedAt(now);
pending.setDeadlineAt(waitForRebuild ? now.plus(RunController.PENDING_BUILD_TIMEOUT) : now.plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS));
return resolver.saveWithPins(pending, pins);
```
`checkReplaySource`: applies only when `mode == REPLAY`. `replayId == null` → 422; `src = runs.findById(replayId)`; a null/`mode != SANDBOX`/foreign-org/other-agent source → `403 REPLAY_SOURCE_INVALID` (one message for all, so existence does not leak); then `llmCalls.existsByRunIdAndStatusAndSourceNotAndRecordingExpiresAtIsNotNullAndRecordingPurgedAtIsNull(replayId, LlmCallStatus.OK, LlmSource.MOCK)` false → `422 REPLAY_SOURCE_INVALID` "no recorded calls". `ownedVersion`/`owned` return 404 via `SandboxApiException.notFound`. `cancel`:

```java
public void cancel(UUID orgId, UUID runId) {
    owned(orgId, runId);
    if (!lifecycle.terminate(runId, RunStatus.CANCELLED, RunStepKind.CANCELLED, Map.of("summary", "Cancelled by the developer"))) {
        throw new SandboxApiException(HttpStatus.CONFLICT, "RUN_ALREADY_ENDED", "The run has already ended");
    }
}
```
(`terminate(runId, terminalStatus, stepKind, detail)` refuses an already-terminal run by returning false; `finish` kills the sandbox.)

- [ ] **Step 4: `LlmCallRepository`** add the `exists…` derived query named in the Interfaces block.

- [ ] **Step 5: `SandboxRunViews`** (`@Component`, read-only): builds `SandboxRunSummary`/`SandboxRunDetail`. `displayId = "S-" + run.getId().toString().substring(0, 8).toUpperCase()`; `versionLabel = versionNumber + " " + status.name().toLowerCase().replace('_', ' ')`; `profileName = profiles.findById(id).map(SandboxProfile::getName).orElse(null)` (null for an inline profile too? no: inline rows exist, so the name is `inline-…`; only a deleted profile gives null); `aiCost = llmCalls.sumCostByRunId(runId)` (add `@Query("select coalesce(sum(c.cost), 0) from LlmCall c where c.runId = :runId") BigDecimal sumCostByRunId(...)` to `LlmCallRepository`); `durationSeconds = Duration.between(startedAt, completedAt != null ? completedAt : Instant.now()).getSeconds()` (0 when `startedAt` is null); `pendingGate` from `GateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, GateStatus.PENDING)` only when the run is `AWAITING_APPROVAL`; steps from `RunStepRepository.findByRunIdOrderByCreatedAtAsc` mapped by the now-public `RunStepResponse.from`.

- [ ] **Step 6: `SandboxRunController`** — `@RequestMapping("/api/v1/sandbox/runs") @PreAuthorize("hasRole('DEVELOPER')")`. Request `StartRequest(@NotNull UUID agentVersionId, UUID profileId, ProfileInput inlineProfile)`; exactly one of `profileId` / `inlineProfile` is required (else 400 `VALIDATION_FAILED`). List: `runs.findByModeAndInitiatedByOrgIdOrderByStartedAtDesc(RunMode.SANDBOX, orgId, PageRequest.of(0, 100))`, or with `agentId` `findByModeAndInitiatedByOrgIdAndAgentVersionAgentIdOrderByStartedAtDesc(...)` (add both to `RunRepository`). Detail/cancel/rerun go through `service.owned(...)`.

- [ ] **Step 7: `ApiKeyScope`** add to `ALLOWED`:

```java
new Rule(null, "/api/v1/sandbox/**"),
new Rule(null, "/api/v1/agents/*/sandbox-profiles"),
new Rule(null, "/api/v1/agents/*/sandbox-profiles/**"),
new Rule(null, "/api/v1/sandbox-profiles/**"),
new Rule(null, "/api/v1/developer/fixtures"),
new Rule(null, "/api/v1/developer/fixtures/**"),
new Rule("GET", "/api/v1/developer/quota"),
```

- [ ] **Step 8: Run** `mvn test -Dtest='Sandbox*Test,PendingBuildRunsTest,RunJoinsInFlightRebuildTest,ApiKey*Test'` → PASS.

- [ ] **Step 9: Commit** — `git commit -m "feat(sandbox): start, list, cancel and rerun sandbox runs with replay ownership check"`.

---

### Task 8: Gate auto-policy and developer decisions

**Files:**
- Create: `sandbox/SandboxGatePolicy.java`
- Modify: `runtime/RuntimeGateController.java`, `sandbox/SandboxRunController.java`
- Test: `sandbox/SandboxGateTest.java`

**Interfaces:**
- Consumes: `GateService.open/decide/await`, `GatePolicy`, `SandboxProfile`.
- Produces: `SandboxGatePolicy.apply(UUID runId, GateRequest gate)` → the current `GateRequest` (decided when the policy is automatic). `POST /api/v1/sandbox/runs/{id}/gates/{gateId}/decision` `{approve, note}`.

- [ ] **Step 1: Failing tests.** `SandboxGateTest extends AbstractStorageIntegrationTest`, using `valid-runtime-agent.json` (gate `post_journal_entries`, WRITE `journal_entries`); the agent side is exercised over HTTP with the run token from `FakeSandboxProvider` (`AGENTSTORE_RUN_TOKEN`), like `RuntimeGateEndpointsTest`:
- `AUTO_APPROVE` default: `POST /runtime/v1/gates {action:"post_journal_entries", summary:"x", payload:{}}` → 201 body `status APPROVED`; run back to RUNNING; the gate row has `decidedByUserId null`, note `auto policy`; a `GATE_DECIDED` step is recorded.
- `AUTO_REJECT`: 201 with `status REJECTED`; run `CANCELLED`; a following `POST /runtime/v1/usage` gets 409 `RUN_TERMINATED`.
- per-action override wins over the default (`default: AUTO_REJECT`, `actions: {post_journal_entries: AUTO_APPROVE}` → approved; and the reverse).
- `MANUAL`: response `PENDING`, run `AWAITING_APPROVAL`, detail shows `pendingGate.id`; the owner `POST …/gates/{gateId}/decision {approve:true, note:"ok"}` → 200, run RUNNING; a foreign developer → 404, a buyer → 403; deciding a decided gate → 409; a gate of a **different run** with this run's id path → 404 (`GateService.decide` checks `findByIdAndRunId`).
- expiry unchanged: with `RunReaper.reapAt(now + 6 min)` a MANUAL gate (timeout 5 min) expires the run (`EXPIRED`).
- a PRODUCTION run never consults a policy: a production run whose id happens to have no profile behaves as before (`RuntimeGateEndpointsTest` stays green) and `SandboxGatePolicy` is not invoked (spy or assert gate stays PENDING).

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: `SandboxGatePolicy`**

```java
@Component
public class SandboxGatePolicy {
    private final RunRepository runs;
    private final SandboxProfileRepository profiles;
    private final GateService gates;
    // ctor

    /** Applies the run's profile policy to a freshly opened gate; returns the gate as it stands afterwards. */
    public GateRequest apply(UUID runId, GateRequest gate) {
        GateMode mode = runs.findById(runId).map(Run::getSandboxProfileId)
                .flatMap(profiles::findById)
                .map(p -> GatePolicy.parse(p.getGatePolicy()).modeFor(gate.getAction()))
                .orElse(GateMode.MANUAL);
        if (mode == GateMode.MANUAL) {
            return gate;
        }
        gates.decide(runId, gate.getId(), mode == GateMode.AUTO_APPROVE, null, "auto policy");
        return gates.await(runId, gate.getId(), Duration.ZERO);
    }
}
```

- [ ] **Step 4: `RuntimeGateController.create`** — inject `SandboxGatePolicy`; after `open(...)`:

```java
if (principal.mode() == RunMode.SANDBOX) {
    gate = sandboxGatePolicy.apply(principal.runId(), gate);
}
return new GateCreatedResponse(gate.getId(), gate.getStatus(), gate.getExpiresAt());
```

- [ ] **Step 5: Decision endpoint** in `SandboxRunController`:

```java
public record GateDecision(boolean approve, @Size(max = 1000) String note) {}

@PostMapping("/{id}/gates/{gateId}/decision")
public SandboxRunSummary decide(@PathVariable UUID id, @PathVariable UUID gateId, @Valid @RequestBody GateDecision body) {
    var claims = Caller.current();
    Run run = service.owned(claims.orgId(), id);
    gateService.decide(run.getId(), gateId, body.approve(), claims.userId(), body.note());
    return views.summary(runs.findById(id).orElseThrow());
}
```

- [ ] **Step 6: Run** `mvn test -Dtest='SandboxGateTest,RuntimeGateEndpointsTest,GateServiceTest,ApprovalGateTest,GateDecisionEndpointTest,RunReaperTest'` → PASS.

- [ ] **Step 7: Commit** — `git commit -m "feat(sandbox): per-profile gate auto-policy and developer gate decisions"`.

---

### Task 9: CLI `sandbox run --follow` and `sandbox logs`

**Files:**
- Create: `cli/src/commands/sandbox.ts`, `cli/test/sandbox.test.ts`
- Modify: `cli/src/cli.ts`, `cli/README.md`

**Interfaces:**
- Consumes: `ApiClient.get/post`, `requireCredentials`, `readProject`, `Context` (`ask`, `sleep`, `now`, `out`, `err`).
- Produces: `agentstore sandbox run [--version v] [--profile name] [--follow]` and `agentstore sandbox logs <runId>`; exports `sandboxRun(ctx, opts)`, `sandboxLogs(ctx, runId)`, `POLL_INTERVAL_MS`.

- [ ] **Step 1: Write failing tests** (`cli/test/sandbox.test.ts`, using `startServer`, `makeContext`, `writeFiles`, `AGENT_ID` from `helpers.ts`). API shapes: `GET /api/v1/agents/mine` → `[{agentId, agentName, category, versions:[{id, versionNumber, status}]}]`; `GET /api/v1/agents/{id}/sandbox-profiles` → `[{id, name, ...}]`; `POST /api/v1/sandbox/runs` → `{id, displayId, status}`; `GET /api/v1/sandbox/runs/{id}` → `{id, displayId, status, simulatedCharge, requestedCharge, steps:[{kind, source, detail, createdAt}], pendingGate|null}`.
  - `run` without `--follow` posts `{agentVersionId, inlineProfile:{}}` for the highest `versionNumber` (numeric-segment compare: `1.10.0 > 1.9.0`), prints the display id and returns 0, and does not poll.
  - `--version 1.2.0` picks that version; an unknown version is a `CliError` listing the available ones (exit 2); `--profile smoke` resolves the profile id by name and posts `{agentVersionId, profileId}`; an unknown profile → error listing names.
  - `--follow` prints each step exactly once as `<kind>: <summary|message|compact json>` across polls (fake clock: `ctx.sleeps` all equal `POLL_INTERVAL_MS`), then a final line `Run S-XXXX COMPLETED (simulated charge 6.00, would have asked 6.00)`, exits 0 for COMPLETED and 1 for FAILED/CANCELLED/TIMED_OUT/EXPIRED.
  - a `pendingGate` prompts via `ctx.ask` (`answers: ["y"]`) → `POST /api/v1/sandbox/runs/{id}/gates/{gateId}/decision` with `{approve:true}`; answer `n` → `{approve:false}`; the same gate id is never prompted twice while it is still pending.
  - the API answering 429 `{code:"SANDBOX_QUOTA_EXCEEDED", message:"Sandbox limit reached: concurrentRuns", limit, used, max}` surfaces the message (existing `ApiClient.failure` already prints `message`) with exit 2.
  - `sandbox logs <runId>` prints every step once and returns 0; a missing id is a usage error (exit 2); a 404 gives a readable error.
  - `main(["sandbox"], ctx)` and `main(["sandbox","bogus"], ctx)` return 2 with usage; `main(["sandbox","run","--wat"], ctx)` returns 2.

- [ ] **Step 2: Run** `cd cli && npx vitest run test/sandbox.test.ts` → FAIL.

- [ ] **Step 3: `commands/sandbox.ts`.**

```ts
import { ApiClient } from "../api.js";
import { requireCredentials } from "../config.js";
import type { Context } from "../context.js";
import { CliError } from "../errors.js";
import { readProject } from "../project.js";

export const POLL_INTERVAL_MS = 2000;
const FOLLOW_LIMIT_MS = 30 * 60 * 1000;
const TERMINAL = new Set(["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"]);

interface Step { kind: string; source: string; detail: string; createdAt: string }
interface RunDetail {
  id: string; displayId: string; status: string; simulatedCharge: number; requestedCharge: number;
  steps: Step[]; pendingGate: { id: string; action: string; summary: string } | null;
}
interface MyAgent { agentId: string; versions: Array<{ id: string; versionNumber: string; status: string }> }

export interface SandboxRunOptions { version?: string; profile?: string; follow: boolean }

function compareVersions(a: string, b: string): number {
  const pa = a.split(".").map(Number), pb = b.split(".").map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

function formatStep(step: Step): string {
  let text = step.detail;
  try {
    const parsed = JSON.parse(step.detail) as Record<string, unknown>;
    const said = parsed.summary ?? parsed.message;
    text = typeof said === "string" ? said : JSON.stringify(parsed);
  } catch { /* keep the raw text */ }
  return `${step.kind}: ${text}`;
}

export async function sandboxRun(ctx: Context, opts: SandboxRunOptions): Promise<number> {
  const project = readProject(ctx.cwd);
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);

  const mine = await api.get<MyAgent[]>("/api/v1/agents/mine");
  const agent = mine.find((a) => a.agentId === project.agentId);
  if (!agent || agent.versions.length === 0) {
    throw new CliError("This agent has no versions yet. Run `agentstore push --wait` first.");
  }
  const sorted = [...agent.versions].sort((a, b) => compareVersions(b.versionNumber, a.versionNumber));
  const version = opts.version ? agent.versions.find((v) => v.versionNumber === opts.version) : sorted[0];
  if (!version) {
    throw new CliError(`No version ${opts.version}. Available: ${sorted.map((v) => v.versionNumber).join(", ")}.`);
  }

  let body: Record<string, unknown> = { agentVersionId: version.id, inlineProfile: {} };
  if (opts.profile) {
    const profiles = await api.get<Array<{ id: string; name: string }>>(`/api/v1/agents/${project.agentId}/sandbox-profiles`);
    const profile = profiles.find((p) => p.name === opts.profile);
    if (!profile) {
      throw new CliError(`No sandbox profile "${opts.profile}". Available: ${profiles.map((p) => p.name).join(", ") || "none"}.`);
    }
    body = { agentVersionId: version.id, profileId: profile.id };
  }

  const started = await api.post<{ id: string; displayId: string; status: string }>("/api/v1/sandbox/runs", body);
  ctx.out(`Started sandbox run ${started.displayId} (${started.status}) on version ${version.versionNumber}.`);
  return opts.follow ? await follow(ctx, api, started.id) : 0;
}

async function follow(ctx: Context, api: ApiClient, runId: string): Promise<number> {
  const deadline = ctx.now() + FOLLOW_LIMIT_MS;
  let printed = 0;
  let askedGate: string | null = null;
  for (;;) {
    const run = await api.get<RunDetail>(`/api/v1/sandbox/runs/${runId}`);
    for (const step of run.steps.slice(printed)) ctx.out(formatStep(step));
    printed = run.steps.length;

    if (run.pendingGate && run.pendingGate.id !== askedGate) {
      askedGate = run.pendingGate.id;
      const answer = (await ctx.ask(`Approve "${run.pendingGate.action}" (${run.pendingGate.summary})? [y/N]`)).trim().toLowerCase();
      await api.post(`/api/v1/sandbox/runs/${runId}/gates/${run.pendingGate.id}/decision`, { approve: answer === "y" || answer === "yes" });
      continue;
    }
    if (TERMINAL.has(run.status)) {
      ctx.out(`Run ${run.displayId} ${run.status} (simulated charge ${run.simulatedCharge}, would have asked ${run.requestedCharge}).`);
      return run.status === "COMPLETED" ? 0 : 1;
    }
    if (ctx.now() >= deadline) {
      throw new CliError(`Stopped following after 30 minutes; the run is still ${run.status}. Use \`agentstore sandbox logs ${runId}\`.`);
    }
    await ctx.sleep(POLL_INTERVAL_MS);
  }
}

export async function sandboxLogs(ctx: Context, runId: string): Promise<number> {
  const api = new ApiClient(requireCredentials(ctx), ctx.fetch);
  const run = await api.get<RunDetail>(`/api/v1/sandbox/runs/${encodeURIComponent(runId)}`);
  for (const step of run.steps) ctx.out(formatStep(step));
  ctx.out(`Run ${run.displayId} is ${run.status}.`);
  return 0;
}
```
(The gate-prompt `continue` re-polls immediately without sleeping so the decision shows up at once.)

- [ ] **Step 4: Wire `cli.ts`.** Add to `USAGE`:

```
  sandbox run [--version V] [--profile NAME] [--follow]
                                        Run a built version in the sandbox (no buyer, no money)
  sandbox logs RUN_ID                   Show what a sandbox run did
```
and a `case "sandbox":` that splits `[sub, ...rest] = args`: `"run"` → `parse(rest, { version: {type:"string"}, profile: {type:"string"}, follow: {type:"boolean"} })` → `sandboxRun(ctx, {...})`; `"logs"` → `parseArgs({ args: rest, options: {}, strict: true, allowPositionals: true })`, requiring exactly one positional (else `CliError` with usage) → `sandboxLogs`; anything else → `ctx.err("Unknown sandbox command…\n\n"+USAGE)`, return 2 (also when `sub` is undefined).

- [ ] **Step 5: Run** `cd cli && npx vitest run && npx tsc --noEmit` → all pass. Document the two commands and the quota message in `cli/README.md`.

- [ ] **Step 6: Commit** — `git commit -m "feat(cli): sandbox run --follow and sandbox logs"`.

---

### Task 10: ONTOLOGY.md, regression and wrap-up

**Files:**
- Modify: `ONTOLOGY.md`
- Memory: `/Users/amittiwari/.claude/projects/-Users-amittiwari-AgentStore/memory/agentstore-ws6a-state.md` (+ `MEMORY.md` pointer)

- [ ] **Step 1: Update `ONTOLOGY.md`** to match the code as built (read it first; keep its existing structure and staleness stamp format; see `scripts/check-ontology-staleness.sh`): add the `sandbox` package and its files, V31 (`sandbox_profile`, the `run` columns and `run_mode_owner_chk`), the new endpoints (fixtures, profiles, bindings, sandbox runs, quota), the connection-less run lifecycle and simulated billing, the no-ledger guarantee, replay ownership, gate policy, quota table, `RunStepKind.CANCELLED`, CLI commands, and the deviations from the parent spec (no `usage_event` rows, computed quotas, `ON DELETE SET NULL`, derived `displayId`). Re-stamp per the script.
- [ ] **Step 2: Run** `scripts/check-ontology-staleness.sh ONTOLOGY.md` → clean.
- [ ] **Step 3: Full regression.** `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test` → all green with **no edits to any pre-existing test file** (`git diff --stat master -- backend/src/test` must list only new files). `cd cli && npx vitest run && npx tsc --noEmit`. `git diff master -- contracts/` is empty.
- [ ] **Step 4: Save the memory** (state, deviations, follow-ups 6b/6c/WS4/WS7/WS8/FE-3, manual checks left: live `agentstore sandbox run --follow` against a real runner; terminal prompting for a MANUAL gate) and update `MEMORY.md`.
- [ ] **Step 5: Commit** — `git commit -m "docs: ontology and plan for WS6a sandbox core"`.

---

## Self-Review

**Spec coverage.** §1 scope → Tasks 1–9; §2 schema → Task 1 (+ the one `ON DELETE SET NULL` departure); §3.1 start (steps 1–5) → Task 7 (`begin`/`create`, in the spec's order); §3.2 connection-touching sites → `Run` (T1), `RunLauncher` (T3), `RunPrincipal`/`RunLifecycleService`/controllers/`OutputsController` (T2), `StatementController`/`RunSummaryResponse`/`RunReaper` (structural; pinned by T2 tests and the full regression); §3.3 violations/gates → T2 (violations) and T8; §3.4 cancel/rerun → T7; §3.5 outputs → T2; §4 billing → T2; §5 quotas → T4 (+T6 LLM, T5 fixture upload hook, T4 builds hook); §6 API/CLI → T3, T5, T7, T8, T9 (ApiKeyScope in T7); §7 errors → each raised where listed; §8 testing → each bullet has a named test above; `ONTOLOGY.md` last task per §8.

**Placeholder scan.** No TBD/TODO. Where a step says "mirror X test" it names the existing test to copy the setup from and gives every assertion.

**Type consistency.** `SandboxApiException.quota(String, Object, Object)` used in T4/T7; `ProfileInput` fixed in T3 and consumed in T7; `LlmOverride` only in T7; `GatePolicy.GateMode` used in T3/T8; `UploadQuota` in T5 only; `SandboxQuotaService.llmAllows` T4 → T6; `RunController.SANDBOX_TIMEOUT_SECONDS`/`PENDING_BUILD_TIMEOUT` made public in T2 for T7; `RunStepResponse.from` public in T2 for T7.

**Review Focus coverage.** 1 → T7 `concurrentTriggersOnlyOnePasses`; 2 → T7 `SandboxReplayOwnershipTest`; 3 → T7 `inlineProfileIsStoredHiddenAndRolledBackOnFailure`; 4 → T7 `deletedProfileKeepsRunReadable`; 5 → T3 launcher test and T7 API test.
