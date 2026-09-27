# WS6b — Behaviour Report, Flow, Readiness, Manifest Patches — Design

> Narrows WS6's parent section in `2026-09-23-agent-store-developer-platform-and-sandbox-design.md` and absorbs a slice of the deferred WS1b (`run_step.block`, `GET /runs/{id}/flow`) that the behaviour report depends on. Backend and contract only, no UI (FE-3), no SDK.

## 1. Goal and scope

After a sandbox run (WS6a) finishes, a developer needs to see exactly what their agent actually did versus what its manifest declares, whether it's ready to submit, and a way to fix a DRAFT manifest without a rebuild.

**In scope**
- `run_step.block` and `GET /runs/{id}/flow` (any run the caller can already view) — the block/flow slice of WS1b, pulled in because the report needs it.
- `run_report`: declared-vs-actual, cost, LLM, gates, timing, logs pointer, result classification. Generated once per SANDBOX run on `RunFinishedEvent`.
- `GET /agents/{id}/versions/{vid}/readiness`.
- `POST /agents/{id}/versions/{vid}/manifest/patches`, DRAFT-only, with `agent_version.manifest_revision` and a diff against the current LIVE version.

**Out of scope (with owner)**
- Callsite header, SDK, in-sandbox logging proxy, `NETWORK_BLOCKED` step kind, `POST /runtime/v1/network-events`: deferred until the SDK exists (planned next after WS6b). Network section of the report/flow shows the declared allowlist only.
- Display IDs, `terminal_reason`, grouped run list, gate `estimated_units`/`preview`/`decided_via`/`eligible_approver_ids`: remain WS1b proper.
- PII scan, `trial_enabled`, REPO profile sync, CLI profile/fixture commands: 6c.
- Submit endpoint, eval runs, admin quota override, UI: elsewhere (readiness is informational only until a submit endpoint exists).

## 2. Data model (migration V32)

```sql
ALTER TABLE run_step ADD COLUMN block VARCHAR(40);
-- 'read:<resource>' | 'llm' | 'draft:<resource>' | 'gate:<action>' | 'write:<resource>' | 'charge' | NULL

CREATE TABLE run_report (
  run_id UUID PRIMARY KEY REFERENCES run(id),
  result VARCHAR(20) NOT NULL,        -- CLEAN, ISSUES, TIMED_OUT, OVER_COST_LIMIT, FAILED
  issue_count INT NOT NULL DEFAULT 0,
  report_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE agent_version ADD COLUMN manifest_revision INT NOT NULL DEFAULT 1;
```

`result`/`issue_count` are real columns, not buried in JSON, so readiness (and a future run list) can query without parsing JSON.

## 3. Block model

Existing recording call sites set `block` from data they already have:

| Step kind | Block |
|---|---|
| `DATA_READ` | `read:<resource>` |
| `DATA_WRITE` | `write:<resource>` |
| `WRITE_STAGED` | `draft:<resource>` |
| `GATE_REQUESTED` / `GATE_DECIDED` | `gate:<action>` |
| `LLM_CALL` | `llm` |
| `USAGE` | `charge` |
| `SCOPE_VIOLATION` | same shape as the attempted access (e.g. `read:<resource>`) |
| `AGENT_LOG`, `TIMEOUT`, `COST_CAP_REACHED`, `CANCELLED` | `null` |

Call sites to touch: `ScopeGuard` (DATA_READ/DATA_WRITE/SCOPE_VIOLATION), the gate controller (GATE_REQUESTED/GATE_DECIDED), the LLM gateway path (LLM_CALL), `RunLifecycleService.charge()` (USAGE).

## 4. `GET /runs/{id}/flow`

Same auth as the existing run-detail endpoint (not sandbox-only).

```jsonc
{ "blocks": [
    { "block": "read:bank_statements", "state": "DONE", "metrics": { "rows": 412 } },
    { "block": "llm", "state": "DONE", "metrics": { "calls": 3, "tokens": 18420 } },
    { "block": "gate:post_journal_entries", "state": "WAITING", "gateId": "…" },
    { "block": "write:journal_entries", "state": "IDLE" },
    { "block": "charge", "state": "IDLE", "metrics": { "units": 0, "amount": 0 } },
    { "block": "read:vendor_master", "state": "FAILED", "flag": "UNDECLARED" }
  ],
  "reachedBlock": "gate:post_journal_entries",
  "spend": { "charged": 0, "cap": 500 },
  "deadlineAt": "…" }
```

