# WS6b — Behaviour Report, Flow, Readiness, Manifest Patches Implementation Plan


**Goal:** Give a developer, after a sandbox run finishes, a behaviour report (declared-vs-actual, cost, LLM, gates, timing), a `flow`/readiness view of any run, and a way to patch a DRAFT manifest without a rebuild.

**Architecture:** A new `run_step.block` column (computed centrally from each step's existing recording data) lets a single shared aggregator (`RunFlowService`) turn a run's steps into a manifest-shaped `blocks[]` view. `GET /runs/{id}/flow` exposes that view directly; `RunReportService` (a `@TransactionalEventListener` on `RunFinishedEvent`, SANDBOX-only) reuses it to write one `run_report` row per finished sandbox run. Readiness reads only `run_report`'s real `result`/`issue_count` columns (never its JSON) plus `agent_version.build_status`. Manifest patches mutate the DRAFT version's raw JSON tree, re-validate through the existing `ManifestValidator`, and diff against the agent's current LIVE manifest.

**Tech Stack:** Spring Boot 3 (Java), Spring Data JPA, Flyway, Jackson, springdoc-openapi 2.8.9, JUnit 5 / AssertJ / Testcontainers (Postgres + Redis).

**Spec:** `docs/superpowers/specs/2026-09-27-ws6b-behaviour-report-design.md`

## Global Constraints

- Backend and contract only — no UI (FE-3), no SDK, no CLI changes this slice (spec §1).
- No pre-existing test file is edited; every test added is a new file (spec §9).
- `result`/`issue_count` on `run_report` are real columns, queried directly by readiness — never parsed out of `report_json` (spec §2, §7).
- Manifest patches are DRAFT-only; a non-DRAFT version answers `409 VERSION_NOT_EDITABLE` (spec §6).
- Manifest patch diff always compares the patched manifest against the agent's current **LIVE** version (not the version's own pre-patch state); empty if no LIVE version exists (spec §6).
- Each patch re-validates the resulting manifest through the existing `ManifestParser`/`ManifestValidator` path before it is ever persisted (spec §6).
- `GET /runs/{id}/flow` uses the run-detail endpoint's auth shape (authenticated caller who owns the run, or ADMIN) but must work for a run with no buyer, unlike the existing `/runs/{id}` endpoint (spec §4).
- No new CLI command this slice; `sandbox report` is an explicit non-goal (spec §8).

**Documented deviations from the spec's literal text (decided during planning, not to be re-litigated during execution):**

