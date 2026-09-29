# WS1 — Agent Runtime API v1 Implementation Plan


**Goal:** Agent code talks to the platform through `/runtime/v1/**`, authenticated by its run token, and the platform records the run's trace itself; the legacy `/internal/agent-callback/**` keeps working behind a flag.

**Architecture:** A new `runtime` package holds a separate `SecurityFilterChain` for `/runtime/**` (a `RuntimeAuthFilter` that resolves the run from the token hash and applies a Redis-backed bucket4j rate limit), a single `ScopeGuard`, thin controllers, and problem+json errors. Every state change still goes through `RunLifecycleService` (row-locked) or the new `GateService` in the `execution` package. `/resources/**` and `/llm/chat` delegate to `ResourceBackend` / `LlmBackend` interfaces that answer 501 until WS2 / WS3.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Security, Flyway (V25, V26), JPA/Hibernate, Postgres 16, Lettuce + bucket4j (`bucket4j_jdk17-lettuce` 8.20.0) on Redis 7.2, springdoc-openapi 2.6.0, JUnit 5 + Testcontainers, TypeScript (cli, frontend).

**Spec:** `docs/superpowers/specs/2026-09-26-ws1-agent-runtime-api-design.md` (approved 2026-09-26). It narrows the WS1 section of `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md`; where they differ, the WS1 spec wins.

## Global Constraints

- Work on branch `feat/ws1-agent-runtime-api`, cut from `docs/ws1-design` (so the spec commit rides along). Nothing is pushed until the user says so.
- Backend tests: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test [-Dtest=ClassName]`. Docker runs through Colima (`colima start` if it is down). Frontend/CLI: `npm ci` first if `node_modules` is missing, then `npx vitest run` and (frontend) `npx tsc --noEmit`.
- Last existing migration is `V24__developer_api_key.sql`; new ones are **V25** and **V26**. `spring.jpa.hibernate.ddl-auto` is `validate`, so every migration must match the entities exactly. `open-in-view` is `false`.
- Run token = 32 random bytes, stored only as SHA-256 hex in `run.callback_token_hash`, compared with `RunTokenService.matches` (constant time). Valid only while the run is non-terminal and before `deadline_at`.
- Path prefix is `/runtime/v1`. API-version convention: a `User-Agent` containing `runtime-api/N` with `N != 1` gets `400 UNSUPPORTED_API_VERSION`; no such token is accepted.
- Error model is RFC 7807 `application/problem+json` with stable `code`: `UNAUTHORIZED` (401), `SCOPE_NOT_GRANTED` (403), `RUN_TERMINATED` (409), `BUDGET_EXCEEDED` (402), `GATE_REJECTED`, `RATE_LIMITED` (429, with `Retry-After`), `NOT_IMPLEMENTED` (501), `UNSUPPORTED_API_VERSION` (400), `VALIDATION_FAILED` (400).
- **Granted scope rule:** a scope is granted to a run when the manifest declares it (`type` + `resource`) **and** the buyer's connection lists its `resource` in `grantedScopes`. Resource names match exactly (case-sensitive).
- Manifest additions are backward compatible: `requiresApproval` on scopes (default `false`), `timeoutMinutes` on gates (default `30`, 1–1440). Gate `action` must equal a declared gate `trigger`; `trigger` is at most 100 characters.
- PRODUCTION runs stop on the **third** scope violation; SANDBOX runs only report. Rejecting a gate still cancels the run (`CANCELLED`, sandbox killed).
- Redis image is `redis:7.2-alpine` (the last BSD-licensed release; no paid vendor). If Redis is unreachable the rate limiter **fails open** (logs a warning) and never blocks startup.
- **The existing legacy-callback tests must pass unchanged** (`AgentCallbackControllerTest`, `AgentCallbackCompletionTest`, `ApprovalGateTest`, `RunControllerTest`, `RunControllerWalletTest`, `RunLaunchFromBuildTest`, `EndToEndTierATest`, `EndToEndTierBTest`, …). Never edit those files to make them pass.
- Out of scope (do not build): WS1b items (display IDs, `terminal_reason`, step `block`, `/runs/{id}/flow`, grouped run list, `estimated_units`, `network-events`), real datasets/LLM (WS2/WS3), SDKs, sandbox runs, nullable `connection_id`, `gate_request.preview/decided_via/eligible_approver_ids`, flagging versions after violations, removing the legacy callback.

## Review Focus

Failure modes the spec implies but no task's happy path exercises; each has a pinning test in the task named.

1. **Redis unreachable or slow** (at startup or mid-run): every runtime call must still succeed and the app must still start. → Task 4 (`RuntimeRateLimiterOutageTest`).
2. **Concurrent scope violations** on one run: exactly three `SCOPE_VIOLATION` steps and one stop, never a double-kill or a fourth recorded violation. → Task 2 (`concurrentViolationsStopTheRunExactlyOnce`).
3. **A buyer decision racing the reaper's gate expiry, and a poll on a run that was just cancelled**: the gate and run must never end in a mixed state, and calls with a dead run's token get `409 RUN_TERMINATED`, never a 500. → Task 3 (`decisionAndExpiryNeverLeaveAMixedState`), Task 7 (`rejectedGateEndsTheRunAndTheTokenStopsWorking`).
4. **Bad parameters and bodies**: negative/huge/non-numeric `wait` and `limit`, empty or oversized log batches, empty record writes, zero/negative/non-numeric usage quantity → 400 or clamped, never a hang or a 500. → Tasks 6 and 7.
5. **Resource-name variants**: `Bank_Statement` (case), an encoded slash, an empty-looking name, a resource the manifest declares but the buyer did not grant, a WRITE-only resource read as READ → all `403 SCOPE_NOT_GRANTED` (or a 4xx from the firewall), each recorded as a violation. → Task 6 (`ScopeGuardTest`, `RuntimeResourceEndpointsTest`).

## File Structure

**Backend, new** (`backend/src/main/java/com/agentstore/platform/…`)
- `execution/RunMode.java`, `RunStepSource.java`, `GateStatus.java`, `GateDecision.java`, `GateDecisionRequest.java` — small enums/records.
- `execution/GateRequest.java`, `GateRequestRepository.java` — the `gate_request` table.
- `execution/GateService.java` — open/decide/await/expire gates (row-locked, in `execution` so it can use package-level lifecycle pieces).
- `execution/RunTerminatedException.java`, `UsageOutcome.java` — shared by lifecycle and runtime.
- `runtime/RunPrincipal.java` — authenticated run identity (`runId, orgId, mode, manifest, grantedScopes`).
- `runtime/RuntimeAuthFilter.java`, `RuntimeRateLimiter.java`, `RuntimeRedisConfig.java` — auth, limiter, Redis client.
- `runtime/RuntimeApiException.java`, `ProblemBody.java`, `ProblemResponses.java`, `RuntimeExceptionHandler.java` — problem+json errors.
- `runtime/ScopeGuard.java`, `RunStepRecorder.java` — enforcement and trace recording.
- `runtime/ResourceBackend.java`, `LlmBackend.java`, `UnavailableResourceBackend.java`, `UnavailableLlmBackend.java` — WS2/WS3 seams.
- `runtime/Runtime{Context,Resource,Gate,Llm,Usage,Lifecycle}Controller.java` plus request/response records.
- `runtime/RuntimeOpenApiConfig.java` — OpenAPI metadata.
- `db/migration/V25__runtime_api.sql`, `V26__gate_request.sql`.

**Backend, modified:** `execution/Run.java`, `RunStep.java`, `RunStepKind.java`, `RunStepResponse.java`, `RunRepository.java`, `RunLifecycleService.java`, `RunReaper.java`, `RunController.java`, `RunLauncher.java`, `AgentCallbackController.java`, `FakeSandboxProvider.java`; `manifest/Scope.java`, `ApprovalGateDecl.java`, `ManifestParser.java`, `resources/manifest/manifest-schema.json`; `identity/SecurityConfig.java`; `resources/application.yml`; `pom.xml`.

**Repo root:** `docker-compose.yml` (Redis), `contracts/runtime-v1.yaml` + `contracts/README.md`, `ONTOLOGY.md`.

**CLI:** `cli/src/scaffold.ts`, `cli/test/scaffold.test.ts`. **Frontend:** `frontend/src/lib/types.ts`, `components/RunTraceStep.tsx` (+ test).

**Tests, new** (`backend/src/test/java/com/agentstore/platform/…`): `execution/RuntimeDataModelTest`, `RunLifecycleRuntimeTest`, `GateServiceTest`, `GateDecisionEndpointTest`, `LegacyCallbackFlagOffTest`, `LegacyEnvVarsTest`; `manifest/ManifestRuntimeFieldsTest`; `runtime/RuntimeFixtures`, `InMemoryBackends`, `RuntimeRateLimiterTest`, `RuntimeRateLimiterOutageTest`, `RuntimeAuthTest`, `RuntimeHttpRateLimitTest`, `ScopeGuardTest`, `RuntimeResourceEndpointsTest`, `RuntimeGateEndpointsTest`, `RuntimeUsageEndpointTest`, `RuntimeLifecycleEndpointsTest`, `RuntimeEndpointMatrixTest`, `RuntimeEndToEndTest`, `RuntimeContractTest`; test resource `manifest/valid-runtime-agent.json`. Modified test helpers: `AbstractIntegrationTest` (Redis container), `execution/ExecutionFixtures` (`liveWithManifest`).

---

### Task 1: Data model — migrations, entities, manifest fields

**Files:**
- Modify: `docs/superpowers/specs/2026-09-26-ws1-agent-runtime-api-design.md` (amendments)
- Create: `backend/src/main/resources/db/migration/V25__runtime_api.sql`, `V26__gate_request.sql`
- Create: `backend/src/main/java/com/agentstore/platform/execution/{RunMode,RunStepSource,GateStatus,GateRequest,GateRequestRepository}.java`
- Modify: `execution/{Run,RunStep,RunStepKind,RunStepResponse,RunRepository}.java`, `manifest/{Scope,ApprovalGateDecl,ManifestParser}.java`, `resources/manifest/manifest-schema.json`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RuntimeDataModelTest.java`, `manifest/ManifestRuntimeFieldsTest.java`

**Interfaces:**
- Produces: `RunMode {PRODUCTION, SANDBOX, REVIEW, TRIAL}`; `RunStepSource {PLATFORM, AGENT}`; `GateStatus {PENDING, APPROVED, REJECTED, EXPIRED}`; `Run.getMode()/setMode`, `getViolationCount()/setViolationCount(int)`, `getInputJson()/setInputJson(String)`; `RunStep(UUID, Run, RunStepKind, String)` (source = `kind.defaultSource()`), `RunStep(UUID, Run, RunStepKind, String, RunStepSource)`, `getSource()`; `RunStepKind.defaultSource()`; `GateRequest(Run run, String action, String summary, String payload, String affectsResources, Instant expiresAt)` with getters/setters `getId, getRun, getAction, getSummary, getPayload, getAffectsResources, getStatus/setStatus, getDecidedByUserId/setDecidedByUserId, getDecisionNote/setDecisionNote, getCreatedAt, getDecidedAt/setDecidedAt, getExpiresAt`; `GateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(UUID, GateStatus): Optional<GateRequest>`, `findByIdAndRunId(UUID, UUID): Optional<GateRequest>`; `RunRepository.findFirstByCallbackTokenHash(String): Optional<Run>`; `Scope(type, resource, description, boolean requiresApproval)` (+ 3-arg constructor); `ApprovalGateDecl(trigger, description, int timeoutMinutes)` (+ 2-arg constructor, `DEFAULT_TIMEOUT_MINUTES = 30`); `RunStepResponse(RunStepKind kind, RunStepSource source, String detail, Instant createdAt)`.

- [ ] **Step 1: Create the branch and amend the spec**

```bash
cd /Users/amittiwari/AgentStore
git switch docs/ws1-design
git switch -c feat/ws1-agent-runtime-api
```

Apply these five edits to `docs/superpowers/specs/2026-09-26-ws1-agent-runtime-api-design.md` (use the Edit tool; each `old_string` appears once):

1. old: `V25 adds a **unique index on \`run.callback_token_hash\`**, which does not exist today (V12 only renamed the column).`
   new: `V25 adds an index on \`run.callback_token_hash\`, which does not exist today (V12 only renamed the column). It is deliberately **not unique**: production tokens are random 256-bit values, but existing tests give several runs the same fixed token string, and the legacy-callback tests must pass unchanged. Lookup uses \`findFirstByCallbackTokenHash\`.`
2. old: `the unique token-hash index,`
   new: `the token-hash index (not unique, see §2),`
3. old: `Redis is added to \`docker-compose.yml\` as one container (self-hosted OSS, so it stays within the no-paid-vendors rule). The backend adds \`spring-boot-starter-data-redis\` and bucket4j's Redis integration.`
   new: `Redis is added to \`docker-compose.yml\` as one container, \`redis:7.2-alpine\` (the last BSD-licensed release; self-hosted OSS, so it stays within the no-paid-vendors rule). The backend adds \`io.lettuce:lettuce-core\` and \`com.bucket4j:bucket4j_jdk17-lettuce\` and builds its own Lettuce \`RedisClient\` (created lazily, fast timeouts, commands rejected while disconnected) rather than using \`spring-boot-starter-data-redis\`: no auto-configuration, no health indicator that would turn \`/actuator/health\` red when Redis is down. \`AbstractIntegrationTest\` starts a shared Redis Testcontainer.`
4. old: `A gate \`action\` must equal a declared gate's \`trigger\`.`
   new: `A gate \`action\` must equal a declared gate's \`trigger\` (at most 100 characters; the schema enforces it). **A scope is granted to a run when the manifest declares it and the buyer's connection lists its resource in \`grantedScopes\`** (buyers grant resource names, not READ/WRITE pairs). The \`User-Agent\` version convention is a \`runtime-api/N\` token; only \`runtime-api/1\` is accepted, and a request without the token is accepted too.`
5. old: `- The legacy \`/internal/agent-callback/**\` stays behind a flag; \`RunLauncher\` injects both sets of environment variables; the \`agentstore init\` scaffold moves to \`/runtime/v1\`.`
   new: that same line, followed by a new line: `- Small frontend change: \`RunStepResponse\` gains \`source\`, and the run console labels the new step kinds and marks agent-reported steps.`

```bash
git add docs/superpowers/specs/2026-09-26-ws1-agent-runtime-api-design.md
git commit -m "docs: amend WS1 spec (plain token index, Lettuce client, grant rule, step labels)"
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/manifest/ManifestRuntimeFieldsTest.java`:

```java
package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestRuntimeFieldsTest {

    private final ManifestParser parser = new ManifestParser();
    private final ManifestValidator validator = new ManifestValidator();

    private static String manifest(String scope, String gate) {
        return """
                {"name":"A","version":"1.0.0","scopes":[%s],"network":{"allowlist":[]},
                 "costEnvelope":{"currency":"INR","maxPerRun":10,"pricingUnit":"METERED","unitPrice":1,"unitLabel":"u"},
                 "approvalGates":[%s],"dataClasses":[]}""".formatted(scope, gate);
    }

    private static final String PLAIN_SCOPE = "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\"}";
    private static final String PLAIN_GATE = "{\"trigger\":\"g\",\"description\":\"d\"}";

    @Test
    void newFieldsDefaultWhenAbsent() {
        Manifest parsed = parser.parse(manifest(PLAIN_SCOPE, PLAIN_GATE));

        assertThat(parsed.scopes().get(0).requiresApproval()).isFalse();
        assertThat(parsed.approvalGates().get(0).timeoutMinutes()).isEqualTo(30);
    }

    @Test
    void newFieldsAreParsedWhenPresent() {
        Manifest parsed = parser.parse(manifest(
                "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\",\"requiresApproval\":true}",
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":5}"));

        assertThat(parsed.scopes().get(0).requiresApproval()).isTrue();
        assertThat(parsed.approvalGates().get(0).timeoutMinutes()).isEqualTo(5);
    }

    @Test
    void schemaAcceptsTheNewFields() {
        var result = validator.validate(manifest(
                "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\",\"requiresApproval\":true}",
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":1440}"));

        assertThat(result.valid()).as(result.errors().toString()).isTrue();
    }

    @Test
    void schemaRejectsBadValues() {
        assertThat(validator.validate(manifest(
                "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\",\"requiresApproval\":\"yes\"}", PLAIN_GATE)).valid()).isFalse();
        assertThat(validator.validate(manifest(PLAIN_SCOPE,
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":0}")).valid()).isFalse();
        assertThat(validator.validate(manifest(PLAIN_SCOPE,
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":1441}")).valid()).isFalse();
        assertThat(validator.validate(manifest(PLAIN_SCOPE,
                "{\"trigger\":\"" + "x".repeat(101) + "\",\"description\":\"d\"}")).valid()).isFalse();
    }
}
```

`backend/src/test/java/com/agentstore/platform/execution/RuntimeDataModelTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RuntimeDataModelTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository runStepRepository;
    @Autowired
    GateRequestRepository gateRequestRepository;

    private Run newRun(String token) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
    }

    @Test
    void newRunsDefaultToProductionWithNoViolationsAndEmptyInput() {
        Run saved = newRun("tok-model-1");

        Run loaded = runRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getMode()).isEqualTo(RunMode.PRODUCTION);
        assertThat(loaded.getViolationCount()).isZero();
        assertThat(loaded.getInputJson()).isEqualTo("{}");
    }

    @Test
    void stepsRecordWhoObservedThem() {
        Run run = newRun("tok-model-2");
        runStepRepository.save(new RunStep(null, run, RunStepKind.READ, "{}"));
        runStepRepository.save(new RunStep(null, run, RunStepKind.TIMEOUT, "{}"));
        runStepRepository.save(new RunStep(null, run, RunStepKind.DATA_READ, "{}", RunStepSource.PLATFORM));

        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .extracting(RunStep::getKind, RunStep::getSource)
                .containsExactlyInAnyOrder(
                        tuple(RunStepKind.READ, RunStepSource.AGENT),
                        tuple(RunStepKind.TIMEOUT, RunStepSource.PLATFORM),
                        tuple(RunStepKind.DATA_READ, RunStepSource.PLATFORM));
    }

    @Test
    void gateRequestsAreStoredAndFoundByRunAndStatus() {
        Run run = newRun("tok-model-3");

        GateRequest gate = gateRequestRepository.save(new GateRequest(run, "post_journal_entries", "Post 12 entries",
                "{\"rows\":12}", "[\"journal_entries\"]", Instant.now().plusSeconds(60)));

        assertThat(gate.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING))
                .get().extracting(GateRequest::getId).isEqualTo(gate.getId());
        assertThat(gateRequestRepository.findByIdAndRunId(gate.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aRunIsFoundByItsTokenHash() {
        Run run = newRun("tok-model-4");

        assertThat(runRepository.findFirstByCallbackTokenHash(RunTokenService.hash("tok-model-4")))
                .get().extracting(Run::getId).isEqualTo(run.getId());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ManifestRuntimeFieldsTest,RuntimeDataModelTest'`
Expected: COMPILATION ERROR (`RunMode`, `GateRequest`, `requiresApproval()` … not defined).