Declared blocks come from the manifest (every scope/resource/gate/llm/charge, starting `IDLE`). Actual blocks come from grouping `run_step` by `block`. State: `DONE` (has a terminal step), `WAITING` (open gate, no decision), `FAILED` (has `SCOPE_VIOLATION`/`COST_CAP_REACHED`), `SKIPPED` (run terminal, block never reached), else `IDLE`. Steps with a `block` not in the manifest are appended with `flag: "UNDECLARED"`. `blockedAttempts`/`held` are omitted (need the proxy/reservation machinery this slice doesn't touch).

## 5. Behaviour report (`run_report`)

**Trigger:** `@TransactionalEventListener` on `RunFinishedEvent`, filtered to `run.getMode() == SANDBOX` — same pattern as `OutputCommitService`. `RunReportService` loads `run_step`, `llm_call`, and the version's manifest, writes one `run_report` row.

**`report_json`:**
- **Declared vs actual** — same block aggregation as `/flow`, plus `UNDECLARED` extras and unused declared scopes flagged.
- **Network** — `{ declaredAllowlist: [...], note: "live visibility not yet available" }`.
- **Cost** — `{ simulatedCharged, requestedCharge, capUsed }` from `Run` (no new computation; `RunLifecycleService.charge()` already computes this).
- **LLM** — total calls/tokens/cost from `llm_call` for the run vs `manifest.llm.maxTokensPerRun`.
- **Gates** — each `GATE_REQUESTED`/`GATE_DECIDED` pair with payload and outcome.
- **Timing** — `startedAt`/`completedAt`/per-gate wait, from existing timestamps.
- **Logs** — pointer to existing agent-log/stdout retrieval.

**`result`** (precedence order):
1. Failed via `TIMEOUT` step → `TIMED_OUT`
2. `COST_CAP_REACHED` step present → `OVER_COST_LIMIT`
3. `run.status == FAILED` (other reason) → `FAILED`
4. Any `SCOPE_VIOLATION` step → `ISSUES`, `issue_count` = violation count
5. Else → `CLEAN`

## 6. Manifest patches

`POST /agents/{id}/versions/{vid}/manifest/patches` — role `DEVELOPER`, caller owns the agent, version must be `DRAFT` (409 `VERSION_NOT_EDITABLE` otherwise).

```jsonc
{ "op": "add_read_scope" | "add_resource" | "add_allowlist_host" | "remove_scope", ... }
```
- `add_read_scope { resource, description?, requiresApproval? }`
- `add_resource { name, format, schema? }`
- `add_allowlist_host { host }`
- `remove_scope { type, resource }`

Each patch re-validates the resulting manifest through the existing `ManifestParser` validation path, replaces `manifest_json`, increments `manifest_revision`, returns:
```jsonc
{ "manifestRevision": 4, "diff": { "addedScopes": [...], "removedScopes": [...], "addedHosts": [...] } }
```
`diff` compares against the agent's current LIVE version's manifest (empty if none).

## 7. Readiness

`GET /agents/{id}/versions/{vid}/readiness` → `{ ready, blockers: [{ code, message, runId }] }`:
- `buildStatus != SUCCEEDED` → `BUILD_NOT_READY`
- Latest SANDBOX run's `run_report` for this version: `ISSUES` → `UNDECLARED_ACCESS` per violation; `OVER_COST_LIMIT` → `COST_ENVELOPE_EXCEEDED`
- `ready = blockers.isEmpty()`
- `runId` is the UUID (display IDs arrive with WS1b proper)

Informational only — no submit endpoint exists yet.

## 8. API summary

- `GET /api/v1/runs/{id}/flow` (new)
- `GET /api/v1/agents/{id}/versions/{vid}/readiness` (new)
- `POST /api/v1/agents/{id}/versions/{vid}/manifest/patches` (new)
- `GET /api/v1/sandbox/runs/{id}` (6a) gains a `report` field
- No new CLI this slice; a `sandbox report` command is a likely small follow-up, not built preemptively.

## 9. Testing

- Block assignment: one test per call site.
- `/flow`: all-`IDLE` on a fresh run; correct states across reads/writes/gates; `UNDECLARED` extra block; `SKIPPED` after termination.
- `RunReportService`: one test per `result` path; SANDBOX-only generation.
- Manifest patches: each op's happy path; DRAFT-only enforcement; revision increments; diff with and without a LIVE version.
- Readiness: each blocker independently; clean → `ready: true`.
- OpenAPI drift test extended for the three new endpoints.
- No pre-existing test file edited.

## 10. Follow-ups (not built)

- Callsite + SDK + in-sandbox proxy + `NETWORK_BLOCKED` + `network-events` (next after this).
- WS1b proper: display IDs, `terminal_reason`, grouped run list, gate `estimated_units`/`preview`/`decided_via`/`eligible_approver_ids`.
- 6c: PII scan, trial attestation, REPO sync, CLI profile/fixture commands.
- Submit endpoint, `sandbox report` CLI, UI (FE-3).
