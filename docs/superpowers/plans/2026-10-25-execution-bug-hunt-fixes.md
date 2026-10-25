# Execution Bug-Hunt Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the four bugs from the 2026-10-25 static bug hunt of `platform/execution` (monthly-limit undercount, unbounded legacy COST quantity, `reached_block` overflow, late gate approval).

**Architecture:** Four independent, small fixes in `backend/.../platform/execution`, one commit each, one PR. No API shape changes; one Flyway migration.

**Tech Stack:** Spring Boot, JPA/native queries, Postgres + Flyway, JUnit 5 + Testcontainers (`AbstractIntegrationTest`).

**Spec:** the bug-hunt report (pasted in the session that wrote this plan); findings #1–#4 below quote its facts.

## Global Constraints

- Branch from `origin/master` (now `f21d68c`, past the hunted `10ad3da`): `fix/execution-bug-hunt`.
- Next free migration is `V51` (`V50__payouts.sql` is on origin/master). Re-check before writing it.
- Quantity bounds must match `runtime/UsageRequest`: `@DecimalMax("9999999999") @Digits(integer = 10, fraction = 4)`.
- Update `docs/status.md` (and bump "Last updated") in the same PR; add nothing to `docs/known-issues.md` unless a fix is deferred.
- No Claude/AI attribution in commits or the PR.
- Tests need Colima + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` (see dev-environment memory).
- Out of scope: the "needs a human look" item (`GET /api/v1/runs/mine` has no paging). Raise it with the user separately.

## Review Focus

1. A run that has charged its whole cap and is still RUNNING (reserved 0) — must count `total_charged` in month-to-date.
2. Legacy COST `quantity` sent as a JSON number vs a string (`1E+9999999` both ways) — both must 400 fast, before any multiply.
3. `quantity` `"0.00001"` (5 decimals) — rejected with 400, same as `/runtime/v1/usage`.
4. A gate exactly at `expiresAt` — treat `!now.isBefore(expiresAt)` as expired, matching the email link's `expires_at > now()`.
5. A scope violation with a 200+-char target in a PRODUCTION run — counted, and the third one still stops the run.

---

### Task 1: Monthly limit counts what open runs have already charged (#1, Critical)

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java:71-78` (`spendSince` query + javadoc)
- Test: `backend/src/test/java/com/agentstore/platform/execution/ConnectionMonthlyLimitTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/ConnectionSummaryControllerTest.java`

