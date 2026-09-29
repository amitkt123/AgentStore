# WS1b: Buyer-facing run API

Follow-up to WS1 (Agent Runtime API v1). Parent spec: `2026-09-23-agent-store-developer-platform-and-sandbox-design.md`, section "UI alignment (screens 2, 5, 6, 7, 13, 14)" under WS1. WS1b delivers the backend that FE-2 (screens 5, 7, 8) needs to show a live run, a run list and why a run stopped.

## Goal and success

A buyer can see every run by a readable ID, see why it ended, filter and count runs by group, and, at an approval gate, see what the agent wants to do, what it will cost and who may decide. The platform, not the agent, computes the cost and the preview.

Success: FE-2 can build screens 5, 7 and 8 without further backend work; all backend tests stay green; the runtime OpenAPI contract, both SDKs and the CLI schema stay in step (drift checks pass).

## Already built, not in scope

- `GET /api/v1/runs/{id}/flow`, `run_step.block` and the flow computation (WS6b, `RunFlowService`).
- `gate_request.expires_at` and gate expiry by the reaper (WS1, migration V26).

## Out of scope

FE-2 screens (only frontend types change), the in-sandbox network proxy that posts network events, email and Slack decision links (WS14), the configurable approver list (WS15), a server-side idempotency key for `/usage` (see `docs/known-issues.md`).

## 1. Readable IDs

Migration **V38**:

```sql
CREATE SEQUENCE run_no_seq;  CREATE SEQUENCE sandbox_run_no_seq;  CREATE SEQUENCE review_no_seq;
ALTER TABLE run         ADD COLUMN display_id VARCHAR(20);
ALTER TABLE review_item ADD COLUMN display_id VARCHAR(20);
-- backfill in created_at order: 'R-'/'S-'/'RV-' || nextval; then SET NOT NULL and add UNIQUE
```

- `R-<n>` for production and trial runs, `S-<n>` for sandbox runs (`run.mode = SANDBOX`), `RV-<n>` for review items.
- A `BEFORE INSERT` trigger on `run` and `review_item` assigns the ID from the sequence, so every insert path (`RunController`, `SandboxRunService`, review-item creation, tests) keeps working. Hibernate reads it back after insert (`@Generated`).
- Sequences are global, so IDs reveal rough platform volume. The parent spec accepts this.
- **Lookup.** Every `/api/v1/runs/{id}/**` and sandbox run path takes a string and resolves it as a UUID first, then as a display ID. One `RunLookup.find(idOrDisplay)` is used by all controllers, so ownership checks stay where they are. An unknown ID is 404. A run owned by another org is still 403 on the buyer paths, as before WS1b (see `docs/known-issues.md`).
- Response DTOs (`RunResponse`, `RunSummaryResponse`, `RunDetailResponse`, sandbox run responses, review item responses) gain `displayId`. UUIDs keep working everywhere.

## 2. Why a run stopped

`run.terminal_reason VARCHAR(30)` (V38), backed by a `TerminalReason` enum: `FINISHED`, `GATE_REJECTED`, `GATE_EXPIRED`, `COST_CAP_REACHED`, `TIMED_OUT`, `SCOPE_VIOLATIONS`, `AGENT_FAILED`, `CANCELLED`, `SANDBOX_LOST`. `RunStatus` is unchanged: status says where, the reason says why.

It is set where a run ends. `RunLifecycleService.finish` and `terminate` take the reason as a parameter, so a call site cannot end a run without choosing one. Mapping, from the current code:

| Path | Reason |
| --- | --- |
| `complete` | `FINISHED` |
| agent `/fail`, launch or build failure | `AGENT_FAILED` |
| `rejectGate` (finishes as `CANCELLED`) | `GATE_REJECTED` |
| sandbox cancel (`SandboxRunService.cancel`) | `CANCELLED` |
| `GateService.expire` | `GATE_EXPIRED` |
| `RunReaper` timeout | `TIMED_OUT` |
| `RunReaper` runner lost | `SANDBOX_LOST` |
| `charge` cap reached | `COST_CAP_REACHED` |
| `recordViolation` at `MAX_PRODUCTION_VIOLATIONS` | `SCOPE_VIOLATIONS` |