- [ ] **Step 4: Migrations**

`V25__runtime_api.sql`:

```sql
ALTER TABLE run ADD COLUMN mode VARCHAR(20) NOT NULL DEFAULT 'PRODUCTION';
ALTER TABLE run ADD COLUMN violation_count INT NOT NULL DEFAULT 0;
ALTER TABLE run ADD COLUMN input_json JSONB NOT NULL DEFAULT '{}';

-- On /runtime/v1 the token, not the run id in the path, identifies the run. Deliberately not UNIQUE: production
-- tokens are random 256-bit values, but existing tests give several runs the same fixed token string.
CREATE INDEX run_callback_token_hash_idx ON run (callback_token_hash);

ALTER TABLE run_step ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'AGENT';
ALTER TABLE run_step ALTER COLUMN kind TYPE VARCHAR(30);
UPDATE run_step SET source = 'PLATFORM' WHERE kind IN ('TIMEOUT', 'COST_CAP_REACHED');
```

`V26__gate_request.sql`:

```sql
CREATE TABLE gate_request (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES run(id),
    action VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    payload JSONB NOT NULL,
    affects_resources JSONB NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL,
    decided_by_user_id UUID,          -- no FK: tests decide with synthetic user ids
    decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX gate_request_run_status_idx ON gate_request (run_id, status);
```

- [ ] **Step 5: Enums and entities**

`execution/RunMode.java`:
```java
package com.agentstore.platform.execution;

/** Who a run belongs to and whether money moves (spec §4.1). Only PRODUCTION runs exist until the sandbox work (WS6). */
public enum RunMode { PRODUCTION, SANDBOX, REVIEW, TRIAL }
```
`execution/RunStepSource.java`:
```java
package com.agentstore.platform.execution;

/** PLATFORM: observed by the platform itself. AGENT: reported by the agent, so only as trustworthy as the agent. */
public enum RunStepSource { PLATFORM, AGENT }
```
`execution/GateStatus.java`:
```java
package com.agentstore.platform.execution;

public enum GateStatus { PENDING, APPROVED, REJECTED, EXPIRED }
```
`execution/RunStepKind.java` (replace the whole file):
```java
package com.agentstore.platform.execution;

/**
 * READ..FAILED are the legacy kinds an agent reports itself over the old callback. TIMEOUT and COST_CAP_REACHED are
 * recorded by the platform only. The rest are recorded by the platform from /runtime/v1 calls; AGENT_LOG carries what
 * the agent said and is labelled as agent-reported.
 */
public enum RunStepKind {
    READ, WRITE, GATE, COST, COMPLETE, FAILED, TIMEOUT, COST_CAP_REACHED,
    DATA_READ, DATA_WRITE, WRITE_STAGED, GATE_REQUESTED, GATE_DECIDED, LLM_CALL, USAGE, SCOPE_VIOLATION, AGENT_LOG;

    /** Who normally records this kind; callers that know better pass the source explicitly. */
    public RunStepSource defaultSource() {
        return this == TIMEOUT || this == COST_CAP_REACHED ? RunStepSource.PLATFORM : RunStepSource.AGENT;
    }
}
```
`execution/RunStep.java`: add the field and constructors. Replace the existing 4-arg constructor block with:
```java
    @Enumerated(EnumType.STRING)
    private RunStepSource source = RunStepSource.AGENT;

    public RunStep(UUID id, Run run, RunStepKind kind, String detail) {
        this(id, run, kind, detail, kind.defaultSource());
    }

    public RunStep(UUID id, Run run, RunStepKind kind, String detail, RunStepSource source) {
        this.id = id;
        this.run = run;
        this.kind = kind;
        this.detail = detail;
        this.source = source;
    }
```
(Delete the old constructor; put the `source` field with the other fields, above the constructors.)

`execution/RunStepResponse.java` (replace whole file):
```java
package com.agentstore.platform.execution;

import java.time.Instant;

public record RunStepResponse(RunStepKind kind, RunStepSource source, String detail, Instant createdAt) {
    static RunStepResponse from(RunStep step) {
        return new RunStepResponse(step.getKind(), step.getSource(), step.getDetail(), step.getCreatedAt());
    }
}
```
`execution/Run.java`: add imports `org.hibernate.annotations.JdbcTypeCode;` and `org.hibernate.type.SqlTypes;`, and after `private Instant awaitingSince;` add:
```java

    @Enumerated(EnumType.STRING)
    private RunMode mode = RunMode.PRODUCTION;

    private int violationCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private String inputJson = "{}";
```
`execution/RunRepository.java`: add `Optional<Run> findFirstByCallbackTokenHash(String callbackTokenHash);` after `findByStatusAndAgentVersionId`.

`execution/GateRequest.java`:
```java
package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One approval request an agent raised through POST /runtime/v1/gates. */
@Entity
@Table(name = "gate_request")
@Getter
@Setter
@NoArgsConstructor
public class GateRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "run_id")
    private Run run;

    private String action;

    @Column(columnDefinition = "text")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affects_resources", columnDefinition = "jsonb")
    private String affectsResources = "[]";

    @Enumerated(EnumType.STRING)
    private GateStatus status = GateStatus.PENDING;

    private UUID decidedByUserId;

    @Column(columnDefinition = "text")
    private String decisionNote;

    private Instant createdAt;
    private Instant decidedAt;
    private Instant expiresAt;

    public GateRequest(Run run, String action, String summary, String payload, String affectsResources,
                       Instant expiresAt) {
        this.run = run;
        this.action = action;
        this.summary = summary;
        this.payload = payload;
        this.affectsResources = affectsResources;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```
`execution/GateRequestRepository.java`:
```java
package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GateRequestRepository extends JpaRepository<GateRequest, UUID> {
    Optional<GateRequest> findFirstByRunIdAndStatusOrderByCreatedAtAsc(UUID runId, GateStatus status);

    Optional<GateRequest> findByIdAndRunId(UUID id, UUID runId);
}
```

- [ ] **Step 6: Manifest fields**

`manifest/Scope.java`:
```java
package com.agentstore.platform.manifest;

public record Scope(ScopeType type, String resource, String description, boolean requiresApproval) {
    public Scope(ScopeType type, String resource, String description) {
        this(type, resource, description, false);
    }
}
```
`manifest/ApprovalGateDecl.java`:
```java
package com.agentstore.platform.manifest;

public record ApprovalGateDecl(String trigger, String description, int timeoutMinutes) {
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;

    public ApprovalGateDecl(String trigger, String description) {
        this(trigger, description, DEFAULT_TIMEOUT_MINUTES);
    }
}
```
`manifest/ManifestParser.java`: replace the scope line
```java
            scopes.add(new Scope(ScopeType.valueOf(s.get("type").asText()), s.get("resource").asText(),
                    s.path("description").asText("")));
```
with
```java
            scopes.add(new Scope(ScopeType.valueOf(s.get("type").asText()), s.get("resource").asText(),
                    s.path("description").asText(""), s.path("requiresApproval").asBoolean(false)));
```
and the gate line
```java
            gates.add(new ApprovalGateDecl(g.get("trigger").asText(), g.path("description").asText("")));
```
with
```java
            gates.add(new ApprovalGateDecl(g.get("trigger").asText(), g.path("description").asText(""),
                    g.path("timeoutMinutes").asInt(ApprovalGateDecl.DEFAULT_TIMEOUT_MINUTES)));
```
`resources/manifest/manifest-schema.json`: in `scopes.items.properties` add after `"description": { "type": "string" }`:
```json
          ,"requiresApproval": { "type": "boolean" }
```
(i.e. the scope `properties` object becomes `type`, `resource`, `description`, `requiresApproval`), and replace the `approvalGates.items.properties` block with:
```json
        "properties": {
          "trigger": { "type": "string", "maxLength": 100 },
          "description": { "type": "string" },
          "timeoutMinutes": { "type": "integer", "minimum": 1, "maximum": 1440 }
        }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ManifestRuntimeFieldsTest,RuntimeDataModelTest,ManifestParserTest,ManifestValidatorTest,RunDetailControllerTest'`
Expected: PASS (schema validation by Hibernate confirms the entities match V25/V26).

- [ ] **Step 8: Run the whole backend suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS (nothing else reads the changed constructors; if a call site fails to compile, fix that call site only, never a legacy test's assertions).

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(runtime): run mode, step source, gate_request table and the manifest fields WS1 needs"
```

---

### Task 2: Lifecycle operations for the runtime API

**Files:**
- Create: `execution/RunTerminatedException.java`, `execution/UsageOutcome.java`
- Modify: `execution/RunLifecycleService.java`
- Test: `execution/RunLifecycleRuntimeTest.java`

**Interfaces:**
- Consumes: Task 1 (`RunMode`, `RunStepSource`, `Run.get/setViolationCount`).
- Produces (all `public`, `@Transactional`, run-row-locked, throw `RunTerminatedException` when the run is terminal):
  - `void recordStep(UUID runId, RunStepKind kind, RunStepSource source, Map<String,Object> detail)`
  - `void recordAgentLogs(UUID runId, List<Map<String,Object>> entries)` (`AGENT_LOG`, source `AGENT`)
  - `void heartbeat(UUID runId)`
  - `boolean recordViolation(UUID runId, Map<String,Object> detail)` — returns true when the run was stopped
  - `UsageOutcome recordUsage(UUID runId, BigDecimal quantity, Map<String,Object> detail)` (`USAGE` step, source `PLATFORM`; 400 for quantity ≤ 0)
  - `void complete(UUID runId, Map<String,Object> detail)` (409 while `AWAITING_APPROVAL`), `void fail(UUID runId, Map<String,Object> detail)`
  - `static final int MAX_PRODUCTION_VIOLATIONS = 3`
  - `RunTerminatedException(RunStatus)` extends `ResponseStatusException` (409, message `Run not accepting events: <status>`); `UsageOutcome(BigDecimal charged, BigDecimal totalCharged, BigDecimal costCapRemaining, boolean capReached)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/agentstore/platform/execution/RunLifecycleRuntimeTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLifecycleRuntimeTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    FakeSandboxProvider sandbox;

    private Run running(String token, String cap) {
        var scenario = fixtures.liveTierA(new BigDecimal(cap), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
    }

    private List<RunStep> steps(Run run) {
        return stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
    }

    @Test
    void recordStepStoresAPlatformStepAndRefreshesTheHeartbeat() {
        Run run = running("tok-lr-1", "500.00");

        lifecycle.recordStep(run.getId(), RunStepKind.DATA_READ, RunStepSource.PLATFORM, Map.of("resource", "r", "count", 3));

        assertThat(steps(run)).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.DATA_READ);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
        });
        assertThat(runRepository.findById(run.getId()).orElseThrow().getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void aTerminalRunRejectsEveryRuntimeOperation() {
        Run run = running("tok-lr-2", "500.00");
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        assertThatThrownBy(() -> lifecycle.recordStep(run.getId(), RunStepKind.DATA_READ, RunStepSource.PLATFORM, Map.of()))
                .isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.heartbeat(run.getId())).isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.recordViolation(run.getId(), Map.of())).isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.recordUsage(run.getId(), BigDecimal.ONE, Map.of())).isInstanceOf(RunTerminatedException.class);
        assertThatThrownBy(() -> lifecycle.fail(run.getId(), Map.of())).isInstanceOf(RunTerminatedException.class);
    }

    @Test
    void thirdProductionViolationStopsTheRun() {
        Run run = running("tok-lr-3", "500.00");

        assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "one"))).isFalse();
        assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "two"))).isFalse();
        assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "three"))).isTrue();

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(updated.getViolationCount()).isEqualTo(3);
        assertThat(sandbox.wasKilled("sbx-tok-lr-3")).isTrue();
        assertThat(steps(run)).extracting(RunStep::getKind).containsExactly(
                RunStepKind.SCOPE_VIOLATION, RunStepKind.SCOPE_VIOLATION, RunStepKind.SCOPE_VIOLATION, RunStepKind.FAILED);
        assertThat(steps(run)).allMatch(s -> s.getSource() == RunStepSource.PLATFORM);
    }

    @Test
    void sandboxRunsReportEveryViolationAndKeepGoing() {
        Run run = running("tok-lr-4", "500.00");
        run.setMode(RunMode.SANDBOX);
        runRepository.save(run);

        for (int i = 0; i < 5; i++) {
            assertThat(lifecycle.recordViolation(run.getId(), Map.of("summary", "v" + i))).isFalse();
        }

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(updated.getViolationCount()).isEqualTo(5);
    }

    @Test
    void concurrentViolationsStopTheRunExactlyOnce() throws Exception {
        Run run = running("tok-lr-5", "500.00");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return lifecycle.recordViolation(run.getId(), Map.of("summary", "race"));
                } catch (RunTerminatedException e) {
                    return null;   // arrived after the run was already stopped
                }
            }));
        }
        start.countDown();
        int stopped = 0;
        for (Future<Boolean> f : results) {
            if (Boolean.TRUE.equals(f.get())) stopped++;
        }
        pool.shutdown();

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(stopped).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(updated.getViolationCount()).isEqualTo(3);
        assertThat(steps(run)).filteredOn(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).hasSize(3);
        assertThat(steps(run)).filteredOn(s -> s.getKind() == RunStepKind.FAILED).hasSize(1);
    }

    @Test
    void usageChargesThePlatformPriceAndRecordsAPlatformStep() {
        Run run = running("tok-lr-6", "500.00");

        UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("3"), Map.of("unit", "reconciled_transaction"));

        assertThat(outcome.charged()).isEqualByComparingTo("6.00");
        assertThat(outcome.totalCharged()).isEqualByComparingTo("6.00");
        assertThat(outcome.costCapRemaining()).isEqualByComparingTo("494.00");
        assertThat(outcome.capReached()).isFalse();
        assertThat(steps(run)).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.USAGE);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
        });
    }

    @Test
    void usageBeyondTheCapChargesOnlyTheRemainderAndStopsTheRun() {
        Run run = running("tok-lr-7", "10.00");

        UsageOutcome outcome = lifecycle.recordUsage(run.getId(), new BigDecimal("6"), Map.of());   // 12.00 requested

        assertThat(outcome.capReached()).isTrue();
        assertThat(outcome.charged()).isEqualByComparingTo("10.00");
        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(sandbox.wasKilled("sbx-tok-lr-7")).isTrue();
        assertThat(steps(run)).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.USAGE, RunStepKind.COST_CAP_REACHED);
    }

    @Test
    void nonPositiveUsageIsRejectedWithoutChargingAnything() {
        Run run = running("tok-lr-8", "500.00");

        assertThatThrownBy(() -> lifecycle.recordUsage(run.getId(), BigDecimal.ZERO, Map.of()))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> lifecycle.recordUsage(run.getId(), new BigDecimal("-2"), Map.of()))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
        assertThat(steps(run)).isEmpty();
    }

    @Test
    void completeFinishesTheRunAndKillsTheSandbox() {
        Run run = running("tok-lr-9", "500.00");

        lifecycle.complete(run.getId(), Map.of("summary", "all done"));

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(sandbox.wasKilled("sbx-tok-lr-9")).isTrue();
        assertThat(steps(run)).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.COMPLETE);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
        });
    }

    @Test
    void anAgentCannotCompleteWhileAGateIsAwaitingApproval() {
        Run run = running("tok-lr-10", "500.00");
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        runRepository.save(run);

        assertThatThrownBy(() -> lifecycle.complete(run.getId(), Map.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("awaiting approval");
        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void failRecordsAPlatformFailedStep() {
        Run run = running("tok-lr-11", "500.00");

        lifecycle.fail(run.getId(), Map.of("summary", "bank API down"));

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(steps(run)).singleElement().satisfies(s -> assertThat(s.getKind()).isEqualTo(RunStepKind.FAILED));
    }

    @Test
    void agentLogsAreStoredAsAgentReportedSteps() {
        Run run = running("tok-lr-12", "500.00");

        lifecycle.recordAgentLogs(run.getId(), List.of(Map.of("level", "info", "message", "a"), Map.of("level", "warn", "message", "b")));

        assertThat(steps(run)).hasSize(2).allSatisfy(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.AGENT_LOG);
            assertThat(s.getSource()).isEqualTo(RunStepSource.AGENT);
        });
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RunLifecycleRuntimeTest`
Expected: COMPILATION ERROR (`RunTerminatedException`, `UsageOutcome`, new service methods).

- [ ] **Step 3: Implement**

`execution/RunTerminatedException.java`:
```java
package com.agentstore.platform.execution;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The run is already terminal (or was stopped meanwhile). 409, the same message the legacy callback always gave. */
public class RunTerminatedException extends ResponseStatusException {
    public RunTerminatedException(RunStatus status) {
        super(HttpStatus.CONFLICT, "Run not accepting events: " + status);
    }
}
```
`execution/UsageOutcome.java`:
```java
package com.agentstore.platform.execution;

import java.math.BigDecimal;

/** What a usage report cost. {@code capReached} means the request would have exceeded the cap: the remainder was charged and the run stopped. */
public record UsageOutcome(BigDecimal charged, BigDecimal totalCharged, BigDecimal costCapRemaining, boolean capReached) {
}
```
Edits to `execution/RunLifecycleService.java` (seven Edit calls, in this order; each `old_string` is unique):

1. imports — old `import java.util.EnumSet;\nimport java.util.Map;` → new `import java.util.EnumSet;\nimport java.util.List;\nimport java.util.Map;`.
2. constant — old `    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);` → same line followed by `\n    /** A PRODUCTION run that keeps calling outside its granted scopes is stopped at this many violations. */\n    static final int MAX_PRODUCTION_VIOLATIONS = 3;`. (Package-private is enough for the constant; the tests do not read it.)
3. legacy terminal check — old
```java
        if (run.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
        }
```
new
```java
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
```
4. cost recording — old
```java
    private void recordCost(Run run, Map<String, Object> detail) {
        BigDecimal quantity = parseQuantity(detail.get("quantity"));
        var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
```
new (this inserts the new public operations before `recordCost`, and turns the rest of the old method into `charge`):
```java
    /** Records a platform-observed step (DATA_READ, LLM_CALL, …) for a run that is still open. */
    @Transactional
    public void recordStep(UUID runId, RunStepKind kind, RunStepSource source, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        step(run, kind, source, detail == null ? Map.of() : detail);
        runRepository.save(run);
    }

    /** Stores what the agent logged, one AGENT_LOG step per entry, clearly marked as agent-reported. */
    @Transactional
    public void recordAgentLogs(UUID runId, List<Map<String, Object>> entries) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        for (Map<String, Object> entry : entries) {
            step(run, RunStepKind.AGENT_LOG, RunStepSource.AGENT, entry);
        }
        runRepository.save(run);
    }

    @Transactional
    public void heartbeat(UUID runId) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        runRepository.save(run);
    }

    /**
     * Records a call outside the run's granted scopes. Returns true when this violation stopped the run
     * (PRODUCTION runs stop at {@link #MAX_PRODUCTION_VIOLATIONS}; other modes only report).
     */
    @Transactional
    public boolean recordViolation(UUID runId, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        step(run, RunStepKind.SCOPE_VIOLATION, RunStepSource.PLATFORM, detail);
        run.setViolationCount(run.getViolationCount() + 1);
        boolean stop = run.getMode() == RunMode.PRODUCTION && run.getViolationCount() >= MAX_PRODUCTION_VIOLATIONS;
        if (stop) {
            step(run, RunStepKind.FAILED, RunStepSource.PLATFORM, Map.of(
                    "summary", "Run stopped after repeated calls outside its permissions", "code", "SCOPE_VIOLATIONS"));
            finish(run, RunStatus.FAILED);
        } else {
            runRepository.save(run);
        }
        return stop;
    }

    /** A usage report from /runtime/v1/usage: same charging, cap clamping and reservation release as the legacy COST event. */
    @Transactional
    public UsageOutcome recordUsage(UUID runId, BigDecimal quantity, Map<String, Object> detail) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        }
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        return charge(run, quantity, detail == null ? Map.of() : detail, RunStepKind.USAGE, RunStepSource.PLATFORM);
    }

    @Transactional
    public void complete(UUID runId, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A gate is awaiting approval; the run cannot complete yet");
        }
        step(run, RunStepKind.COMPLETE, RunStepSource.PLATFORM, detail == null ? Map.of() : detail);
        finish(run, RunStatus.COMPLETED);
    }

    @Transactional
    public void fail(UUID runId, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        step(run, RunStepKind.FAILED, RunStepSource.PLATFORM, detail == null ? Map.of() : detail);
        finish(run, RunStatus.FAILED);
    }

    private Run lockOpen(UUID runId) {
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
        return run;
    }

    private void recordCost(Run run, Map<String, Object> detail) {
        charge(run, parseQuantity(detail.get("quantity")), detail, RunStepKind.COST, RunStepSource.AGENT);
    }

    private UsageOutcome charge(Run run, BigDecimal quantity, Map<String, Object> detail, RunStepKind stepKind,
                                RunStepSource source) {
        var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
```
5. inside `charge`, old `        step(run, RunStepKind.COST, detail);` → new `        step(run, stepKind, source, detail);`
6. tail of the old `recordCost` — old
```java
        if (requested.compareTo(remaining) > 0) {
            step(run, RunStepKind.COST_CAP_REACHED, Map.of(
                    "summary", "Spend limit reached; run stopped",
                    "requested", requested, "charged", charged, "costCap", run.getCostCap()));
            finish(run, RunStatus.FAILED);
        } else {
            runRepository.save(run);
        }
    }