**Interfaces:** `RunRepository.spendSince(UUID, Instant): BigDecimal` keeps its signature. In-flight rows now contribute `total_charged + reserved_amount` (reservation shrinks as charges land, so the sum is the run's committed worst case).

- [ ] **Step 1: Fix the unrealistic fixture.** In `anInFlightRunCountsForItsReservation`, change `priorRun(s, RunStatus.RUNNING, "10.00", "100.00", …)` to `"10.00", "90.00"` (a state real code produces on a 100 cap). Keep its assertion.

- [ ] **Step 2: Write the failing tests.**

```java
@Test
void anInFlightRunCountsWhatItHasAlreadyCharged() {
    var s = scenario("140.00");                       // new run's cap is 100
    priorRun(s, RunStatus.RUNNING, "40.00", "10.00", Instant.now());   // 50 committed
    var response = trigger(s);                        // 50 + 100 > 140
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    assertThat(reserved(s)).isEqualByComparingTo("0");
}

@Test
void aRunThatSpentItsWholeCapStillCounts() {
    var s = scenario("150.00");
    priorRun(s, RunStatus.RUNNING, "100.00", "0.00", Instant.now());
    assertThat(trigger(s).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
}
```

In `ConnectionSummaryControllerTest`, add `monthToDateIncludesChargesOfARunStillInFlight`: one RUNNING production run with charged `40.00`, reserved `10.00`; `GET /api/v1/connections/mine` returns `monthToDate` `50.00` (follow that file's existing request/assert helpers).

- [ ] **Step 3: Run, expect FAIL** (402 tests get 202; summary returns 10.00).

Run: `cd backend && ./mvnw -q test -Dtest='ConnectionMonthlyLimitTest,ConnectionSummaryControllerTest'`

- [ ] **Step 4: Change the `ELSE` branch** of `spendSince` to `total_charged + reserved_amount`; update the javadoc to "the charge so far plus the remaining reservation for one still in flight".

- [ ] **Step 5: Run, expect PASS** (same command).

- [ ] **Step 6: Commit** `fix(execution): count charges of in-flight runs toward the monthly limit`

---

### Task 2: Bound the legacy COST quantity before charging (#2, High)

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java:339-350` (`parseQuantity`)
- Test: `backend/src/test/java/com/agentstore/platform/execution/AgentCallbackControllerTest.java`

**Interfaces:** `parseQuantity(Object): BigDecimal` keeps its signature; new constants in `RunLifecycleService`: `static final BigDecimal MAX_QUANTITY = new BigDecimal("9999999999")`, `static final int MAX_QUANTITY_FRACTION = 4`.

- [ ] **Step 1: Write the failing tests** (parameterised, through `POST /internal/agent-callback/{runId}/event` with a COST event, using the file's existing callback helper):

```java
@ParameterizedTest
@ValueSource(strings = {"\"1E+9999999\"", "1E+9999999", "\"10000000000\"", "\"0.00001\""})
void rejectsAnOutOfRangeCostQuantity(String quantityJson) {
    long t0 = System.nanoTime();
    var response = postCost(run, quantityJson);       // body {"type":"COST","detail":{"quantity":<quantityJson>}}
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    assertThat(runRepository.findById(run.getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
    assertThat(usageEventRepository.findByRunId(run.getId())).isEmpty();   // or the file's equivalent check
}
```

Keep the existing positive-path COST test green (e.g. `"3"` still charges). If the JSON number form fails earlier in Jackson with a 400, that is an acceptable pass; confirm it does not hang in deserialisation.

- [ ] **Step 2: Run, expect FAIL** (500 or hang on the first two; 500 on `10000000000`; 200 on `0.00001`).

Run: `cd backend && ./mvnw -q test -Dtest=AgentCallbackControllerTest`

- [ ] **Step 3: Implement.** In `parseQuantity`, after parsing and the `signum` check, reject with 400 `"quantity must be at most 9999999999 with up to 4 decimal places"` when `quantity.scale() > MAX_QUANTITY_FRACTION || quantity.compareTo(MAX_QUANTITY) > 0`. Both checks are O(1) on huge exponents — do them before anything calls `multiply`/`setScale`. Do not call `stripTrailingZeros()` or `toPlainString()` first (those are the expensive paths).

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Commit** `fix(execution): bound the legacy COST quantity like the usage endpoint`

---

### Task 3: Widen `run.reached_block` so long block names don't 500 (#3, High)

**Files:**
- Create: `backend/src/main/resources/db/migration/V51__run_reached_block_text.sql`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunStepBlockLengthTest.java`

**Interfaces:** none change. `run_step.block` is already `TEXT` (V32); this makes `run.reached_block` match, since its value is copied from it and a violation target is agent-controlled with no cap.

- [ ] **Step 1: Write the failing tests** in `RunStepBlockLengthTest`:

```java
@Test
void aLongBlockNameIsCopiedToReachedBlock() {
    // PRODUCTION run (use ExecutionFixtures.liveTierA + a RUNNING run, as other lifecycle tests do)
    String target = "a".repeat(200);
    lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", target));
    Run after = runRepository.findById(run.getId()).orElseThrow();
    assertThat(after.getReachedBlock()).isEqualTo("read:" + target);
    assertThat(after.getViolationCount()).isEqualTo(1);
}

@Test
void threeLongViolationsStillStopAProductionRun() {
    // same setup; call recordViolation 3x with 200-char targets
    assertThat(lifecycle.recordViolation(...)).isTrue();   // on the third
    assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
}
```

Also add `aGateWithAHundredCharTriggerOpens`: open a gate whose `action` is 100 chars via `GateService` (schema max) and assert the run's `reachedBlock` is `"gate:" + action`.

- [ ] **Step 2: Run, expect FAIL** (`DataIntegrityViolationException` / value too long for `varchar(100)`).

Run: `cd backend && ./mvnw -q test -Dtest=RunStepBlockLengthTest`

- [ ] **Step 3: Write the migration:** `ALTER TABLE run ALTER COLUMN reached_block TYPE TEXT;` If `Run.reachedBlock` has `@Column(length = 100)`, drop the length.

- [ ] **Step 4: Run, expect PASS**, then `-Dtest='RunListTest,RunFlowServiceTest'` to confirm list/flow still read it.

- [ ] **Step 5: Commit** `fix(execution): store reached_block as text so long block names don't fail the run`

---

### Task 4: Refuse a decision on a gate past its expiry (#4, Medium)

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/GateService.java:113-117` (`decide`, the `found.isPresent()` branch)
- Test: `backend/src/test/java/com/agentstore/platform/execution/GateServiceTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/GateDecisionEndpointTest.java`

**Interfaces:** `GateService.decide(...)` keeps its signature; it throws `ResponseStatusException(CONFLICT, "Gate expired")` for an expired PENDING gate. `ApprovalLinkService` already maps CONFLICT to 410, so the email path is unchanged.

- [ ] **Step 1: Write the failing tests.**

```java
@Test
void refusesToApproveAGateWhoseExpiryHasPassed() {
    // open a gate, then set gate.expiresAt = Instant.now().minusSeconds(1) and save (reaper not run)
    assertThatThrownBy(() -> gateService.decide(runId, gateId, true, approverId, null, DecisionChannel.PORTAL))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(gateRequestRepository.findById(gateId).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
}
```

Plus `refusesARejectionPastExpiry` (approve=false, same 409), and in `GateDecisionEndpointTest` one HTTP test: the portal decide endpoint returns 409 for an expired gate. Use the `DecisionChannel` value the existing tests use for the portal.

- [ ] **Step 2: Run, expect FAIL** (decision succeeds).

Run: `cd backend && ./mvnw -q test -Dtest='GateServiceTest,GateDecisionEndpointTest'`

- [ ] **Step 3: Implement.** Right after the `PENDING` check and before `requireApprover`, throw 409 `"Gate expired"` when `!Instant.now().isBefore(gate.getExpiresAt())`. Leave the reaper to move it to EXPIRED (it already does; the throw rolls this transaction back). The legacy no-gate-row path is out of scope.

- [ ] **Step 4: Run, expect PASS**, plus `-Dtest='ApprovalGateTest,GateWs1bTest,RunReaperTest'` to confirm the expiry path still works.

- [ ] **Step 5: Commit** `fix(execution): refuse gate decisions after the gate's expiry`

---

### Task 5: Full suite, status doc, PR

- [ ] **Step 1:** `cd backend && ./mvnw -q verify` — expect BUILD SUCCESS.
- [ ] **Step 2:** In `docs/status.md`, add a line under fixes for the four execution bug-hunt fixes (and note the open `/runs/mine` paging question); bump "Last updated" to the PR date. Commit `docs(status): record execution bug-hunt fixes`.
- [ ] **Step 3:** Open the PR with the `create-pr` skill against `master`. Do not merge or watch CI.