1. **Three migrations, not one.** The spec groups all of §2's schema changes under "migration V32". This plan splits them into `V32__run_step_block.sql`, `V33__run_report.sql`, `V34__agent_version_manifest_revision.sql` — one per task, so each task stays independently testable and mergeable. Nothing depends on them sharing a single file.
2. **A new springdoc group, not the existing runtime contract.** `springdoc.paths-to-match: /runtime/v1/**` (`application.yml`) restricts the *default* OpenAPI document to the runtime API, and `RuntimeContractTest` (a pre-existing test file, not to be edited) asserts the default document contains *only* `/runtime/v1/**` paths. The three new endpoints live under `/api/v1/**`, so this plan adds a second, additive `GroupedOpenApi` bean (group `"platform"`, served at `/v3/api-docs/platform`) and a parallel `contracts/platform-v1.yaml` + `PlatformContractTest`, exactly mirroring `RuntimeContractTest`'s own pattern. The default document and `RuntimeContractTest` are untouched.
3. **`COST_CAP_REACHED`'s block is null, but it still fails the `charge` block.** Spec §3's table maps `COST_CAP_REACHED → null`; spec §4's flow-state rule says a block is `FAILED` when it "has SCOPE_VIOLATION/COST_CAP_REACHED". Both are honored: the persisted `run_step.block` for a `COST_CAP_REACHED` step stays `null` (so it never invents a fake block), but `RunFlowService` separately checks for a `COST_CAP_REACHED` step's *existence* on the run and, when present, forces the declared `charge` block's state to `FAILED` regardless of whether a `USAGE` step also exists for it.
4. **Readiness's `UNDECLARED_ACCESS` blockers are generic, not per-resource.** Per the spec's own rationale (real columns so readiness never parses JSON), one generic `UNDECLARED_ACCESS` blocker is emitted per unit of `run_report.issue_count` — no resource-specific detail is pulled from `report_json`.
5. **Manifest-patch scope/resource identity conflicts are `409`s; allowlist-host adds are idempotent.** `add_read_scope`/`add_resource` reject a name/resource that's already declared (`409 SCOPE_ALREADY_DECLARED` / `409 RESOURCE_ALREADY_DECLARED`) since silently discarding a different `description`/`schema` would be a silent data loss. `add_allowlist_host` is a no-op (still `200`, revision unchanged) when the host is already present, since a bare host string carries no other payload to conflict over.
6. **Readiness and manifest patches share the same auth**: role `DEVELOPER`, caller's org owns the agent — `404` if the version doesn't belong to the given agent, `403` if the agent doesn't belong to the caller's org. The spec states this explicitly for manifest patches (§6); this plan applies the identical rule to readiness (§7 doesn't specify an auth rule of its own), since both expose internal developer-only detail.

## Review Focus

- A run that never started (zero `run_step` rows, `PENDING` status) hitting `GET /runs/{id}/flow` must return every declared block as `IDLE`, not throw or show `DONE` — tested in Task 2.
- A `COST_CAP_REACHED` step (whose own `run_step.block` is `null`, deviation 3 above) must still flip the declared `charge` block to `FAILED` in `/flow` — tested in Task 2.
- A decided gate (`GATE_DECIDED`) must carry the *same* `gate:<action>` block as its `GATE_REQUESTED`, not `gate:null` — `GateService.decide()`'s detail map didn't carry `action` before this change; tested in Task 1.
- A manifest patch with a missing required field (e.g. `add_read_scope` with no `resource`) must answer `400 INVALID_PATCH`, not a raw NPE/500 — tested in Task 6.
- A manifest patch on an agent with no LIVE version yet must not NPE computing the diff (`liveManifest == null`) — tested in Task 6.

---

## Task 1: `run_step.block` — migration, computation, and every recording call site

**Files:**
- Create: `backend/src/main/resources/db/migration/V32__run_step_block.sql`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunStep.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunStepBlocks.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/GateService.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunStepBlocksTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/runtime/RunStepBlockPersistenceTest.java`

**Interfaces:**
- Consumes: `RunStepKind` (existing), `RunLifecycleService.step(Run, RunStepKind, RunStepSource, Map<String,Object>)` (existing private method, modified in place), `GateService.step(Run, RunStepKind, Map<String,Object>)` (existing private method, modified in place).
- Produces: `RunStep.getBlock() : String` (nullable) — every later task's `RunFlowService` groups on this. `RunStepBlocks.of(RunStepKind, Map<String,Object>) : String` (package-private, static) — the single place block strings are computed.

- [ ] **Step 1: Write the migration**

```sql
-- backend/src/main/resources/db/migration/V32__run_step_block.sql
ALTER TABLE run_step ADD COLUMN block VARCHAR(40);
-- 'read:<resource>' | 'llm' | 'draft:<resource>' | 'gate:<action>' | 'write:<resource>' | 'charge' | NULL
```

- [ ] **Step 2: Add the `block` field to `RunStep`**

Edit `backend/src/main/java/com/agentstore/platform/execution/RunStep.java`, adding one field (the class already has `@Getter @Setter` at the class level, so no accessor code is needed):

```java
    @Enumerated(EnumType.STRING)
    private RunStepSource source = RunStepSource.AGENT;

    /** The manifest-facing shape of this step: 'read:<resource>' | 'write:<resource>' | 'draft:<resource>' |
     * 'gate:<action>' | 'llm' | 'charge' | null. See {@link RunStepBlocks}. */
    private String block;

    private Instant createdAt;
```

- [ ] **Step 3: Write the failing unit test for `RunStepBlocks`**

```java
// backend/src/test/java/com/agentstore/platform/execution/RunStepBlocksTest.java
package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunStepBlocksTest {

    @Test
    void dataReadBlockIncludesTheResource() {
        assertThat(RunStepBlocks.of(RunStepKind.DATA_READ, Map.of("resource", "bank_statement")))
                .isEqualTo("read:bank_statement");
    }

    @Test
    void dataWriteBlockIncludesTheResource() {
        assertThat(RunStepBlocks.of(RunStepKind.DATA_WRITE, Map.of("resource", "journal_entries")))
                .isEqualTo("write:journal_entries");
    }

    @Test
    void writeStagedBlockIsADraft() {
        assertThat(RunStepBlocks.of(RunStepKind.WRITE_STAGED, Map.of("resource", "journal_entries")))
                .isEqualTo("draft:journal_entries");
    }

    @Test
    void gateRequestedBlockIncludesTheAction() {
        assertThat(RunStepBlocks.of(RunStepKind.GATE_REQUESTED, Map.of("action", "post_journal_entries")))
                .isEqualTo("gate:post_journal_entries");
    }

    @Test
    void gateDecidedBlockIncludesTheAction() {
        assertThat(RunStepBlocks.of(RunStepKind.GATE_DECIDED, Map.of("action", "post_journal_entries")))
                .isEqualTo("gate:post_journal_entries");
    }

    @Test
    void llmCallBlockIsAlwaysLlm() {
        assertThat(RunStepBlocks.of(RunStepKind.LLM_CALL, Map.of("tokens", 100))).isEqualTo("llm");
    }

    @Test
    void usageBlockIsAlwaysCharge() {
        assertThat(RunStepBlocks.of(RunStepKind.USAGE, Map.of("quantity", "2"))).isEqualTo("charge");
    }

    @Test
    void scopeViolationBlockMirrorsTheAttemptedAccess() {
        assertThat(RunStepBlocks.of(RunStepKind.SCOPE_VIOLATION, Map.of("attempted", "read", "target", "vendor_master")))
                .isEqualTo("read:vendor_master");
        assertThat(RunStepBlocks.of(RunStepKind.SCOPE_VIOLATION, Map.of("attempted", "gate", "target", "post_journal_entries")))
                .isEqualTo("gate:post_journal_entries");
    }

    @Test
    void agentLogTimeoutCostCapReachedAndCancelledHaveNoBlock() {
        assertThat(RunStepBlocks.of(RunStepKind.AGENT_LOG, Map.of())).isNull();
        assertThat(RunStepBlocks.of(RunStepKind.TIMEOUT, Map.of())).isNull();
        assertThat(RunStepBlocks.of(RunStepKind.COST_CAP_REACHED, Map.of())).isNull();
        assertThat(RunStepBlocks.of(RunStepKind.CANCELLED, Map.of())).isNull();
    }
}
```

- [ ] **Step 4: Run it to verify it fails (compile error — `RunStepBlocks` doesn't exist yet)**

Run: `cd backend && mvn -q -pl . test -Dtest=RunStepBlocksTest`
Expected: FAIL to compile — `cannot find symbol: class RunStepBlocks`

- [ ] **Step 5: Write `RunStepBlocks`**

```java
// backend/src/main/java/com/agentstore/platform/execution/RunStepBlocks.java
package com.agentstore.platform.execution;

import java.util.Map;

/**
 * The manifest-facing shape of a run_step, derived from its kind and the fields its own recording call site already
 * puts in {@code detail} (spec §3). {@code COST_CAP_REACHED}'s block is null on purpose — see
 * {@code RunFlowService}, which still surfaces it as a FAILED {@code charge} block by checking for the step's
 * existence directly, not through this mapping.
 */
final class RunStepBlocks {

    private RunStepBlocks() {
    }

    static String of(RunStepKind kind, Map<String, Object> detail) {
        return switch (kind) {
            case DATA_READ -> "read:" + detail.get("resource");
            case DATA_WRITE -> "write:" + detail.get("resource");
            case WRITE_STAGED -> "draft:" + detail.get("resource");
            case GATE_REQUESTED, GATE_DECIDED -> "gate:" + detail.get("action");
            case LLM_CALL -> "llm";
            case USAGE -> "charge";
            case SCOPE_VIOLATION -> detail.get("attempted") + ":" + detail.get("target");
            default -> null;
        };
    }
}
```

- [ ] **Step 6: Run the unit test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunStepBlocksTest`
Expected: PASS (8 tests)

- [ ] **Step 7: Wire block computation into `RunLifecycleService.step`**

In `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`, replace the private `step` overload that actually persists (the other overload just delegates to it via `kind.defaultSource()`):

```java
    private void step(Run run, RunStepKind kind, RunStepSource source, Map<String, Object> detail) {
        try {
            RunStep step = new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), source);
            step.setBlock(RunStepBlocks.of(kind, detail));
            runStepRepository.save(step);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
```

- [ ] **Step 8: Wire block computation into `GateService.step`, and carry `action` into the decision detail**

In `backend/src/main/java/com/agentstore/platform/execution/GateService.java`, the `decide()` method currently builds its detail map without `action`:

```java
            Map<String, Object> detail = new HashMap<>();
            detail.put("gateId", gate.getId().toString());
            detail.put("decision", approve ? "APPROVED" : "REJECTED");
            if (note != null) {
                detail.put("note", note);
            }
            step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);
```

Change it to also carry `action` (needed so `RunStepBlocks.of(GATE_DECIDED, ...)` produces the same `gate:<action>` block as the matching `GATE_REQUESTED`, per this task's Review Focus item):

```java
            Map<String, Object> detail = new HashMap<>();
            detail.put("gateId", gate.getId().toString());
            detail.put("action", gate.getAction());
            detail.put("decision", approve ? "APPROVED" : "REJECTED");
            if (note != null) {
                detail.put("note", note);
            }
            step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);
```

Then update the private `step` method in the same file to set the block:

```java
    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        try {
            RunStep step = new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), RunStepSource.PLATFORM);
            step.setBlock(RunStepBlocks.of(kind, detail));
            runStepRepository.save(step);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
```

- [ ] **Step 9: Write the failing integration test driving every call site over real HTTP**

This mirrors `RuntimeEndToEndTest`'s exact scripted-agent sequence (same fixtures), asserting `block` instead of `kind`:

```java
// backend/src/test/java/com/agentstore/platform/runtime/RunStepBlockPersistenceTest.java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.InMemoryBackends.InMemoryResourceBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(InMemoryBackends.class)
class RunStepBlockPersistenceTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    InMemoryResourceBackend resources;
    @Autowired
    JwtService jwtService;
    @Autowired
    RunStepRepository stepRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> agentCall(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void everyRecordingCallSiteSetsTheExpectedBlock() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        resources.seed("bank_statement", List.of(Map.of("id", 1, "amount", 100)));
        String token = agent.token();

        agentCall(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records?limit=10", token, null);

        var gate = agentCall(HttpMethod.POST, "/runtime/v1/gates", token, Map.of(
                "action", "post_journal_entries", "summary", "Post 1 entry", "payload", Map.of("rows", 1),
                "affectsResources", List.of("journal_entries")));
        String gateId = (String) gate.getBody().get("gateId");

        HttpHeaders buyer = new HttpHeaders();
        buyer.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_APPROVER));
        buyer.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE", "note", "ok"), buyer), RunResponse.class);

        agentCall(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", token,
                Map.of("records", List.of(Map.of("account", "1000"))));
        agentCall(HttpMethod.POST, "/runtime/v1/usage", token, Map.of("quantity", 1, "unit", "reconciled_transaction"));
        agentCall(HttpMethod.POST, "/runtime/v1/complete", token, Map.of("summary", "done"));

        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).extracting(RunStep::getKind, RunStep::getBlock).containsExactly(
                org.assertj.core.groups.Tuple.tuple(RunStepKind.DATA_READ, "read:bank_statement"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.GATE_REQUESTED, "gate:post_journal_entries"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.GATE_DECIDED, "gate:post_journal_entries"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.WRITE_STAGED, "draft:journal_entries"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.USAGE, "charge"),
                org.assertj.core.groups.Tuple.tuple(RunStepKind.COMPLETE, null));
    }

    @Test
    void anUndeclaredReadsBlockMirrorsTheAttemptedAccess() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        agentCall(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);

        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.SCOPE_VIOLATION);
            assertThat(s.getBlock()).isEqualTo("read:payroll");
        });
    }
}
```

- [ ] **Step 10: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunStepBlocksTest,RunStepBlockPersistenceTest`
Expected: PASS (all tests). If `everyRecordingCallSiteSetsTheExpectedBlock` fails on the `GATE_DECIDED` tuple with block `gate:null`, Step 8's `detail.put("action", gate.getAction())` addition was missed.

- [ ] **Step 11: Run the full backend suite to confirm nothing pre-existing broke**

Run: `cd backend && mvn -q test`
Expected: PASS, same test count as before plus the new tests (no pre-existing test file was touched, so none should have changed behavior).

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/resources/db/migration/V32__run_step_block.sql \
        backend/src/main/java/com/agentstore/platform/execution/RunStep.java \
        backend/src/main/java/com/agentstore/platform/execution/RunStepBlocks.java \
        backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java \
        backend/src/main/java/com/agentstore/platform/execution/GateService.java \
        backend/src/test/java/com/agentstore/platform/execution/RunStepBlocksTest.java \
        backend/src/test/java/com/agentstore/platform/runtime/RunStepBlockPersistenceTest.java
git commit -m "feat(execution): add run_step.block, computed at every recording call site"
```

---

## Task 2: `RunFlowService` and `GET /api/v1/runs/{runId}/flow`

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/BlockState.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/FlowBlock.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/FlowView.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunFlowService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunFlowServiceTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunFlowControllerTest.java`

**Interfaces:**
- Consumes: `RunStepRepository.findByRunIdOrderByCreatedAtAsc(UUID)` (existing), `ManifestParser.parse(String) : Manifest` (existing), `Run` entity's `getConnection()`/`getInitiatedByOrgId()`/`getSimulatedCharged()`/`getTotalCharged()`/`getCostCap()`/`getDeadlineAt()`/`getStatus()` (existing).
- Produces: `RunFlowService.compute(Run) : FlowView` — consumed directly by this task's controller endpoint and by Task 3's `RunReportService`. `FlowView(List<FlowBlock> blocks, String reachedBlock, FlowView.Spend spend, Instant deadlineAt)`. `FlowBlock(String block, BlockState state, Map<String,Object> metrics, String gateId, String flag)`. `BlockState { DONE, WAITING, FAILED, SKIPPED, IDLE }`.

- [ ] **Step 1: Write the enum and records**

```java
// backend/src/main/java/com/agentstore/platform/execution/BlockState.java
package com.agentstore.platform.execution;

public enum BlockState { DONE, WAITING, FAILED, SKIPPED, IDLE }
```

```java
// backend/src/main/java/com/agentstore/platform/execution/FlowBlock.java
package com.agentstore.platform.execution;

import java.util.Map;

/** One manifest-declared or undeclared block in a run's flow (spec §4). {@code flag} is "UNDECLARED" or null. */
public record FlowBlock(String block, BlockState state, Map<String, Object> metrics, String gateId, String flag) {

    static FlowBlock declared(String block, BlockState state, Map<String, Object> metrics, String gateId) {
        return new FlowBlock(block, state, metrics, gateId, null);
    }

    static FlowBlock undeclared(String block, BlockState state, Map<String, Object> metrics) {
        return new FlowBlock(block, state, metrics, null, "UNDECLARED");
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/execution/FlowView.java
package com.agentstore.platform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FlowView(List<FlowBlock> blocks, String reachedBlock, Spend spend, Instant deadlineAt) {

    public record Spend(BigDecimal charged, BigDecimal cap) {
    }
}
```

- [ ] **Step 2: Write the failing unit test for `RunFlowService`**

This test builds `Run`/`RunStep` rows directly (no HTTP), covering: all-IDLE on a fresh run, DONE/WAITING states, an UNDECLARED extra, SKIPPED after termination, and the `COST_CAP_REACHED` → FAILED `charge` special case from this task's Review Focus.

```java
// backend/src/test/java/com/agentstore/platform/execution/RunFlowServiceTest.java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RunFlowServiceTest extends AbstractIntegrationTest {

    @Autowired
    RunFlowService flowService;
    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;

    @Test
    void aFreshRunHasEveryDeclaredBlockIdle() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).extracting(FlowBlock::block, FlowBlock::state).containsExactlyInAnyOrder(
                tuple("read:bank_statement", BlockState.IDLE),
                tuple("read:invoices", BlockState.IDLE),
                tuple("draft:journal_entries", BlockState.IDLE),
                tuple("gate:post_journal_entries", BlockState.IDLE),
                tuple("charge", BlockState.IDLE));
        assertThat(flow.reachedBlock()).isNull();
    }

    @Test
    void aDecidedGateIsDoneAndAnOpenGateIsWaitingWithItsGateId() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordStep(run.getId(), RunStepKind.GATE_REQUESTED, RunStepSource.PLATFORM,
                Map.of("gateId", "11111111-1111-1111-1111-111111111111", "action", "post_journal_entries"));

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("gate:post_journal_entries"))
                .singleElement().satisfies(b -> {
                    assertThat(b.state()).isEqualTo(BlockState.WAITING);
                    assertThat(b.gateId()).isEqualTo("11111111-1111-1111-1111-111111111111");
                });
        assertThat(flow.reachedBlock()).isEqualTo("gate:post_journal_entries");
    }

    @Test
    void anUndeclaredReadAppearsAsAFailedFlaggedExtra() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", "vendor_master"));

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("read:vendor_master"))
                .singleElement().satisfies(b -> {
                    assertThat(b.state()).isEqualTo(BlockState.FAILED);
                    assertThat(b.flag()).isEqualTo("UNDECLARED");
                });
    }

    @Test
    void unreachedBlocksAreSkippedOnceTheRunIsTerminal() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.complete(run.getId(), Map.of("summary", "done"));
        run = runRepository.findById(run.getId()).orElseThrow();

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("draft:journal_entries"))
                .singleElement().extracting(FlowBlock::state).isEqualTo(BlockState.SKIPPED);
    }

    @Test
    void aCostCapReachedStepFailsTheChargeBlockEvenThoughItsOwnBlockColumnIsNull() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordUsage(run.getId(), new BigDecimal("1000"), Map.of("quantity", "1000", "unit", "reconciled_transaction"));
        run = runRepository.findById(run.getId()).orElseThrow();

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("charge"))
                .singleElement().extracting(FlowBlock::state).isEqualTo(BlockState.FAILED);
    }
}
```

- [ ] **Step 3: Run it to verify it fails to compile (`RunFlowService` doesn't exist yet)**

Run: `cd backend && mvn -q test -Dtest=RunFlowServiceTest`
Expected: FAIL to compile — `cannot find symbol: class RunFlowService`

- [ ] **Step 4: Write `RunFlowService`**

```java
// backend/src/main/java/com/agentstore/platform/execution/RunFlowService.java
package com.agentstore.platform.execution;