```
new
```java
        boolean capReached = requested.compareTo(remaining) > 0;
        if (capReached) {
            step(run, RunStepKind.COST_CAP_REACHED, Map.of(
                    "summary", "Spend limit reached; run stopped",
                    "requested", requested, "charged", charged, "costCap", run.getCostCap()));
            finish(run, RunStatus.FAILED);
        } else {
            runRepository.save(run);
        }
        return new UsageOutcome(charged, run.getTotalCharged(),
                run.getCostCap().subtract(run.getTotalCharged()).max(BigDecimal.ZERO), capReached);
    }
```
7. the `step` helper — old
```java
    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        try {
            runStepRepository.save(new RunStep(null, run, kind, objectMapper.writeValueAsString(detail)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
```
new
```java
    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        step(run, kind, kind.defaultSource(), detail);
    }

    private void step(Run run, RunStepKind kind, RunStepSource source, Map<String, Object> detail) {
        try {
            runStepRepository.save(new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), source));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
```

- [ ] **Step 4: Run to verify pass, then the legacy tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RunLifecycleRuntimeTest,RunLifecycleServiceTest,AgentCallbackControllerTest,AgentCallbackCompletionTest,ApprovalGateTest,RunReaperTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(runtime): lifecycle operations for steps, violations, usage, complete and fail"
```

---

### Task 3: Gates — service, reaper, buyer decision endpoint

**Files:**
- Create: `execution/{GateService,GateDecision,GateDecisionRequest}.java`
- Modify: `execution/RunReaper.java` (full replacement), `execution/RunController.java`
- Test: `execution/GateServiceTest.java`, `execution/GateDecisionEndpointTest.java`

**Interfaces:**
- Consumes: Task 1 (`GateRequest`, repositories), Task 2 (`RunLifecycleService.approveGate/rejectGate/terminate`, `RunTerminatedException`).
- Produces:
  - `GateService.open(UUID runId, String action, int timeoutMinutes, String summary, String payloadJson, String affectsJson): GateRequest` — run must be open and not already `AWAITING_APPROVAL` (409); writes `GATE_REQUESTED` (PLATFORM) and sets the run `AWAITING_APPROVAL`.
  - `GateService.decide(UUID runId, UUID gateId /* null = the run's single pending gate */, boolean approve, UUID userId, String note): Run` — with a gate row it marks the gate and writes `GATE_DECIDED`; with no gate row (legacy) it only flips the run. Approve → `RUNNING`; reject → `CANCELLED` + sandbox killed.
  - `GateService.await(UUID runId, UUID gateId, Duration wait): GateRequest` — polls the row about once a second, never holds a DB connection between polls.
  - `GateService.expire(UUID runId, RunStatus expectedStatus, Map<String,Object> detail): boolean` — run → `EXPIRED` (+ `TIMEOUT` step) and its pending gate → `EXPIRED`, atomically.
  - `POST /api/v1/runs/{runId}/gates/{gateId}/decision` body `{"decision":"APPROVE"|"REJECT","note":"…"}` → `RunResponse`; role `BUYER_APPROVER`, same ownership check as approve/reject.

- [ ] **Step 1: Write the failing tests**

`execution/GateServiceTest.java`:

```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateServiceTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    GateService gateService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    GateRequestRepository gateRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    RunReaper reaper;
    @Autowired
    FakeSandboxProvider sandbox;

    private Run running(String token) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
    }

    private GateRequest open(Run run, int timeoutMinutes) {
        return gateService.open(run.getId(), "post_journal_entries", timeoutMinutes, "Post 12 entries",
                "{\"rows\":12}", "[\"journal_entries\"]");
    }

    private Run reload(Run run) {
        return runRepository.findById(run.getId()).orElseThrow();
    }

    @Test
    void openingAGatePausesTheRunAndRecordsAPlatformStep() {
        Run run = running("tok-gs-1");

        GateRequest gate = open(run, 5);

        assertThat(gate.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(gate.getExpiresAt()).isAfter(Instant.now().plusSeconds(240)).isBefore(Instant.now().plusSeconds(360));
        Run updated = reload(run);
        assertThat(updated.getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(updated.getAwaitingSince()).isNotNull();
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.GATE_REQUESTED);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
            assertThat(s.getDetail()).contains(gate.getId().toString());
        });
    }

    @Test
    void aSecondGateWhileOneIsOpenIsRefused() {
        Run run = running("tok-gs-2");
        open(run, 5);

        assertThatThrownBy(() -> open(run, 5)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(gateRepository.findAll().stream().filter(g -> g.getRun().getId().equals(run.getId()))).hasSize(1);
    }

    @Test
    void openingAGateOnATerminalRunIsRefused() {
        Run run = running("tok-gs-3");
        run.setStatus(RunStatus.COMPLETED);
        runRepository.save(run);

        assertThatThrownBy(() -> open(run, 5)).isInstanceOf(RunTerminatedException.class);
    }

    @Test
    void approvingMarksTheGateAndResumesTheRun() {
        Run run = running("tok-gs-4");
        GateRequest gate = open(run, 5);
        UUID approver = UUID.randomUUID();

        Run result = gateService.decide(run.getId(), gate.getId(), true, approver, "looks fine");

        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
        GateRequest decided = gateRepository.findById(gate.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(decided.getDecidedByUserId()).isEqualTo(approver);
        assertThat(decided.getDecisionNote()).isEqualTo("looks fine");
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.GATE_REQUESTED, RunStepKind.GATE_DECIDED);
    }

    @Test
    void rejectingMarksTheGateCancelsTheRunAndKillsTheSandbox() {
        Run run = running("tok-gs-5");
        GateRequest gate = open(run, 5);

        Run result = gateService.decide(run.getId(), gate.getId(), false, UUID.randomUUID(), "too risky");

        assertThat(result.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(gateRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
        assertThat(sandbox.wasKilled("sbx-tok-gs-5")).isTrue();
    }

    @Test
    void aRunWithoutAGateRowKeepsTheLegacyApprovalFlow() {
        Run run = running("tok-gs-6");
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        runRepository.save(run);

        Run result = gateService.decide(run.getId(), null, true, UUID.randomUUID(), null);

        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(gateRepository.findAll().stream().filter(g -> g.getRun().getId().equals(run.getId()))).isEmpty();
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).isEmpty();
    }

    @Test
    void aGateIdFromAnotherRunIsNotFoundAndDecidingTwiceIsAConflict() {
        Run run = running("tok-gs-7");
        Run other = running("tok-gs-7b");
        GateRequest gate = open(run, 5);

        assertThatThrownBy(() -> gateService.decide(other.getId(), gate.getId(), true, UUID.randomUUID(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null);
        assertThatThrownBy(() -> gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void awaitReturnsAtOnceWhenDecidedAndPendingAfterTheWaitOtherwise() throws Exception {
        Run run = running("tok-gs-8");
        GateRequest gate = open(run, 5);

        long started = System.nanoTime();
        GateRequest stillPending = gateService.await(run.getId(), gate.getId(), Duration.ofMillis(300));
        assertThat(stillPending.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(250));

        gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null);
        started = System.nanoTime();
        assertThat(gateService.await(run.getId(), gate.getId(), Duration.ofSeconds(20)).getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void theReaperExpiresAGateFromItsOwnTimeoutNotTheGlobalOne() {
        Run shortGate = running("tok-gs-9");
        Run longGate = running("tok-gs-9b");
        GateRequest shortOne = open(shortGate, 1);
        GateRequest longOne = open(longGate, 60);

        // 40 minutes on: past the 1-minute gate and the global 30-minute default, but inside the 60-minute gate.
        reaper.reapAt(Instant.now().plus(Duration.ofMinutes(40)));

        assertThat(reload(shortGate).getStatus()).isEqualTo(RunStatus.EXPIRED);
        assertThat(gateRepository.findById(shortOne.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.EXPIRED);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(shortGate.getId())).extracting(RunStep::getKind)
                .contains(RunStepKind.TIMEOUT);
        assertThat(reload(longGate).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(gateRepository.findById(longOne.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    }

    @Test
    void decisionAndExpiryNeverLeaveAMixedState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 15; i++) {
            Run run = running("tok-gs-race-" + i);
            GateRequest gate = open(run, 1);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> decide = pool.submit(() -> {
                start.await();
                try {
                    gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null);
                } catch (ResponseStatusException expected) {
                    // lost the race to the expiry
                }
                return null;
            });
            Future<?> expire = pool.submit(() -> {
                start.await();
                gateService.expire(run.getId(), RunStatus.AWAITING_APPROVAL, Map.of("summary", "expired"));
                return null;
            });
            start.countDown();
            decide.get();
            expire.get();

            GateStatus gateStatus = gateRepository.findById(gate.getId()).orElseThrow().getStatus();
            RunStatus runStatus = reload(run).getStatus();
            boolean approved = gateStatus == GateStatus.APPROVED && runStatus == RunStatus.RUNNING;
            boolean expired = gateStatus == GateStatus.EXPIRED && runStatus == RunStatus.EXPIRED;
            assertThat(approved || expired).as("gate %s with run %s", gateStatus, runStatus).isTrue();
        }
        pool.shutdown();
    }

    @Test
    void anExpiryThatLostToADecisionChangesNothing() {
        Run run = running("tok-gs-10");
        GateRequest gate = open(run, 1);
        gateService.decide(run.getId(), gate.getId(), true, UUID.randomUUID(), null);

        boolean expired = gateService.expire(run.getId(), RunStatus.AWAITING_APPROVAL, Map.of());

        assertThat(expired).isFalse();
        assertThat(reload(run).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(gateRepository.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }
}
```
`execution/GateDecisionEndpointTest.java`:

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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GateDecisionEndpointTest extends AbstractIntegrationTest {

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
    GateRequestRepository gateRepository;
    @Autowired
    RunRepository runRepository;

    record Fx(ExecutionFixtures.Scenario scenario, Run run, GateRequest gate) {}

    private Fx awaiting(String token) {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + token, token);
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post entries", "{}", "[]");
        return new Fx(scenario, run, gate);
    }

    private HttpHeaders approver(UUID orgId, UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(userId, orgId, UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<RunResponse> decide(Fx fx, HttpHeaders headers, Object body) {
        return rest.exchange(url("/api/v1/runs/" + fx.run().getId() + "/gates/" + fx.gate().getId() + "/decision"),
                HttpMethod.POST, new HttpEntity<>(body, headers), RunResponse.class);
    }

    @Test
    void approvingThroughTheGateEndpointRecordsWhoDecided() {
        Fx fx = awaiting("tok-gd-1");
        UUID userId = UUID.randomUUID();

        var response = decide(fx, approver(fx.scenario().buyer().getId(), userId), Map.of("decision", "APPROVE", "note", "ok"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);
        GateRequest gate = gateRepository.findById(fx.gate().getId()).orElseThrow();
        assertThat(gate.getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(gate.getDecidedByUserId()).isEqualTo(userId);
        assertThat(gate.getDecisionNote()).isEqualTo("ok");
    }

    @Test
    void rejectingThroughTheGateEndpointCancelsTheRun() {
        Fx fx = awaiting("tok-gd-2");

        var response = decide(fx, approver(fx.scenario().buyer().getId(), UUID.randomUUID()), Map.of("decision", "REJECT"));

        assertThat(response.getBody().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(gateRepository.findById(fx.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
    }

    @Test
    void anApproverFromAnotherOrgIsForbiddenAndNothingChanges() {
        Fx fx = awaiting("tok-gd-3");

        var response = decide(fx, approver(UUID.randomUUID(), UUID.randomUUID()), Map.of("decision", "APPROVE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(gateRepository.findById(fx.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    }

    @Test
    void anUnknownDecisionValueIsABadRequest() {
        Fx fx = awaiting("tok-gd-4");

        var response = decide(fx, approver(fx.scenario().buyer().getId(), UUID.randomUUID()), Map.of("decision", "MAYBE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theOldApproveAndRejectEndpointsDelegateToTheSinglePendingGate() {
        Fx approve = awaiting("tok-gd-5");
        rest.exchange(url("/api/v1/runs/" + approve.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("fine"), approver(approve.scenario().buyer().getId(), UUID.randomUUID())),
                RunResponse.class);
        assertThat(gateRepository.findById(approve.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);

        Fx reject = awaiting("tok-gd-6");
        rest.exchange(url("/api/v1/runs/" + reject.run().getId() + "/reject"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("no"), approver(reject.scenario().buyer().getId(), UUID.randomUUID())),
                RunResponse.class);
        assertThat(gateRepository.findById(reject.gate().getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
        assertThat(runRepository.findById(reject.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='GateServiceTest,GateDecisionEndpointTest'`
Expected: COMPILATION ERROR (`GateService`, `GateDecisionRequest` missing).

- [ ] **Step 3: Implement `GateService`, the decision types and the controller changes**

`execution/GateDecision.java`:
```java
package com.agentstore.platform.execution;

public enum GateDecision { APPROVE, REJECT }
```
`execution/GateDecisionRequest.java`:
```java
package com.agentstore.platform.execution;

import jakarta.validation.constraints.NotNull;

public record GateDecisionRequest(@NotNull GateDecision decision, String note) {
}
```
`execution/GateService.java`:
```java
package com.agentstore.platform.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Approval gates: opening one, the buyer's decision, the agent's wait, and expiry. Each change locks the run row first
 * (like every other transition), so a decision, an expiry and the agent's own calls cannot interleave.
 */
@Service
public class GateService {

    static final long POLL_INTERVAL_MILLIS = 1000;

    private final RunRepository runRepository;
    private final GateRequestRepository gateRequestRepository;
    private final RunStepRepository runStepRepository;
    private final RunLifecycleService runLifecycleService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GateService(RunRepository runRepository, GateRequestRepository gateRequestRepository,
                       RunStepRepository runStepRepository, RunLifecycleService runLifecycleService) {
        this.runRepository = runRepository;
        this.gateRequestRepository = gateRequestRepository;
        this.runStepRepository = runStepRepository;
        this.runLifecycleService = runLifecycleService;
    }

    @Transactional
    public GateRequest open(UUID runId, String action, int timeoutMinutes, String summary, String payloadJson,
                            String affectsJson) {
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A gate is already awaiting approval");
        }
        Instant now = Instant.now();
        GateRequest gate = gateRequestRepository.save(new GateRequest(run, action, summary, payloadJson, affectsJson,
                now.plus(Duration.ofMinutes(timeoutMinutes))));
        run.setStatus(RunStatus.AWAITING_APPROVAL);
        run.setAwaitingSince(now);
        run.setLastHeartbeatAt(now);
        runRepository.save(run);
        Map<String, Object> detail = new HashMap<>();
        detail.put("gateId", gate.getId().toString());
        detail.put("action", action);
        detail.put("summary", summary);
        step(run, RunStepKind.GATE_REQUESTED, detail);
        return gate;
    }

    /**
     * Decides a gate. {@code gateId == null} means "the run's single pending gate"; a run that has none (it used the
     * legacy callback) takes the old status-flip path unchanged.
     */
    @Transactional
    public Run decide(UUID runId, UUID gateId, boolean approve, UUID userId, String note) {
        runRepository.findByIdForUpdate(runId)
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
            gate.setStatus(approve ? GateStatus.APPROVED : GateStatus.REJECTED);
            gate.setDecidedByUserId(userId);
            gate.setDecisionNote(note);
            gate.setDecidedAt(Instant.now());
            gateRequestRepository.save(gate);
            Map<String, Object> detail = new HashMap<>();
            detail.put("gateId", gate.getId().toString());
            detail.put("decision", approve ? "APPROVED" : "REJECTED");
            if (note != null) {
                detail.put("note", note);
            }
            step(gate.getRun(), RunStepKind.GATE_DECIDED, detail);
        }
        return approve ? runLifecycleService.approveGate(runId) : runLifecycleService.rejectGate(runId);
    }

    /** Waits up to {@code wait} for the gate to leave PENDING. Not transactional: each read is its own short transaction. */
    public GateRequest await(UUID runId, UUID gateId, Duration wait) {
        Instant until = Instant.now().plus(wait);
        while (true) {
            GateRequest gate = gateRequestRepository.findByIdAndRunId(gateId, runId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Gate not found"));
            long left = Duration.between(Instant.now(), until).toMillis();
            if (gate.getStatus() != GateStatus.PENDING || left <= 0) {
                return gate;
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return gate;
            }
        }
    }

    /** Ends a run whose gate went unanswered (or whose deadline passed) and marks its pending gate EXPIRED. */
    @Transactional
    public boolean expire(UUID runId, RunStatus expectedStatus, Map<String, Object> detail) {
        boolean ended = runLifecycleService.terminate(runId, expectedStatus, RunStatus.EXPIRED, RunStepKind.TIMEOUT, detail);
        if (ended) {
            gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, GateStatus.PENDING).ifPresent(gate -> {
                gate.setStatus(GateStatus.EXPIRED);
                gate.setDecidedAt(Instant.now());
                gateRequestRepository.save(gate);
            });
        }
        return ended;
    }

    private Run lock(UUID runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        try {
            runStepRepository.save(new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), RunStepSource.PLATFORM));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
```
`execution/RunReaper.java` — replace the whole file with:
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
            List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;
    private final GateService gateService;
    private final GateRequestRepository gateRequestRepository;
    private final SandboxProvider sandboxProvider;
    private final Duration gateTimeout;

    public RunReaper(RunRepository runRepository, RunLifecycleService runLifecycleService, GateService gateService,
                     GateRequestRepository gateRequestRepository, SandboxProvider sandboxProvider,
                     @Value("${agentstore.runs.gate-timeout:PT30M}") Duration gateTimeout) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
        this.gateService = gateService;
        this.gateRequestRepository = gateRequestRepository;
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

    /**
     * {@code run} is a snapshot read outside any lock; it may be stale by the time the terminate call below
     * acquires the row lock (a buyer decision or an agent callback can land in between). Every terminate call
     * passes the snapshot status as {@code expectedStatus} so a stale decision is a no-op rather than clobbering
     * whatever actually happened to the run since it was read.
     */
    private boolean reap(Run run, Instant now) {
        RunStatus snapshotStatus = run.getStatus();
        boolean deadlinePassed = run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(now);
        if (snapshotStatus == RunStatus.AWAITING_APPROVAL) {
            // A run with a gate row expires when that gate does (the manifest's timeoutMinutes); a legacy run
            // without one falls back to the global gate timeout.
            Instant gateExpiresAt = gateRequestRepository
                    .findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING)
                    .map(GateRequest::getExpiresAt)
                    .orElseGet(() -> run.getAwaitingSince() == null ? null : run.getAwaitingSince().plus(gateTimeout));
            boolean gateExpired = gateExpiresAt != null && gateExpiresAt.isBefore(now);
            if (!gateExpired && !deadlinePassed) {
                return false;
            }
            return gateService.expire(run.getId(), snapshotStatus,
                    Map.of("summary", "No approval decision in time; run stopped"));
        }
        if (deadlinePassed) {
            return runLifecycleService.terminate(run.getId(), snapshotStatus, RunStatus.TIMED_OUT, RunStepKind.TIMEOUT,
                    Map.of("summary", "Run exceeded its time limit"));
        }
        if (snapshotStatus == RunStatus.RUNNING && run.getSandboxId() != null) {
            SandboxStatus sandbox = sandboxProvider.status(run.getSandboxId());
            if (sandbox.stopped()) {
                Map<String, Object> detail = new java.util.HashMap<>();
                detail.put("summary", "Agent stopped without reporting completion");
                if (sandbox.exitCode() != null) {
                    detail.put("exitCode", sandbox.exitCode());
                }
                return runLifecycleService.terminate(run.getId(), snapshotStatus, RunStatus.FAILED,
                        RunStepKind.FAILED, detail);
            }
        }
        return false;
    }
}
```
`execution/RunController.java` — four edits:
1. imports: old `import com.agentstore.platform.ledger.WalletReservationService;` stays; no new import needed beyond same package.
2. field: old `    private final RunLifecycleService runLifecycleService;` → new `    private final GateService gateService;`.
3. constructor param: old `WalletReservationService walletReservationService, RunLifecycleService runLifecycleService,` → new `WalletReservationService walletReservationService, GateService gateService,`; and assignment: old `        this.runLifecycleService = runLifecycleService;` → new `        this.gateService = gateService;`.
4. endpoints — old
```java
    public RunResponse approve(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(runLifecycleService.approveGate(runId));
    }
```
new
```java
    public RunResponse approve(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(gateService.decide(runId, null, true, callerUserId(), request.notes()));
    }
```
and old
```java
    public RunResponse reject(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(runLifecycleService.rejectGate(runId));
    }
```
new
```java
    public RunResponse reject(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(gateService.decide(runId, null, false, callerUserId(), request.notes()));
    }

    @PostMapping("/{runId}/gates/{gateId}/decision")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse decide(@PathVariable UUID runId, @PathVariable UUID gateId,
                              @Valid @RequestBody GateDecisionRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(gateService.decide(runId, gateId, request.decision() == GateDecision.APPROVE,
                callerUserId(), request.note()));
    }

    private UUID callerUserId() {
        return ((JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
    }
```

- [ ] **Step 4: Run to verify pass, plus the legacy gate and reaper tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='GateServiceTest,GateDecisionEndpointTest,ApprovalGateTest,RunReaperTest,RunControllerTest,RunControllerWalletTest'`
Expected: PASS. If `RunReaperTest` constructs the reaper by hand and no longer compiles, the constructor gained `GateService` and `GateRequestRepository`: update that test's construction only (it is a wiring change, not a behaviour change).

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(runtime): gate service, per-gate expiry and the gate decision endpoint"
```

---

### Task 4: Redis and the rate limiter

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `docker-compose.yml`, `backend/src/test/java/com/agentstore/platform/AbstractIntegrationTest.java`
- Create: `runtime/RuntimeRedisConfig.java`, `runtime/RuntimeRateLimiter.java`
- Test: `runtime/RuntimeRateLimiterTest.java`, `runtime/RuntimeRateLimiterOutageTest.java`

**Interfaces:**
- Produces: `RuntimeRedisConfig.client(String uri): RedisClient` (static; lazy, fast timeouts, commands rejected while disconnected); `RuntimeRateLimiter(RedisClient, int perMinute, int logsPerMinute, int llmPerMinute)`; `RuntimeRateLimiter.Kind {DEFAULT, LOGS, LLM}`; `Optional<Duration> tryAcquire(UUID runId, Kind kind)` — empty means allowed, otherwise the time until the next token; never throws; fails open on any Redis problem.
- Config keys: `agentstore.runtime.redis-uri` (default `redis://localhost:6379`), `agentstore.runtime.rate-limit.per-minute` (600), `.logs-per-minute` (120), `.llm-per-minute` (60).

- [ ] **Step 1: Dependencies, config, compose, test container**

`backend/pom.xml`: add to `<properties>` `<bucket4j.version>8.20.0</bucket4j.version>` and, next to the other dependencies:
```xml
    <dependency>
      <groupId>io.lettuce</groupId>
      <artifactId>lettuce-core</artifactId>
    </dependency>
    <dependency>
      <groupId>com.bucket4j</groupId>
      <artifactId>bucket4j_jdk17-lettuce</artifactId>
      <version>${bucket4j.version}</version>
    </dependency>
```
(`lettuce-core`'s version is managed by the Spring Boot parent.)

`application.yml`: under `spring:` add
```yaml
  threads:
    virtual:
      enabled: true      # a long-polling agent (GET /runtime/v1/gates/{id}?wait=) must not tie up a platform thread
```
and under `agentstore:` add
```yaml
  runtime:
    redis-uri: ${REDIS_URI:redis://localhost:6379}
    legacy-callback:
      enabled: ${AGENTSTORE_LEGACY_CALLBACK_ENABLED:true}
    rate-limit:
      per-minute: 600
      logs-per-minute: 120
      llm-per-minute: 60
```
`docker-compose.yml`: change the header comment's command to `docker compose up -d seaweedfs redis`, add a comment line `# Redis backs the runtime API's per-run rate limits (WS1). redis:7.2 is the last BSD-licensed release.`, and add under `services:`
```yaml
  redis:
    image: redis:7.2-alpine
    ports:
      - "6379:6379"
```
`AbstractIntegrationTest.java` — add `import org.testcontainers.containers.GenericContainer;`, a field
```java
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
```
change the static block to `POSTGRES.start();\n        REDIS.start();` and add to `datasourceProperties`:
```java
        registry.add("agentstore.runtime.redis-uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
```

- [ ] **Step 2: Write the failing tests**

`runtime/RuntimeRateLimiterTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "agentstore.runtime.rate-limit.per-minute=3",
        "agentstore.runtime.rate-limit.logs-per-minute=2"})
class RuntimeRateLimiterTest extends AbstractIntegrationTest {

    @Autowired
    RuntimeRateLimiter limiter;

    @Test
    void allowsUpToTheLimitThenAsksTheCallerToWait() {
        UUID run = UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).as("call %d", i + 1).isEmpty();
        }
        Optional<Duration> wait = limiter.tryAcquire(run, Kind.DEFAULT);

        assertThat(wait).isPresent();
        assertThat(wait.get()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void eachRunAndEachKindHasItsOwnBucket() {
        UUID run = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(run, Kind.DEFAULT);
        }

        assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).isPresent();
        assertThat(limiter.tryAcquire(UUID.randomUUID(), Kind.DEFAULT)).isEmpty();
        assertThat(limiter.tryAcquire(run, Kind.LOGS)).isEmpty();
        assertThat(limiter.tryAcquire(run, Kind.LOGS)).isEmpty();
        assertThat(limiter.tryAcquire(run, Kind.LOGS)).isPresent();
    }
}
```
`runtime/RuntimeRateLimiterOutageTest.java` (plain unit test, no Spring, no containers):
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeRateLimiterOutageTest {

    @Test
    void requestsStillPassWhenRedisIsUnreachableAndDoNotStallForLong() {
        RedisClient dead = RuntimeRedisConfig.client("redis://localhost:1");
        try {
            RuntimeRateLimiter limiter = new RuntimeRateLimiter(dead, 1, 1, 1);
            UUID run = UUID.randomUUID();

            long started = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.tryAcquire(run, Kind.DEFAULT)).as("call %d", i + 1).isEmpty();
            }

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            dead.shutdown();
        }
    }

    @Test
    void constructingTheClientNeverConnects() {
        RedisClient dead = RuntimeRedisConfig.client("redis://localhost:1");
        try {
            assertThat(dead).isNotNull();   // no exception: the app must start with Redis down
        } finally {
            dead.shutdown();
        }
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeRateLimiterTest,RuntimeRateLimiterOutageTest'`
Expected: COMPILATION ERROR (`RuntimeRateLimiter`, `RuntimeRedisConfig` missing).

- [ ] **Step 4: Implement**

`runtime/RuntimeRedisConfig.java`:
```java
package com.agentstore.platform.runtime;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RuntimeRedisConfig {

    /**
     * Creating the client does not connect. Commands fail fast (300 ms, and immediately while disconnected) so a Redis
     * outage cannot stall runtime calls; {@link RuntimeRateLimiter} treats any failure as "allow".
     */
    public static RedisClient client(String uri) {
        RedisClient client = RedisClient.create(RedisURI.create(uri));
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(300)))
                .build());
        return client;
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient runtimeRedisClient(@Value("${agentstore.runtime.redis-uri:redis://localhost:6379}") String uri) {
        return client(uri);
    }
}
```
`runtime/RuntimeRateLimiter.java`:
```java
package com.agentstore.platform.runtime;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-run token buckets kept in Redis. It guards against abuse, not money (the cost cap does that), so any Redis
 * problem means "allow" and never blocks a run or startup.
 */
@Component
public class RuntimeRateLimiter {

    public enum Kind { DEFAULT, LOGS, LLM }

    private static final Logger log = LoggerFactory.getLogger(RuntimeRateLimiter.class);
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    private final RedisClient redisClient;
    private final Map<Kind, Integer> perMinute;
    private LettuceBasedProxyManager<byte[]> proxyManager;   // guarded by this
    private Instant nextConnectAttempt = Instant.MIN;         // guarded by this

    public RuntimeRateLimiter(RedisClient redisClient,
                              @Value("${agentstore.runtime.rate-limit.per-minute:600}") int perMinute,
                              @Value("${agentstore.runtime.rate-limit.logs-per-minute:120}") int logsPerMinute,
                              @Value("${agentstore.runtime.rate-limit.llm-per-minute:60}") int llmPerMinute) {
        this.redisClient = redisClient;
        this.perMinute = Map.of(Kind.DEFAULT, perMinute, Kind.LOGS, logsPerMinute, Kind.LLM, llmPerMinute);
    }

    /** Empty when the call is allowed; otherwise how long until the next token. */
    public Optional<Duration> tryAcquire(UUID runId, Kind kind) {
        try {
            LettuceBasedProxyManager<byte[]> manager = manager();
            if (manager == null) {
                return Optional.empty();
            }
            int limit = perMinute.get(kind);
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder().capacity(limit).refillGreedy(limit, Duration.ofMinutes(1)).build())
                    .build();
            byte[] key = ("agentstore:rl:" + kind + ":" + runId).getBytes(StandardCharsets.UTF_8);
            ConsumptionProbe probe = manager.builder().build(key, () -> configuration).tryConsumeAndReturnRemaining(1);
            return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        } catch (RuntimeException e) {
            log.warn("Runtime rate limiter unavailable, allowing the call: {}", e.toString());
            return Optional.empty();
        }
    }

    private synchronized LettuceBasedProxyManager<byte[]> manager() {
        if (proxyManager == null) {
            if (Instant.now().isBefore(nextConnectAttempt)) {
                return null;
            }
            try {
                proxyManager = LettuceBasedProxyManager.builderFor(redisClient)
                        .withExpirationStrategy(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
                        .build();
            } catch (RuntimeException e) {
                nextConnectAttempt = Instant.now().plus(RECONNECT_BACKOFF);
                throw e;
            }
        }
        return proxyManager;
    }
}
```
If the compiler rejects a bucket4j call (for example `Bandwidth.builder()` or `withExpirationStrategy`), the 8.20.0 jars are at `~/.m2/repository/com/bucket4j/`; inspect with `javap -cp <jar> <class>` and adjust to the actual signature. The Lettuce module API was checked: `LettuceBasedProxyManager.builderFor(RedisClient)` exists and returns a builder of `byte[]` keys.

- [ ] **Step 5: Run to verify pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeRateLimiterTest,RuntimeRateLimiterOutageTest'`
Expected: PASS. Then `mvn -q test -Dtest='PlatformApplicationTest,SchedulingWiringTest'` to confirm the context still starts with the new bean and `spring.threads.virtual.enabled`.

- [ ] **Step 6: Commit**

```bash
git add backend docker-compose.yml
git commit -m "feat(runtime): Redis-backed per-run rate limiter that fails open"
```

---

### Task 5: Runtime security chain, authentication, errors and `GET /context`

**Files:**
- Create: `runtime/{RunPrincipal,RuntimeApiException,ProblemBody,ProblemResponses,RuntimeExceptionHandler,RuntimeAuthFilter,ContextResponse,RuntimeContextController}.java`
- Modify: `identity/SecurityConfig.java` (full replacement)
- Create (test): `manifest/valid-runtime-agent.json`, `runtime/RuntimeFixtures.java`, `runtime/RuntimeAuthTest.java`, `runtime/RuntimeHttpRateLimitTest.java`; modify `execution/ExecutionFixtures.java`

**Interfaces:**
- Consumes: Task 1 (`Run.getMode`, `findFirstByCallbackTokenHash`), Task 4 (`RuntimeRateLimiter`).
- Produces:
  - `RunPrincipal(UUID runId, UUID orgId, RunMode mode, Manifest manifest, Set<String> grantedScopes)` with `static RunPrincipal current()`, `static RunPrincipal forRun(Run run, ManifestParser parser)`, `static String scopeKey(ScopeType type, String resource)`, `boolean granted(ScopeType type, String resource)`.
  - `RuntimeApiException(HttpStatus status, String code, String message)`.
  - `ProblemResponses.write(HttpServletResponse, HttpStatus, String code, String detail, Duration retryAfterOrNull)`.
  - Test helpers: `ExecutionFixtures.liveWithManifest(String manifestFile, List<String> grantedScopes, BigDecimal spendCap, BigDecimal walletBalance): Scenario`; `RuntimeFixtures.runningAgent(BigDecimal spendCap): RunningAgent(Scenario scenario, Run run, String token)`; `RuntimeFixtures.bearer(String token): HttpHeaders`; constants `RESOURCE_READ="bank_statement"`, `RESOURCE_WRITE="journal_entries"`, `RESOURCE_UNGRANTED="invoices"`.

- [ ] **Step 1: Test manifest and fixtures**

`backend/src/test/resources/manifest/valid-runtime-agent.json`:
```json
{
  "name": "Runtime Test Agent",
  "version": "1.0.0",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read statement lines" },
    { "type": "READ", "resource": "invoices", "description": "Read open invoices" },
    { "type": "WRITE", "resource": "journal_entries", "description": "Propose entries", "requiresApproval": true }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [
    { "trigger": "post_journal_entries", "description": "Buyer approves entries before they are committed", "timeoutMinutes": 5 }
  ],
  "dataClasses": ["BANK_STATEMENT"]
}
```
`execution/ExecutionFixtures.java`: replace the `liveTierA` method with a delegating pair:
```java
    public Scenario liveTierA(BigDecimal spendCap, BigDecimal walletBalance) {
        return liveWithManifest("valid-tier-a.json", List.of("bank_statement"), spendCap, walletBalance);
    }

    /** As {@link #liveTierA} but with any manifest from src/test/resources/manifest and the buyer's granted resources. */
    public Scenario liveWithManifest(String manifestFile, List<String> grantedScopes, BigDecimal spendCap,
                                     BigDecimal walletBalance) {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", readManifest(manifestFile),
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        version = agentVersionRepository.save(version);
        Connection connection = new Connection(null, buyer, agent, grantedScopes, spendCap, ConnectionStatus.ACTIVE);
        connection.setAgentVersion(version);
        connection = connectionRepository.save(connection);
        Wallet wallet = new Wallet(null, buyer.getId(), walletBalance, null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return new Scenario(buyer, version, connection);
    }
```
`runtime/RuntimeFixtures.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A RUNNING run of the runtime test agent, with a raw token the test uses as the agent would. */
@Component
public class RuntimeFixtures {

    public static final String RESOURCE_READ = "bank_statement";
    public static final String RESOURCE_WRITE = "journal_entries";
    /** Declared in the manifest as READ, but the buyer did not grant it. */
    public static final String RESOURCE_UNGRANTED = "invoices";

    public record RunningAgent(ExecutionFixtures.Scenario scenario, Run run, String token) {}

    private final ExecutionFixtures fixtures;
    private final RunRepository runRepository;

    public RuntimeFixtures(ExecutionFixtures fixtures, RunRepository runRepository) {
        this.fixtures = fixtures;
        this.runRepository = runRepository;
    }

    public RunningAgent runningAgent(BigDecimal spendCap) {
        var scenario = fixtures.liveWithManifest("valid-runtime-agent.json", List.of(RESOURCE_READ, RESOURCE_WRITE),
                spendCap, new BigDecimal("1000.00"));
        String token = "rt-" + UUID.randomUUID();
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), token);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return new RunningAgent(scenario, runRepository.save(run), token);
    }

    public static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
```

- [ ] **Step 2: Write the failing tests**

`runtime/RuntimeAuthTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAuthTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    JwtService jwtService;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @Test
    void aValidRunTokenGetsTheContext() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = get("/runtime/v1/context", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body.get("runId")).isEqualTo(agent.run().getId().toString());
        assertThat(body.get("mode")).isEqualTo("PRODUCTION");
        assertThat(body.get("input")).isEqualTo(Map.of());
        assertThat((List<?>) body.get("grantedScopes")).extracting(s -> ((Map<?, ?>) s).get("resource"))
                .containsExactlyInAnyOrder("bank_statement", "journal_entries");   // "invoices" is declared but not granted
        Map<?, ?> limits = (Map<?, ?>) body.get("limits");
        assertThat(new BigDecimal(String.valueOf(limits.get("costCapRemaining")))).isEqualByComparingTo("500.00");
        assertThat(limits.get("currency")).isEqualTo("INR");
    }

    @Test
    void missingWrongAndNonRunTokensAreUnauthorizedProblems() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        HttpHeaders jwt = new HttpHeaders();
        jwt.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_ADMIN));

        for (HttpHeaders headers : List.of(new HttpHeaders(), RuntimeFixtures.bearer("not-a-token"), jwt)) {
            var response = get("/runtime/v1/context", headers);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
            assertThat(response.getBody().get("code")).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void aTerminalRunsTokenStopsWorking() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setStatus(RunStatus.COMPLETED);
        runRepository.save(run);

        var response = get("/runtime/v1/context", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }

    @Test
    void aRunPastItsDeadlineIsRejected() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setDeadlineAt(Instant.now().minusSeconds(5));
        runRepository.save(run);

        var response = get("/runtime/v1/context", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }

    @Test
    void anUnsupportedApiVersionInTheUserAgentIsRejectedClearly() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        HttpHeaders headers = RuntimeFixtures.bearer(agent.token());

        headers.set(HttpHeaders.USER_AGENT, "agentstore-python/0.1 runtime-api/2");
        var v2 = get("/runtime/v1/context", headers);
        assertThat(v2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(v2.getBody().get("code")).isEqualTo("UNSUPPORTED_API_VERSION");
        assertThat((String) v2.getBody().get("detail")).contains("runtime-api/1");

        headers.set(HttpHeaders.USER_AGENT, "agentstore-python/0.1 runtime-api/1");
        assertThat(get("/runtime/v1/context", headers).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theRunTokenDoesNotOpenTheBuyerApi() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = get("/api/v1/runs/mine", RuntimeFixtures.bearer(agent.token()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```
`runtime/RuntimeHttpRateLimitTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.runtime.rate-limit.per-minute=3")
class RuntimeHttpRateLimitTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> get(String token) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/context", HttpMethod.GET,
                new HttpEntity<>(RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void theFourthCallInAMinuteIsRateLimitedWithARetryAfter() {
        var busy = fx.runningAgent(new BigDecimal("500.00"));
        var quiet = fx.runningAgent(new BigDecimal("500.00"));

        for (int i = 0; i < 3; i++) {
            assertThat(get(busy.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        var limited = get(busy.token());

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code")).isEqualTo("RATE_LIMITED");
        assertThat(Integer.parseInt(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isPositive();
        assertThat(get(quiet.token()).getStatusCode()).isEqualTo(HttpStatus.OK);   // another run is unaffected
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeAuthTest,RuntimeHttpRateLimitTest'`
Expected: FAIL (404 from `/runtime/v1/context`, or compile errors until the classes exist).

- [ ] **Step 4: Implement errors and the principal**

`runtime/ProblemBody.java`:
```java
package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

/** RFC 7807 problem details plus the stable {@code code} the SDKs map to typed exceptions. */
public record ProblemBody(String type, String title, int status, String code, String detail) {
    static ProblemBody of(HttpStatus status, String code, String detail) {
        return new ProblemBody("about:blank", status.getReasonPhrase(), status.value(), code, detail);
    }
}
```
`runtime/RuntimeApiException.java`:
```java
package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;

public class RuntimeApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public RuntimeApiException(HttpStatus status, String code, String message) {
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
`runtime/ProblemResponses.java`:
```java
package com.agentstore.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.time.Duration;

/** Writes a problem+json response from inside a servlet filter, where controller advice cannot reach. */
final class ProblemResponses {

    static final String PROBLEM_JSON = "application/problem+json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String code, String detail, Duration retryAfter)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON);
        response.setCharacterEncoding("UTF-8");
        if (retryAfter != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
        }
        MAPPER.writeValue(response.getOutputStream(), ProblemBody.of(status, code, detail));
    }
}
```
`runtime/RuntimeExceptionHandler.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunTerminatedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.agentstore.platform.runtime")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RuntimeExceptionHandler {

    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(ProblemResponses.PROBLEM_JSON))
                .body(ProblemBody.of(status, code, detail));
    }

    @ExceptionHandler(RuntimeApiException.class)
    ResponseEntity<ProblemBody> api(RuntimeApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(RunTerminatedException.class)
    ResponseEntity<ProblemBody> terminated(RunTerminatedException e) {
        return problem(HttpStatus.CONFLICT, "RUN_TERMINATED", e.getReason());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemBody> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code = switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case PAYMENT_REQUIRED -> "PAYMENT_REQUIRED";
            default -> "ERROR";
        };
        return problem(status, code, e.getReason());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemBody> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).sorted().reduce((a, b) -> a + "; " + b)
                .orElse("Request is not valid");
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemBody> badParameter(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Parameter '" + e.getName() + "' is not valid");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemBody> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is missing or not valid JSON");
    }
}
```
`runtime/RunPrincipal.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The run an authenticated runtime call belongs to, with what it may touch. */
public record RunPrincipal(UUID runId, UUID orgId, RunMode mode, Manifest manifest, Set<String> grantedScopes) {

    /**
     * A scope is granted when the manifest declares it and the buyer's connection lists its resource in
     * {@code grantedScopes} (buyers grant resource names, not READ/WRITE pairs).
     */
    public static RunPrincipal forRun(Run run, ManifestParser parser) {
        Manifest manifest = parser.parse(run.getAgentVersion().getManifestJson());
        Set<String> buyerGranted = new HashSet<>(run.getConnection().getGrantedScopes());
        Set<String> granted = manifest.scopes().stream()
                .filter(s -> buyerGranted.contains(s.resource()))
                .map(s -> scopeKey(s.type(), s.resource()))
                .collect(Collectors.toSet());
        return new RunPrincipal(run.getId(), run.getConnection().getBuyerOrg().getId(), run.getMode(), manifest, granted);
    }

    public static RunPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (RunPrincipal) authentication.getPrincipal();
    }

    public static String scopeKey(ScopeType type, String resource) {
        return type + ":" + resource;
    }

    public boolean granted(ScopeType type, String resource) {
        return grantedScopes.contains(scopeKey(type, resource));
    }
}
```

- [ ] **Step 5: Implement the filter, the security chains and the context endpoint**

`runtime/RuntimeAuthFilter.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RuntimeRateLimiter.Kind;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates a /runtime call by its run token. Deliberately not a Spring bean (SecurityConfig builds it), so it
 * runs only inside the /runtime security chain and is never auto-registered for every URL.
 */
public class RuntimeAuthFilter extends OncePerRequestFilter {

    static final String SUPPORTED_API_VERSION = "1";
    private static final Pattern API_VERSION = Pattern.compile("runtime-api/(\\S+)");

    private final RunRepository runRepository;
    private final ManifestParser manifestParser;
    private final RuntimeRateLimiter rateLimiter;

    public RuntimeAuthFilter(RunRepository runRepository, ManifestParser manifestParser, RuntimeRateLimiter rateLimiter) {
        this.runRepository = runRepository;
        this.manifestParser = manifestParser;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith("/runtime/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String unsupported = unsupportedApiVersion(request.getHeader("User-Agent"));
        if (unsupported != null) {
            ProblemResponses.write(response, HttpStatus.BAD_REQUEST, "UNSUPPORTED_API_VERSION",
                    "This server supports runtime-api/" + SUPPORTED_API_VERSION + "; the client sent runtime-api/"
                            + unsupported, null);
            return;
        }
        String token = bearerToken(request.getHeader("Authorization"));
        Run run = token.isEmpty() ? null
                : runRepository.findFirstByCallbackTokenHash(RunTokenService.hash(token)).orElse(null);
        if (run == null || !RunTokenService.matches(token, run.getCallbackTokenHash())) {
            ProblemResponses.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid run token", null);
            return;
        }
        if (run.getStatus().isTerminal()) {
            ProblemResponses.write(response, HttpStatus.CONFLICT, "RUN_TERMINATED",
                    "The run is no longer accepting calls: " + run.getStatus(), null);
            return;
        }
        if (run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(Instant.now())) {
            ProblemResponses.write(response, HttpStatus.CONFLICT, "RUN_TERMINATED",
                    "The run is past its deadline", null);
            return;
        }
        Optional<Duration> wait = rateLimiter.tryAcquire(run.getId(),
                kindFor(UrlPathHelper.defaultInstance.getPathWithinApplication(request)));
        if (wait.isPresent()) {
            ProblemResponses.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many calls for this run; retry shortly", wait.get());
            return;
        }
        RunPrincipal principal = RunPrincipal.forRun(run, manifestParser);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT_RUN"))));
        chain.doFilter(request, response);
    }

    static Kind kindFor(String path) {
        if (path.equals("/runtime/v1/logs")) {
            return Kind.LOGS;
        }
        return path.startsWith("/runtime/v1/llm/") ? Kind.LLM : Kind.DEFAULT;
    }

    private static String bearerToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
    }

    /** The N of a {@code runtime-api/N} token in the User-Agent when it is not the supported version, else null. */
    private static String unsupportedApiVersion(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        Matcher matcher = API_VERSION.matcher(userAgent);
        return matcher.find() && !SUPPORTED_API_VERSION.equals(matcher.group(1)) ? matcher.group(1) : null;
    }
}
```
`identity/SecurityConfig.java` — replace the whole file with:
```java
package com.agentstore.platform.identity;

import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RuntimeAuthFilter;
import com.agentstore.platform.runtime.RuntimeRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    /** The agent-facing API: no JWT, no API key; only a run token, checked by RuntimeAuthFilter. */
    @Bean
    @Order(1)
    SecurityFilterChain runtimeFilterChain(HttpSecurity http, RunRepository runRepository,
                                           ManifestParser manifestParser, RuntimeRateLimiter rateLimiter) throws Exception {
        http.securityMatcher("/runtime/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(new RuntimeAuthFilter(runRepository, manifestParser, rateLimiter),
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/**", "/api/v1/invitations/**", "/actuator/**", "/internal/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```
`runtime/ContextResponse.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ScopeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContextResponse(UUID runId, RunMode mode, AgentVersionInfo agentVersion, Map<String, Object> input,
                              List<GrantedScope> grantedScopes, Limits limits) {

    public record AgentVersionInfo(UUID id, String versionNumber) {}

    public record GrantedScope(ScopeType type, String resource, boolean requiresApproval) {}

    public record Limits(Instant deadlineAt, BigDecimal costCapRemaining, String currency) {}
}
```
`runtime/RuntimeContextController.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1")
public class RuntimeContextController {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;

    public RuntimeContextController(RunRepository runRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/context")
    public ContextResponse context() throws JsonProcessingException {
        RunPrincipal principal = RunPrincipal.current();
        Run run = runRepository.findById(principal.runId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        Map<String, Object> input = objectMapper.readValue(run.getInputJson(), new TypeReference<>() {});
        var scopes = principal.manifest().scopes().stream()
                .filter(s -> principal.granted(s.type(), s.resource()))
                .map(s -> new ContextResponse.GrantedScope(s.type(), s.resource(), s.requiresApproval()))
                .toList();
        BigDecimal remaining = run.getCostCap().subtract(run.getTotalCharged()).max(BigDecimal.ZERO);
        return new ContextResponse(run.getId(), run.getMode(),
                new ContextResponse.AgentVersionInfo(run.getAgentVersion().getId(), run.getAgentVersion().getVersionNumber()),
                input, scopes,
                new ContextResponse.Limits(run.getDeadlineAt(), remaining, principal.manifest().costEnvelope().currency()));
    }
}
```

- [ ] **Step 6: Run to verify pass, plus the existing security tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeAuthTest,RuntimeHttpRateLimitTest,SecurityConfigTest,ApiKeyAuthTest,ApiKeyScopeTest,AgentCallbackControllerTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(runtime): run-token authentication on its own security chain, problem+json errors and GET /context"
```

---

### Task 6: ScopeGuard, resources and LLM endpoints

**Files:**
- Create: `runtime/{ScopeGuard,RunStepRecorder,ResourceBackend,LlmBackend,UnavailableResourceBackend,UnavailableLlmBackend,WriteRecordsRequest,RuntimeResourceController,RuntimeLlmController}.java`
- Test: `runtime/ScopeGuardTest.java`, `runtime/RuntimeResourceEndpointsTest.java`, `runtime/InMemoryBackends.java` (test helper)

**Interfaces:**
- Consumes: Task 2 (`recordViolation`, `recordStep`), Task 5 (`RunPrincipal`, `RuntimeApiException`, fixtures).
- Produces:
  - `ScopeGuard.read(RunPrincipal, String resource): Scope`, `write(RunPrincipal, String resource): Scope`, `gateAction(RunPrincipal, String action): ApprovalGateDecl`, `usageUnit(RunPrincipal, String unit): void` — each throws `RuntimeApiException(403, "SCOPE_NOT_GRANTED", …)` after recording one `SCOPE_VIOLATION`.
  - `RunStepRecorder.record(RunPrincipal, RunStepKind, Map<String,Object>)` — a PLATFORM step for the principal's run.
  - `ResourceBackend` and `LlmBackend` (below), the WS2/WS3 seams. `RuntimeResourceController.clampLimit(int): int` (package-private static, 1..1000).

- [ ] **Step 1: Write the failing tests**

`runtime/InMemoryBackends.java` (test helper, imported by tests that need working backends):
```java
package com.agentstore.platform.runtime;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Working ResourceBackend / LlmBackend for tests; @Primary so they win over the 501 defaults. */
@TestConfiguration
public class InMemoryBackends {

    @Bean
    @Primary
    InMemoryResourceBackend inMemoryResourceBackend() {
        return new InMemoryResourceBackend();
    }

    @Bean
    @Primary
    InMemoryLlmBackend inMemoryLlmBackend() {
        return new InMemoryLlmBackend();
    }

    public static class InMemoryResourceBackend implements ResourceBackend {
        private final Map<String, List<Map<String, Object>>> data = new HashMap<>();
        public final List<Map<String, Object>> written = new ArrayList<>();

        public void seed(String resource, List<Map<String, Object>> rows) {
            data.put(resource, rows);
        }

        @Override
        public ResourceMeta metadata(RunPrincipal principal, String resource) {
            return new ResourceMeta(resource, "csv", (long) data.getOrDefault(resource, List.of()).size());
        }

        @Override
        public RecordPage records(RunPrincipal principal, String resource, String cursor, int limit) {
            List<Map<String, Object>> rows = data.getOrDefault(resource, List.of());
            return new RecordPage(rows.subList(0, Math.min(limit, rows.size())), null);
        }

        @Override
        public FileLink file(RunPrincipal principal, String resource) {
            return new FileLink("https://storage.example/" + resource, Instant.now().plusSeconds(60));
        }

        @Override
        public WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                                 boolean requiresApproval) {
            written.addAll(records);
            return new WriteResult(records.size(), requiresApproval);
        }
    }

    public static class InMemoryLlmBackend implements LlmBackend {
        @Override
        public Map<String, Object> chat(RunPrincipal principal, Map<String, Object> request) {
            return Map.of("content", "echo", "model", String.valueOf(request.getOrDefault("model", "test")));
        }
    }
}
```
`runtime/ScopeGuardTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeGuardTest extends AbstractIntegrationTest {

    @Autowired
    RuntimeFixtures fx;
    @Autowired
    ScopeGuard guard;
    @Autowired
    ManifestParser parser;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;

    private RunPrincipal principalFor(RuntimeFixtures.RunningAgent agent) {
        return RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
    }

    private List<RunStep> violations(Run run) {
        return stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).toList();
    }

    @Test
    void grantedScopesAreReturnedWithoutAnyViolation() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        Scope read = guard.read(principal, "bank_statement");
        Scope write = guard.write(principal, "journal_entries");

        assertThat(read.type()).isEqualTo(ScopeType.READ);
        assertThat(write.requiresApproval()).isTrue();
        assertThat(violations(agent.run())).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} is denied")
    @CsvSource({
            "READ,invoices",           // declared, but the buyer did not grant it
            "READ,payroll",            // not declared at all
            "READ,journal_entries",    // declared only as WRITE
            "WRITE,bank_statement",    // declared only as READ
            "WRITE,invoices",          // declared as READ and not granted
            "READ,Bank_Statement",     // names are case-sensitive
            "READ,'bank_statement '",  // no trimming (quoted, or JUnit would trim it and the case would be granted)
            "READ,..%2Fsecret"         // path-shaped name
    })
    void everythingElseIsDeniedAndRecordedOnce(String type, String resource) {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        assertThatThrownBy(() -> {
            if (type.equals("READ")) {
                guard.read(principal, resource);
            } else {
                guard.write(principal, resource);
            }
        }).isInstanceOfSatisfying(RuntimeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.code()).isEqualTo("SCOPE_NOT_GRANTED");
        });

        assertThat(violations(agent.run())).hasSize(1);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getViolationCount()).isEqualTo(1);
    }

    @Test
    void gateActionsMustBeDeclaredAndCarryTheirTimeout() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        assertThat(guard.gateAction(principal, "post_journal_entries").timeoutMinutes()).isEqualTo(5);
        assertThatThrownBy(() -> guard.gateAction(principal, "wire_money"))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> assertThat(e.code()).isEqualTo("SCOPE_NOT_GRANTED"));
        assertThat(violations(agent.run())).hasSize(1);
    }

    @Test
    void usageMustUseTheManifestUnit() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        guard.usageUnit(principal, "reconciled_transaction");
        assertThat(violations(agent.run())).isEmpty();
        assertThatThrownBy(() -> guard.usageUnit(principal, "gigabytes"))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> assertThat(e.code()).isEqualTo("SCOPE_NOT_GRANTED"));
        assertThat(violations(agent.run())).hasSize(1);
    }

    @Test
    void theThirdProductionViolationStopsTheRunAndSaysSo() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var principal = principalFor(agent);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.read(principal, "payroll")).isInstanceOf(RuntimeApiException.class);
        }
        assertThatThrownBy(() -> guard.read(principal, "payroll"))
                .isInstanceOfSatisfying(RuntimeApiException.class, e -> assertThat(e.getMessage()).contains("stopped"));

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void sandboxRunsKeepGoingAfterManyViolations() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        Run run = runRepository.findById(agent.run().getId()).orElseThrow();
        run.setMode(RunMode.SANDBOX);
        runRepository.save(run);
        var principal = principalFor(agent);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> guard.read(principal, "payroll")).isInstanceOf(RuntimeApiException.class);
        }

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(violations(agent.run())).hasSize(5);
    }
}
```
`runtime/RuntimeResourceEndpointsTest.java` (uses the default 501 backends):
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeResourceEndpointsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunStepRepository stepRepository;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    private long violationSteps(java.util.UUID runId) {
        return stepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .filter(s -> s.getKind() == RunStepKind.SCOPE_VIOLATION).count();
    }

    @Test
    void aGrantedReadReachesTheBackendWhichIsNotImplementedYet() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(violationSteps(agent.run().getId())).isZero();
    }

    @Test
    void aGrantedWriteReachesTheBackendWhichIsNotImplementedYet() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(),
                Map.of("records", List.of(Map.of("account", "1000"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(violationSteps(agent.run().getId())).isZero();
    }

    @Test
    void undeclaredUngrantedAndWrongTypeAccessIsForbiddenAndRecorded() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var undeclared = call(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);
        var ungranted = call(HttpMethod.GET, "/runtime/v1/resources/invoices", agent.token(), null);
        var wrongType = call(HttpMethod.POST, "/runtime/v1/resources/bank_statement/records", agent.token(),
                Map.of("records", List.of(Map.of("a", 1))));

        for (var r : List.of(undeclared, ungranted, wrongType)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        }
        assertThat(violationSteps(agent.run().getId())).isEqualTo(3);
    }

    @Test
    void caseVariantsAndEncodedSlashesAreNeverGranted() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var caseVariant = call(HttpMethod.GET, "/runtime/v1/resources/Bank_Statement", agent.token(), null);
        var encodedSlash = call(HttpMethod.GET, "/runtime/v1/resources/..%2Fsecret", agent.token(), null);

        assertThat(caseVariant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(encodedSlash.getStatusCode().is4xxClientError()).isTrue();   // the firewall or the guard, never a 5xx or a 2xx
    }

    @Test
    void emptyOrMissingWriteBodiesAreValidationErrorsNotServerErrors() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var empty = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(), Map.of("records", List.of()));
        var missing = call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agent.token(), null);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void badPagingParametersAreClampedOrRejected() {
        assertThat(RuntimeResourceController.clampLimit(-5)).isEqualTo(1);
        assertThat(RuntimeResourceController.clampLimit(0)).isEqualTo(1);
        assertThat(RuntimeResourceController.clampLimit(50)).isEqualTo(50);
        assertThat(RuntimeResourceController.clampLimit(5_000_000)).isEqualTo(1000);

        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var response = call(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records?limit=abc", agent.token(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void theLlmEndpointIsNotImplementedYet() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/llm/chat", agent.token(),
                Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ScopeGuardTest,RuntimeResourceEndpointsTest'`