`CANCELLED` is set by the sandbox cancel path (`SandboxRunService.cancel`); a buyer cancel endpoint does not exist yet. Existing terminal runs are backfilled where status and closing step make the reason unambiguous (COMPLETED to `FINISHED`; a `TIMEOUT` step on EXPIRED with a gate to `GATE_EXPIRED`; the `SCOPE_VIOLATIONS` and `COST_CAP_REACHED` closing steps) and left null otherwise. `terminalReason` is null while a run is not terminal.

## 3. Gate additions

Migration **V39** adds to `gate_request`: `estimated_units NUMERIC`, `preview JSONB`, `decided_via VARCHAR(20)`, `eligible_approver_ids JSONB`.

### estimated_units and cost if approved

`POST /runtime/v1/gates` accepts an optional `estimatedUnits` (positive, same `NUMERIC(14,4)` bound as `usage.quantity`). The contract `contracts/runtime-v1.yaml` changes, both SDK clients are regenerated (`sdk/python/regenerate.sh`, `sdk/typescript/regenerate.sh`, drift check) and both SDKs' `gates.request` gain `estimated_units` / `estimatedUnits`.

The platform computes `costIfApproved = min(estimatedUnits x unitPrice, costCap - totalCharged)` from the version's pricing, and returns it on the buyer-facing gate view and in the flow's gate block. It is null when the agent sent no estimate. The agent never supplies the money figure.

### preview

Built by the platform when the gate opens, from the outputs the run has staged for the gate's `affectsResources`. The agent cannot supply or alter it.

```json
{ "columns": ["date", "account", "amount"], "rows": [ {...}, ... ], "totalRows": 142, "total": 84520.50 }
```

- Rows: the first 20, cut to `preview.columns`. Only those 20 rows are read from the output store, never the whole staged file.
- `totalRows`: count of staged rows for the affected resources. `total`: sum of `preview.totalField` over all staged rows; absent when no `totalField` is declared.
- Manifest: each approval gate declaration gets an optional `preview {columns: [string], totalField: string}`. Absent means all columns and no total. Nothing staged means `preview` is null. The manifest JSON schema (`manifest-schema.json`), the CLI's bundled schema (`npm run sync-schema` in `cli/`, drift-tested) and the frontend `ApprovalGateDecl` type change together.
- The preview is a snapshot at gate-open time. Rows staged afterwards are not added.

### decided_via

Values `WEB`, `EMAIL_LINK`, `SLACK_LINK`. WS1b writes only `WEB` (the existing decision endpoints); WS14 adds the other two.

### eligible_approver_ids

Snapshot, at gate open, of the user IDs allowed to decide, stored on the gate so later role changes do not affect an open gate. The decision endpoints (`/approve`, `/reject`, `/gates/{gateId}/decision`) return 403 for a caller not on the list, in addition to the existing `BUYER_APPROVER` role check and org ownership check.

**Deviation from the earlier discussion.** We agreed to snapshot "org admins plus connection owner". The code does not support that: the decision endpoints require `BUYER_APPROVER` (a `BUYER_ADMIN` cannot approve today; there is no role hierarchy) and `Connection` has no creator field. So the snapshot is the buyer org's users holding `BUYER_APPROVER`, which is today's rule made explicit and enforced. Behaviour does not change; WS15 replaces the source with the configurable approver list without touching the column or the check.

Sandbox runs have no connection and no buyer org. Their gates keep today's decision rule; `eligible_approver_ids` is null and the list check is skipped.

## 4. Run list

`GET /api/v1/runs?group=needs_you|running|finished|stopped&agentId=&q=&from=&to=&page=&size=` (size default 25, max 100; `/api/v1/runs/mine` stays for compatibility).

| Group | Statuses |
| --- | --- |
| `needs_you` | `AWAITING_APPROVAL` |
| `running` | `PENDING`, `PENDING_BUILD`, `RUNNING` |
| `finished` | `COMPLETED` |
| `stopped` | `FAILED`, `CANCELLED`, `TIMED_OUT`, `EXPIRED` |