import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declared-vs-actual, one block per manifest scope/gate/llm/charge slot, aggregated from run_step (spec §4). Shared
 * by GET /runs/{id}/flow (this package's RunDetailController) and RunReportService's behaviour report, so the two
 * views of a run's blocks can never drift apart.
 */
@Component
public class RunFlowService {

    private final RunStepRepository stepRepository;
    private final ManifestParser manifestParser;
    private final ObjectMapper mapper;

    public RunFlowService(RunStepRepository stepRepository, ManifestParser manifestParser, ObjectMapper mapper) {
        this.stepRepository = stepRepository;
        this.manifestParser = manifestParser;
        this.mapper = mapper;
    }

    public FlowView compute(Run run) {
        Manifest manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        List<RunStep> allSteps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        boolean terminal = run.getStatus().isTerminal();
        boolean costCapReached = allSteps.stream().anyMatch(s -> s.getKind() == RunStepKind.COST_CAP_REACHED);

        Map<String, List<RunStep>> byBlock = new LinkedHashMap<>();
        for (RunStep s : allSteps) {
            if (s.getBlock() != null) {
                byBlock.computeIfAbsent(s.getBlock(), k -> new ArrayList<>()).add(s);
            }
        }

        List<FlowBlock> blocks = new ArrayList<>();
        for (String block : declaredBlocks(manifest)) {
            blocks.add(declaredBlock(block, byBlock.remove(block), terminal, costCapReached));
        }
        for (var entry : byBlock.entrySet()) {
            blocks.add(undeclaredBlock(entry.getKey(), entry.getValue()));
        }

        String reachedBlock = null;
        for (int i = allSteps.size() - 1; i >= 0; i--) {
            if (allSteps.get(i).getBlock() != null) {
                reachedBlock = allSteps.get(i).getBlock();
                break;
            }
        }

        BigDecimal charged = run.getConnection() == null ? run.getSimulatedCharged() : run.getTotalCharged();
        return new FlowView(blocks, reachedBlock, new FlowView.Spend(charged, run.getCostCap()), run.getDeadlineAt());
    }

    private static Set<String> declaredBlocks(Manifest manifest) {
        Set<String> blocks = new LinkedHashSet<>();
        for (Scope scope : manifest.scopes()) {
            blocks.add(scope.type() == ScopeType.READ ? "read:" + scope.resource()
                    : (scope.requiresApproval() ? "draft:" : "write:") + scope.resource());
        }
        manifest.approvalGates().forEach(gate -> blocks.add("gate:" + gate.trigger()));
        if (manifest.llm() != null) {
            blocks.add("llm");
        }
        blocks.add("charge");
        return blocks;
    }

    private FlowBlock declaredBlock(String block, List<RunStep> steps, boolean terminal, boolean costCapReached) {
        if (block.equals("charge")) {
            BlockState state = costCapReached ? BlockState.FAILED
                    : steps != null ? BlockState.DONE
                    : terminal ? BlockState.SKIPPED : BlockState.IDLE;
            return FlowBlock.declared("charge", state, null, null);
        }
        if (block.startsWith("gate:")) {
            if (steps == null) {
                return FlowBlock.declared(block, terminal ? BlockState.SKIPPED : BlockState.IDLE, null, null);
            }
            RunStep last = steps.get(steps.size() - 1);
            return last.getKind() == RunStepKind.GATE_DECIDED
                    ? FlowBlock.declared(block, BlockState.DONE, null, null)
                    : FlowBlock.declared(block, BlockState.WAITING, null, stringField(last, "gateId"));
        }
        if (steps == null) {
            return FlowBlock.declared(block, terminal ? BlockState.SKIPPED : BlockState.IDLE, null, null);
        }
        return FlowBlock.declared(block, BlockState.DONE, metrics(block, steps), null);
    }

    private FlowBlock undeclaredBlock(String block, List<RunStep> steps) {
        RunStepKind lastKind = steps.get(steps.size() - 1).getKind();
        BlockState state = switch (lastKind) {
            case SCOPE_VIOLATION -> BlockState.FAILED;
            case GATE_REQUESTED -> BlockState.WAITING;
            default -> BlockState.DONE;
        };
        return FlowBlock.undeclared(block, state, block.equals("llm") ? metrics(block, steps) : null);
    }

    private Map<String, Object> metrics(String block, List<RunStep> steps) {
        if (block.equals("llm")) {
            long tokens = steps.stream().mapToLong(s -> fieldOrDefault(s, "tokens", 0)).sum();
            return Map.of("calls", steps.size(), "tokens", tokens);
        }
        long count = steps.stream().mapToLong(s -> fieldOrDefault(s, "count", 1)).sum();
        return Map.of("count", count);
    }

    private long fieldOrDefault(RunStep step, String field, long defaultValue) {
        JsonNode node = parse(step).path(field);
        return node.isMissingNode() ? defaultValue : node.asLong();
    }

    private String stringField(RunStep step, String field) {
        JsonNode node = parse(step).path(field);
        return node.isMissingNode() ? null : node.asText();
    }

    private JsonNode parse(RunStep step) {
        try {
            return mapper.readTree(step.getDetail());
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
```

- [ ] **Step 5: Run the unit test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunFlowServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 6: Add the controller endpoint, generalizing the existing ownership check**

Edit `backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java` in full:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunDetailController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final RunFlowService flowService;

    public RunDetailController(RunRepository runRepository, RunStepRepository runStepRepository, RunFlowService flowService) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.flowService = flowService;
    }

    @GetMapping("/{runId}")
    @PreAuthorize("isAuthenticated()")
    public RunDetailResponse get(@PathVariable UUID runId) {
        Run run = findRun(runId);
        if (run.getConnection() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");   // a sandbox run is not a buyer's
        }
        requireViewable(run);

        var steps = runStepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(RunStepResponse::from).toList();
        return new RunDetailResponse(run.getId(), run.getStatus(), run.getCostCap(), run.getTotalCharged(), steps);
    }

    /** Any run the caller can already view — unlike {@link #get}, not restricted to a buyer's own run (spec §4). */
    @GetMapping("/{runId}/flow")
    @PreAuthorize("isAuthenticated()")
    public FlowView flow(@PathVariable UUID runId) {
        Run run = findRun(runId);
        requireViewable(run);
        return flowService.compute(run);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public java.util.List<RunSummaryResponse> mine(@RequestParam(required = false) RunStatus status) {
        var claims = (JwtService.Claims)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var runs = status == null
                ? runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(claims.orgId())
                : runRepository.findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(claims.orgId(), status);
        return runs.stream().map(RunSummaryResponse::from).toList();
    }

    private Run findRun(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    /** Same ownership rule as the run-detail endpoint, generalized to a run with no buyer (a sandbox run's initiator). */
    private void requireViewable(Run run) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean owns = run.getConnection() != null
                ? run.getConnection().getBuyerOrg().getId().equals(claims.orgId())
                : claims.orgId().equals(run.getInitiatedByOrgId());
        if (!owns && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
    }
}
```

- [ ] **Step 7: Write the failing controller test (ownership across both run shapes)**

```java
// backend/src/test/java/com/agentstore/platform/execution/RunFlowControllerTest.java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunFlowControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures runtimeFixtures;
    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(UUID orgId, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return headers;
    }

    @Test
    @SuppressWarnings("rawtypes")
    void theBuyerCanSeeFlowForAProductionRun() {
        var agent = runtimeFixtures.runningAgent(new BigDecimal("500.00"));

        var response = rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/flow"), HttpMethod.GET,
                new HttpEntity<>(bearer(agent.scenario().buyer().getId(), UserRole.BUYER_APPROVER)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void theDeveloperCanSeeFlowForTheirOwnSandboxRun() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/flow"), HttpMethod.GET,
                new HttpEntity<>(sandboxFixtures.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNonOwningDeveloperCannotSeeAnotherOrgsSandboxRunFlow() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));

        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/flow"), HttpMethod.GET,
                new HttpEntity<>(bearer(intruder.getId(), UserRole.DEVELOPER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunFlowServiceTest,RunFlowControllerTest`
Expected: PASS

- [ ] **Step 9: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/execution/BlockState.java \
        backend/src/main/java/com/agentstore/platform/execution/FlowBlock.java \
        backend/src/main/java/com/agentstore/platform/execution/FlowView.java \
        backend/src/main/java/com/agentstore/platform/execution/RunFlowService.java \
        backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java \
        backend/src/test/java/com/agentstore/platform/execution/RunFlowServiceTest.java \
        backend/src/test/java/com/agentstore/platform/execution/RunFlowControllerTest.java
git commit -m "feat(execution): add RunFlowService and GET /api/v1/runs/{id}/flow"
```

---

## Task 3: `run_report` and `RunReportService`

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__run_report.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunReportResult.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunReport.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunReportRepository.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/GateRequestRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunReportService.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunReportServiceTest.java`

**Interfaces:**
- Consumes: `RunFlowService.compute(Run) : FlowView` (Task 2), `RunFinishedEvent` (existing), `LlmCallRepository.findByRunIdOrderByCreatedAtAsc`/`sumCostByRunId` (existing), `ManifestParser` (existing).
- Produces: `RunReport` entity (`getRunId()`, `getResult()`, `getIssueCount()`, `getReportJson()`), `RunReportRepository.findLatestForVersion(UUID versionId) : Optional<RunReport>` — consumed by Task 5's readiness endpoint. `RunReportRepository.findById(UUID runId)` (inherited) — consumed by Task 4.

- [ ] **Step 1: Write the migration**

```sql
-- backend/src/main/resources/db/migration/V33__run_report.sql
CREATE TABLE run_report (
  run_id UUID PRIMARY KEY REFERENCES run(id),
  result VARCHAR(20) NOT NULL,        -- CLEAN, ISSUES, TIMED_OUT, OVER_COST_LIMIT, FAILED
  issue_count INT NOT NULL DEFAULT 0,
  report_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Write the result enum, entity, and repository**

```java
// backend/src/main/java/com/agentstore/platform/execution/RunReportResult.java
package com.agentstore.platform.execution;

public enum RunReportResult { CLEAN, ISSUES, TIMED_OUT, OVER_COST_LIMIT, FAILED }
```

```java
// backend/src/main/java/com/agentstore/platform/execution/RunReport.java
package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One behaviour report per finished SANDBOX run (spec §5). {@code result}/{@code issueCount} are real columns so
 * readiness (Task 5) can query them without ever parsing {@code reportJson}. */
@Entity
@Table(name = "run_report")
@Getter
@Setter
@NoArgsConstructor
public class RunReport {

    @Id
    private UUID runId;

    @Enumerated(EnumType.STRING)
    private RunReportResult result;

    private int issueCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report_json", columnDefinition = "jsonb")
    private String reportJson;

    private Instant createdAt;

    public RunReport(UUID runId, RunReportResult result, int issueCount, String reportJson) {
        this.runId = runId;
        this.result = result;
        this.issueCount = issueCount;
        this.reportJson = reportJson;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/execution/RunReportRepository.java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RunReportRepository extends JpaRepository<RunReport, UUID> {

    @Query(value = "SELECT rr.* FROM run_report rr JOIN run r ON r.id = rr.run_id " +
            "WHERE r.agent_version_id = :versionId AND r.mode = 'SANDBOX' " +
            "ORDER BY rr.created_at DESC LIMIT 1", nativeQuery = true)
    Optional<RunReport> findLatestForVersion(@Param("versionId") UUID versionId);
}
```

- [ ] **Step 3: Add a run-scoped lookup to `GateRequestRepository`**

Edit `backend/src/main/java/com/agentstore/platform/execution/GateRequestRepository.java`, adding one method:

```java
public interface GateRequestRepository extends JpaRepository<GateRequest, UUID> {
    Optional<GateRequest> findFirstByRunIdAndStatusOrderByCreatedAtAsc(UUID runId, GateStatus status);

    Optional<GateRequest> findByIdAndRunId(UUID id, UUID runId);

    List<GateRequest> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
```

(add `import java.util.List;` alongside the existing imports)

- [ ] **Step 4: Write the failing test for `RunReportService`**

```java
// backend/src/test/java/com/agentstore/platform/execution/RunReportServiceTest.java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunReportServiceTest extends AbstractIntegrationTest {

    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    RuntimeFixtures runtimeFixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunReportRepository reports;

    @Test
    void aCleanSandboxRunGetsACleanReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.CLEAN);
        assertThat(report.getIssueCount()).isZero();
        assertThat(report.getReportJson()).contains("\"declaredVsActual\"");
    }

    @Test
    void violationsProduceAnIssuesReportWithTheViolationCount() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", "payroll"));
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.ISSUES);
        assertThat(report.getIssueCount()).isEqualTo(1);
    }

    @Test
    void hittingTheCostCapProducesAnOverCostLimitReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordUsage(run.getId(), new BigDecimal("1000"), Map.of("quantity", "1000", "unit", "reconciled_transaction"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.OVER_COST_LIMIT);
    }

    @Test
    void aTimedOutRunProducesATimedOutReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.terminate(run.getId(), RunStatus.RUNNING, RunStatus.TIMED_OUT, RunStepKind.TIMEOUT,
                Map.of("summary", "no heartbeat"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.TIMED_OUT);
    }

    @Test
    void anOtherwiseFailedRunProducesAFailedReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.fail(run.getId(), Map.of("summary", "crashed"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.FAILED);
    }

    @Test
    void aProductionRunGetsNoReport() {
        var agent = runtimeFixtures.runningAgent(new BigDecimal("500.00"));

        lifecycle.complete(agent.run().getId(), Map.of("summary", "done"));

        assertThat(reports.findById(agent.run().getId())).isEmpty();
    }
}
```

- [ ] **Step 5: Run it to verify it fails (no rows are ever written — `RunReportService` doesn't exist yet)**

Run: `cd backend && mvn -q test -Dtest=RunReportServiceTest`
Expected: FAIL — every `reports.findById(...)` is empty.

- [ ] **Step 6: Write `RunReportService`**

```java
// backend/src/main/java/com/agentstore/platform/execution/RunReportService.java
package com.agentstore.platform.execution;

import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the once-per-SANDBOX-run behaviour report after {@code RunLifecycleService.finish} commits (spec §5).
 * {@code @TransactionalEventListener} on purpose: unlike {@code OutputCommitService}'s plain {@code @EventListener},
 * this reads the run's full, already-committed step history rather than acting on one event's own payload, so it
 * does not need to share the finishing transaction.
 */
@Component
public class RunReportService {

    private final RunRepository runRepository;
    private final RunStepRepository stepRepository;
    private final LlmCallRepository llmCallRepository;
    private final GateRequestRepository gateRequestRepository;
    private final ManifestParser manifestParser;
    private final RunFlowService flowService;
    private final RunReportRepository reportRepository;
    private final ObjectMapper mapper;

    public RunReportService(RunRepository runRepository, RunStepRepository stepRepository,
                            LlmCallRepository llmCallRepository, GateRequestRepository gateRequestRepository,
                            ManifestParser manifestParser, RunFlowService flowService,
                            RunReportRepository reportRepository, ObjectMapper mapper) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.llmCallRepository = llmCallRepository;
        this.gateRequestRepository = gateRequestRepository;
        this.manifestParser = manifestParser;
        this.flowService = flowService;
        this.reportRepository = reportRepository;
        this.mapper = mapper;
    }

    @TransactionalEventListener
    @Transactional
    public void onRunFinished(RunFinishedEvent event) {
        Run run = runRepository.findById(event.runId()).orElseThrow();
        if (run.getMode() != RunMode.SANDBOX) {
            return;
        }
        generate(run);
    }

    private void generate(Run run) {
        List<RunStep> steps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        boolean timedOut = steps.stream().anyMatch(s -> s.getKind() == RunStepKind.TIMEOUT);
        boolean overCost = steps.stream().anyMatch(s -> s.getKind() == RunStepKind.COST_CAP_REACHED);
        long violations = steps.stream().filter(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).count();

        RunReportResult result;
        int issueCount = 0;
        if (timedOut) {
            result = RunReportResult.TIMED_OUT;
        } else if (overCost) {
            result = RunReportResult.OVER_COST_LIMIT;
        } else if (run.getStatus() == RunStatus.FAILED) {
            result = RunReportResult.FAILED;
        } else if (violations > 0) {
            result = RunReportResult.ISSUES;
            issueCount = (int) violations;
        } else {
            result = RunReportResult.CLEAN;
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("result", result.name());
        report.put("issueCount", issueCount);
        report.put("declaredVsActual", flowService.compute(run).blocks());
        report.put("network", network(run));
        report.put("cost", cost(run));
        report.put("llm", llm(run));
        report.put("gates", gates(run));
        report.put("timing", timing(run));
        report.put("logs", Map.of("runId", run.getId(),
                "note", "AGENT_LOG steps on this run carry what the agent logged; fetch this run's steps to read them"));

        String json;
        try {
            json = mapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the behaviour report", e);
        }
        reportRepository.save(new RunReport(run.getId(), result, issueCount, json));
    }

    private Map<String, Object> network(Run run) {
        Manifest manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        return Map.of("declaredAllowlist", manifest.networkAllowlist(), "note", "live visibility not yet available");
    }

    private Map<String, Object> cost(Run run) {
        boolean capUsed = run.getRequestedCharge().compareTo(run.getCostCap()) > 0;
        return Map.of("simulatedCharged", run.getSimulatedCharged(), "requestedCharge", run.getRequestedCharge(),
                "capUsed", capUsed);
    }

    private Map<String, Object> llm(Run run) {
        List<LlmCall> calls = llmCallRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        long tokens = calls.stream().mapToLong(c -> c.getInputTokens() + c.getOutputTokens()).sum();
        Manifest manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        long maxTokensPerRun = manifest.llm() == null ? 0 : manifest.llm().maxTokensPerRun();
        return Map.of("calls", calls.size(), "tokens", tokens,
                "cost", llmCallRepository.sumCostByRunId(run.getId()), "maxTokensPerRun", maxTokensPerRun);
    }

    private List<Map<String, Object>> gates(Run run) {
        return gateRequestRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream().map(gate -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("gateId", gate.getId());
            g.put("action", gate.getAction());
            g.put("summary", gate.getSummary());
            g.put("payload", parsePayload(gate));
            g.put("outcome", gate.getStatus().name());
            g.put("decidedAt", gate.getDecidedAt());
            return g;
        }).toList();
    }

    private Object parsePayload(GateRequest gate) {
        try {
            return mapper.readTree(gate.getPayload());
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> timing(Run run) {
        List<Map<String, Object>> gateWaits = gateRequestRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .map(gate -> {
                    Instant end = gate.getDecidedAt() != null ? gate.getDecidedAt() : Instant.now();
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("gateId", gate.getId());
                    w.put("waitSeconds", Duration.between(gate.getCreatedAt(), end).getSeconds());
                    return w;
                }).toList();
        return Map.of("startedAt", run.getStartedAt(), "completedAt", run.getCompletedAt(), "gateWaits", gateWaits);
    }
}
```

- [ ] **Step 7: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RunReportServiceTest`
Expected: PASS (6 tests)

- [ ] **Step 8: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V33__run_report.sql \
        backend/src/main/java/com/agentstore/platform/execution/RunReportResult.java \
        backend/src/main/java/com/agentstore/platform/execution/RunReport.java \
        backend/src/main/java/com/agentstore/platform/execution/RunReportRepository.java \
        backend/src/main/java/com/agentstore/platform/execution/GateRequestRepository.java \
        backend/src/main/java/com/agentstore/platform/execution/RunReportService.java \
        backend/src/test/java/com/agentstore/platform/execution/RunReportServiceTest.java
git commit -m "feat(execution): generate a run_report for every finished SANDBOX run"
```

---

## Task 4: Surface the report on `GET /api/v1/sandbox/runs/{id}`

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunViews.java`
- Test: `backend/src/test/java/com/agentstore/platform/sandbox/SandboxRunReportViewTest.java`

**Interfaces:**
- Consumes: `RunReportRepository.findById(UUID)` (Task 3, inherited from `JpaRepository`).
- Produces: `SandboxRunViews.SandboxRunDetail.report() : JsonNode` (nullable) — spec §8's "gains a report field".

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/agentstore/platform/sandbox/SandboxRunReportViewTest.java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxRunReportViewTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    SandboxFixtures fx;
    @Autowired
    RunLifecycleService lifecycle;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aFinishedSandboxRunExposesItsBehaviourReport() {
        var dev = fx.developer("valid-runtime-agent.json");
        Run run = fx.sandboxRun(dev, null, RunStatus.RUNNING);
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        var response = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) response.getBody().get("report")).get("result")).isEqualTo("CLEAN");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aRunningSandboxRunHasNoReportYet() {
        var dev = fx.developer("valid-runtime-agent.json");
        Run run = fx.sandboxRun(dev, null, RunStatus.RUNNING);

        var response = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);

        assertThat(response.getBody().get("report")).isNull();
    }
}
```

- [ ] **Step 2: Run it to verify it fails to compile (`SandboxRunDetail` has no `report` component yet)**

Run: `cd backend && mvn -q test -Dtest=SandboxRunReportViewTest`
Expected: FAIL — the test itself compiles (it only inspects a `Map`), but re-run after Step 3 shows `report` is currently absent/`null` in both cases; confirm first that `aFinishedSandboxRunExposesItsBehaviourReport` fails today (null pointer on `((Map) null).get("result")`).

- [ ] **Step 3: Wire `RunReport` into `SandboxRunViews`**

Edit `backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunViews.java` in full:

```java
package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateRequestRepository;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunReport;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.execution.RunStepResponse;
import com.agentstore.platform.llm.LlmCallRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds the API views of a sandbox run from its row, its steps, its LLM calls, and its behaviour report. */
@Component
public class SandboxRunViews {