Expected: COMPILATION ERROR (`ScopeGuard`, `ResourceBackend`, … missing).

- [ ] **Step 3: Implement the seams, guard and recorder**

`runtime/ResourceBackend.java`:
```java
package com.agentstore.platform.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What WS2 (the data plane) implements. The runtime controllers check scopes before calling it. */
public interface ResourceBackend {

    ResourceMeta metadata(RunPrincipal principal, String resource);

    RecordPage records(RunPrincipal principal, String resource, String cursor, int limit);

    FileLink file(RunPrincipal principal, String resource);

    /** {@code requiresApproval} tells the backend to stage the records until an approved gate covers them. */
    WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records, boolean requiresApproval);

    record ResourceMeta(String resource, String format, Long rowCount) {}

    record RecordPage(List<Map<String, Object>> records, String nextCursor) {}

    record FileLink(String url, Instant expiresAt) {}

    record WriteResult(int accepted, boolean staged) {}
}
```
`runtime/LlmBackend.java`:
```java
package com.agentstore.platform.runtime;

import java.util.Map;

/** What WS3 (the LLM gateway) implements. */
public interface LlmBackend {
    Map<String, Object> chat(RunPrincipal principal, Map<String, Object> request);
}
```
`runtime/UnavailableResourceBackend.java`:
```java
package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Until WS2 lands. Delete this class when the real backend exists. */
@Component
class UnavailableResourceBackend implements ResourceBackend {

    private static RuntimeApiException unavailable() {
        return new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "Datasets arrive with the data plane; this endpoint is not available yet");
    }

    @Override
    public ResourceMeta metadata(RunPrincipal principal, String resource) {
        throw unavailable();
    }

    @Override
    public RecordPage records(RunPrincipal principal, String resource, String cursor, int limit) {
        throw unavailable();
    }

    @Override
    public FileLink file(RunPrincipal principal, String resource) {
        throw unavailable();
    }

    @Override
    public WriteResult write(RunPrincipal principal, String resource, List<Map<String, Object>> records,
                             boolean requiresApproval) {
        throw unavailable();
    }
}
```
`runtime/UnavailableLlmBackend.java`:
```java
package com.agentstore.platform.runtime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Until WS3 lands. Delete this class when the real backend exists. */
@Component
class UnavailableLlmBackend implements LlmBackend {
    @Override
    public Map<String, Object> chat(RunPrincipal principal, Map<String, Object> request) {
        throw new RuntimeApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "The LLM gateway is not available yet");
    }
}
```
`runtime/RunStepRecorder.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepSource;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Writes the platform's own trace of what an agent did, from the calls the platform actually served. */
@Component
public class RunStepRecorder {

    private final RunLifecycleService runLifecycleService;

    public RunStepRecorder(RunLifecycleService runLifecycleService) {
        this.runLifecycleService = runLifecycleService;
    }

    public void record(RunPrincipal principal, RunStepKind kind, Map<String, Object> detail) {
        runLifecycleService.recordStep(principal.runId(), kind, RunStepSource.PLATFORM, detail);
    }
}
```
`runtime/ScopeGuard.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The one place that decides whether a runtime call is inside what the run was granted. A denial records a
 * SCOPE_VIOLATION step (and may stop the run, see {@link RunLifecycleService#recordViolation}) and answers 403.
 */
@Service
public class ScopeGuard {

    private final RunLifecycleService runLifecycleService;

    public ScopeGuard(RunLifecycleService runLifecycleService) {
        this.runLifecycleService = runLifecycleService;
    }

    public Scope read(RunPrincipal principal, String resource) {
        return require(principal, ScopeType.READ, resource);
    }

    public Scope write(RunPrincipal principal, String resource) {
        return require(principal, ScopeType.WRITE, resource);
    }

    /** The gate the manifest declares for {@code action} (its trigger). */
    public ApprovalGateDecl gateAction(RunPrincipal principal, String action) {
        return principal.manifest().approvalGates().stream()
                .filter(gate -> gate.trigger().equals(action))
                .findFirst()
                .orElseThrow(() -> deny(principal, "gate", action, "Approval gate not declared in the manifest: " + action));
    }

    public void usageUnit(RunPrincipal principal, String unit) {
        String expected = principal.manifest().costEnvelope().unitLabel();
        if (!expected.equals(unit)) {
            throw deny(principal, "usage", String.valueOf(unit), "Usage unit must be '" + expected + "'");
        }
    }

    private Scope require(RunPrincipal principal, ScopeType type, String resource) {
        if (principal.granted(type, resource)) {
            return principal.manifest().scopes().stream()
                    .filter(s -> s.type() == type && s.resource().equals(resource))
                    .findFirst().orElseThrow();
        }
        throw deny(principal, type.name().toLowerCase(), resource,
                "No " + type + " access to '" + resource + "' was granted to this run");
    }

    private RuntimeApiException deny(RunPrincipal principal, String attempted, String target, String message) {
        boolean stopped = runLifecycleService.recordViolation(principal.runId(),
                Map.of("summary", message, "attempted", attempted, "target", target));
        return new RuntimeApiException(HttpStatus.FORBIDDEN, "SCOPE_NOT_GRANTED",
                stopped ? message + "; the run was stopped after repeated violations" : message);
    }
}
```

