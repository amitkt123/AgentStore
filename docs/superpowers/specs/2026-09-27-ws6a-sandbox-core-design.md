# WS6a — Developer Sandbox Core — Design

> **Status:** sections 1–4 approved by the user in conversation on 2026-09-27. Narrows the parent's WS6 section in `2026-09-23-agent-store-developer-platform-and-sandbox-design.md`; where the two differ, this document wins. Backend, contract and a thin CLI only. Decisions that were inferred rather than stated are marked `[I#]` and listed in §9.

## 1. Goal and scope

A developer can run any of their built versions, including DRAFT, against fixture data in the real runner, with no buyer, no connection and no money moving. The run uses the same orchestration, Runtime API, LLM gateway and trace as production. What differs is who owns it, where data comes from, and that nothing reaches the ledger.

WS6 is split into three slices, each with its own spec, plan and PR:

- **6a (this document):** the sandbox core.
- **6b:** behaviour report, declared-vs-actual, network visibility, result class, submit readiness, manifest patches.
- **6c:** PII scan, trial attestation, REPO profile sync, CLI profile and fixture commands.

**In scope (6a)**
- Connection-less SANDBOX runs (§2, §3).
- Sandbox profiles and fixture datasets (§2, §6).
- `POST /api/v1/sandbox/runs`, list, detail, cancel, rerun, developer gate decisions and per-profile auto-policy (§3, §6).
- Simulated billing (§4).
- Quotas (§5).
- MOCK and REPLAY wiring, including the ownership check WS3 deferred (§3).
- CLI: `agentstore sandbox run --follow` and `sandbox logs` (§6).

**Out of scope** (with owner)
- Behaviour report, callsites, readiness, manifest patches, `run_report`: 6b.
- PII scan, `trial_enabled` and attestation, `source = REPO` sync, CLI profile and fixture commands: 6c.
- Local emulator (`agentstore dev`): WS4, because it needs the OpenAPI contract and the conformance suite.
- Eval runs and `eval_case_result`: WS8.
- Reading MOCK answers from `mocks/` in the bundle: later. 6a stores them inline on the profile.
- Admin per-org quota override: needs an admin screen.
- UI (sandbox page, fixtures page, quota widget): FE-3.
- Verification workflow that sets `org.developer_verified_at`: WS7. Until then every org gets the unverified limits.

## 2. Data model (migration V31)

```sql
CREATE TABLE sandbox_profile (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    name VARCHAR(100) NOT NULL,
    inline BOOLEAN NOT NULL DEFAULT FALSE,    -- a one-off profile from POST /sandbox/runs; hidden from listings
    input_json JSONB NOT NULL DEFAULT '{}',
    gate_policy JSONB NOT NULL DEFAULT '{"default":"MANUAL","actions":{}}',
    llm_mode VARCHAR(10),                     -- NULL = real; MOCK | REPLAY
    replay_run_id UUID REFERENCES run(id),
    llm_mock_json JSONB,
    spend_cap NUMERIC(12,2),                  -- NULL = manifest costEnvelope.maxPerRun
    fake_secrets_encrypted BYTEA,
    fake_secrets_nonce BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, name)
);

ALTER TABLE run ALTER COLUMN connection_id DROP NOT NULL;
ALTER TABLE run ADD COLUMN initiated_by_org_id UUID REFERENCES org(id);
ALTER TABLE run ADD COLUMN sandbox_profile_id UUID REFERENCES sandbox_profile(id);
ALTER TABLE run ADD COLUMN simulated_charged NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE run ADD COLUMN requested_charge   NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE run ADD CONSTRAINT run_mode_owner_chk CHECK (
    (mode = 'PRODUCTION' AND connection_id IS NOT NULL)
 OR (mode = 'SANDBOX'    AND connection_id IS NULL AND initiated_by_org_id IS NOT NULL)
 OR (mode NOT IN ('PRODUCTION','SANDBOX')));
CREATE INDEX run_sandbox_org_idx ON run (initiated_by_org_id, started_at) WHERE mode = 'SANDBOX';
```

- `run.llm_mode`, `replay_run_id` and `llm_mock_json` already exist (V30). The sandbox run copies them from the profile.
- The profile's `llm_mode` uses the same values as `RunLlmMode`; NULL means real.
- Fixture datasets reuse `dataset` (`purpose = FIXTURE`, `owner_org_id` = the developer org). Bindings reuse `dataset_binding` with owner type `SANDBOX_PROFILE`, whose `owner_id` is the profile id.
- Fake secrets use the existing `AesGcmCipher` and `SecretEncryptionProperties`. The stored value is one encrypted JSON map of name to value.
- `spend_cap` overrides what a buyer's spend cap would be. NULL means the manifest's `maxPerRun`. [I1]
- No `usage_event`, `developer_quota` or `run_report` rows or tables. See the deviations below.