    public record PendingGate(UUID id, String action, String summary, Instant expiresAt) {
    }

    public record SandboxRunSummary(UUID id, String displayId, UUID agentId, UUID agentVersionId, String versionLabel,
                                    String profileName, RunLlmMode llmMode, UUID replayOfRunId, RunStatus status,
                                    BigDecimal simulatedCharge, BigDecimal requestedCharge, BigDecimal costCap,
                                    long durationSeconds, BigDecimal aiCost, Instant startedAt, Instant completedAt) {
    }

    public record SandboxRunDetail(@com.fasterxml.jackson.annotation.JsonUnwrapped SandboxRunSummary run,
                                   List<RunStepResponse> steps, PendingGate pendingGate, JsonNode report) {
    }

    private final SandboxProfileRepository profiles;
    private final LlmCallRepository llmCalls;
    private final RunStepRepository steps;
    private final GateRequestRepository gates;
    private final RunReportRepository reports;
    private final ObjectMapper mapper;

    public SandboxRunViews(SandboxProfileRepository profiles, LlmCallRepository llmCalls, RunStepRepository steps,
                           GateRequestRepository gates, RunReportRepository reports, ObjectMapper mapper) {
        this.profiles = profiles;
        this.llmCalls = llmCalls;
        this.steps = steps;
        this.gates = gates;
        this.reports = reports;
        this.mapper = mapper;
    }