Row: `displayId`, `agent`, `version`, `status`, `terminalReason`, `reachedBlock`, `startedAt`, `completedAt`, `charged`, `held` (`reservedAmount`), `decidedBy{name}`. The response also carries `counts` per group for the filter chips; counts apply every filter except `group`.

- Access: `BUYER_ADMIN` and `BUYER_APPROVER`, the caller's org's connection runs only. Sandbox runs are excluded. A request with no `group` returns all groups.
- `q` is a Postgres `ILIKE` on display ID, agent name and `run.summary`. No trigram index for now.
- `from` / `to` filter on `started_at`.
- `decidedBy` is the user of the latest decided gate, or null.
- Two columns added in V38 keep the list cheap: `run.reached_block` (updated by `RunStepRecorder` whenever it records a step with a block; matches `FlowView.reachedBlock`) and `run.summary` (set at finish from the closing step's summary). Both are backfilled from existing steps.

## 5. Network events

`POST /runtime/v1/network-events`, body `{ "events": [ { "host": "...", "count": 3, "callsite": "..."? } ] }`, at most 100 events per call.

- Run token auth; no scope grant needed. Same rate-limit and body-size filters as `/logs`.
- Records a `NETWORK_BLOCKED` step (new `RunStepKind`) with `source = AGENT`, since the proxy runs inside the untrusted VM and the VM-level deny is the real control. The step has no block.
- `FlowView` gains `blockedAttempts` (sum of `count` over the run's `NETWORK_BLOCKED` steps). This field is new; the WS6b flow does not have it yet.
- The behaviour report's `network` section currently says `"live visibility not yet available"` (`RunReportService.network`). It now adds `blocked: [{host, count}]` aggregated from those steps.
- The runtime contract gains the endpoint; the generated SDK clients pick it up. No SDK method is added because the in-sandbox proxy is the caller, not agent code.

## 6. Clients

- **Frontend:** `frontend/src/lib/types.ts` gains `displayId`, `terminalReason`, gate `costIfApproved`, `preview`, `eligibleApproverIds`, `decidedVia`, and the manifest `preview` type. Types only; the screens are FE-2.
- **CLI:** run commands accept a display ID wherever they accept a run ID.

## 7. Testing

TDD with Testcontainers as elsewhere.

- **IDs:** prefixes and separate sequences; lookup by UUID and by display ID; unknown and foreign IDs give 404; backfill keeps creation order.
- **Stop reason:** one test per row in the mapping table; null while non-terminal; backfill cases.
- **Gates:** `costIfApproved` caps at remaining budget and is null without an estimate; preview honours `columns` and `totalField`, reads only 20 rows, is null with nothing staged, and cannot be influenced by the agent request; a caller off `eligible_approver_ids` gets 403; a role change after the gate opens does not alter the snapshot; sandbox gates skip the list check.
- **Run list:** each group and filter, `q`, pagination, `counts` semantics, cross-org isolation, sandbox runs excluded.
- **Network events:** step recorded with `AGENT` source, batch cap, unauthenticated and terminal-run cases, `blockedAttempts` sum, report section.
- **Contracts and clients:** runtime-v1 OpenAPI drift test, `scripts/check-sdk-drift.sh`, CLI schema drift test, frontend type-check, SDK unit tests for `estimated_units`.
- **End to end:** extend the scripted-agent test: agent requests a gate with `estimatedUnits`, buyer sees `costIfApproved` and `preview`, approves as an eligible user, run finishes with `terminalReason = FINISHED` and appears under `finished`.

## 8. Risks

- Changing run path variables from UUID to string touches every run controller and the CLI. A single `RunLookup` keeps the change mechanical.
- Preview reads staged output, which can be large. Only the first 20 rows are read; the `totalRows` and `total` aggregates must be computed without loading the file into memory.
- Backfill migrations run over existing runs; V38 must be tested against a database with rows in every status.
- Sequences are global (accepted above).

## 9. Follow-ups this spec creates

- WS14: `EMAIL_LINK` / `SLACK_LINK` decisions.
- WS15: configurable approver list replaces the snapshot source.
- A buyer cancel endpoint (would also use `CANCELLED`).
- The in-sandbox proxy that posts `network-events`.