**Deviations from the parent spec**
1. **No `usage_event` rows for sandbox runs.** The parent writes them with `simulated = true`. Here the COST run steps and `run.simulated_charged` already hold the numbers, and with no rows there is nothing a ledger or earnings query could pick up. The no-ledger guarantee is structural. `usage_event.simulated` is not added.
2. **Quota usage is computed, not stored.** The parent's `developer_quota` table keeps counters. Here usage is derived from `run` and `llm_call` for the current UTC month (§5). No drift, no reset job. The admin override waits for an admin screen.
3. **`run_report` and the profile's `description`, `trial_enabled`, attestation and `source` columns** are added by 6b and 6c, not here.

## 3. Run lifecycle

### 3.1 Start

`SandboxRunService.start(callerOrgId, agentVersionId, profileId | inlineProfile)` checks, in order:

1. The caller has the DEVELOPER role and the version's agent belongs to the caller's org. A foreign version is a 404. Any `VersionStatus` is allowed. [I2]
2. The version has a built image. If it needs a rebuild, the run enters `PENDING_BUILD` through the existing `RebuildService` and `PendingBuildRuns` path. A version with no bundle and no image gets `409 VERSION_NOT_BUILT`.
3. Quota (§5): lock the org row (`SELECT … FOR UPDATE`), then check concurrent runs and run-seconds. LLM spend is enforced later, by the gateway.
4. Fixture bindings resolve through `BindingResolver`, keyed on the profile (owner type `SANDBOX_PROFILE`) instead of a connection. The granted resources are all the manifest's READ resources. A declared READ resource with no usable binding gives `422 MISSING_BINDING`, as for buyers. `DataAccessRules` is unchanged, so only non-`BUYER_DATA` datasets qualify.
5. If the profile's `llm_mode` is REPLAY, `replay_run_id` must reference a run that is `mode = SANDBOX`, of the **same agent**, with `initiated_by_org_id` equal to the caller's org. Otherwise `403 REPLAY_SOURCE_INVALID`. A source with no recorded OK calls gives `422 REPLAY_SOURCE_INVALID`. This is the ownership check WS3 left to WS6. [I3]

It then creates the run: `mode = SANDBOX`, `connection = null`, `initiated_by_org_id`, `sandbox_profile_id`, `cost_cap = profile.spend_cap or manifest maxPerRun`, `input_json`, `llm_mode`, `replay_run_id`, `llm_mock_json` from the profile, the same deadline as production, and status `PENDING`. Pins are saved in the same transaction (`saveWithPins`). It launches through `RunLauncher`.

An **inline profile** is a one-off: the same fields as a saved profile, stored as a profile row named `inline-<random suffix>` with `inline = true`, which profile listings exclude. [I4]

### 3.2 The connection-touching sites

| Site | Change |
|---|---|
| `Run` | `connection` becomes optional. `initiatedByOrgId`, `sandboxProfileId`, `simulatedCharged`, `requestedCharge` added. |
| `RunLauncher` | Secrets come from the profile's fake secrets, not connection secrets. A BYOK-direct agent still gets the developer's real key unless the fake secrets supply the same name. Env vars, allowlist and command are unchanged. |
| `RunPrincipal.forRun` | A sandbox run is granted every scope the manifest declares, so undeclared calls are the only violations. The principal's org is `initiated_by_org_id`. |
| `RunLifecycleService.charge` and `finish` | For a sandbox run: no `UsageEvent`, no `LedgerService`, no wallet release. `simulated_charged` and `requested_charge` are updated. Cap clipping is identical to production. |
| Ownership checks in `RunController`, `RunDetailController`, `OutputsController` | "Buyer org via the connection" or, for a sandbox run, "initiator org". Buyer endpoints refuse sandbox runs (404), and sandbox endpoints refuse production runs. |
| `StatementController`, `RunSummaryResponse` | Sandbox runs never appear in buyer statements or run lists. |
| `RunReaper` | Deadline, heartbeat and gate-expiry handling are unchanged. Wallet releases are guarded like `finish`. |

`requested_charge` accumulates the un-clipped amount (units × unit price). `simulated_charged` accumulates what the cap allowed. Together they give "₹620 asked, ₹500 kept".

### 3.3 Violations and gates