- [ ] **Step 4: Controllers**

`runtime/WriteRecordsRequest.java`:
```java
package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record WriteRecordsRequest(@NotEmpty @Size(max = 10000) List<Map<String, Object>> records) {
}
```
`runtime/RuntimeResourceController.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.manifest.Scope;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/runtime/v1/resources")
public class RuntimeResourceController {

    static final int MAX_LIMIT = 1000;

    private final ScopeGuard scopeGuard;
    private final ResourceBackend backend;
    private final RunStepRecorder recorder;

    public RuntimeResourceController(ScopeGuard scopeGuard, ResourceBackend backend, RunStepRecorder recorder) {
        this.scopeGuard = scopeGuard;
        this.backend = backend;
        this.recorder = recorder;
    }

    @GetMapping("/{resource}")
    public ResourceBackend.ResourceMeta metadata(@PathVariable String resource) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        return backend.metadata(principal, resource);
    }

    @GetMapping("/{resource}/records")
    public ResourceBackend.RecordPage records(@PathVariable String resource,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "100") int limit) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        ResourceBackend.RecordPage page = backend.records(principal, resource, cursor, clampLimit(limit));
        recorder.record(principal, RunStepKind.DATA_READ, Map.of("resource", resource, "count", page.records().size()));
        return page;
    }

    @GetMapping("/{resource}/file")
    public ResourceBackend.FileLink file(@PathVariable String resource) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.read(principal, resource);
        ResourceBackend.FileLink link = backend.file(principal, resource);
        recorder.record(principal, RunStepKind.DATA_READ, Map.of("resource", resource, "file", true));
        return link;
    }

    @PostMapping("/{resource}/records")
    public ResourceBackend.WriteResult write(@PathVariable String resource, @Valid @RequestBody WriteRecordsRequest request) {
        RunPrincipal principal = RunPrincipal.current();
        Scope scope = scopeGuard.write(principal, resource);
        ResourceBackend.WriteResult result = backend.write(principal, resource, request.records(), scope.requiresApproval());
        recorder.record(principal, result.staged() ? RunStepKind.WRITE_STAGED : RunStepKind.DATA_WRITE,
                Map.of("resource", resource, "count", result.accepted()));
        return result;
    }

    static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
```
`runtime/RuntimeLlmController.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunStepKind;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/runtime/v1/llm")
public class RuntimeLlmController {

    private final LlmBackend backend;
    private final RunStepRecorder recorder;

    public RuntimeLlmController(LlmBackend backend, RunStepRecorder recorder) {
        this.backend = backend;
        this.recorder = recorder;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> request) {
        RunPrincipal principal = RunPrincipal.current();
        Map<String, Object> response = backend.chat(principal, request);
        recorder.record(principal, RunStepKind.LLM_CALL, Map.of("calls", 1));
        return response;
    }
}
```