    public SandboxRunSummary summary(Run run) {
        String displayId = "S-" + run.getId().toString().substring(0, 8).toUpperCase();
        String profileName = run.getSandboxProfileId() == null ? null
                : profiles.findById(run.getSandboxProfileId()).map(SandboxProfile::getName).orElse(null);
        String versionLabel = run.getAgentVersion().getVersionNumber() + " "
                + run.getAgentVersion().getStatus().name().toLowerCase().replace('_', ' ');
        Instant end = run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now();
        long durationSeconds = run.getStartedAt() == null ? 0 : Duration.between(run.getStartedAt(), end).getSeconds();
        return new SandboxRunSummary(run.getId(), displayId, run.getAgentVersion().getAgent().getId(),
                run.getAgentVersion().getId(), versionLabel, profileName, run.getLlmMode(), run.getReplayRunId(),
                run.getStatus(), run.getSimulatedCharged(), run.getRequestedCharge(), run.getCostCap(), durationSeconds,
                llmCalls.sumCostByRunId(run.getId()), run.getStartedAt(), run.getCompletedAt());
    }

    public SandboxRunDetail detail(Run run) {
        List<RunStepResponse> stepList = steps.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .map(RunStepResponse::from).toList();
        PendingGate pendingGate = run.getStatus() == RunStatus.AWAITING_APPROVAL
                ? gates.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING)
                        .map(this::pendingGate).orElse(null)
                : null;
        JsonNode report = reports.findById(run.getId()).map(this::parseReport).orElse(null);
        return new SandboxRunDetail(summary(run), stepList, pendingGate, report);
    }

    private PendingGate pendingGate(GateRequest gate) {
        return new PendingGate(gate.getId(), gate.getAction(), gate.getSummary(), gate.getExpiresAt());
    }

    private JsonNode parseReport(RunReport report) {
        try {
            return mapper.readTree(report.getReportJson());
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=SandboxRunReportViewTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions (`SandboxGateTest`'s `pendingGate` assertion still holds — the new `report` field is additive).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/sandbox/SandboxRunViews.java \
        backend/src/test/java/com/agentstore/platform/sandbox/SandboxRunReportViewTest.java
git commit -m "feat(sandbox): expose the behaviour report on GET /api/v1/sandbox/runs/{id}"
```

---

## Task 5: `GET /api/v1/agents/{id}/versions/{vid}/readiness`

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/catalog/ReadinessResponse.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`
- Test: `backend/src/test/java/com/agentstore/platform/catalog/ReadinessControllerTest.java`

**Interfaces:**
- Consumes: `RunReportRepository.findLatestForVersion(UUID)` (Task 3), `AgentVersion.getBuildStatus()` (existing).
- Produces: `ReadinessResponse(boolean ready, List<Blocker> blockers)`, `ReadinessResponse.Blocker(String code, String message, UUID runId)`.

- [ ] **Step 1: Write the response record**

```java
// backend/src/main/java/com/agentstore/platform/catalog/ReadinessResponse.java
package com.agentstore.platform.catalog;

import java.util.List;
import java.util.UUID;

public record ReadinessResponse(boolean ready, List<Blocker> blockers) {

    public record Blocker(String code, String message, UUID runId) {
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
// backend/src/test/java/com/agentstore/platform/catalog/ReadinessControllerTest.java
package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunReport;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunReportResult;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ReadinessControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    SandboxFixtures fx;
    @Autowired
    RunReportRepository reports;
    @Autowired
    OrgRepository orgRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ReadinessResponse readiness(com.agentstore.platform.catalog.Agent agent, AgentVersion version, Org caller) {
        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/readiness"),
                HttpMethod.GET, new HttpEntity<>(fx.bearer(caller, UserRole.DEVELOPER)), ReadinessResponse.class);
        return response.getBody();
    }

    @Test
    void anUnbuiltDraftVersionIsNotReady() {
        var dev = fx.developer("valid-runtime-agent.json");

        ReadinessResponse body = readiness(dev.agent(), dev.version(), dev.org());

        assertThat(body.ready()).isFalse();
        assertThat(body.blockers()).extracting(ReadinessResponse.Blocker::code).containsExactly("BUILD_NOT_READY");
    }

    @Test
    void aBuiltVersionWithNoSandboxRunsIsReady() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.ready()).isTrue();
        assertThat(body.blockers()).isEmpty();
    }

    @Test
    void aBuiltVersionWithACleanLatestReportIsReady() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.COMPLETED);
        reports.save(new RunReport(run.getId(), RunReportResult.CLEAN, 0, "{}"));

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.ready()).isTrue();
    }

    @Test
    void issuesInTheLatestReportBecomeOneBlockerPerViolation() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.COMPLETED);
        reports.save(new RunReport(run.getId(), RunReportResult.ISSUES, 2, "{}"));

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.ready()).isFalse();
        assertThat(body.blockers()).hasSize(2)
                .allMatch(b -> b.code().equals("UNDECLARED_ACCESS") && b.runId().equals(run.getId()));
    }

    @Test
    void overCostLimitInTheLatestReportBlocksReadiness() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.FAILED);
        reports.save(new RunReport(run.getId(), RunReportResult.OVER_COST_LIMIT, 0, "{}"));

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.blockers()).extracting(ReadinessResponse.Blocker::code).containsExactly("COST_ENVELOPE_EXCEEDED");
    }

    @Test
    void aNonOwningDeveloperCannotSeeReadiness() {
        var dev = fx.developer("valid-runtime-agent.json");
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));

        var response = rest.exchange(
                url("/api/v1/agents/" + dev.agent().getId() + "/versions/" + dev.version().getId() + "/readiness"),
                HttpMethod.GET, new HttpEntity<>(fx.bearer(intruder, UserRole.DEVELOPER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 3: Run it to verify it fails to compile (no `readiness` endpoint yet)**

Run: `cd backend && mvn -q test -Dtest=ReadinessControllerTest`
Expected: FAIL — `404` on every call (no route), so `response.getBody()` deserialization/status assertions fail.

- [ ] **Step 4: Add the endpoint to `CatalogController`**

Edit `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`: add imports, a new field + constructor param, and the endpoint plus its shared helper.

```java
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunReportResult;
```

(add these three alongside the existing imports; `BuildStatus` is already reachable via `BuildSummaries` but not directly imported — add it explicitly)

```java
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final OrgRepository orgRepository;
    private final ManifestValidator manifestValidator;
    private final ManifestParser manifestParser;
    private final BuildSummaries buildSummaries;
    private final RunReportRepository runReportRepository;

    public CatalogController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository,
                              OrgRepository orgRepository, ManifestValidator manifestValidator, ManifestParser manifestParser,
                              BuildSummaries buildSummaries, RunReportRepository runReportRepository) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
        this.buildSummaries = buildSummaries;
        this.runReportRepository = runReportRepository;
    }
```

Add the endpoint and its shared ownership helper (after `detail(...)`, before `currentOrgId()`):

```java
    @GetMapping("/{agentId}/versions/{versionId}/readiness")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ReadinessResponse readiness(@PathVariable UUID agentId, @PathVariable UUID versionId) {
        AgentVersion version = ownedVersion(agentId, versionId);
        List<ReadinessResponse.Blocker> blockers = new ArrayList<>();
        if (version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            blockers.add(new ReadinessResponse.Blocker("BUILD_NOT_READY",
                    "This version has no successful build yet", null));
        }
        runReportRepository.findLatestForVersion(versionId).ifPresent(report -> {
            UUID runId = report.getRunId();
            if (report.getResult() == RunReportResult.ISSUES) {
                for (int i = 0; i < report.getIssueCount(); i++) {
                    blockers.add(new ReadinessResponse.Blocker("UNDECLARED_ACCESS",
                            "The last sandbox run attempted access outside its declared manifest", runId));
                }
            } else if (report.getResult() == RunReportResult.OVER_COST_LIMIT) {
                blockers.add(new ReadinessResponse.Blocker("COST_ENVELOPE_EXCEEDED",
                        "The last sandbox run asked for more than its cost envelope allows", runId));
            }
        });
        return new ReadinessResponse(blockers.isEmpty(), blockers);
    }

    /** Shared by {@link #readiness} and (Task 6) the manifest-patches endpoint: 404 if the version isn't this
     * agent's, 403 if the agent isn't the caller's (spec §6's rule, applied identically to readiness). */
    private AgentVersion ownedVersion(UUID agentId, UUID versionId) {
        AgentVersion version = agentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found"));
        if (!version.getAgent().getId().equals(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Version not found for this agent");
        }
        if (!version.getAgent().getDeveloperOrg().getId().equals(currentOrgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent not owned by caller's org");
        }
        return version;
    }
```

Add `import java.util.ArrayList;` and `import java.util.List;` alongside the existing `java.util.UUID` import.

- [ ] **Step 5: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ReadinessControllerTest`
Expected: PASS (6 tests)

- [ ] **Step 6: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/catalog/ReadinessResponse.java \
        backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java \
        backend/src/test/java/com/agentstore/platform/catalog/ReadinessControllerTest.java
git commit -m "feat(catalog): add GET /api/v1/agents/{id}/versions/{vid}/readiness"
```

---

## Task 6: Manifest patches

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__agent_version_manifest_revision.sql`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchRequest.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchResponse.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestApiException.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestApiErrors.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`
- Test: `backend/src/test/java/com/agentstore/platform/catalog/ManifestPatchControllerTest.java`

**Interfaces:**
- Consumes: `ManifestParser.parse`/`ManifestValidator.validate` (existing), `AgentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc(UUID, VersionStatus)` (existing), `CatalogController.ownedVersion` (Task 5).
- Produces: `ManifestPatchService.apply(String currentManifestJson, ManifestPatchRequest, String liveManifestJsonOrNull) : ManifestPatchService.Result`, `AgentVersion.getManifestRevision()`/`setManifestRevision(int)`.

- [ ] **Step 1: Write the migration and the entity field**

```sql
-- backend/src/main/resources/db/migration/V34__agent_version_manifest_revision.sql
ALTER TABLE agent_version ADD COLUMN manifest_revision INT NOT NULL DEFAULT 1;
```

Edit `backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java`, adding one field (the class already has `@Getter @Setter`):

```java
    private String changelog;
    private Instant createdAt;
    private Instant approvedAt;

    private int manifestRevision = 1;
```

- [ ] **Step 2: Write the request/response records and the coded exception**

```java
// backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchRequest.java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * One manifest patch op (spec §6): {@code add_read_scope} uses resource/description/requiresApproval;
 * {@code add_resource} uses name/format/schema; {@code add_allowlist_host} uses host; {@code remove_scope} uses
 * type/resource. Unused fields for a given op are null.
 */
public record ManifestPatchRequest(@NotBlank String op, String resource, String description,
                                   Boolean requiresApproval, String name, String format, JsonNode schema,
                                   String host, String type) {
}
```

```java
// backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchResponse.java
package com.agentstore.platform.manifest;

import java.util.List;

public record ManifestPatchResponse(int manifestRevision, Diff diff) {

    public record Diff(List<String> addedScopes, List<String> removedScopes, List<String> addedHosts) {
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/manifest/ManifestApiException.java
package com.agentstore.platform.manifest;

import org.springframework.http.HttpStatus;

public class ManifestApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ManifestApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
```

```java
// backend/src/main/java/com/agentstore/platform/manifest/ManifestApiErrors.java
package com.agentstore.platform.manifest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** The app hides ResponseStatusException reasons; manifest-patch failures need a stable {code, message} the
 * developer (and this plan's tests) can act on. */
@RestControllerAdvice
public class ManifestApiErrors {

    @ExceptionHandler(ManifestApiException.class)
    ResponseEntity<Map<String, String>> api(ManifestApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
}
```

- [ ] **Step 3: Write the failing controller test**

```java
// backend/src/test/java/com/agentstore/platform/catalog/ManifestPatchControllerTest.java
package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestPatchRequest;
import com.agentstore.platform.manifest.ManifestPatchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestPatchControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ManifestParser manifestParser;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authHeaders(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.DEVELOPER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String manifest(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    private AgentVersion draftVersion(Agent agent, String number) throws Exception {
        return agentVersionRepository.save(new AgentVersion(null, agent, number,
                manifest("valid-runtime-agent.json"), null, VersionStatus.DRAFT, Tier.TIER_A, "cl"));
    }

    private String patchUrl(Agent agent, AgentVersion version) {
        return url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/manifest/patches");
    }

    private ResponseEntity<ManifestPatchResponse> patch(Agent agent, AgentVersion version, UUID orgId, ManifestPatchRequest body) {
        return rest.postForEntity(patchUrl(agent, version), new HttpEntity<>(body, authHeaders(orgId)), ManifestPatchResponse.class);
    }

    private ResponseEntity<String> patchRaw(Agent agent, AgentVersion version, UUID orgId, ManifestPatchRequest body) {
        return rest.postForEntity(patchUrl(agent, version), new HttpEntity<>(body, authHeaders(orgId)), String.class);
    }

    @Test
    void addReadScopeAppendsANewReadScopeAndBumpsTheRevision() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patch(agent, version, org.getId(),
                new ManifestPatchRequest("add_read_scope", "vendor_master", "Read vendor master", null, null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().manifestRevision()).isEqualTo(2);
        assertThat(response.getBody().diff().addedScopes()).contains("read:vendor_master");
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).scopes()).anyMatch(s -> s.resource().equals("vendor_master"));
    }

    @Test
    void addingTheSameReadScopeTwiceConflicts() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_read_scope", "bank_statement", "dup", null, null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addResourceAppendsATypedResourceDeclaration() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo3", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patch(agent, version, org.getId(),
                new ManifestPatchRequest("add_resource", null, null, null, "vendor_master", "csv", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).resource("vendor_master")).isPresent();
    }

    @Test
    void addAllowlistHostIsIdempotent() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo4", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");
        var body = new ManifestPatchRequest("add_allowlist_host", null, null, null, null, null, null, "api.vendor.com", null);

        patch(agent, version, org.getId(), body);
        var second = patch(agent, version, org.getId(), body);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).networkAllowlist()).containsExactly("api.vendor.com");
    }

    @Test
    void removeScopeDropsAnExistingScope() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo5", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patch(agent, version, org.getId(),
                new ManifestPatchRequest("remove_scope", "invoices", null, null, null, null, null, null, "READ"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).scopes()).noneMatch(s -> s.resource().equals("invoices"));
    }

    @Test
    void removingAScopeThatIsNotDeclaredIs404() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo6", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("remove_scope", "nope", null, null, null, null, null, null, "READ"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aLiveVersionCannotBePatched() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo7", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0",
                manifest("valid-runtime-agent.json"), "img", VersionStatus.LIVE, Tier.TIER_A, "cl"));

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_allowlist_host", null, null, null, null, null, null, "x.com", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void diffComparesAgainstTheCurrentLiveVersionNotThePriorDraftState() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo8", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion live = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0",
                manifest("valid-tier-a.json"), "img", VersionStatus.LIVE, Tier.TIER_A, "cl"));
        live.setApprovedAt(Instant.now());
        agentVersionRepository.save(live);
        AgentVersion draft = draftVersion(agent, "1.1.0");

        var response = patch(agent, draft, org.getId(),
                new ManifestPatchRequest("add_read_scope", "vendor_master", "d", null, null, null, null, null, null));

        assertThat(response.getBody().diff().addedScopes())
                .containsExactlyInAnyOrder("read:invoices", "write:journal_entries", "read:vendor_master");
        assertThat(response.getBody().diff().removedScopes()).isEmpty();
    }

    @Test
    void anInvalidPatchResultIsRejected() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo9", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_resource", null, null, null, "bad", "not-a-format", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingRequiredFieldIsRejectedNotAServerError() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo10", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_read_scope", null, null, null, null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNonOwningDeveloperCannotPatch() throws Exception {
        Org owner = orgRepository.save(new Org(null, "Owner", OrgType.DEVELOPER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, owner, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, intruder.getId(),
                new ManifestPatchRequest("add_allowlist_host", null, null, null, null, null, null, "x.com", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
```

- [ ] **Step 4: Run it to verify it fails (no route yet)**

Run: `cd backend && mvn -q test -Dtest=ManifestPatchControllerTest`
Expected: FAIL — every call 404s (route doesn't exist).

- [ ] **Step 5: Write `ManifestPatchService`**

```java
// backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchService.java
package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mutates a DRAFT version's raw manifest JSON tree for one of the four developer-facing patch ops (spec §6),
 * re-validating the result through the existing {@link ManifestValidator} path before it is ever persisted.
 */
@Component
public class ManifestPatchService {

    private final ManifestParser parser;
    private final ManifestValidator validator;
    private final ObjectMapper mapper;

    public ManifestPatchService(ManifestParser parser, ManifestValidator validator, ObjectMapper mapper) {
        this.parser = parser;
        this.validator = validator;
        this.mapper = mapper;
    }

    public record Result(String manifestJson, ManifestPatchResponse.Diff diff) {
    }

    public Result apply(String currentManifestJson, ManifestPatchRequest request, String liveManifestJsonOrNull) {
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(currentManifestJson);
        } catch (Exception e) {
            throw new ManifestApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_MANIFEST",
                    "The stored manifest is not valid JSON");
        }

        switch (request.op()) {
            case "add_read_scope" -> addScope(root, ScopeType.READ, request);
            case "add_resource" -> addResource(root, request);
            case "add_allowlist_host" -> addAllowlistHost(root, request);
            case "remove_scope" -> removeScope(root, request);
            default -> throw new ManifestApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_OP",
                    "Unknown patch op: " + request.op());
        }

        String patched = root.toString();
        var validation = validator.validate(patched);
        if (!validation.valid()) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST", String.join("; ", validation.errors()));
        }

        Manifest patchedManifest = parser.parse(patched);
        Manifest liveManifest = liveManifestJsonOrNull == null ? null : parser.parse(liveManifestJsonOrNull);
        return new Result(patched, diff(patchedManifest, liveManifest));
    }

    private void addScope(ObjectNode root, ScopeType type, ManifestPatchRequest request) {
        if (request.resource() == null || request.resource().isBlank()) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "resource is required");
        }
        ArrayNode scopes = arrayField(root, "scopes");
        for (JsonNode s : scopes) {
            if (s.path("type").asText().equals(type.name()) && s.path("resource").asText().equals(request.resource())) {
                throw new ManifestApiException(HttpStatus.CONFLICT, "SCOPE_ALREADY_DECLARED",
                        "A " + type + " scope for '" + request.resource() + "' is already declared");
            }
        }
        ObjectNode scope = mapper.createObjectNode();
        scope.put("type", type.name());
        scope.put("resource", request.resource());
        scope.put("description", request.description() == null ? "" : request.description());
        if (request.requiresApproval() != null) {
            scope.put("requiresApproval", request.requiresApproval());
        }
        scopes.add(scope);
    }

    private void addResource(ObjectNode root, ManifestPatchRequest request) {
        if (request.name() == null || request.name().isBlank() || request.format() == null) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "name and format are required");
        }
        ArrayNode resources = arrayField(root, "resources");
        for (JsonNode r : resources) {
            if (r.path("name").asText().equals(request.name())) {
                throw new ManifestApiException(HttpStatus.CONFLICT, "RESOURCE_ALREADY_DECLARED",
                        "A resource named '" + request.name() + "' is already declared");
            }
        }
        ObjectNode resource = mapper.createObjectNode();
        resource.put("name", request.name());
        resource.put("format", request.format().toLowerCase(Locale.ROOT));
        if (request.schema() != null) {
            resource.set("schema", request.schema());
        }
        resources.add(resource);
    }

    private void addAllowlistHost(ObjectNode root, ManifestPatchRequest request) {
        if (request.host() == null || request.host().isBlank()) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "host is required");
        }
        ObjectNode network = root.has("network") && root.get("network").isObject()
                ? (ObjectNode) root.get("network") : root.putObject("network");
        ArrayNode allowlist = network.has("allowlist") && network.get("allowlist").isArray()
                ? (ArrayNode) network.get("allowlist") : network.putArray("allowlist");
        for (JsonNode host : allowlist) {
            if (host.asText().equals(request.host())) {
                return;   // idempotent: the host is already allowed
            }
        }
        allowlist.add(request.host());
    }

    private void removeScope(ObjectNode root, ManifestPatchRequest request) {
        if (request.type() == null || request.resource() == null) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "type and resource are required");
        }
        ArrayNode scopes = arrayField(root, "scopes");
        for (int i = 0; i < scopes.size(); i++) {
            JsonNode s = scopes.get(i);
            if (s.path("type").asText().equals(request.type()) && s.path("resource").asText().equals(request.resource())) {
                scopes.remove(i);
                return;
            }
        }
        throw new ManifestApiException(HttpStatus.NOT_FOUND, "SCOPE_NOT_FOUND",
                "No " + request.type() + " scope for '" + request.resource() + "' is declared");
    }

    private ArrayNode arrayField(ObjectNode root, String field) {
        return root.has(field) && root.get(field).isArray() ? (ArrayNode) root.get(field) : root.putArray(field);
    }

    private ManifestPatchResponse.Diff diff(Manifest patched, Manifest live) {
        List<String> liveScopes = live == null ? List.of() : scopeKeys(live);
        List<String> patchedScopes = scopeKeys(patched);
        List<String> added = patchedScopes.stream().filter(s -> !liveScopes.contains(s)).toList();
        List<String> removed = liveScopes.stream().filter(s -> !patchedScopes.contains(s)).toList();
        List<String> liveHosts = live == null ? List.of() : live.networkAllowlist();
        List<String> addedHosts = patched.networkAllowlist().stream().filter(h -> !liveHosts.contains(h)).toList();
        return new ManifestPatchResponse.Diff(added, removed, addedHosts);
    }

    private List<String> scopeKeys(Manifest manifest) {
        List<String> keys = new ArrayList<>();
        for (Scope s : manifest.scopes()) {
            keys.add(s.type().name().toLowerCase(Locale.ROOT) + ":" + s.resource());
        }
        return keys;
    }
}
```

- [ ] **Step 6: Wire the endpoint into `CatalogController`**

Add one more field/constructor param and the endpoint. Update the constructor:

```java
    private final RunReportRepository runReportRepository;
    private final ManifestPatchService manifestPatchService;

    public CatalogController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository,
                              OrgRepository orgRepository, ManifestValidator manifestValidator, ManifestParser manifestParser,
                              BuildSummaries buildSummaries, RunReportRepository runReportRepository,
                              ManifestPatchService manifestPatchService) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestValidator = manifestValidator;
        this.manifestParser = manifestParser;
        this.buildSummaries = buildSummaries;
        this.runReportRepository = runReportRepository;
        this.manifestPatchService = manifestPatchService;
    }