- **Violations.** In SANDBOX a scope violation is recorded and the run continues. The three-strikes kill stays PRODUCTION-only.
- **Auto-policy.** After `RuntimeGateController` opens a gate for a sandbox run, it looks up the profile's `gate_policy`: the entry for that action, else `default`. `AUTO_APPROVE` and `AUTO_REJECT` call `GateService.decide` at once with no user and the note `auto policy`. `MANUAL` waits. Policy values: `MANUAL`, `AUTO_APPROVE`, `AUTO_REJECT`. A production run never consults a policy.
- **Developer decision.** `POST /api/v1/sandbox/runs/{id}/gates/{gateId}/decision` `{approve, note}`, owner-checked, reuses `GateService.decide`. Expiry is unchanged.

### 3.4 Cancel and rerun

- **Cancel.** `POST /sandbox/runs/{id}/cancel` ends a non-terminal run as `CANCELLED` and kills the sandbox, through the existing terminate path.
- **Rerun.** `POST /sandbox/runs/{id}/rerun` starts the same version with the same profile settings and `llm_mode = REPLAY`, `replay_run_id` = the run being re-run. It goes through §3.1, so it is quota-checked and the source is ownership-checked.

### 3.5 Outputs

Outputs stay in the platform store and are never committed anywhere (`ModeGuard` already forces this). The initiating developer can list and read them through the existing outputs endpoints, with the ownership branch above.

## 4. Simulated billing

`usage` calls in a SANDBOX run compute `requested = quantity × manifest unit price` and `charged = min(requested, remaining cap)`, exactly as production. A `COST` step is recorded. `run.total_charged` is not touched by SANDBOX; `simulated_charged` and `requested_charge` are. When the requested amount exceeds the remaining cap, the run gets `COST_CAP_REACHED` and fails, as in production. No `UsageEvent`, ledger entry, wallet reservation or release exists for a sandbox run.

## 5. Quotas

Limits come from configuration, keyed on `org.developer_verified_at` (null = unverified):

| | Unverified | Verified |
|---|---|---|
| Sandbox run-seconds per UTC month | 7,200 | 60,000 |
| Concurrent sandbox runs | 1 | 5 |
| Platform LLM spend per UTC month | 200 | 2,000 |
| Fixture storage | 500 MB | 5 GB |
| Builds per day | 20 | 100 |

Config prefix `agentstore.sandbox.quota.{unverified|verified}.*`. [I5]

- **Concurrency and run-seconds** are checked in §3.1 under the org row lock, so two simultaneous triggers cannot both pass. Concurrent = the org's SANDBOX runs in a non-terminal status. Run-seconds = the sum of `coalesce(completed_at, now) - started_at` for the org's SANDBOX runs this month. `PENDING_BUILD` runs count toward concurrency and not toward run-seconds.
- **LLM spend** is enforced continuously in `GatewayLlmBackend`, for sandbox runs whose `llm_mode` is null (real) only. MOCK and REPLAY calls cost nothing, and `byok_direct` calls never reach the gateway. Before a call it sums `llm_call.cost` for the org's SANDBOX runs this month and rejects, with the gateway's existing budget error, when spend plus the estimated maximum cost of this call (`max_tokens` × output price) exceeds the limit. Limits are in the same currency unit as `llm_call.cost`.
- **Fixture storage:** checked on upload. Sum of `size_bytes` of the org's non-purged FIXTURE datasets plus the new file.
- **Builds per day:** checked where a bundle build is started, counting the org's builds since 00:00 UTC.
- A refused check returns `429 SANDBOX_QUOTA_EXCEEDED` with `{limit, used, max}` naming which limit was hit.

## 6. API and CLI

All endpoints require DEVELOPER, are owner-checked, and return 404 for another org's ids. `SecurityConfig` allows API keys on these paths, as the CLI already uses them for `push`.

**Fixtures**
- `POST /api/v1/developer/fixtures`, `GET /api/v1/developer/fixtures`, `DELETE /api/v1/developer/fixtures/{id}`. Same ingestion path as buyer files (format detection, normalization, size caps), with `purpose = FIXTURE`, owner = the developer org, and the storage quota. Buyer library queries already filter by purpose, so fixtures never appear there.

**Profiles**
- `POST/GET/PUT/DELETE /api/v1/agents/{id}/sandbox-profiles`, `GET …/{profileId}`.
- `PUT /api/v1/sandbox-profiles/{id}/bindings/{resource}` `{datasetId}`: binds a fixture to a READ resource. Same `RESOURCE_NOT_BINDABLE` rule and schema checking as buyers; the dataset must be a FIXTURE owned by the caller's org.
- Fake secrets are write-only. Reads return the names only.