- [ ] **Step 5: Run to verify pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='ScopeGuardTest,RuntimeResourceEndpointsTest,RuntimeAuthTest'`
Expected: PASS. If the `..%2Fsecret` request is answered 200 by some setup, that is a real defect: stop and fix the guard/firewall, do not loosen the assertion.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(runtime): ScopeGuard, resource and LLM endpoints behind WS2/WS3 seams"
```

---

### Task 7: Gates, usage and lifecycle endpoints

**Files:**
- Create: `runtime/{CreateGateRequest,GateCreatedResponse,GateView,UsageRequest,UsageResponse,LogBatch,CompleteRequest,FailRequest,RunStateResponse,RuntimeGateController,RuntimeUsageController,RuntimeLifecycleController}.java`
- Test: `runtime/RuntimeGateEndpointsTest.java`, `runtime/RuntimeUsageEndpointTest.java`, `runtime/RuntimeLifecycleEndpointsTest.java`, `runtime/RuntimeEndpointMatrixTest.java`

**Interfaces:**
- Consumes: Tasks 2, 3, 5, 6 (`GateService`, `RunLifecycleService`, `ScopeGuard`).
- Produces (HTTP, all under `/runtime/v1`): `POST /gates` → 201 `GateCreatedResponse(gateId, status, expiresAt)`; `GET /gates/{gateId}?wait=N` → `GateView(gateId, status, note, expiresAt, decidedAt)` (wait clamped to 0–30 s by `RuntimeGateController.clampWait(int)`); `POST /usage` → `UsageResponse(charged, totalCharged, costCapRemaining)` or `402 BUDGET_EXCEEDED`; `POST /logs` → 204; `POST /heartbeat` → 204; `POST /complete`, `POST /fail` → `RunStateResponse(status)`.

- [ ] **Step 1: Write the failing tests**

`runtime/RuntimeGateEndpointsTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeGateEndpointsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    JwtService jwtService;
    @Autowired
    RunRepository runRepository;
    @Autowired
    GateRequestRepository gateRepository;
    @Autowired
    RunStepRepository stepRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    private Map<String, Object> gateBody() {
        return Map.of("action", "post_journal_entries", "summary", "Post 2 entries", "payload", Map.of("rows", 2),
                "affectsResources", List.of("journal_entries"));
    }

    private void buyerDecides(RuntimeFixtures.RunningAgent agent, String gateId, String decision) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", decision), headers), RunResponse.class);
    }

    @Test
    void aDeclaredGatePausesTheRunAndReturnsItsIdAndExpiry() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat(response.getBody().get("expiresAt")).isNotNull();
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).extracting(RunStep::getKind)
                .containsExactly(RunStepKind.GATE_REQUESTED);
    }

    @Test
    void anUndeclaredGateActionIsForbiddenAndRecorded() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = call(HttpMethod.POST, "/runtime/v1/gates", agent.token(),
                Map.of("action", "wire_money", "summary", "s", "payload", Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getViolationCount()).isEqualTo(1);
    }

    @Test
    void aSecondOpenGateIsAConflictAndBadBodiesAreValidationErrors() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody());

        assertThat(call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        var other = fx.runningAgent(new BigDecimal("500.00"));
        var missing = call(HttpMethod.POST, "/runtime/v1/gates", other.token(), Map.of("action", "post_journal_entries"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void pollingShowsPendingThenApproved() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");

        assertThat(call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null).getBody().get("status"))
                .isEqualTo("PENDING");
        buyerDecides(agent, gateId, "APPROVE");

        var approved = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null);
        assertThat(approved.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aLongPollReturnsAsSoonAsTheBuyerDecides() throws Exception {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");

        Thread buyer = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            buyerDecides(agent, gateId, "APPROVE");
        });
        buyer.start();
        long started = System.nanoTime();
        var response = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=20", agent.token(), null);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        buyer.join();

        assertThat(response.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(took).isGreaterThan(Duration.ofMillis(1000)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void waitValuesAreClampedAndGarbageIsRejected() {
        assertThat(RuntimeGateController.clampWait(-5)).isZero();
        assertThat(RuntimeGateController.clampWait(0)).isZero();
        assertThat(RuntimeGateController.clampWait(12)).isEqualTo(12);
        assertThat(RuntimeGateController.clampWait(99_999)).isEqualTo(30);

        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");
        long started = System.nanoTime();
        var negative = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=-5", agent.token(), null);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(negative.getBody().get("status")).isEqualTo("PENDING");

        var garbage = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=abc", agent.token(), null);
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(garbage.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void aGateOfAnotherRunIsNotFound() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var other = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", other.token(), gateBody()).getBody().get("gateId");

        var response = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/runtime/v1/gates/not-a-uuid", agent.token(), null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectedGateEndsTheRunAndTheTokenStopsWorking() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        String gateId = (String) call(HttpMethod.POST, "/runtime/v1/gates", agent.token(), gateBody()).getBody().get("gateId");

        buyerDecides(agent, gateId, "REJECT");

        assertThat(gateRepository.findById(UUID.fromString(gateId)).orElseThrow().getStatus()).isEqualTo(GateStatus.REJECTED);
        var after = call(HttpMethod.GET, "/runtime/v1/gates/" + gateId, agent.token(), null);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(after.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }
}
```
`runtime/RuntimeUsageEndpointTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeUsageEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> usage(String token, Object body) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/usage", HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void usageInTheManifestUnitIsChargedAtThePlatformPrice() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = usage(agent.token(), Map.of("quantity", 3, "unit", "reconciled_transaction", "outcomeRef", "batch-7"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(String.valueOf(response.getBody().get("charged")))).isEqualByComparingTo("6.00");
        assertThat(new BigDecimal(String.valueOf(response.getBody().get("costCapRemaining")))).isEqualByComparingTo("494.00");
    }

    @Test
    void aWrongUnitIsForbiddenAndNothingIsCharged() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = usage(agent.token(), Map.of("quantity", 3, "unit", "gigabytes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
    }

    @Test
    void usageBeyondTheCapChargesTheRemainderReturns402AndStopsTheRun() {
        var agent = fx.runningAgent(new BigDecimal("10.00"));

        var response = usage(agent.token(), Map.of("quantity", 6, "unit", "reconciled_transaction"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        var run = runRepository.findById(agent.run().getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getTotalCharged()).isEqualByComparingTo("10.00");
    }

    @Test
    void zeroNegativeMissingAndNonNumericQuantitiesAreValidationErrors() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        for (Object quantity : new Object[]{0, -3, "abc"}) {
            var response = usage(agent.token(), Map.of("quantity", quantity, "unit", "reconciled_transaction"));
            assertThat(response.getStatusCode()).as("quantity %s", quantity).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(usage(agent.token(), Map.of("unit", "reconciled_transaction")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
    }
}
```
`runtime/RuntimeLifecycleEndpointsTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLifecycleEndpointsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    FakeSandboxProvider sandbox;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> post(String path, String token, Object body) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void heartbeatRefreshesLiveness() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        assertThat(post("/runtime/v1/heartbeat", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getLastHeartbeatAt()).isNotNull();
    }

    @Test
    void logsAreStoredAsAgentReportedSteps() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of(
                Map.of("level", "info", "message", "matched 40 lines", "fields", Map.of("count", 40)),
                Map.of("level", "warn", "message", "one duplicate"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).hasSize(2).allSatisfy(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.AGENT_LOG);
            assertThat(s.getSource()).isEqualTo(RunStepSource.AGENT);
        });
    }

    @Test
    void emptyOversizedAndMalformedLogBatchesAreRejectedNotStored() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(Map.of("level", "info", "message", "m" + i));
        }

        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", tooMany)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of(
                Map.of("level", "info", "message", "x".repeat(4001))))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/runtime/v1/logs", agent.token(), Map.of("entries", List.of(
                Map.of("message", "no level")))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId())).isEmpty();
    }

    @Test
    void completeFinishesTheRunAndKillsTheSandbox() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = post("/runtime/v1/complete", agent.token(), Map.of("summary", "reconciled 40 lines"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(sandbox.wasKilled(agent.run().getSandboxId())).isTrue();
        assertThat(post("/runtime/v1/heartbeat", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void completeWithoutABodyIsFine() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        assertThat(post("/runtime/v1/complete", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void completeWhileAGateIsOpenIsAConflict() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        post("/runtime/v1/gates", agent.token(), Map.of("action", "post_journal_entries", "summary", "s", "payload", Map.of()));

        var response = post("/runtime/v1/complete", agent.token(), Map.of("summary", "sneaky"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void failRecordsTheErrorAndEndsTheRun() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = post("/runtime/v1/fail", agent.token(), Map.of("error", "bank API down", "retriable", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId()).get(0).getDetail()).contains("bank API down");
        assertThat(post("/runtime/v1/fail", fx.runningAgent(new BigDecimal("500.00")).token(), Map.of("retriable", false)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);   // error is required
    }
}
```
`runtime/RuntimeEndpointMatrixTest.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every runtime endpoint: bad token, terminal run, past deadline. (Not-granted cases live with each endpoint.) */
class RuntimeEndpointMatrixTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;

    static Stream<Arguments> endpoints() {
        UUID gate = UUID.randomUUID();
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/runtime/v1/context", null),
                Arguments.of(HttpMethod.GET, "/runtime/v1/resources/bank_statement", null),
                Arguments.of(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records", null),
                Arguments.of(HttpMethod.GET, "/runtime/v1/resources/bank_statement/file", null),
                Arguments.of(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", Map.of("records", List.of(Map.of("a", 1)))),
                Arguments.of(HttpMethod.POST, "/runtime/v1/gates", Map.of("action", "post_journal_entries", "summary", "s", "payload", Map.of())),
                Arguments.of(HttpMethod.GET, "/runtime/v1/gates/" + gate, null),
                Arguments.of(HttpMethod.POST, "/runtime/v1/llm/chat", Map.of("messages", List.of())),
                Arguments.of(HttpMethod.POST, "/runtime/v1/usage", Map.of("quantity", 1, "unit", "reconciled_transaction")),
                Arguments.of(HttpMethod.POST, "/runtime/v1/logs", Map.of("entries", List.of(Map.of("level", "info", "message", "m")))),
                Arguments.of(HttpMethod.POST, "/runtime/v1/heartbeat", null),
                Arguments.of(HttpMethod.POST, "/runtime/v1/complete", Map.of("summary", "s")),
                Arguments.of(HttpMethod.POST, "/runtime/v1/fail", Map.of("error", "e", "retriable", false)));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return rest.exchange("http://localhost:" + port + path, method,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void aBadTokenIsUnauthorized(HttpMethod method, String path, Object body) {
        assertThat(call(method, path, "not-a-token", body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void aTerminalRunIsRejectedWithRunTerminated(HttpMethod method, String path, Object body) {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setStatus(RunStatus.CANCELLED);
        runRepository.save(run);

        var response = call(method, path, agent.token(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("endpoints")
    void aRunPastItsDeadlineIsRejectedWithRunTerminated(HttpMethod method, String path, Object body) {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var run = agent.run();
        run.setDeadlineAt(Instant.now().minusSeconds(1));
        runRepository.save(run);

        var response = call(method, path, agent.token(), body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("RUN_TERMINATED");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeGateEndpointsTest,RuntimeUsageEndpointTest,RuntimeLifecycleEndpointsTest,RuntimeEndpointMatrixTest'`