```

Add the endpoint (after `readiness(...)`):

```java
    @PostMapping("/{agentId}/versions/{versionId}/manifest/patches")
    @PreAuthorize("hasRole('DEVELOPER')")
    public ManifestPatchResponse patchManifest(@PathVariable UUID agentId, @PathVariable UUID versionId,
                                               @Valid @RequestBody ManifestPatchRequest request) {
        AgentVersion version = ownedVersion(agentId, versionId);
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new ManifestApiException(HttpStatus.CONFLICT, "VERSION_NOT_EDITABLE",
                    "Only a DRAFT version's manifest can be patched");
        }
        String liveManifest = agentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc(agentId, VersionStatus.LIVE)
                .map(AgentVersion::getManifestJson).orElse(null);
        var result = manifestPatchService.apply(version.getManifestJson(), request, liveManifest);
        version.setManifestJson(result.manifestJson());
        version.setManifestRevision(version.getManifestRevision() + 1);
        agentVersionRepository.save(version);
        return new ManifestPatchResponse(version.getManifestRevision(), result.diff());
    }
```

Add these imports alongside the existing ones:

```java
import com.agentstore.platform.manifest.ManifestApiException;
import com.agentstore.platform.manifest.ManifestPatchRequest;
import com.agentstore.platform.manifest.ManifestPatchResponse;
import com.agentstore.platform.manifest.ManifestPatchService;
```

- [ ] **Step 7: Run it to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ManifestPatchControllerTest`
Expected: PASS (11 tests)