**Runs**
- `POST /api/v1/sandbox/runs` `{agentVersionId, profileId | inlineProfile}`
- `GET /api/v1/sandbox/runs?agentId=`: `displayId, versionLabel, profileName, llmMode, replayOfRunId, status, simulatedCharge, requestedCharge, durationSeconds, aiCost`
- `GET /api/v1/sandbox/runs/{id}`: the run, its steps, and the same fields.
- `POST …/{id}/cancel`, `POST …/{id}/rerun`, `POST …/{id}/gates/{gateId}/decision`.

`result` and `issueCount` are absent until 6b.

**Quota**
- `GET /api/v1/developer/quota`: `{runSeconds, concurrentRuns, llmSpend, fixtureBytes, buildsToday}`, each `{used, limit, verifiedLimit}`.

**CLI (thin)**
- `agentstore sandbox run [--version v] [--profile name] [--follow]`. `--follow` polls run detail and prints new steps until the run is terminal, and prompts in the terminal for `MANUAL` gate decisions.
- `agentstore sandbox logs <runId>`.

## 7. Errors

RFC 7807-style codes, consistent with WS1–3.

| Code | Status | When |
|---|---|---|
| `SANDBOX_QUOTA_EXCEEDED` | 429 | Any quota in §5, with `{limit, used, max}` |
| `MISSING_BINDING` | 422 | A declared READ resource has no usable fixture |
| `REPLAY_SOURCE_INVALID` | 403 / 422 | Foreign, other-agent or non-sandbox source / no recorded calls |
| `VERSION_NOT_BUILT` | 409 | No image and no bundle to build from |
| `RESOURCE_NOT_BINDABLE` | 422 | Binding a fixture to a resource the manifest does not read |
| existing LLM and cap errors | as before | Budget, key and cap failures |

## 8. Testing

Testcontainers integration tests with `FakeSandboxProvider`, as in WS1–3.

- **No-ledger invariant.** A sandbox run with `usage` calls leaves `ledger_entry`, wallet balances, reservations and `usage_event` unchanged, while `simulated_charged` and `requested_charge` reflect the clipping.
- **Isolation.** A buyer or another developer gets 404 on a sandbox run. Sandbox runs are absent from buyer run lists and statements. A PRODUCTION run cannot read a fixture, and a sandbox run cannot read buyer data. The DB check constraint rejects a SANDBOX run with a connection and a PRODUCTION run without one.
- **Replay ownership.** 403 for another org's run, another agent's run, and a PRODUCTION run as the source. 422 for a source with no recordings.
- **Quotas.** The second concurrent run is rejected for an unverified org and allowed for a verified one. Run-seconds, fixture storage, builds per day and LLM-spend refusals are each covered. A concurrent-trigger race test shows only one passes.
- **Gates.** `AUTO_APPROVE`, `AUTO_REJECT`, a per-action override over the default, and a `MANUAL` decision by the owner but not by a foreign developer.
- **Behaviour.** A scope violation does not kill a SANDBOX run and still kills a PRODUCTION run. Cancel. Rerun with replay. Cap clipping.
- **Fake secrets.** Reads never return values.
- **Regression.** The whole existing suite passes with no test edits, which proves the nullable-connection guards.
- **CLI.** `sandbox run --follow` and `sandbox logs` against a mocked API (vitest), as for the other commands.

`/runtime/v1` does not change, so `contracts/runtime-v1.yaml` should not drift. `ONTOLOGY.md` and its staleness stamp are updated in the last task.

**Manual checks left for the user:** a live `agentstore sandbox run --follow` against a real runner, and terminal prompting for a `MANUAL` gate.

## 9. Inferred decisions for confirmation

| # | Decision | Where |
|---|---|---|
| I1 | Sandbox cost cap = `profile.spend_cap`, else the manifest's `maxPerRun`. | §2, §3.1 |
| I2 | Any version status, including DRAFT, may run; only the developer's own agent. | §3.1 |
| I3 | Replay source must be a SANDBOX run of the same agent by the same org. Cross-version replay is allowed on purpose. | §3.1 |
| I4 | An inline profile is stored as a profile row with `inline = true`, hidden from listings. | §3.1 |
| I5 | Quota numbers and config keys as listed; every org is "unverified" until WS7. | §5 |
| I6 | LLM quota currency equals `llm_call.cost`'s unit. | §5 |
| I7 | Month boundaries are UTC calendar months. | §5 |

## 10. Follow-ups (not built)

- 6b: behaviour report, callsites, network visibility, readiness, manifest patches.
- 6c: PII scan, trial attestation, REPO sync, CLI profile and fixture commands.
- WS4: local emulator. WS8: eval runs. WS7: verification. FE-3: UI.
- Bundle `mocks/` extraction; admin per-org quota override.