Expected: FAIL (404s / compile errors: `RuntimeGateController` missing).

- [ ] **Step 3: Request/response records**

```java
// runtime/CreateGateRequest.java
package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateGateRequest(@NotBlank @Size(max = 100) String action, @NotBlank @Size(max = 2000) String summary,
                                @NotNull Map<String, Object> payload, List<String> affectsResources) {
}
```
```java
// runtime/GateCreatedResponse.java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.GateStatus;

import java.time.Instant;
import java.util.UUID;

public record GateCreatedResponse(UUID gateId, GateStatus status, Instant expiresAt) {
}
```
```java
// runtime/GateView.java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateStatus;

import java.time.Instant;
import java.util.UUID;

public record GateView(UUID gateId, GateStatus status, String note, Instant expiresAt, Instant decidedAt) {
    static GateView from(GateRequest gate) {
        return new GateView(gate.getId(), gate.getStatus(), gate.getDecisionNote(), gate.getExpiresAt(), gate.getDecidedAt());
    }
}
```
```java
// runtime/UsageRequest.java
package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UsageRequest(@NotNull @Positive BigDecimal quantity, @NotBlank @Size(max = 100) String unit,
                           @Size(max = 200) String outcomeRef) {
}
```
```java
// runtime/UsageResponse.java
package com.agentstore.platform.runtime;

import java.math.BigDecimal;

public record UsageResponse(BigDecimal charged, BigDecimal totalCharged, BigDecimal costCapRemaining) {
}
```
```java
// runtime/LogBatch.java
package com.agentstore.platform.runtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record LogBatch(@NotEmpty @Size(max = 100) List<@Valid LogEntry> entries) {

    public record LogEntry(@NotBlank @Size(max = 16) String level, @NotBlank @Size(max = 4000) String message,
                           Map<String, Object> fields) {
    }
}
```
```java
// runtime/CompleteRequest.java
package com.agentstore.platform.runtime;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record CompleteRequest(@Size(max = 2000) String summary, Map<String, Object> outputs) {
}
```
```java
// runtime/FailRequest.java
package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailRequest(@NotBlank @Size(max = 2000) String error, boolean retriable) {
}
```
```java
// runtime/RunStateResponse.java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunStatus;

public record RunStateResponse(RunStatus status) {
}
```

- [ ] **Step 4: Controllers**

`runtime/RuntimeGateController.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/runtime/v1/gates")
public class RuntimeGateController {

    static final int MAX_WAIT_SECONDS = 30;

    private final ScopeGuard scopeGuard;
    private final GateService gateService;
    private final ObjectMapper objectMapper;

    public RuntimeGateController(ScopeGuard scopeGuard, GateService gateService, ObjectMapper objectMapper) {
        this.scopeGuard = scopeGuard;
        this.gateService = gateService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GateCreatedResponse create(@Valid @RequestBody CreateGateRequest request) throws JsonProcessingException {
        RunPrincipal principal = RunPrincipal.current();
        ApprovalGateDecl declared = scopeGuard.gateAction(principal, request.action());
        List<String> affects = request.affectsResources() == null ? List.of() : request.affectsResources();
        GateRequest gate = gateService.open(principal.runId(), declared.trigger(), declared.timeoutMinutes(),
                request.summary(), objectMapper.writeValueAsString(request.payload()),
                objectMapper.writeValueAsString(affects));
        return new GateCreatedResponse(gate.getId(), gate.getStatus(), gate.getExpiresAt());
    }

    @GetMapping("/{gateId}")
    public GateView get(@PathVariable UUID gateId, @RequestParam(defaultValue = "0") int wait) {
        RunPrincipal principal = RunPrincipal.current();
        return GateView.from(gateService.await(principal.runId(), gateId, Duration.ofSeconds(clampWait(wait))));
    }

    static int clampWait(int seconds) {
        return Math.max(0, Math.min(seconds, MAX_WAIT_SECONDS));
    }
}
```
`runtime/RuntimeUsageController.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.UsageOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1")
public class RuntimeUsageController {

    private final ScopeGuard scopeGuard;
    private final RunLifecycleService runLifecycleService;

    public RuntimeUsageController(ScopeGuard scopeGuard, RunLifecycleService runLifecycleService) {
        this.scopeGuard = scopeGuard;
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping("/usage")
    public UsageResponse usage(@Valid @RequestBody UsageRequest request) {
        RunPrincipal principal = RunPrincipal.current();
        scopeGuard.usageUnit(principal, request.unit());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quantity", request.quantity());
        detail.put("unit", request.unit());
        if (request.outcomeRef() != null) {
            detail.put("outcomeRef", request.outcomeRef());
        }
        UsageOutcome outcome = runLifecycleService.recordUsage(principal.runId(), request.quantity(), detail);
        if (outcome.capReached()) {
            throw new RuntimeApiException(HttpStatus.PAYMENT_REQUIRED, "BUDGET_EXCEEDED",
                    "Spend limit reached: charged " + outcome.charged() + " and stopped the run");
        }
        return new UsageResponse(outcome.charged(), outcome.totalCharged(), outcome.costCapRemaining());
    }
}
```
`runtime/RuntimeLifecycleController.java`:
```java
package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1")
public class RuntimeLifecycleController {

    private final RunLifecycleService runLifecycleService;

    public RuntimeLifecycleController(RunLifecycleService runLifecycleService) {
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat() {
        runLifecycleService.heartbeat(RunPrincipal.current().runId());
    }

    @PostMapping("/logs")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logs(@Valid @RequestBody LogBatch batch) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LogBatch.LogEntry entry : batch.entries()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("level", entry.level());
            detail.put("message", entry.message());
            if (entry.fields() != null) {
                detail.put("fields", entry.fields());
            }
            entries.add(detail);
        }
        runLifecycleService.recordAgentLogs(RunPrincipal.current().runId(), entries);
    }

    @PostMapping("/complete")
    public RunStateResponse complete(@Valid @RequestBody(required = false) CompleteRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("summary", request != null && request.summary() != null ? request.summary() : "Run completed");
        if (request != null && request.outputs() != null) {
            detail.put("outputs", request.outputs());
        }
        runLifecycleService.complete(RunPrincipal.current().runId(), detail);
        return new RunStateResponse(RunStatus.COMPLETED);
    }

    @PostMapping("/fail")
    public RunStateResponse fail(@Valid @RequestBody FailRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("summary", request.error());
        detail.put("retriable", request.retriable());
        runLifecycleService.fail(RunPrincipal.current().runId(), detail);
        return new RunStateResponse(RunStatus.FAILED);
    }
}
```

- [ ] **Step 5: Run to verify pass**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RuntimeGateEndpointsTest,RuntimeUsageEndpointTest,RuntimeLifecycleEndpointsTest,RuntimeEndpointMatrixTest,RuntimeResourceEndpointsTest,RuntimeAuthTest'`
Expected: PASS. The matrix tests take a moment (39 cases).

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(runtime): gate, usage, log, heartbeat, complete and fail endpoints"
```

---

### Task 8: End-to-end acceptance test

**Files:**
- Test: `runtime/RuntimeEndToEndTest.java`

**Interfaces:**
- Consumes: everything above. Uses `InMemoryBackends` (Task 6) and `RuntimeFixtures` (Task 5).

- [ ] **Step 1: Write the test**

`backend/src/test/java/com/agentstore/platform/runtime/RuntimeEndToEndTest.java`:
```java
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

/**
 * The WS1 acceptance test: a scripted agent that only ever speaks /runtime/v1 reads a dataset, asks for approval, is
 * approved by the buyer, writes, reports usage and completes; the trace is entirely platform-recorded.
 */
@Import(InMemoryBackends.class)
class RuntimeEndToEndTest extends AbstractIntegrationTest {

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
    RunRepository runRepository;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    FakeSandboxProvider sandbox;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> agentCall(HttpMethod method, String path, String token, Object body) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void aScriptedAgentReadsGatesWritesChargesAndCompletesWithAPlatformRecordedTrace() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        resources.seed("bank_statement", List.of(Map.of("id", 1, "amount", 100), Map.of("id", 2, "amount", 250)));
        String token = agent.token();

        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/context", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        var page = agentCall(HttpMethod.GET, "/runtime/v1/resources/bank_statement/records?limit=10", token, null);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) page.getBody().get("records")).hasSize(2);

        var gate = agentCall(HttpMethod.POST, "/runtime/v1/gates", token, Map.of(
                "action", "post_journal_entries", "summary", "Post 2 entries", "payload", Map.of("rows", 2),
                "affectsResources", List.of("journal_entries")));
        assertThat(gate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String gateId = (String) gate.getBody().get("gateId");
        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/gates/" + gateId, token, null).getBody().get("status")).isEqualTo("PENDING");

        HttpHeaders buyer = new HttpHeaders();
        buyer.setBearerAuth(jwtService.issue(UUID.randomUUID(), agent.scenario().buyer().getId(), UserRole.BUYER_APPROVER));
        buyer.setContentType(MediaType.APPLICATION_JSON);
        var decision = rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/gates/" + gateId + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE", "note", "ok"), buyer), RunResponse.class);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/gates/" + gateId + "?wait=5", token, null).getBody().get("status"))
                .isEqualTo("APPROVED");

        var write = agentCall(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", token,
                Map.of("records", List.of(Map.of("account", "1000"), Map.of("account", "2000"))));
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(write.getBody().get("staged")).isEqualTo(true);   // the scope requiresApproval

        assertThat(agentCall(HttpMethod.POST, "/runtime/v1/usage", token,
                Map.of("quantity", 2, "unit", "reconciled_transaction")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(agentCall(HttpMethod.POST, "/runtime/v1/complete", token, Map.of("summary", "done")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Run run = runRepository.findById(agent.run().getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getTotalCharged()).isEqualByComparingTo("4.00");
        assertThat(sandbox.wasKilled(run.getSandboxId())).isTrue();
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        assertThat(steps).extracting(RunStep::getKind).containsExactly(
                RunStepKind.DATA_READ, RunStepKind.GATE_REQUESTED, RunStepKind.GATE_DECIDED,
                RunStepKind.WRITE_STAGED, RunStepKind.USAGE, RunStepKind.COMPLETE);
        assertThat(steps).allMatch(s -> s.getSource() == RunStepSource.PLATFORM);   // nothing here is the agent's own word
        assertThat(resources.written).hasSize(2);
    }

    @Test
    void anUndeclaredReadIsDeniedAndVisibleInTheTrace() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = agentCall(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.SCOPE_VIOLATION);
            assertThat(s.getSource()).isEqualTo(RunStepSource.PLATFORM);
            assertThat(s.getDetail()).contains("payroll");
        });
    }

    @Test
    void threeUndeclaredReadsStopTheRun() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        for (int i = 0; i < 3; i++) {
            agentCall(HttpMethod.GET, "/runtime/v1/resources/payroll/records", agent.token(), null);
        }

        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(agentCall(HttpMethod.GET, "/runtime/v1/context", agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
```

- [ ] **Step 2: Run**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeEndToEndTest`
Expected: PASS (all behaviour exists from Tasks 1–7). If a step order assertion fails, fix the production ordering; do not reorder the expectation.

- [ ] **Step 3: Commit**

```bash
git add backend
git commit -m "test(runtime): end-to-end acceptance test with a scripted agent"
```

---

### Task 9: Legacy flag, launcher environment and the CLI scaffold

**Files:**
- Modify: `execution/RunLauncher.java`, `execution/AgentCallbackController.java`, `execution/FakeSandboxProvider.java`, `cli/src/scaffold.ts`
- Test: `execution/LegacyEnvVarsTest.java`, `execution/LegacyCallbackFlagOffTest.java`, `cli/test/scaffold.test.ts`

**Interfaces:**
- Config: `agentstore.runtime.legacy-callback.enabled` (default `true`; added to `application.yml` in Task 4).
- Launcher env always: `AGENTSTORE_API_URL`, `AGENTSTORE_RUN_TOKEN`, `AGENTSTORE_RUN_ID`, `AGENTSTORE_MODE`; with the flag on also `AGENT_RUN_ID`, `AGENT_CALLBACK_URL`, `AGENT_CALLBACK_TOKEN`. `FakeSandboxProvider.specForRun` now matches on `AGENTSTORE_RUN_ID`.

- [ ] **Step 1: Write the failing backend tests**

`execution/LegacyEnvVarsTest.java`:
```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyEnvVarsTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLauncher launcher;
    @Autowired
    FakeSandboxProvider sandbox;

    @Test
    void aLaunchedRunGetsBothTheRuntimeAndTheLegacyEnvironment() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.PENDING, null, "placeholder");

        launcher.launch(run.getId());

        var env = sandbox.specForRun(run.getId()).envVars();
        assertThat(env).containsEntry("AGENTSTORE_RUN_ID", run.getId().toString())
                .containsEntry("AGENTSTORE_MODE", "PRODUCTION")
                .containsKeys("AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN")
                .containsEntry("AGENT_RUN_ID", run.getId().toString())
                .containsKeys("AGENT_CALLBACK_URL", "AGENT_CALLBACK_TOKEN");
        assertThat(env.get("AGENTSTORE_RUN_TOKEN")).isEqualTo(env.get("AGENT_CALLBACK_TOKEN"));   // one token, two names
        assertThat(env.get("AGENT_CALLBACK_URL")).startsWith(env.get("AGENTSTORE_API_URL") + "/internal/agent-callback/runs/");
    }
}
```
`execution/LegacyCallbackFlagOffTest.java`:
```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.runtime.legacy-callback.enabled=false")
class LegacyCallbackFlagOffTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLauncher launcher;
    @Autowired
    FakeSandboxProvider sandbox;

    @Test
    void withTheFlagOffTheLauncherOnlyInjectsTheRuntimeEnvironment() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.PENDING, null, "placeholder");

        launcher.launch(run.getId());

        var env = sandbox.specForRun(run.getId()).envVars();
        assertThat(env).containsKeys("AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN", "AGENTSTORE_RUN_ID", "AGENTSTORE_MODE");
        assertThat(env).doesNotContainKeys("AGENT_RUN_ID", "AGENT_CALLBACK_URL", "AGENT_CALLBACK_TOKEN");
    }

    @Test
    void withTheFlagOffTheLegacyEndpointIsGone() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-flag-off", "tok-flag-off");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-flag-off");
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = rest.postForEntity("http://localhost:" + port + "/internal/agent-callback/runs/" + run.getId() + "/events",
                new HttpEntity<>(Map.of("kind", "READ", "detail", Map.of()), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='LegacyEnvVarsTest,LegacyCallbackFlagOffTest'`
Expected: FAIL (`AGENTSTORE_RUN_ID` missing; `specForRun` cannot find the run).

- [ ] **Step 3: Implement**

`execution/RunLauncher.java` — three edits:
1. constructor parameter: old `                       @Value("${agentstore.runs.agent-command:/workspace/app/agentstore-entrypoint}") String fallbackCommand) {` → new
```java
                       @Value("${agentstore.runs.agent-command:/workspace/app/agentstore-entrypoint}") String fallbackCommand,
                       @Value("${agentstore.runtime.legacy-callback.enabled:true}") boolean legacyCallback) {
```
and add the field `    private final boolean legacyCallback;` after `private final String fallbackCommand;`, and `        this.legacyCallback = legacyCallback;` after `this.fallbackCommand = fallbackCommand;`.
2. environment — old
```java
            envVars = new HashMap<>();
            envVars.put("AGENT_RUN_ID", runId.toString());
            envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + runId);
            envVars.put("AGENT_CALLBACK_TOKEN", rawToken);
```
new
```java
            envVars = new HashMap<>();
            envVars.put("AGENTSTORE_API_URL", callbackBaseUrl);
            envVars.put("AGENTSTORE_RUN_TOKEN", rawToken);
            envVars.put("AGENTSTORE_RUN_ID", runId.toString());
            envVars.put("AGENTSTORE_MODE", run.getMode().name());
            if (legacyCallback) {
                envVars.put("AGENT_RUN_ID", runId.toString());
                envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + runId);
                envVars.put("AGENT_CALLBACK_TOKEN", rawToken);
            }
```
`execution/AgentCallbackController.java`: add `import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;` and, above `@RestController`, the annotation
```java
@ConditionalOnProperty(name = "agentstore.runtime.legacy-callback.enabled", havingValue = "true", matchIfMissing = true)
```
`execution/FakeSandboxProvider.java`: old `.filter(spec -> runId.toString().equals(spec.envVars().get("AGENT_RUN_ID")))` → new `.filter(spec -> runId.toString().equals(spec.envVars().get("AGENTSTORE_RUN_ID")))`.

- [ ] **Step 4: Run to verify pass, then every legacy test**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='LegacyEnvVarsTest,LegacyCallbackFlagOffTest,RunControllerTest,RunControllerWalletTest,RunLaunchFromBuildTest,AgentCallbackControllerTest,AgentCallbackCompletionTest,ApprovalGateTest,EndToEndTierATest,EndToEndTierBTest'`
Expected: PASS, with none of the legacy test files edited.

- [ ] **Step 5: The CLI scaffold — failing test first**

`cli/test/scaffold.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { scaffoldFiles } from "../src/scaffold.js";

describe("scaffold agent code", () => {
  it.each([
    ["python", "agent/main.py"],
    ["node", "agent/main.mjs"],
  ] as const)("the %s scaffold completes through the runtime API", (runtime, path) => {
    const source = scaffoldFiles(runtime, "Recon", "agent-id")[path];

    expect(source).toContain("AGENTSTORE_API_URL");
    expect(source).toContain("AGENTSTORE_RUN_TOKEN");
    expect(source).toContain("/runtime/v1/complete");
    expect(source).not.toContain("AGENT_CALLBACK");
    expect(source).not.toContain("/events");
  });
});
```
Run: `cd cli && npx vitest run test/scaffold.test.ts` — Expected: FAIL (still uses `AGENT_CALLBACK_URL`).

Then in `cli/src/scaffold.ts` replace the `PYTHON_MAIN` and `NODE_MAIN` constants with:
```ts
const PYTHON_MAIN = `"""AgentStore runs this file with python. The build only imports it, so keep the work under main()."""
import json
import os
import urllib.request


def complete(summary):
    request = urllib.request.Request(
        os.environ["AGENTSTORE_API_URL"] + "/runtime/v1/complete",
        data=json.dumps({"summary": summary}).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["AGENTSTORE_RUN_TOKEN"],
        },
        method="POST",
    )
    urllib.request.urlopen(request, timeout=10).read()


def main():
    complete("hello from my agent")


if __name__ == "__main__":
    main()
`;

const NODE_MAIN = `// AgentStore runs this file with node. The build only syntax-checks it.
async function complete(summary) {
  const res = await fetch(process.env.AGENTSTORE_API_URL + "/runtime/v1/complete", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + process.env.AGENTSTORE_RUN_TOKEN,
    },
    body: JSON.stringify({ summary }),
  });
  if (!res.ok) throw new Error("runtime call failed: " + res.status);
}

await complete("hello from my agent");
`;
```
and in the doc comment above `scaffoldFiles` change "reports COMPLETE over the callback protocol" to "completes the run through the runtime API (`/runtime/v1/complete`)".

- [ ] **Step 6: Run the CLI suite**

Run: `cd cli && npx vitest run`
Expected: PASS (including `init.test.ts` "passes validate").

- [ ] **Step 7: Commit**

```bash
git add backend cli
git commit -m "feat(runtime): legacy callback behind a flag, runtime environment for agents, scaffold on /runtime/v1"
```

---

### Task 10: OpenAPI contract and drift test

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Create: `runtime/RuntimeOpenApiConfig.java`, `contracts/runtime-v1.yaml` (generated), `contracts/README.md`
- Test: `runtime/RuntimeContractTest.java`

**Interfaces:**
- Produces: `GET /v3/api-docs` (JSON) and `/v3/api-docs.yaml` for `/runtime/v1/**` only; `contracts/runtime-v1.yaml` is the committed copy. Regenerate with `mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true`.

- [ ] **Step 1: Dependency and config**

`backend/pom.xml`:
```xml
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-api</artifactId>
      <version>2.6.0</version>
    </dependency>
```
`application.yml` (top level, next to `management:`):
```yaml
springdoc:
  paths-to-match: /runtime/v1/**
  api-docs:
    version: openapi_3_1
  swagger-ui:
    enabled: false
```
`runtime/RuntimeOpenApiConfig.java`:
```java
package com.agentstore.platform.runtime;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
class RuntimeOpenApiConfig {

    /** A fixed relative server so the generated document does not depend on the port a test happens to run on. */
    @Bean
    OpenAPI runtimeOpenApi() {
        return new OpenAPI()
                .info(new Info().title("AgentStore Agent Runtime API").version("1")
                        .description("The only interface agent code uses. Authenticate with the run token in AGENTSTORE_RUN_TOKEN."))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("runToken",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("runToken"));
    }
}
```

- [ ] **Step 2: Write the drift test**

`runtime/RuntimeContractTest.java`:
```java
package com.agentstore.platform.runtime;

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

class RuntimeContractTest extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("../contracts/runtime-v1.yaml");

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    @Test
    void theCommittedContractMatchesWhatTheControllersGenerate() throws Exception {
        String base = "http://localhost:" + port;
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject(base + "/v3/api-docs", String.class));

        if (System.getProperty("contract.update") != null) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, rest.getForObject(base + "/v3/api-docs.yaml", String.class));
        }

        JsonNode committed = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        assertThat(generated.equals(committed))
                .as("contracts/runtime-v1.yaml is out of date. If the change is intended, regenerate it with: "
                        + "cd backend && mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true, then commit the file.")
                .isTrue();
    }

    @Test
    void theContractDescribesOnlyTheRuntimeApi() throws Exception {
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject("http://localhost:" + port + "/v3/api-docs", String.class));

        assertThat(generated.get("openapi").asText()).startsWith("3.1");
        generated.get("paths").fieldNames().forEachRemaining(path -> assertThat(path).startsWith("/runtime/v1/"));
        assertThat(generated.get("paths").fieldNames()).toIterable().contains(
                "/runtime/v1/context", "/runtime/v1/gates", "/runtime/v1/gates/{gateId}", "/runtime/v1/usage",
                "/runtime/v1/logs", "/runtime/v1/heartbeat", "/runtime/v1/complete", "/runtime/v1/fail",
                "/runtime/v1/llm/chat", "/runtime/v1/resources/{resource}/records");
    }
}
```

- [ ] **Step 3: Run to verify failure, generate, run again**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest`
Expected: FAIL (`NoSuchFileException: ../contracts/runtime-v1.yaml`, or the `paths` test fails if springdoc listed other controllers: then fix `springdoc.paths-to-match`).