- [ ] **Step 8: Run the full backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V34__agent_version_manifest_revision.sql \
        backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java \
        backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchRequest.java \
        backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchResponse.java \
        backend/src/main/java/com/agentstore/platform/manifest/ManifestApiException.java \
        backend/src/main/java/com/agentstore/platform/manifest/ManifestApiErrors.java \
        backend/src/main/java/com/agentstore/platform/manifest/ManifestPatchService.java \
        backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java \
        backend/src/test/java/com/agentstore/platform/catalog/ManifestPatchControllerTest.java
git commit -m "feat(catalog): add POST /api/v1/agents/{id}/versions/{vid}/manifest/patches"
```

---

## Task 7: OpenAPI drift test for the three new endpoints

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java`
- Create: `backend/src/test/java/com/agentstore/platform/common/PlatformContractTest.java`
- Create: `contracts/platform-v1.yaml` (generated by Step 3, not hand-written)

**Interfaces:**
- Consumes: the three endpoints from Tasks 2, 5, 6 (`GET /api/v1/runs/{runId}/flow`, `GET /api/v1/agents/{agentId}/versions/{versionId}/readiness`, `POST /api/v1/agents/{agentId}/versions/{versionId}/manifest/patches`).
- Produces: `/v3/api-docs/platform` (JSON) and `/v3/api-docs/platform.yaml` (YAML) springdoc endpoints, additive to the existing default `/v3/api-docs` document (deviation 2 in Global Constraints).

- [ ] **Step 1: Write the `GroupedOpenApi` bean**

```java
// backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java
package com.agentstore.platform.common;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PlatformOpenApiConfig {

    /**
     * A second, additive OpenAPI document (served at /v3/api-docs/platform) for WS6b's new /api/v1 endpoints.
     * springdoc.paths-to-match (application.yml) restricts the *default* document to /runtime/v1/**, which
     * RuntimeContractTest asserts; this group is independent of that restriction.
     */
    @Bean
    GroupedOpenApi platformOpenApi() {
        return GroupedOpenApi.builder()
                .group("platform")
                .pathsToMatch("/api/v1/runs/*/flow", "/api/v1/agents/*/versions/*/readiness",
                        "/api/v1/agents/*/versions/*/manifest/patches")
                .build();
    }
}
```

- [ ] **Step 2: Write the contract test, pointed at a not-yet-existing file**

```java
// backend/src/test/java/com/agentstore/platform/common/PlatformContractTest.java
package com.agentstore.platform.common;

import com.agentstore.platform.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformContractTest extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("../contracts/platform-v1.yaml");

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    @Test
    void theCommittedContractMatchesWhatTheControllersGenerate() throws Exception {
        String base = "http://localhost:" + port;
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject(base + "/v3/api-docs/platform", String.class));

        if (System.getProperty("contract.update") != null) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, rest.getForObject(base + "/v3/api-docs/platform.yaml", String.class));
        }

        JsonNode committed = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        assertThat(generated.equals(committed))
                .as("contracts/platform-v1.yaml is out of date. If the change is intended, regenerate it with: "
                        + "cd backend && mvn -q test -Dtest=PlatformContractTest -Dcontract.update=true, then commit the file.")
                .isTrue();
    }

    @Test
    void theContractDescribesExactlyTheThreeNewEndpoints() throws Exception {
        JsonNode generated = new ObjectMapper().readTree(
                rest.getForObject("http://localhost:" + port + "/v3/api-docs/platform", String.class));

        assertThat(generated.get("paths").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/api/v1/runs/{runId}/flow", "/api/v1/agents/{agentId}/versions/{versionId}/readiness",
                "/api/v1/agents/{agentId}/versions/{versionId}/manifest/patches");
    }
}
```