Generate: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true`
Then read `contracts/runtime-v1.yaml` once and check it: `openapi: 3.1.x`, no `localhost`/port, no `/api/v1` paths, every runtime path present, no internal types leaking (`Run`, `Manifest`). If a schema looks wrong (for example `principal` shown as a parameter), fix the controller, regenerate.
Run again without the flag: `mvn -q test -Dtest=RuntimeContractTest` — Expected: PASS. Run it a second time to confirm the output is stable (deterministic across runs).

- [ ] **Step 4: Contract README and commit**

`contracts/README.md`:
```markdown
# Contracts

`runtime-v1.yaml` is the OpenAPI 3.1 contract of the Agent Runtime API (`/runtime/v1`), generated from the Spring
controllers by springdoc. It is what the SDKs (WS4) and the CLI build against. `RuntimeContractTest` fails when the
controllers and this file disagree.

After an intended API change, regenerate and commit:

    cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true

Breaking changes go to `/runtime/v2` with a deprecation window (spec §4.2).
```
```bash
git add backend contracts
git commit -m "feat(runtime): OpenAPI 3.1 contract for /runtime/v1 with a drift test"
```

---

### Task 11: Frontend labels, ontology, full verification

**Files:**
- Modify: `frontend/src/lib/types.ts`, `frontend/src/components/RunTraceStep.tsx`, `frontend/src/components/RunTraceStep.test.tsx`, `ONTOLOGY.md`

- [ ] **Step 1: Frontend — failing tests**

Append to `frontend/src/components/RunTraceStep.test.tsx` (inside the `describe`):
```tsx
  it("labels the steps the runtime API records", () => {
    const cases: [RunStepResponse["kind"], string][] = [
      ["DATA_READ", "Read"],
      ["DATA_WRITE", "Wrote"],
      ["WRITE_STAGED", "Drafted, waiting for approval"],
      ["GATE_REQUESTED", "Needs your decision"],
      ["GATE_DECIDED", "Decision recorded"],
      ["LLM_CALL", "Asked the AI model"],
      ["USAGE", "Reported usage"],
      ["SCOPE_VIOLATION", "Blocked: outside its permissions"],
    ];
    for (const [kind, label] of cases) {
      const { unmount } = render(<RunTraceStep step={{ kind, detail: "{}", createdAt: "2026-09-26T10:00:00Z" }} />);
      expect(screen.getByText(label)).toBeInTheDocument();
      unmount();
    }
  });

  it("marks what the agent said itself as reported by the agent", () => {
    render(<RunTraceStep step={{ kind: "AGENT_LOG", source: "AGENT", detail: '{"message":"matched 40 lines"}', createdAt: "2026-09-26T10:00:00Z" }} />);
    expect(screen.getByText("Reported by the agent")).toBeInTheDocument();
    expect(screen.getByText("matched 40 lines")).toBeInTheDocument();
  });

  it("highlights a newly requested gate like the legacy one", () => {
    const { container } = render(<RunTraceStep step={{ kind: "GATE_REQUESTED", detail: '{"summary":"Post 2 entries"}', createdAt: "2026-09-26T10:00:00Z" }} />);
    expect(container.firstChild).toHaveClass("border-amber");
  });
```
Run: `cd frontend && npx vitest run src/components/RunTraceStep.test.tsx` — Expected: FAIL (labels missing / type errors).

- [ ] **Step 2: Frontend — implement**

`frontend/src/lib/types.ts`: replace line 10 with
```ts
export type RunStepKind =
  | "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED" | "TIMEOUT" | "COST_CAP_REACHED"
  | "DATA_READ" | "DATA_WRITE" | "WRITE_STAGED" | "GATE_REQUESTED" | "GATE_DECIDED"
  | "LLM_CALL" | "USAGE" | "SCOPE_VIOLATION" | "AGENT_LOG";
export type RunStepSource = "PLATFORM" | "AGENT";
```
and in `RunStepResponse` add `source?: RunStepSource;` after `kind: RunStepKind;` (optional so existing fixtures still type-check; the backend always sends it).

`frontend/src/components/RunTraceStep.tsx`: replace `KIND_LABEL` and the two lines that use it:
```tsx
const KIND_LABEL: Record<RunStepResponse["kind"], string> = {
  READ: "Read",
  WRITE: "Wrote",
  GATE: "Needs your decision",
  COST: "Charged",
  COMPLETE: "Completed",
  FAILED: "Failed",
  TIMEOUT: "Stopped by platform",
  COST_CAP_REACHED: "Spend limit reached",
  DATA_READ: "Read",
  DATA_WRITE: "Wrote",
  WRITE_STAGED: "Drafted, waiting for approval",
  GATE_REQUESTED: "Needs your decision",
  GATE_DECIDED: "Decision recorded",
  LLM_CALL: "Asked the AI model",
  USAGE: "Reported usage",
  SCOPE_VIOLATION: "Blocked: outside its permissions",
  AGENT_LOG: "Reported by the agent",
};

export function RunTraceStep({ step }: { step: RunStepResponse }) {
  const detail = parseDetail(step.detail);
  const isGate = step.kind === "GATE" || step.kind === "GATE_REQUESTED";
  return (
    <div className={`relative pl-6 pb-5 border-l-2 ${isGate ? "border-amber" : "border-line"}`}>
      <div className="text-xs text-muted">{new Date(step.createdAt).toLocaleTimeString()}</div>
      <div className="font-medium">{KIND_LABEL[step.kind]}</div>
      <div className="text-sm text-ink2">{detail.summary ?? detail.message ?? step.detail}</div>
    </div>
  );
}
```
and widen `parseDetail`'s type to `{ summary?: string; message?: string }` (both the return type and the cast).

Run: `cd frontend && npx vitest run && npx tsc --noEmit` — Expected: PASS.

- [ ] **Step 3: Ontology**

In `ONTOLOGY.md`:
1. Append to the header's `ontology-tracked-files:` line (single line; use `sed -i '' 's|^\(ontology-tracked-files:.*\)$|\1,backend/src/main/java/com/agentstore/platform/execution/GateRequest.java,backend/src/main/java/com/agentstore/platform/execution/GateService.java,backend/src/main/java/com/agentstore/platform/execution/RunMode.java,backend/src/main/java/com/agentstore/platform/execution/RunStepSource.java,backend/src/main/java/com/agentstore/platform/execution/GateStatus.java,backend/src/main/java/com/agentstore/platform/runtime/RunPrincipal.java,backend/src/main/java/com/agentstore/platform/runtime/RuntimeAuthFilter.java,backend/src/main/java/com/agentstore/platform/runtime/ScopeGuard.java,backend/src/main/java/com/agentstore/platform/runtime/RuntimeRateLimiter.java,backend/src/main/java/com/agentstore/platform/runtime/ResourceBackend.java,backend/src/main/java/com/agentstore/platform/runtime/LlmBackend.java,backend/src/main/resources/db/migration/V25__runtime_api.sql,backend/src/main/resources/db/migration/V26__gate_request.sql,contracts/runtime-v1.yaml|' ONTOLOGY.md`).
2. Under `### Execution` (section 2) add these bullets right after the heading line (use the Edit tool with `old_string` = `### Execution\n` — the heading text is unique):
```markdown
* **`GateRequest`** (`backend/src/main/java/com/agentstore/platform/execution/GateRequest.java`): One approval request an agent raised through `POST /runtime/v1/gates`: `action` (a declared manifest gate `trigger`), `summary`, `payload`, `affectsResources`, `status`: `[PENDING | APPROVED | REJECTED | EXPIRED]` (`GateStatus`), `decidedByUserId`, `decisionNote`, `expiresAt` (created + the gate's `timeoutMinutes`, default 30). At most one PENDING gate per run. Table `gate_request` (migration `V26__gate_request.sql`). `GateService` opens, decides, awaits and expires gates under the run's row lock.
* **`Run`** now also carries `mode`: `[PRODUCTION | SANDBOX | REVIEW | TRIAL]` (`RunMode`; only PRODUCTION runs exist until WS6), `violationCount` (PRODUCTION runs stop at 3 scope violations) and `inputJson` (migration `V25__runtime_api.sql`). `RunStep` now carries `source`: `[PLATFORM | AGENT]` (`RunStepSource`): PLATFORM steps are what the platform itself observed; AGENT steps (legacy events, `AGENT_LOG`) are the agent's own word. New step kinds: `DATA_READ`, `DATA_WRITE`, `WRITE_STAGED`, `GATE_REQUESTED`, `GATE_DECIDED`, `LLM_CALL`, `USAGE`, `SCOPE_VIOLATION`, `AGENT_LOG`.
* **`RunPrincipal`** (`backend/src/main/java/com/agentstore/platform/runtime/RunPrincipal.java`): The authenticated identity of a `/runtime/v1` call: `(runId, orgId, mode, manifest, grantedScopes)`. A scope is granted when the manifest declares it **and** the buyer's connection lists its resource in `grantedScopes`.
```
3. Immediately before the heading `### Agent-facing callbacks (called from inside the sandbox)` (section 5; unique) insert:
```markdown
### Agent Runtime API v1 (called from inside the sandbox; `/runtime/v1/**`)
The interface agent code uses; contract in `contracts/runtime-v1.yaml`. Authenticated by the run token (`Authorization: Bearer $AGENTSTORE_RUN_TOKEN`) on its own `SecurityFilterChain` (`RuntimeAuthFilter`, no JWT); the token identifies the run, so no run id is in the path. Errors are `application/problem+json` with a stable `code`. Per-run rate limits (bucket4j on Redis) fail open.
* `GET /runtime/v1/context`: run id, mode, agent version, input, granted scopes, limits.
* `GET /runtime/v1/resources/{resource}`, `/records`, `/file`, `POST /runtime/v1/resources/{resource}/records`: checked by `ScopeGuard`, then delegated to `ResourceBackend` (501 `NOT_IMPLEMENTED` until WS2). Records `DATA_READ` / `DATA_WRITE` / `WRITE_STAGED`.
* `POST /runtime/v1/gates` (201), `GET /runtime/v1/gates/{gateId}?wait=` (long-poll, max 30 s): open a gate and wait for the buyer's decision. The buyer decides with `POST /api/v1/runs/{id}/gates/{gateId}/decision {decision: APPROVE|REJECT, note}`; the old `/approve` and `/reject` delegate to the run's single pending gate.
* `POST /runtime/v1/llm/chat`: delegated to `LlmBackend` (501 until WS3).
* `POST /runtime/v1/usage`: `unit` must equal `costEnvelope.unitLabel`; charges like the legacy COST event; 402 `BUDGET_EXCEEDED` when the cap is hit (the remainder is charged and the run stops).
* `POST /runtime/v1/logs` (`AGENT_LOG`, agent-reported), `/heartbeat`, `/complete`, `/fail`.
* A call outside the granted scopes returns 403 `SCOPE_NOT_GRANTED` and records `SCOPE_VIOLATION`; the third one stops a PRODUCTION run.
The legacy `/internal/agent-callback/**` below stays while `agentstore.runtime.legacy-callback.enabled` is true (default); `RunLauncher` then injects both `AGENTSTORE_*` and `AGENT_*` variables.

```
4. In the `### Agent-facing callbacks` subsection, add a first line: `Legacy. New agents use the Agent Runtime API above. Served only while \`agentstore.runtime.legacy-callback.enabled\` is true.`
5. In the ontology header comment of section 1's generated-from line, append: ` The Agent Runtime API (WS1) was added on branch \`feat/ws1-agent-runtime-api\`.`

Then re-stamp and check:
```bash
./scripts/stamp-ontology-hash.sh
./scripts/check-ontology-staleness.sh
```
Expected: the check exits 0.

- [ ] **Step 4: Full verification (evidence before claiming done)**

Run, and read the tail of each output:
```bash
cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test 2>&1 | tail -30
cd ../cli && npx vitest run 2>&1 | tail -15
cd ../frontend && npx vitest run 2>&1 | tail -15 && npx tsc --noEmit
cd .. && git status --short
```
Expected: all suites green (backend, cli, frontend), `tsc` silent, `git status` shows only intended files. Confirm no legacy test file was edited: `git diff --stat docs/ws1-design..HEAD -- backend/src/test | cat` lists only new files plus `AbstractIntegrationTest.java` and `ExecutionFixtures.java` (and possibly one wiring line in `RunReaperTest` if it constructs the reaper by hand).

- [ ] **Step 5: Commit**

```bash
git add frontend ONTOLOGY.md
git commit -m "feat(frontend): label the runtime API's step kinds; docs: ontology for the runtime API"
```

- [ ] **Step 6: Hand off — do not push**

Report to the user: the branch `feat/ws1-agent-runtime-api`, the commit list, the three test-suite results, and what still needs them: `git push` and `gh pr create` (not done without their say-so), and a manual check that the docker-compose Redis (`docker compose up -d redis`) plus a real agent using `AGENTSTORE_API_URL` / `AGENTSTORE_RUN_TOKEN` completes a run against a live runner. Update the memory notes (`agentstore-ws1-design-state`, `agentstore-workstream-status`, `MEMORY.md`).