- [ ] **Step 3: Run it to verify it fails (no committed contract file yet)**

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest`
Expected: FAIL on `theCommittedContractMatchesWhatTheControllersGenerate` — `Files.readString(CONTRACT)` throws `NoSuchFileException`.

- [ ] **Step 4: Generate the contract file**

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest -Dcontract.update=true`
Expected: the first test still fails or errors this run (the assertion runs against the file it just wrote in the same test method, so it should now pass; if the framework quirk makes this run's assertion itself unreliable, that's fine — Step 5 re-runs cleanly), but `contracts/platform-v1.yaml` now exists.

- [ ] **Step 5: Run it again to verify both tests pass cleanly**

Run: `cd backend && mvn -q test -Dtest=PlatformContractTest`
Expected: PASS (2 tests)

- [ ] **Step 6: Run the full backend suite, including `RuntimeContractTest`, to confirm the default document is untouched**

Run: `cd backend && mvn -q test`
Expected: PASS, no regressions — in particular `RuntimeContractTest.theContractDescribesOnlyTheRuntimeApi` still passes (the default `/v3/api-docs` document still contains only `/runtime/v1/**` paths).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/common/PlatformOpenApiConfig.java \
        backend/src/test/java/com/agentstore/platform/common/PlatformContractTest.java \
        contracts/platform-v1.yaml
git commit -m "test(common): add an OpenAPI contract for WS6b's three new /api/v1 endpoints"
```

---

## Task 8: Update `ONTOLOGY.md`

**Files:**
- Modify: `ONTOLOGY.md`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Update the generation banner**

In `ONTOLOGY.md`, edit the line-8 generation banner to add WS6b to the list of later additions:

Old:
```
_Generated from commit `e295ec6` (2026-09-25, branch `feat/ws5a-sandbox-runner`). Later additions: build pipeline (WS5b), developer API keys and the CLI (WS5c), Agent Runtime API (WS1), data plane (WS2), LLM gateway (WS3), sandbox core (WS6a, branch `feat/ws6a-sandbox-core`). Audited against master at commit `7fb6b50` (2026-09-27, after the WS3 merge), then updated in place for WS6a. Regenerate when core schemas or APIs change._
```

New:
```
_Generated from commit `e295ec6` (2026-09-25, branch `feat/ws5a-sandbox-runner`). Later additions: build pipeline (WS5b), developer API keys and the CLI (WS5c), Agent Runtime API (WS1), data plane (WS2), LLM gateway (WS3), sandbox core (WS6a, branch `feat/ws6a-sandbox-core`), behaviour report/flow/readiness/manifest patches (WS6b). Audited against master at commit `7fb6b50` (2026-09-27, after the WS3 merge), then updated in place for WS6a and WS6b. Regenerate when core schemas or APIs change._
```

- [ ] **Step 2: Update the Execution section's `RunStep` sentence**

Find the sentence (in the "Execution" subsection, currently ending with the WS6a step-kind list):

Old:
```
New step kinds: `DATA_READ`, `DATA_WRITE`, `WRITE_STAGED`, `GATE_REQUESTED`, `GATE_DECIDED`, `LLM_CALL`, `USAGE`, `SCOPE_VIOLATION`, `AGENT_LOG`, `CANCELLED` (WS6a, platform-sourced).
```

New:
```
New step kinds: `DATA_READ`, `DATA_WRITE`, `WRITE_STAGED`, `GATE_REQUESTED`, `GATE_DECIDED`, `LLM_CALL`, `USAGE`, `SCOPE_VIOLATION`, `AGENT_LOG`, `CANCELLED` (WS6a, platform-sourced). As of WS6b, `RunStep` also carries `block` (migration `V32__run_step_block.sql`): a manifest-shaped string (`read:<resource>`, `write:<resource>`, `draft:<resource>`, `gate:<action>`, `llm`, `charge`, or null) computed once, centrally, by `RunStepBlocks` from each step's own recording data — never a per-call-site concern. `RunFlowService` groups a run's steps by `block` against its manifest's declared scopes/gates/llm/cost-envelope to answer `GET /api/v1/runs/{id}/flow` and to build the behaviour report below.
```

- [ ] **Step 3: Add a WS6b subsection after "Sandbox core (WS6a)"**

Insert a new subsection immediately after the existing "Sandbox core (WS6a)" paragraph (the one ending "...see the deviations below."):

```markdown
### Behaviour report, flow & readiness (WS6b)
After a SANDBOX run finishes, `RunReportService` (a `@TransactionalEventListener` on `RunFinishedEvent`, filtered to `run.mode == SANDBOX`) writes one `run_report` row (`backend/src/main/java/com/agentstore/platform/execution/RunReport.java`, table `run_report`, migration `V33__run_report.sql`): a real `result` column (`CLEAN`/`ISSUES`/`TIMED_OUT`/`OVER_COST_LIMIT`/`FAILED`, precedence in that order) and `issue_count`, plus a `report_json` blob (declared-vs-actual blocks, network allowlist, cost, LLM totals, gate payload/outcome pairs, timing, a logs pointer). `GET /api/v1/runs/{id}/flow` exposes the same declared-vs-actual block view live for any run the caller can already view (production buyer or sandbox initiator, generalizing the run-detail endpoint's auth). `GET /api/v1/agents/{id}/versions/{vid}/readiness` reads only `run_report`'s real columns plus `agent_version.build_status` — never `report_json` — to answer `{ready, blockers}` (`BUILD_NOT_READY`, `UNDECLARED_ACCESS` per violation, `COST_ENVELOPE_EXCEEDED`); it is informational only until a submit endpoint exists. `POST /api/v1/agents/{id}/versions/{vid}/manifest/patches` (DEVELOPER, DRAFT-only, `409 VERSION_NOT_EDITABLE` otherwise) mutates the raw manifest JSON tree for one of four ops (`add_read_scope`, `add_resource`, `add_allowlist_host`, `remove_scope`), re-validates through the existing `ManifestValidator`, and increments `agent_version.manifest_revision` (migration `V34__agent_version_manifest_revision.sql`), returning a diff against the agent's current LIVE manifest. `sandbox_profile`, `sandbox report` CLI, and the in-sandbox network proxy remain deferred (see below).
```

- [ ] **Step 4: Add the three new endpoints to section 5's API mappings**

In section "5. Tool, API, & Function Mappings", under "### Connections & runs", add a line for the flow endpoint; under "### Sandbox core (WS6a)" (or a small new "### Behaviour report & readiness (WS6b)" subsection right after it, matching the existing per-workstream grouping in that section), add lines for readiness and manifest patches, each pointing at the controller method added in this plan (`RunDetailController.flow`, `CatalogController.readiness`, `CatalogController.patchManifest`).

- [ ] **Step 5: Update the Inconsistencies section**

Find the sentence in "Sandbox core (WS6a)" (around the existing "**`run_report`... not built here**" line, if present as written, or the "not built here" clause in the WS6a description near line 100):

Old (the "not built here" clause from the current file):
```
`usage_event`, `developer_quota` and `run_report` from the parent design (`docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md`) are **not** built here; see the deviations below.
```

New:
```
`usage_event` and `developer_quota` from the parent design (`docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md`) are **not** built here; see the deviations below. `run_report` was built in WS6b (above).
```

Then, in the "Inconsistencies & Unclear Areas" section, add one new bullet documenting this plan's deviations (mirroring the existing WS6a bullets' style):

```markdown
- **WS6b splits the spec's single "migration V32" into three files, adds a second springdoc group instead of touching the runtime contract, and resolves a spec self-contradiction over `COST_CAP_REACHED`'s block:** see `docs/superpowers/plans/2026-09-27-ws6b-behaviour-report-flow-readiness-manifest-patches.md`'s "Documented deviations" for the reasoning on each.
```

- [ ] **Step 6: Confirm the stale "not built here" language is gone**

Run: `grep -n "run_report.*not.*built\|not.*built.*run_report" ONTOLOGY.md`
Expected: no output (the old phrasing no longer appears; Step 5's replacement removed it).

- [ ] **Step 7: Commit**

```bash
git add ONTOLOGY.md
git commit -m "docs: update ONTOLOGY.md for WS6b (run_step.block, run_report, readiness, manifest patches)"
```

---

## Self-Review

**Spec coverage:**
- §2 data model → Tasks 1, 3, 6 (three migrations, deviation 1 documented).
- §3 block model / call sites → Task 1 (`RunStepBlocks`, `ScopeGuard`'s violations via `RunLifecycleService.step`, gate controller via `GateService.step`, LLM gateway via existing `LlmCallLog`→`RunStepRecorder`→`RunLifecycleService.recordStep`, `charge()` via the same `step` method).
- §4 `GET /flow` → Task 2.
- §5 behaviour report → Task 3, surfaced per §8 in Task 4.
- §6 manifest patches → Task 6.
- §7 readiness → Task 5.
- §8 API summary → Tasks 2, 3/4, 5, 6 cover all four listed changes; "no new CLI" honored (no CLI files touched anywhere in this plan).
- §9 testing → block-assignment unit + integration tests (Task 1), `/flow` state coverage including UNDECLARED/SKIPPED (Task 2), one test per `run_report` result path plus SANDBOX-only (Task 3), manifest-patch op happy paths/DRAFT-only/revision/diff-with-and-without-LIVE (Task 6), readiness per-blocker (Task 5), OpenAPI drift test (Task 7), no pre-existing test file edited (verified per-task by only ever creating new test files).
- §10 follow-ups → left alone; nothing in this plan builds the callsite/SDK/proxy/`NETWORK_BLOCKED`/`network-events` slice, WS1b proper, or 6c's items.

**Placeholder scan:** every step above has runnable code or an exact shell command; no "TBD"/"add appropriate handling" phrasing appears.

**Type consistency:** `RunStepBlocks.of` (Task 1) is consumed identically in `RunLifecycleService.step` and `GateService.step`. `FlowView`/`FlowBlock`/`BlockState` (Task 2) are produced by `RunFlowService.compute` and consumed as-is by `RunReportService.generate` (Task 3, `flowService.compute(run).blocks()`) and by `RunDetailController.flow` (Task 2). `RunReportRepository.findLatestForVersion`/`findById` (Task 3) are consumed by Task 5's readiness and Task 4's sandbox-detail view respectively, with matching signatures throughout. `ManifestPatchService.Result` (Task 6) matches `CatalogController.patchManifest`'s usage exactly.

**Review Focus:** all five items each have a named test in the task that owns the code (see inline callouts in Tasks 1, 2, 6).
