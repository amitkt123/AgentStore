# FE-2 — Buyer Agents, Files and the Live Flow Implementation Plan


**Goal:** Build the buyer screens that show an agent as a flow of blocks:
- the store's agent page, with its flow, track record and versions (including agreeing to an update ahead of time);
- the file library;
- the data sockets on "Plug in data and set limits";
- the live run drawn as a flow, with the approval preview;
- the runs history with how far each run got.

**Architecture:**
- **Flow model.** One pure function, `flowBlocks(manifest)`, turns a manifest into ordered blocks whose ids match the backend's run-step blocks (`read:<resource>`, `llm`, `draft:<resource>`, `gate:<action>`, `write:<resource>`, `charge`).
- **Flow canvas.** One `FlowCanvas` component lays those blocks out on a dotted canvas and joins them with SVG edges. The store listing (static), the live run (states from `/runs/{id}/flow`) and, in FE-3, the developer studio all use it.
- **Store pages.** They share a header with tabs (Flow, Track record, Versions).
- **Files.** Uploads go through a small Next route that forwards the multipart body to the backend, so the browser never holds a token.
- **Data layer.** It follows FE-1's patterns: server pages, client views, server actions that return `ActionResult`, and polling through `/api` proxies.

**Tech Stack:** Next.js 14.2, React 18, TypeScript, Tailwind 3.4 (FE-1 tokens), TanStack Query 5, Vitest + React Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §10 rows 3, 4 (data sockets), 5, 7, 8, 11 and 12. Also: the manifest v2 buyer-language fields (§4.3), WS1 UI alignment (flow, gates, run lists), WS2 UI alignment (file library), WS8 (scorecard), WS9 (trials), WS11 UI alignment (versions, pre-consent, track record) and WS16 (public disputes).

**Mockups:** `fe-mockups/latest/Karya — Agentic UI.html`, boards "Store: the agent as a flow", "Plug in data and set limits", "Live run with approval", "Runs history", "Files and where they go", "Agent track record" and "Agent versions and update consent".

**Prerequisites:** FE-1 merged. Backend WS1, WS2, WS8, WS9 and WS11 merged, on top of WS0 and WS13–WS16. Those five have no implementation plans yet, so this plan pins the contracts it needs in the next section, from the spec. Task 1 checks each one against the merged backend before any screen is built.

## Backend contracts this plan assumes

The endpoints are listed here, and their exact field shapes are in Task 1's `src/lib/types.ts` block. When a WS1/WS2/WS8/WS9/WS11 implementation plan is written, it must provide these shapes, or this section and Task 1's types must be updated in the same change.

| Endpoint | From | Returns |
|---|---|---|
| `GET /api/v1/runs/{id}` | WS1 | `RunDetailResponse` plus `displayId`, `agentId`, `agentName`, `versionNumber` |
| `GET /api/v1/runs/{id}/flow` | WS1 | `RunFlow` |
| `GET /api/v1/runs/{id}` → `manifest` (optional) | WS1 | the pinned version's manifest; when present, the live run draws blocks from it instead of the LIVE manifest |
| `GET /api/v1/runs/{id}/gates/{gateId}` | WS1 | `GateDetail` |
| `POST /api/v1/runs/{id}/gates/{gateId}/decision {decision}` | WS1 | `GateDetail` (403 `NOT_AN_APPROVER`, 409 closed) |
| `GET /api/v1/runs?group=&q=` | WS1 | `RunList` |
| `GET /api/v1/catalog/agents` | WS8/WS11 | `StoreAgentCard[]` |
| `GET /api/v1/catalog/agents/{agentId}` | WS8/WS9/WS11 | `StoreAgent` |
| `GET /api/v1/catalog/agents/{agentId}/track-record?days=30\|90` | WS11 | `TrackRecord` |
| `GET /api/v1/catalog/agents/{agentId}/disputes?days=90` | WS16 (planned) | `PublicDisputes` |
| `GET /api/v1/publishers/{orgId}` | WS7 | `Publisher` |
| `POST /api/v1/catalog/agents/{agentId}/trials` | WS9 | `{ runId }` |
| `GET /api/v1/agents/{agentId}/versions/{versionId}/resources` | WS2 | `ResourceInfo[]` |
| `GET /api/v1/connections/{id}/versions` | WS11 | `ConnectionVersions` |
| `POST /api/v1/connections/{id}/upgrades {targetVersionId, bindings}` | WS11 | `{ id }` |
| `DELETE /api/v1/connections/{id}/upgrades/{upgradeId}` | WS11 | 204 |
| `POST /api/v1/connections/{id}/upgrades/{upgradeId}/decline` | WS11 | 204 |
| `POST /api/v1/connections/{id}/upgrades {targetVersionId, bindings: {}, decline: true}` | WS11 | `{ id }`: an upgrade created already `DECLINED` ("Stay on 1.3.0" before any agreement) |
| `PATCH /api/v1/connections/{id} {autoApplySafeUpdates}` | WS11 | `ConnectionResponse` |
| `GET /api/v1/datasets?include=usage` | WS2 | `Dataset[]` (each pending request carries `agentId`) |
| `POST /api/v1/datasets` (multipart `file`, `displayName`, `sourceLabel`) | WS2 | `Dataset` |
| `DELETE /api/v1/datasets/{id}` | WS2 | 204 |
| `POST /api/v1/datasets/{id}/check {agentVersionId, resource}` | WS2 | `CheckResult` |
| `PUT` / `DELETE /api/v1/connections/{id}/bindings/{resource}` | WS2 | 204 (422 with `CheckResult` body on failing rows) |
| `GET /api/v1/orgs/{orgId}/storage` | WS2 | `{ usedBytes, limitBytes }` |
| `POST /api/v1/connections` | WS15 + WS2 | also accepts `bindings: Record<resource, datasetId>` |

## Global Constraints

- Everything in FE-1's Global Constraints applies: tokens, `inr()`, India time, `ActionResult` and `actionError`, `clientFetch`, accessibility, no new dependencies, `npx tsc --noEmit && npm test`, and no AI attribution in commits.
- Block ids are exactly the backend's: `read:<resource>`, `llm`, `draft:<resource>`, `gate:<action>`, `write:<resource>`, `charge`. Block order is fixed and derived, never declared: reads → llm → drafts → gates → writes → charge.
- Block states are exactly `IDLE`, `RUNNING`, `DONE`, `WAITING`, `FAILED`, `SKIPPED`. The API returns numbers and the frontend writes the words (spec WS1).
- The flow canvas uses fixed geometry: blocks are 200×84 px with a 40 px horizontal and 56 px vertical gap. Edges are cubic curves from the bottom centre of one block to the top centre of the next. Animation is off under `prefers-reduced-motion` (FE-1 global rule).
- Readable run ids (`displayId`, such as `R-2291`) replace `shortId` everywhere a run is named once WS1 provides them.
- File uploads: 50 MB per file in the UI. The backend enforces its own limit and quota, and a 413 or quota error is shown as a sentence.
- Nothing in these screens shows a star rating. Trust numbers are measured values, with "Not enough runs yet" below the backend's minimum sample.
- **Not in FE-2:** "Replay run" on the live run (a later polish), the "Ask Karya" questions, and anything for developers or admins (FE-3, FE-4).

## Review Focus

1. **An agent with no LLM, no gate or no writes** (a read-only checker) must draw a short, correct flow with no empty rows or dangling edges. [Task 2]
2. **A live run that fails before reaching the gate** must show later blocks as "Not reached", not as waiting. A rejected gate must show the write and charge blocks as "Skipped". [Task 7]
3. **A file whose columns don't match** must stay unplugged, and the buyer sees which columns are missing before anything is saved. [Task 6]
4. **Agreeing to a version still in review, then taking it back** must leave the buyer on the old version, with the "Agree" button available again. [Task 5]
5. **A track record with too few runs** must say "Not enough runs yet", not show 100% success from 2 runs. [Task 4]

---

## File Structure

```
frontend/src/
  lib/types.ts                      (modify: WS1/WS2/WS8/WS9/WS11 contracts)
  lib/flow.ts                       (create: flowBlocks, flowEdges, blockMetricText)
  lib/runSteps.ts                   (modify: WS1 step kinds)
  lib/bytes.ts                      (create: formatBytes)
  components/flow/FlowCanvas.tsx    (create)
  components/flow/layout.ts         (create: positions and edge paths)
  components/shell/nav.ts           (modify: Agent store → /store, Files)
  app/api/runs/[runId]/flow/route.ts, app/api/datasets/upload/route.ts         (create)
  app/(buyer)/store/page.tsx, StoreIndex.tsx                                     (create)
  app/(buyer)/store/[agentId]/layout.tsx, StoreHeader.tsx, page.tsx,
      AgentFlowView.tsx, actions.ts                                              (create/replace)
  app/(buyer)/store/[agentId]/track-record/page.tsx, TrackRecordView.tsx         (create)
  app/(buyer)/store/[agentId]/versions/page.tsx, VersionsView.tsx, actions.ts    (create)
  app/(buyer)/files/page.tsx, FilesView.tsx, actions.ts                          (create)
  app/(buyer)/connect/[agentId]/[versionId]/DataSockets.tsx                      (create)
  app/(buyer)/connect/[agentId]/[versionId]/ConnectSetup.tsx, page.tsx, actions.ts (modify)
  app/(buyer)/runs/[runId]/LiveRun.tsx, GateCard.tsx, actions.ts, page.tsx       (create/modify)
  app/(buyer)/runs/page.tsx, RunsHistory.tsx, TodayTimeline.tsx                  (modify/create)
  app/(buyer)/catalog/, app/(buyer)/agents/                                      (replace with redirects)
e2e/core-loop.spec.ts                                                           (modify)
```

All commands run from `frontend/`.

---

### Task 1: Confirm the contracts and add their types

**Files:**
- Modify: `src/lib/types.ts`, `src/lib/runSteps.ts`, `src/lib/runSteps.test.ts`
- Create: `src/lib/bytes.ts`, `src/lib/bytes.test.ts`

**Interfaces:**
- Produces:
  - Types: `BlockState`, `RunFlow`, `GateDetail`, `TerminalReason`, `RunListItem`, `RunList`, `ResourceDecl`, `ScopeV2`, `GateDeclV2`, `ManifestV2`, `StoreAgentCard`, `StoreAgent`, `Scorecard`, `TrackRecord`, `Publisher`, `PublicDisputes`, `BuyerVersion`, `VersionDiffRow`, `ConnectionVersions`, `ResourceInfo`, `Dataset`, `CheckResult`, `StorageUse`.
  - `RunDetailResponse` gains `displayId`, `agentId`, `agentName`, `versionNumber`.
  - `stepSentence` understands the WS1 step kinds. `formatBytes(n)`.

- [ ] **Step 1: Check each contract against the running backend**

With the backend running and the E2E seed loaded, sign in as `e2e-buyer@example.com` and request each endpoint in the "Backend contracts" table. Use the browser devtools, or `curl -H "Authorization: Bearer <token>"` against `http://localhost:8080`. Compare each response with the types in Step 3. If a field name or shape differs, change the type in Step 3, and the same line in this plan's contracts table, to match the backend. Do not add adapters. Record any differences in the commit message.

- [ ] **Step 2: Write the failing tests**

`src/lib/bytes.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { formatBytes } from "./bytes";

describe("formatBytes", () => {
  it("uses the largest sensible unit", () => {
    expect(formatBytes(48 * 1024)).toBe("48 KB");
    expect(formatBytes(1.1 * 1024 * 1024)).toBe("1.1 MB");
    expect(formatBytes(5 * 1024 ** 3)).toBe("5 GB");
    expect(formatBytes(512)).toBe("512 B");
  });
});
```

Append to `src/lib/runSteps.test.ts`:

```ts
describe("WS1 step kinds", () => {
  it("describes platform-recorded steps", () => {
    expect(stepSentence(step("DATA_READ", { resource: "bank_statements", rows: 412 }))).toBe("Read bank_statements, 412 rows");
    expect(stepSentence(step("WRITE_STAGED", { resource: "journal_entries", records: 142 }))).toBe("Drafted 142 journal_entries. Nothing written yet.");
    expect(stepSentence(step("GATE_REQUESTED", { summary: "Post 142 entries" }))).toBe("Asked for approval: Post 142 entries");
    expect(stepSentence(step("GATE_DECIDED", { decision: "APPROVED", by: "Priya Shah" }))).toBe("Priya Shah approved");
    expect(stepSentence(step("LLM_CALL", { model: "claude-sonnet-5", tokens: 18420 }))).toBe("Asked claude-sonnet-5, 18,420 tokens");
    expect(stepSentence(step("USAGE", { units: 142, amount: 284 }))).toBe("Charged ₹284 for 142 results");
    expect(stepSentence(step("SCOPE_VIOLATION", { resource: "vendor_master" }))).toBe("Tried to read vendor_master. Refused.");
    expect(stepSentence(step("NETWORK_BLOCKED", { host: "hooks.slack.com", count: 2 }))).toBe("Tried to reach hooks.slack.com 2 times. Blocked.");
    expect(stepSentence(step("AGENT_LOG", { message: "matched 142" }))).toBe("Agent says: matched 142");
  });
});
```

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `npm test -- src/lib/bytes.test.ts src/lib/runSteps.test.ts`
Expected: FAIL.

- [ ] **Step 4: Types**

Append to `src/lib/types.ts`:

```ts
// ---------- WS1: runs as flows ----------
export type BlockState = "IDLE" | "RUNNING" | "DONE" | "WAITING" | "FAILED" | "SKIPPED";
export interface RunFlow {
  blocks: { block: string; state: BlockState; metrics?: Record<string, number>; gateId?: string; expiresAt?: string }[];
  reachedBlock: string | null;
  spend: { charged: number; cap: number; held: number };
  deadlineAt: string | null;
  blockedAttempts: number;
}
export interface GateDetail {
  id: string;
  action: string;
  summary: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
  expiresAt: string;
  estimatedCharge: number | null;
  preview: { columns: string[]; rows: Record<string, unknown>[]; totalRows: number; total: number | null } | null;
}
export type TerminalReason =
  | "FINISHED" | "GATE_REJECTED" | "GATE_EXPIRED" | "COST_CAP_REACHED" | "TIMED_OUT"
  | "SCOPE_VIOLATIONS" | "AGENT_FAILED" | "CANCELLED" | "SANDBOX_LOST";
export interface RunListItem {
  id: string;
  displayId: string;
  agentName: string;
  version: string;
  status: RunStatus;
  terminalReason: TerminalReason | null;
  reachedBlock: string | null;
  blocks: string[];                  // the agent's block ids in order, for the mini flow
  startedAt: string | null;
  completedAt: string | null;
  charged: number;
  held: number;
  decidedBy: { name: string } | null;
}
export interface RunList {
  items: RunListItem[];
  counts: Record<"all" | "needs_you" | "running" | "finished" | "stopped", number>;
}

// ---------- Manifest v2 (buyer-language fields) ----------
export interface ResourceDecl {
  name: string;
  format: string;
  displayName?: string;
  description?: string;
  kind?: "tabular" | "document";
  preview?: { columns: string[]; totalField?: string };
}
export interface ScopeV2 extends Scope {
  requiresApproval?: boolean;
}
export interface GateDeclV2 extends ApprovalGateDecl {
  title?: string;
  timeoutMinutes?: number;
}
export interface ManifestV2 {
  name: string;
  version: string;
  summary?: string;
  releaseNotes?: string;
  resources: ResourceDecl[];
  scopes: ScopeV2[];
  approvalGates: GateDeclV2[];
  llm?: { mode: string; models: string[]; maxTokensPerRun: number; purpose?: string } | null;
  networkAllowlist: string[];
  costEnvelope: CostEnvelope & { unitDisplay?: string };
  dataClasses: string[];
}

// ---------- WS8 / WS9 / WS11: the store ----------
export interface StoreAgentCard {
  agentId: string;
  name: string;
  category: string;
  summary: string | null;
  publisherName: string;
  verifiedPublisher: boolean;
  typicalCost: number | null;
  casesPassed: number | null;
  casesTotal: number | null;
}
export interface StoreAgent {
  agentId: string;
  name: string;
  category: string;
  summary: string | null;
  publisher: { orgId: string; displayName: string; verified: boolean };
  liveVersion: { id: string; versionNumber: string; manifest: ManifestV2 };
  llmModels: { id: string; displayName: string; providerRetainsData: boolean }[];
  trialAvailable: boolean;
  myConnection: { id: string; versionNumber: string } | null;
  newVersionsForMe: number;
}
export interface Scorecard {
  version: string;
  checkedAt: string;
  casesPassed: number;
  casesTotal: number;
  probesBlocked: number;
  probesTotal: number;
  costP50: number;
  costP95: number;
  highlights: { tone: "ok" | "miss"; text: string }[];
}
export interface TrackRecord {
  enoughData: boolean;
  runs: number;
  finishedWithoutError: number;
  draftsApproved: number | null;
  disputed: number;
  medianDurationSeconds: number;
  workspaces: number;
  cost: { p50: number; p95: number; cap: number; buckets: { lt?: number; stoppedAtCap?: boolean; share: number }[] };
  review: Scorecard | null;
}
export interface Publisher {
  displayName: string;
  legalName: string;
  city: string | null;
  country: string;
  verifiedAt: string | null;
  agentsListed: number;
  supportEmail: string | null;
  medianReplyHours: number | null;
}
export interface PublicDisputes {
  openedInWindow: number;
  decided: { decidedAt: string; publicSummary: string; outcome: "REFUNDED" | "NOT_REFUNDED"; amountRefunded: number | null }[];
}
export type BuyerVersionState = "IN_REVIEW" | "AVAILABLE" | "YOURS" | "APPLIED_FOR_YOU" | "PAST";
export interface VersionDiffRow {
  kind: "READ" | "WRITE" | "GATE" | "HOST" | "PRICE" | "DATA_CLASS";
  text: string;
  tag: "NEW" | "SAME" | "REMOVED" | "SAFER";
}
export interface BuyerVersion {
  versionId: string;
  version: string;
  buyerState: BuyerVersionState;
  date: string;
  summary: string | null;
  releaseNotes: string | null;
  diff: VersionDiffRow[];
  scorecard: Scorecard | null;
  newResources: ResourceInfo[];
}
export interface ConnectionVersions {
  versions: BuyerVersion[];
  autoApplySafeUpdates: boolean;
  pendingUpgrade: { id: string; targetVersionId: string; status: "WAITING_FOR_APPROVAL" | "APPLIED" | "WITHDRAWN" | "DECLINED" | "VOID" } | null;
}

// ---------- WS2: files ----------
export interface ResourceInfo {
  name: string;
  displayName: string | null;
  description: string | null;
  format: string;
  kind: "tabular" | "document";
  requiredColumns: string[];
}
export interface Dataset {
  id: string;
  displayName: string;
  sourceLabel: string | null;
  format: string;
  rowCount: number | null;
  sizeBytes: number;
  createdAt: string;
  uploadedBy: string | null;
  purgeAfter: string | null;
  bindings: { connectionId: string; agentName: string; version: string; resource: string; access: "READ" }[];
  pendingRequests: { connectionId: string; agentId: string; agentName: string; version: string; resource: string; reason: string | null; upgradeId: string | null }[];
}
export interface CheckResult {
  rowCount: number | null;
  columnsMatch: boolean;
  missingColumns: string[];
  rowErrors: { row: number; column: string; message: string }[];
}
export interface StorageUse {
  usedBytes: number;
  limitBytes: number;
}
```

Also change the existing `RunStepKind` to include the WS1 kinds, and extend `RunDetailResponse`:

```ts
export type RunStepKind =
  | "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED" | "TIMEOUT" | "COST_CAP_REACHED"
  | "DATA_READ" | "DATA_WRITE" | "WRITE_STAGED" | "GATE_REQUESTED" | "GATE_DECIDED" | "LLM_CALL" | "USAGE"
  | "SCOPE_VIOLATION" | "NETWORK_BLOCKED" | "AGENT_LOG";

export interface RunDetailResponse {
  id: string;
  displayId?: string;
  agentId?: string;
  agentName?: string;
  versionNumber?: string;
  status: RunStatus;
  costCap: number;
  totalCharged: number;
  steps: RunStepResponse[];
}
```

- [ ] **Step 5: Helpers**

`src/lib/bytes.ts`:

```ts
export function formatBytes(bytes: number): string {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i++;
  }
  const rounded = value >= 10 || i === 0 ? Math.round(value) : Math.round(value * 10) / 10;
  return `${rounded} ${units[i]}`;
}
```

In `src/lib/runSteps.ts`, add these cases to the `switch` in `stepSentence`, before `default`. Add `import { inr } from "./format";` at the top.

```ts
    case "DATA_READ":
      return d.resource ? `Read ${String(d.resource)}${rows}` : "Read data";
    case "DATA_WRITE":
      return d.resource ? `Wrote ${String(d.resource)}${rows}` : "Wrote data";
    case "WRITE_STAGED":
      return `Drafted ${String(d.records ?? "")} ${String(d.resource ?? "records")}. Nothing written yet.`.replace("  ", " ");
    case "GATE_REQUESTED":
      return summary ? `Asked for approval: ${summary}` : "Asked for approval";
    case "GATE_DECIDED":
      return `${String(d.by ?? "Someone")} ${d.decision === "APPROVED" ? "approved" : "rejected"}`;
    case "LLM_CALL":
      return `Asked ${String(d.model ?? "the AI")}${typeof d.tokens === "number" ? `, ${d.tokens.toLocaleString("en-IN")} tokens` : ""}`;
    case "USAGE":
      return `Charged ${inr(Number(d.amount ?? 0))} for ${String(d.units ?? "?")} results`;
    case "SCOPE_VIOLATION":
      return `Tried to read ${String(d.resource ?? "something it didn't declare")}. Refused.`;
    case "NETWORK_BLOCKED":
      return `Tried to reach ${String(d.host ?? "a website")}${typeof d.count === "number" && d.count > 1 ? ` ${d.count} times` : ""}. Blocked.`;
    case "AGENT_LOG":
      return `Agent says: ${String(d.message ?? "")}`;
```

In `lastGateSummary`, treat `GATE_REQUESTED` like `GATE`: change `if (steps[i].kind === "GATE")` to `if (steps[i].kind === "GATE" || steps[i].kind === "GATE_REQUESTED")`.

- [ ] **Step 6: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/lib
git commit -m "feat(frontend): types for flows, gates, store, versions and files; WS1 step sentences"
```

---

### Task 2: Flow model and the flow canvas

**Files:**
- Create: `src/lib/flow.ts`, `src/components/flow/layout.ts`, `src/components/flow/FlowCanvas.tsx`
- Test: create `src/lib/flow.test.ts`, `src/components/flow/FlowCanvas.test.tsx`

**Interfaces:**
- Consumes: `ManifestV2`, `RunFlow`, `BlockState` (Task 1); `inr`; `Icon`.
- Produces:
  - `type BlockKind = "read" | "llm" | "draft" | "gate" | "write" | "charge"`.
  - `interface FlowBlock { id: string; kind: BlockKind; label: string; title: string; subtitle: string; level: number }`.
  - `flowBlocks(manifest, modelNames?: Record<string, string>): FlowBlock[]`, `flowEdges(blocks): [string, string][]`.
  - `blockMetricText(block: FlowBlock, state: BlockState, metrics?: Record<string, number>): string`.
  - `layoutFlow(blocks): { positions: Record<string, { x: number; y: number }>; width: number; height: number }`, `edgePath(from, to): string`.
  - `FlowCanvas({ blocks, states?, metrics?, selected?, onSelect?, extraBlocks?, label })`. `extraBlocks` is used by FE-3 for undeclared attempts: `{ id, title, subtitle, tone: "bad" | "fixed"; after: string }[]`.

- [ ] **Step 1: Write the failing tests**

`src/lib/flow.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { blockMetricText, flowBlocks, flowEdges } from "./flow";
import type { ManifestV2 } from "./types";

const recon: ManifestV2 = {
  name: "Bank Reconciliation", version: "1.3.0",
  resources: [
    { name: "bank_statements", format: "csv", displayName: "Bank statements", description: "Your statement lines, CSV" },
    { name: "invoices", format: "csv", displayName: "Open invoices", description: "Unpaid invoices, CSV" },
    { name: "journal_entries", format: "json", displayName: "Journal entries" },
  ],
  scopes: [
    { type: "READ", resource: "bank_statements", description: "" },
    { type: "READ", resource: "invoices", description: "" },
    { type: "WRITE", resource: "journal_entries", description: "", requiresApproval: true },
  ],
  approvalGates: [{ trigger: "post_journal_entries", description: "Buyer approves", title: "Post to your ledger", timeoutMinutes: 30 }],
  llm: { mode: "platform", models: ["claude-sonnet-5"], maxTokensPerRun: 400000, purpose: "Match lines to invoices" },
  networkAllowlist: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "OUTCOME", unitPrice: 2, unitLabel: "invoice_matched", unitDisplay: "match" },
  dataClasses: ["financial"],
};

describe("flowBlocks", () => {
  it("orders the blocks reads, thinks, drafts, asks, writes, charges, with backend ids", () => {
    const blocks = flowBlocks(recon, { "claude-sonnet-5": "Claude Sonnet" });
    expect(blocks.map((b) => [b.id, b.label, b.level])).toEqual([
      ["read:bank_statements", "Reads", 0],
      ["read:invoices", "Reads", 0],
      ["llm", "Thinks", 1],
      ["draft:journal_entries", "Drafts", 2],
      ["gate:post_journal_entries", "Asks you", 3],
      ["write:journal_entries", "Writes", 4],
      ["charge", "Charges", 5],
    ]);
    expect(blocks.find((b) => b.id === "llm")).toMatchObject({ title: "Match lines to invoices", subtitle: "Claude Sonnet via Karya" });
    expect(blocks.find((b) => b.id === "write:journal_entries")).toMatchObject({ title: "Post to your ledger", subtitle: "Only approved entries" });
    expect(blocks.find((b) => b.id === "charge")).toMatchObject({ title: "₹2 per match", subtitle: "Never more than ₹500 a run" });
  });

  it("draws a read-only checker as reads then charges, with no empty levels", () => {
    const checker: ManifestV2 = { ...recon, scopes: [recon.scopes[0]], approvalGates: [], llm: null };
    const blocks = flowBlocks(checker);
    expect(blocks.map((b) => [b.id, b.level])).toEqual([["read:bank_statements", 0], ["charge", 1]]);
    expect(flowEdges(blocks)).toEqual([["read:bank_statements", "charge"]]);
  });

  it("joins every block to every block on the next level", () => {
    const edges = flowEdges(flowBlocks(recon));
    expect(edges).toContainEqual(["read:bank_statements", "llm"]);
    expect(edges).toContainEqual(["read:invoices", "llm"]);
    expect(edges).toContainEqual(["gate:post_journal_entries", "write:journal_entries"]);
    expect(edges).toHaveLength(6);
  });

  it("writes without a gate are direct writes", () => {
    const ungated: ManifestV2 = { ...recon, approvalGates: [], scopes: recon.scopes.map((s) => ({ ...s, requiresApproval: false })) };
    const ids = flowBlocks(ungated).map((b) => b.id);
    expect(ids).not.toContain("draft:journal_entries");
    expect(flowBlocks(ungated).find((b) => b.id === "write:journal_entries")?.subtitle).toBe("Written directly");
  });
});

describe("blockMetricText", () => {
  const blocks = flowBlocks(recon);
  const byId = (id: string) => blocks.find((b) => b.id === id)!;
  it("turns numbers into words", () => {
    expect(blockMetricText(byId("read:bank_statements"), "DONE", { rows: 412 })).toBe("412 lines read");
    expect(blockMetricText(byId("llm"), "DONE", { calls: 3, tokens: 18420 })).toBe("3 calls, 18,420 tokens");
    expect(blockMetricText(byId("draft:journal_entries"), "DONE", { records: 142 })).toBe("142 drafted");
    expect(blockMetricText(byId("gate:post_journal_entries"), "WAITING")).toBe("Waiting for you");
    expect(blockMetricText(byId("gate:post_journal_entries"), "FAILED")).toBe("Rejected");
    expect(blockMetricText(byId("write:journal_entries"), "SKIPPED")).toBe("Skipped");
    expect(blockMetricText(byId("charge"), "DONE", { amount: 284 })).toBe("₹284 charged");
    expect(blockMetricText(byId("llm"), "RUNNING")).toBe("Working…");
    expect(blockMetricText(byId("llm"), "IDLE")).toBe("Not reached");
  });
});
```

`src/components/flow/FlowCanvas.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { FlowCanvas } from "./FlowCanvas";
import { layoutFlow, edgePath } from "./layout";
import type { FlowBlock } from "@/lib/flow";

const blocks: FlowBlock[] = [
  { id: "read:a", kind: "read", label: "Reads", title: "A", subtitle: "a", level: 0 },
  { id: "read:b", kind: "read", label: "Reads", title: "B", subtitle: "b", level: 0 },
  { id: "gate:g", kind: "gate", label: "Asks you", title: "Your approval", subtitle: "g", level: 1 },
  { id: "charge", kind: "charge", label: "Charges", title: "₹2 per match", subtitle: "c", level: 2 },
];

describe("layoutFlow", () => {
  it("centres each level and stacks levels", () => {
    const { positions, width, height } = layoutFlow(blocks);
    expect(width).toBe(440);                    // two blocks and one gap
    expect(positions["read:a"]).toEqual({ x: 0, y: 0 });
    expect(positions["read:b"]).toEqual({ x: 240, y: 0 });
    expect(positions["gate:g"]).toEqual({ x: 120, y: 140 });
    expect(height).toBe(3 * 84 + 2 * 56);
  });
  it("draws edges from bottom centre to top centre", () => {
    expect(edgePath({ x: 0, y: 0 }, { x: 120, y: 140 })).toBe("M100 84 C100 112, 220 112, 220 140");
  });
});

describe("FlowCanvas", () => {
  it("names each block with its role, title and state for screen readers", () => {
    render(<FlowCanvas label="How it works" blocks={blocks} states={{ "gate:g": "WAITING" }} metrics={{ "gate:g": "Waiting for you" }} />);
    expect(screen.getByRole("group", { name: "How it works" })).toBeInTheDocument();
    expect(screen.getByText("Waiting for you")).toBeInTheDocument();
    expect(screen.getByTestId("block-gate:g")).toHaveAttribute("data-state", "WAITING");
  });

  it("lets the reader select a block when selection is enabled", async () => {
    const onSelect = vi.fn();
    render(<FlowCanvas label="Flow" blocks={blocks} selected="charge" onSelect={onSelect} />);
    expect(screen.getByRole("button", { name: /Charges: ₹2 per match/ })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: /Asks you: Your approval/ }));
    expect(onSelect).toHaveBeenCalledWith("gate:g");
  });

  it("draws extra flagged blocks next to the one they follow", () => {
    render(
      <FlowCanvas
        label="Actual"
        blocks={blocks}
        extraBlocks={[{ id: "x:vendor_master", title: "vendor_master", subtitle: "1 request, refused", tone: "bad", after: "read:b" }]}
      />,
    );
    expect(screen.getByTestId("block-x:vendor_master")).toHaveAttribute("data-tone", "bad");
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/flow.test.ts src/components/flow`
Expected: FAIL.

- [ ] **Step 3: Flow model**

`src/lib/flow.ts`:

```ts
import { inr } from "./format";
import type { BlockState, ManifestV2 } from "./types";

export type BlockKind = "read" | "llm" | "draft" | "gate" | "write" | "charge";

export interface FlowBlock {
  id: string;
  kind: BlockKind;
  label: string;
  title: string;
  subtitle: string;
  level: number;
}

const LABEL: Record<BlockKind, string> = {
  read: "Reads", llm: "Thinks", draft: "Drafts", gate: "Asks you", write: "Writes", charge: "Charges",
};

export function flowBlocks(m: ManifestV2, modelNames: Record<string, string> = {}): FlowBlock[] {
  const resource = (name: string) => m.resources.find((r) => r.name === name);
  const show = (name: string) => resource(name)?.displayName ?? name;
  const reads = m.scopes.filter((s) => s.type === "READ");
  const writes = m.scopes.filter((s) => s.type === "WRITE");
  const gated = m.approvalGates.length > 0;
  const staged = writes.filter((w) => w.requiresApproval ?? gated);
  const gateTitle = m.approvalGates.find((g) => g.title)?.title;

  const rows: Omit<FlowBlock, "level">[][] = [
    reads.map((s) => ({
      id: `read:${s.resource}`, kind: "read", label: LABEL.read, title: show(s.resource),
      subtitle: resource(s.resource)?.description ?? s.resource.replace(/_/g, " "),
    })),
    m.llm
      ? [{
          id: "llm", kind: "llm", label: LABEL.llm, title: m.llm.purpose ?? "Works out the answer",
          subtitle: `${modelNames[m.llm.models[0]] ?? m.llm.models[0] ?? "AI"} via Karya`,
        }]
      : [],
    staged.map((s) => ({ id: `draft:${s.resource}`, kind: "draft", label: LABEL.draft, title: show(s.resource), subtitle: "Held as a draft" })),
    m.approvalGates.map((g) => ({ id: `gate:${g.trigger}`, kind: "gate", label: LABEL.gate, title: "Your approval", subtitle: "Before anything is written" })),
    writes.map((s) => {
      const isStaged = staged.includes(s);
      return {
        id: `write:${s.resource}`, kind: "write", label: LABEL.write,
        title: isStaged && gateTitle ? gateTitle : `Write ${show(s.resource)}`,
        subtitle: isStaged ? "Only approved entries" : "Written directly",
      };
    }),
    [{
      id: "charge", kind: "charge", label: LABEL.charge,
      title: `${inr(m.costEnvelope.unitPrice)} per ${m.costEnvelope.unitDisplay ?? m.costEnvelope.unitLabel.replace(/_/g, " ")}`,
      subtitle: `Never more than ${inr(m.costEnvelope.maxPerRun)} a run`,
    }],
  ] as Omit<FlowBlock, "level">[][];

  return rows.filter((r) => r.length > 0).flatMap((row, level) => row.map((b) => ({ ...b, level })));
}

export function flowEdges(blocks: FlowBlock[]): [string, string][] {
  const levels = [...new Set(blocks.map((b) => b.level))].sort((a, b) => a - b);
  const edges: [string, string][] = [];
  for (let i = 0; i < levels.length - 1; i++) {
    const from = blocks.filter((b) => b.level === levels[i]);
    const to = blocks.filter((b) => b.level === levels[i + 1]);
    for (const f of from) for (const t of to) edges.push([f.id, t.id]);
  }
  return edges;
}

const n = (v: number | undefined) => (v ?? 0).toLocaleString("en-IN");

export function blockMetricText(block: FlowBlock, state: BlockState, metrics: Record<string, number> = {}): string {
  if (state === "IDLE") return "Not reached";
  if (state === "SKIPPED") return "Skipped";
  if (state === "RUNNING") return "Working…";
  if (block.kind === "gate") {
    return state === "WAITING" ? "Waiting for you" : state === "DONE" ? "Approved" : "Rejected";
  }
  if (state === "FAILED") return "Stopped here";
  switch (block.kind) {
    case "read":
      return `${n(metrics.rows)} lines read`;
    case "llm":
      return `${n(metrics.calls)} calls, ${n(metrics.tokens)} tokens`;
    case "draft":
      return `${n(metrics.records)} drafted`;
    case "write":
      return `${n(metrics.records)} written`;
    case "charge":
      return `${inr(metrics.amount ?? 0)} charged`;
  }
}
```

- [ ] **Step 4: Layout and canvas**

`src/components/flow/layout.ts`:

```ts
import type { FlowBlock } from "@/lib/flow";

export const BLOCK_W = 200;
export const BLOCK_H = 84;
export const GAP_X = 40;
export const GAP_Y = 56;

export interface Point {
  x: number;
  y: number;
}

export function layoutFlow(blocks: { id: string; level: number }[]): { positions: Record<string, Point>; width: number; height: number } {
  const levels = [...new Set(blocks.map((b) => b.level))].sort((a, b) => a - b);
  const rows = levels.map((l) => blocks.filter((b) => b.level === l));
  const widest = Math.max(...rows.map((r) => r.length), 1);
  const width = widest * BLOCK_W + (widest - 1) * GAP_X;
  const positions: Record<string, Point> = {};
  rows.forEach((row, rowIndex) => {
    const rowWidth = row.length * BLOCK_W + (row.length - 1) * GAP_X;
    const start = (width - rowWidth) / 2;
    row.forEach((b, i) => {
      positions[b.id] = { x: start + i * (BLOCK_W + GAP_X), y: rowIndex * (BLOCK_H + GAP_Y) };
    });
  });
  return { positions, width, height: rows.length * BLOCK_H + (rows.length - 1) * GAP_Y };
}

export function edgePath(from: Point, to: Point): string {
  const x1 = from.x + BLOCK_W / 2;
  const y1 = from.y + BLOCK_H;
  const x2 = to.x + BLOCK_W / 2;
  const y2 = to.y;
  const mid = (y1 + y2) / 2;
  return `M${x1} ${y1} C${x1} ${mid}, ${x2} ${mid}, ${x2} ${y2}`;
}
```

`src/components/flow/FlowCanvas.tsx`:

```tsx
"use client";

import { Icon, type IconName } from "@/components/ui/Icon";
import type { BlockKind, FlowBlock } from "@/lib/flow";
import { flowEdges } from "@/lib/flow";
import type { BlockState } from "@/lib/types";
import { BLOCK_H, BLOCK_W, edgePath, GAP_X, layoutFlow } from "./layout";

const ICON: Record<BlockKind, IconName> = { read: "read", llm: "layers", draft: "write", gate: "gate", write: "write", charge: "rupee" };
const ICON_TONE: Record<BlockKind, string> = {
  read: "bg-sky text-blue", llm: "bg-sky text-blue", draft: "bg-sky text-blue",
  gate: "bg-wa-soft text-wa", write: "bg-ok-soft text-ok", charge: "bg-ok-soft text-ok",
};
const STATE_CLASS: Record<BlockState, string> = {
  IDLE: "opacity-50",
  RUNNING: "border-blue-2 shadow-[0_0_0_4px_var(--sky)]",
  DONE: "",
  WAITING: "border-wa-line shadow-wait",
  FAILED: "border-bad-line",
  SKIPPED: "opacity-50",
};
const EDGE_CLASS: Record<BlockState, string> = {
  IDLE: "stroke-[#C8D5EA]",
  RUNNING: "stroke-blue [stroke-dasharray:5_6] animate-[flow_.9s_linear_infinite]",
  DONE: "stroke-blue-3",
  WAITING: "stroke-wa-line [stroke-dasharray:5_6]",
  FAILED: "stroke-bad-line",
  SKIPPED: "stroke-[#C8D5EA]",
};

export interface ExtraBlock {
  id: string;
  title: string;
  subtitle: string;
  tone: "bad" | "fixed";
  after: string;
}

export function FlowCanvas({
  blocks,
  states,
  metrics,
  selected,
  onSelect,
  extraBlocks = [],
  label,
}: {
  blocks: FlowBlock[];
  states?: Record<string, BlockState>;
  metrics?: Record<string, string>;
  selected?: string;
  onSelect?: (id: string) => void;
  extraBlocks?: ExtraBlock[];
  label: string;
}) {
  const { positions, width, height } = layoutFlow(blocks);
  const edges = flowEdges(blocks);
  const extraWidth = extraBlocks.length ? BLOCK_W + GAP_X : 0;

  return (
    <div role="group" aria-label={label} className="relative mx-auto" style={{ width: width + extraWidth, height }}>
      <svg aria-hidden className="absolute inset-0 overflow-visible" width={width + extraWidth} height={height}>
        {edges.map(([from, to]) => (
          <path
            key={`${from}-${to}`}
            d={edgePath(positions[from], positions[to])}
            className={`fill-none stroke-[1.6] ${EDGE_CLASS[states?.[to] ?? "DONE"]}`}
          />
        ))}
        {extraBlocks.map((x) => {
          const anchor = positions[x.after];
          if (!anchor) return null;
          return (
            <path
              key={`extra-${x.id}`}
              d={`M${anchor.x + BLOCK_W} ${anchor.y + BLOCK_H / 2} L${width + GAP_X} ${anchor.y + BLOCK_H / 2}`}
              className={`fill-none stroke-[1.6] ${x.tone === "bad" ? "stroke-[#E19A91] [stroke-dasharray:4_5]" : "stroke-blue-3"}`}
            />
          );
        })}
      </svg>
      {blocks.map((b) => {
        const pos = positions[b.id];
        const state = states?.[b.id];
        const isSelected = selected === b.id;
        const Tag = onSelect ? "button" : "div";
        return (
          <Tag
            key={b.id}
            data-testid={`block-${b.id}`}
            data-state={state ?? ""}
            type={onSelect ? "button" : undefined}
            aria-pressed={onSelect ? isSelected : undefined}
            aria-label={`${b.label}: ${b.title}${metrics?.[b.id] ? `, ${metrics[b.id]}` : ""}`}
            onClick={onSelect ? () => onSelect(b.id) : undefined}
            className={`absolute flex flex-col gap-1 px-3.5 py-2.5 text-left bg-surface border border-line rounded-[14px] shadow-block transition ${
              state ? STATE_CLASS[state] : ""
            } ${isSelected ? "border-blue shadow-[0_0_0_4px_var(--sky2)]" : ""} ${onSelect ? "hover:border-blue-3 cursor-pointer" : ""}`}
            style={{ left: pos.x, top: pos.y, width: BLOCK_W, height: BLOCK_H }}
          >
            <span className="flex items-center gap-2 text-xs text-ink3">
              <span className={`w-6 h-6 rounded-[7px] grid place-items-center ${ICON_TONE[b.kind]}`}>
                <Icon name={ICON[b.kind]} className="w-3.5 h-3.5" />
              </span>
              {b.label}
            </span>
            <span className="text-sm font-semibold leading-tight truncate">{b.title}</span>
            <span className="text-xs text-ink2 truncate">{metrics?.[b.id] ?? b.subtitle}</span>
          </Tag>
        );
      })}
      {extraBlocks.map((x) => {
        const anchor = positions[x.after];
        if (!anchor) return null;
        return (
          <div
            key={x.id}
            data-testid={`block-${x.id}`}
            data-tone={x.tone}
            className={`absolute flex flex-col gap-1 px-3.5 py-2.5 rounded-[14px] ${
              x.tone === "bad" ? "bg-[rgba(252,234,232,.55)] border-[1.5px] border-dashed border-[#E19A91]" : "bg-surface border border-blue-3 shadow-[0_0_0_4px_var(--sky)]"
            }`}
            style={{ left: width + GAP_X, top: anchor.y, width: BLOCK_W, height: BLOCK_H }}
          >
            <span className={`text-xs ${x.tone === "bad" ? "text-bad" : "text-ink3"}`}>{x.tone === "bad" ? "Undeclared" : "Declared now"}</span>
            <span className="text-sm font-semibold font-mono truncate">{x.title}</span>
            <span className="text-xs text-ink2 truncate">{x.subtitle}</span>
          </div>
        );
      })}
    </div>
  );
}
```

Add the edge animation keyframes to `src/app/globals.css`:

```css
@keyframes flow { to { stroke-dashoffset: -22; } }
@keyframes blink { 50% { opacity: .25; } }
```

- [ ] **Step 5: Run the tests**

Run: `npm test -- src/lib/flow.test.ts src/components/flow && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): flow model derived from the manifest, and a flow canvas with states and edges"
```

---

### Task 3: The store: index and the agent as a flow

**Files:**
- Create: `src/app/(buyer)/store/page.tsx`, `StoreIndex.tsx`; `src/app/(buyer)/store/[agentId]/layout.tsx`, `StoreHeader.tsx`, `AgentFlowView.tsx`, `actions.ts`
- Replace: `src/app/(buyer)/store/[agentId]/page.tsx` (FE-1's redirect)
- Modify: `src/components/shell/nav.ts`, `src/components/shell/nav.test.ts`
- Replace with redirects: `src/app/(buyer)/catalog/page.tsx` → `/store`; `src/app/(buyer)/agents/[agentId]/versions/[versionId]/page.tsx` → `/store/{agentId}`
- Delete: `src/app/(buyer)/catalog/CatalogList.tsx`, `CatalogList.test.tsx`, `src/app/(buyer)/agents/[agentId]/versions/[versionId]/AgentDetailView.tsx`, `AgentDetailView.test.tsx`
- Test: create `src/app/(buyer)/store/StoreIndex.test.tsx`, `src/app/(buyer)/store/[agentId]/AgentFlowView.test.tsx`, `StoreHeader.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/catalog/agents`, `GET /api/v1/catalog/agents/{id}`, `GET /api/v1/catalog/agents/{id}/track-record?days=90`, `POST /api/v1/catalog/agents/{id}/trials`; `flowBlocks`, `FlowCanvas` (Task 2).
- Produces:
  - `StoreHeader({ agent, active: "flow" | "track" | "versions", stats })`, which FE-2's other store pages reuse.
  - `blockFacts(block, agent, track): { kind: string; body: string; facts: [string, string][] }`.
  - `startTrialAction(agentId): ActionFailure`, which redirects to the trial run.
  - Nav: buyer "Agent store" → `/store`, plus a "Files" item at `/files`.

- [ ] **Step 1: Write the failing tests**

`src/app/(buyer)/store/StoreIndex.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StoreIndex } from "./StoreIndex";

describe("StoreIndex", () => {
  it("shows measured facts, never stars", () => {
    render(
      <StoreIndex
        agents={[{
          agentId: "a1", name: "Bank Reconciliation", category: "Reconciliation", summary: "Matches statement lines to invoices.",
          publisherName: "Ledgerline Labs", verifiedPublisher: true, typicalCost: 148, casesPassed: 47, casesTotal: 50,
        }]}
      />,
    );
    const card = screen.getByRole("link", { name: /Bank Reconciliation/ });
    expect(card).toHaveAttribute("href", "/store/a1");
    expect(screen.getByText("47 of 50 test cases passed")).toBeInTheDocument();
    expect(screen.getByText("₹148 typical run")).toBeInTheDocument();
    expect(screen.getByText("Verified publisher")).toBeInTheDocument();
    expect(screen.queryByText(/★|stars?/i)).not.toBeInTheDocument();
  });
});
```

`src/app/(buyer)/store/[agentId]/StoreHeader.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StoreHeader } from "./StoreHeader";
import { storeAgent } from "./fixtures";

describe("StoreHeader", () => {
  it("shows publisher, version and tabs with the new-version count", () => {
    render(<StoreHeader agent={{ ...storeAgent, newVersionsForMe: 1 }} active="flow" />);
    expect(screen.getByRole("heading", { name: "Bank Reconciliation" })).toBeInTheDocument();
    expect(screen.getByText("Verified publisher")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Flow" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Versions, 1 new" })).toHaveAttribute("href", "/store/a1/versions");
  });
});
```

Create the shared fixture `src/app/(buyer)/store/[agentId]/fixtures.ts` (used by this plan's tests only):

```ts
import type { StoreAgent, TrackRecord } from "@/lib/types";

export const storeAgent: StoreAgent = {
  agentId: "a1",
  name: "Bank Reconciliation",
  category: "Reconciliation",
  summary: "Matches bank statement lines to open invoices and proposes journal entries for your approval.",
  publisher: { orgId: "p1", displayName: "Ledgerline Labs", verified: true },
  liveVersion: {
    id: "v13",
    versionNumber: "1.3.0",
    manifest: {
      name: "Bank Reconciliation", version: "1.3.0",
      resources: [
        { name: "bank_statements", format: "csv", displayName: "Bank statements", description: "Your statement lines, CSV" },
        { name: "invoices", format: "csv", displayName: "Open invoices", description: "Unpaid invoices, CSV" },
        { name: "journal_entries", format: "json", displayName: "Journal entries" },
      ],
      scopes: [
        { type: "READ", resource: "bank_statements", description: "Read statement lines" },
        { type: "READ", resource: "invoices", description: "Read open invoices" },
        { type: "WRITE", resource: "journal_entries", description: "Propose entries", requiresApproval: true },
      ],
      approvalGates: [{ trigger: "post_journal_entries", description: "Approve before posting", title: "Post to your ledger", timeoutMinutes: 30 }],
      llm: { mode: "platform", models: ["claude-sonnet-5"], maxTokensPerRun: 400000, purpose: "Match lines to invoices" },
      networkAllowlist: [],
      costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "OUTCOME", unitPrice: 2, unitLabel: "invoice_matched", unitDisplay: "match" },
      dataClasses: ["financial", "pii:basic"],
    },
  },
  llmModels: [{ id: "claude-sonnet-5", displayName: "Claude Sonnet", providerRetainsData: false }],
  trialAvailable: true,
  myConnection: null,
  newVersionsForMe: 0,
};

export const trackRecord: TrackRecord = {
  enoughData: true,
  runs: 1204, finishedWithoutError: 0.991, draftsApproved: 0.94, disputed: 0.004, medianDurationSeconds: 52, workspaces: 63,
  cost: { p50: 148, p95: 310, cap: 500, buckets: [{ lt: 100, share: 0.22 }, { lt: 200, share: 0.41 }, { lt: 300, share: 0.29 }, { lt: 500, share: 0.076 }, { stoppedAtCap: true, share: 0.004 }] },
  review: {
    version: "1.3.0", checkedAt: "2026-09-11T00:00:00Z", casesPassed: 47, casesTotal: 50, probesBlocked: 12, probesTotal: 12,
    costP50: 148, costP95: 310,
    highlights: [
      { tone: "ok", text: "Never wrote anything without an approval, across all review runs." },
      { tone: "miss", text: "A payment split across two invoices." },
    ],
  },
};
```

`src/app/(buyer)/store/[agentId]/AgentFlowView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AgentFlowView } from "./AgentFlowView";
import { storeAgent, trackRecord } from "./fixtures";

vi.mock("./actions", () => ({ startTrialAction: vi.fn().mockResolvedValue({ ok: false, error: "You've used this month's 3 trials for this agent." }) }));

describe("AgentFlowView", () => {
  it("opens on the approval block and explains it", () => {
    render(<AgentFlowView agent={storeAgent} track={trackRecord} />);
    const panel = screen.getByRole("region", { name: "Selected block" });
    expect(within(panel).getByText("Your approval")).toBeInTheDocument();
    expect(within(panel).getByText("Stops after 30 min")).toBeInTheDocument();
  });

  it("shows another block's facts when chosen", async () => {
    render(<AgentFlowView agent={storeAgent} track={trackRecord} />);
    await userEvent.click(screen.getByRole("button", { name: /Thinks: Match lines to invoices/ }));
    const panel = screen.getByRole("region", { name: "Selected block" });
    expect(within(panel).getByText("Claude Sonnet")).toBeInTheDocument();
    expect(within(panel).getByText("No")).toBeInTheDocument();          // provider keeps your data
    expect(within(panel).getByText("400,000")).toBeInTheDocument();
  });

  it("states what the buyer agrees to", () => {
    render(<AgentFlowView agent={storeAgent} track={trackRecord} />);
    const terms = screen.getByRole("region", { name: "What you agree to" });
    expect(within(terms).getByText("₹2 per match")).toBeInTheDocument();
    expect(within(terms).getByText("₹500")).toBeInTheDocument();
    expect(within(terms).getByText("None")).toBeInTheDocument();
    expect(within(terms).getByText("Only when you accept them")).toBeInTheDocument();
  });

  it("offers a trial and adding it, and explains a refused trial", async () => {
    render(<AgentFlowView agent={storeAgent} track={trackRecord} />);
    expect(screen.getByRole("link", { name: "Add to workspace" })).toHaveAttribute("href", "/connect/a1/v13");
    await userEvent.click(screen.getByRole("button", { name: "Try on sample data" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("3 trials");
  });

  it("opens the workspace connection when already added", () => {
    render(<AgentFlowView agent={{ ...storeAgent, myConnection: { id: "c1", versionNumber: "1.3.0" }, trialAvailable: false }} track={trackRecord} />);
    expect(screen.queryByRole("button", { name: "Try on sample data" })).not.toBeInTheDocument();
    expect(screen.getByText("In your workspace on 1.3.0")).toBeInTheDocument();
  });
});
```

Update `src/components/shell/nav.test.ts`: the buyer admin hrefs become `["/home", "/store", "/runs", "/files", "/billing"]`, and the approver's become `["/home", "/store", "/runs", "/files"]`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(buyer)/store" src/components/shell/nav.test.ts`
Expected: FAIL.

- [ ] **Step 3: Navigation**

In `src/components/shell/nav.ts`, change `STORE` and add `FILES`:

```ts
const STORE: NavItem = { href: "/store", label: "Agent store", icon: "store" };
const FILES: NavItem = { href: "/files", label: "Files", icon: "files" };
```

and return `[HOME, STORE, RUNS, FILES, BILLING]` for `BUYER_ADMIN`, and `[HOME, STORE, RUNS, FILES]` for `BUYER_APPROVER`. In `src/lib/routing.ts`, add `["/files", BUYER]` to `ROLE_PREFIXES`.

- [ ] **Step 4: Store index**

`src/app/(buyer)/store/StoreIndex.tsx`:

```tsx
import Link from "next/link";
import { Panel } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { inr } from "@/lib/format";
import type { StoreAgentCard } from "@/lib/types";

export function StoreIndex({ agents }: { agents: StoreAgentCard[] }) {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Agent store</h1>
        <p className="text-ink2">Every agent here was checked by Karya before you see it. Everything shown is measured, so it can&apos;t be bought.</p>
      </div>
      <div className="grid grid-cols-3 gap-4">
        {agents.map((a) => (
          <Link key={a.agentId} href={`/store/${a.agentId}`} className="block">
            <Panel as="div" className="p-5 h-full flex flex-col gap-3 hover:border-blue-3">
              <div className="flex items-center gap-2">
                <span className="text-xs text-ink3">{a.category}</span>
                {a.verifiedPublisher && <Pill tone="ok">Verified publisher</Pill>}
              </div>
              <div>
                <div className="text-base font-semibold">{a.name}</div>
                <div className="text-xs text-ink3">by {a.publisherName}</div>
              </div>
              {a.summary && <p className="text-[13px] text-ink2 line-clamp-2">{a.summary}</p>}
              <div className="mt-auto flex gap-3 text-xs text-ink2">
                {a.casesTotal ? <span>{a.casesPassed} of {a.casesTotal} test cases passed</span> : null}
                {a.typicalCost !== null && <span className="font-mono">{inr(a.typicalCost)} typical run</span>}
              </div>
            </Panel>
          </Link>
        ))}
      </div>
    </div>
  );
}
```

`src/app/(buyer)/store/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { StoreAgentCard } from "@/lib/types";
import { StoreIndex } from "./StoreIndex";

export default async function StorePage() {
  return <StoreIndex agents={await backend.get<StoreAgentCard[]>("/api/v1/catalog/agents")} />;
}
```

- [ ] **Step 5: Shared header and layout**

`src/app/(buyer)/store/[agentId]/StoreHeader.tsx`:

```tsx
import Link from "next/link";
import { Pill } from "@/components/ui/Pill";
import type { StoreAgent } from "@/lib/types";

export function StoreHeader({ agent, active }: { agent: StoreAgent; active: "flow" | "track" | "versions" }) {
  const tabs = [
    { key: "flow", href: `/store/${agent.agentId}`, label: "Flow" },
    { key: "track", href: `/store/${agent.agentId}/track-record`, label: "Track record" },
    { key: "versions", href: `/store/${agent.agentId}/versions`, label: "Versions" },
  ] as const;
  return (
    <div className="flex items-end justify-between gap-4 mb-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">{agent.name}</h1>
        <div className="flex items-center gap-2 text-[13px] text-ink2 mt-1">
          <span>by {agent.publisher.displayName}</span>
          {agent.publisher.verified && <Pill tone="ok">Verified publisher</Pill>}
          <span className="font-mono text-xs">v{agent.liveVersion.versionNumber}</span>
        </div>
      </div>
      <nav aria-label="Agent sections" className="flex gap-1 bg-line2 p-[3px] rounded-[10px]">
        {tabs.map((t) => {
          const count = t.key === "versions" ? agent.newVersionsForMe : 0;
          return (
            <Link
              key={t.key}
              href={t.href}
              aria-current={active === t.key ? "page" : undefined}
              aria-label={count ? `${t.label}, ${count} new` : t.label}
              className={`h-[34px] px-3 rounded-lg text-[13px] inline-flex items-center gap-1.5 ${active === t.key ? "bg-surface font-semibold shadow-sm" : "text-ink2"}`}
            >
              {t.label}
              {count > 0 && <span className="text-[11px] font-medium bg-sky text-blue rounded-full px-1.5">{count} new</span>}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
```

`src/app/(buyer)/store/[agentId]/layout.tsx`:

```tsx
export default function StoreAgentLayout({ children }: { children: React.ReactNode }) {
  return <div className="flex flex-col">{children}</div>;
}
```

(Each page renders `StoreHeader` itself, so the page can pass the active tab and data without a second fetch.)

- [ ] **Step 6: The flow view, trial action and page**

`src/app/(buyer)/store/[agentId]/actions.ts`:

```ts
"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { actionError, type ActionFailure } from "@/lib/problems";

export async function startTrialAction(agentId: string): Promise<ActionFailure> {
  let runId: string;
  try {
    ({ runId } = await backend.post<{ runId: string }>(`/api/v1/catalog/agents/${agentId}/trials`));
  } catch (e) {
    return actionError(e, {
      "429": "You've used this month's 3 trials for this agent.",
      "409": "This agent has no sample data to try yet.",
    }, "The trial couldn't start.");
  }
  redirect(`/runs/${runId}`);
}
```

`src/app/(buyer)/store/[agentId]/AgentFlowView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { FlowCanvas } from "@/components/flow/FlowCanvas";
import { Button, ButtonLink } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { flowBlocks, type FlowBlock } from "@/lib/flow";
import { inr } from "@/lib/format";
import type { StoreAgent, TrackRecord } from "@/lib/types";
import { startTrialAction } from "./actions";

export function blockFacts(block: FlowBlock, agent: StoreAgent, track: TrackRecord | null): { body: string; facts: [string, string][] } {
  const m = agent.liveVersion.manifest;
  const gate = m.approvalGates[0];
  const model = agent.llmModels.find((x) => x.id === m.llm?.models[0]);
  switch (block.kind) {
    case "read":
      return {
        body: `Reads the ${block.title.toLowerCase()} file you plug in for this agent. It cannot see any other file in your workspace.`,
        facts: [["Access", "Read only"], ["Format", (m.resources.find((r) => `read:${r.name}` === block.id)?.format ?? "").toUpperCase()], ["Every read is logged", "By Karya, not the agent"]],
      };
    case "llm":
      return {
        body: `Sends the data it read to ${model?.displayName ?? "an AI model"} through Karya. It cannot call any other model or website.`,
        facts: [["Model", model?.displayName ?? m.llm?.models[0] ?? "—"], ["Provider keeps your data", model?.providerRetainsData ? "Yes" : "No"], ["Token budget per run", (m.llm?.maxTokensPerRun ?? 0).toLocaleString("en-IN")]],
      };
    case "draft":
      return { body: "Proposed records are held in a draft area on Karya. You can preview every line before deciding.", facts: [["Written anywhere yet", "No"], ["You can download", "CSV or JSON"]] };
    case "gate":
      return {
        body: "The run pauses here and asks you or an approver to review the drafts. Nothing moves until someone says yes.",
        facts: [["Who can decide", "Your approvers"], ["Where you are asked", "Karya and email"], ["If nobody answers", `Stops after ${gate?.timeoutMinutes ?? 30} min`]],
      };
    case "write":
      return {
        body: block.subtitle === "Only approved entries" ? "Only records you approved are written. Rejected ones are thrown away and the run stops." : "Writes records without asking first.",
        facts: [["Writes without approval", block.subtitle === "Only approved entries" ? "Never" : "Yes"], ["Where", "Your Karya output folder"]],
      };
    case "charge":
      return {
        body: `You pay per ${m.costEnvelope.unitDisplay ?? m.costEnvelope.unitLabel}, taken from your wallet at the end of the run.`,
        facts: [["Typical run", track?.enoughData ? inr(track.cost.p50) : "Not enough runs yet"], ["Hard stop", `${inr(m.costEnvelope.maxPerRun)} per run`], ["Held while running", "Your run limit"]],
      };
  }
}

const pct = (v: number) => `${(v * 100).toFixed(v < 0.01 ? 1 : 0)}%`;

export function AgentFlowView({ agent, track }: { agent: StoreAgent; track: TrackRecord | null }) {
  const names = Object.fromEntries(agent.llmModels.map((m) => [m.id, m.displayName]));
  const blocks = flowBlocks(agent.liveVersion.manifest, names);
  const [selected, setSelected] = useState(blocks.find((b) => b.kind === "gate")?.id ?? blocks[0].id);
  const [trialError, setTrialError] = useState<string | null>(null);
  const current = blocks.find((b) => b.id === selected)!;
  const info = blockFacts(current, agent, track);
  const m = agent.liveVersion.manifest;
  const review = track?.review;

  return (
    <div className="flex flex-col gap-5">
      {review && track && (
        <Panel className="px-5 py-4 flex">
          {[
            [`${review.casesPassed} of ${review.casesTotal}`, "test cases passed"],
            [`${review.probesBlocked} of ${review.probesTotal}`, "attack probes blocked"],
            [track.enoughData ? inr(track.cost.p50) : "—", "typical cost per run"],
            [track.enoughData ? `${track.runs.toLocaleString("en-IN")} runs` : "New", track.enoughData ? `${pct(track.disputed)} disputed` : "not enough runs yet"],
          ].map(([value, label]) => (
            <div key={label} className="flex flex-col gap-0.5 px-5 first:pl-0 border-l border-line2 first:border-l-0">
              <b className="font-mono font-medium text-[17px]">{value}</b>
              <span className="text-xs text-ink3">{label}</span>
            </div>
          ))}
        </Panel>
      )}
      <div className="grid grid-cols-[minmax(0,1fr)_340px] gap-6 items-start">
        <Panel className="p-8 dot-grid overflow-x-auto">
          <FlowCanvas label={`How ${agent.name} works`} blocks={blocks} selected={selected} onSelect={setSelected} />
        </Panel>
        <div className="flex flex-col gap-4">
          <Panel aria-label="Selected block" className="p-5 flex flex-col gap-2">
            <span className="text-xs text-ink3">{current.label}</span>
            <h2 className="text-base font-semibold">{current.title}</h2>
            <p className="text-[13px] text-ink2">{info.body}</p>
            <div className="border-t border-line2 mt-1">
              {info.facts.map(([k, v]) => <Kv key={k} label={k} value={v} mono={false} />)}
            </div>
          </Panel>
          <Panel aria-label="What you agree to" className="p-5 flex flex-col">
            <PanelTitle>What you agree to</PanelTitle>
            <Kv label="Price" value={`${inr(m.costEnvelope.unitPrice)} per ${m.costEnvelope.unitDisplay ?? m.costEnvelope.unitLabel}`} mono={false} />
            <Kv label="Most a run can cost" value={inr(m.costEnvelope.maxPerRun)} />
            <Kv label="Outside websites" value={m.networkAllowlist.length ? m.networkAllowlist.join(", ") : "None"} mono={false} />
            <Kv label="Data it handles" value={m.dataClasses.join(", ") || "None"} mono={false} />
            <Kv label="Updates" value="Only when you accept them" mono={false} />
          </Panel>
          {trialError && <p role="alert" className="text-[13px] text-bad">{trialError}</p>}
          {agent.myConnection ? (
            <Panel className="p-4 flex items-center justify-between text-[13px]">
              <span>In your workspace on {agent.myConnection.versionNumber}</span>
              <Pill tone="ok">Added</Pill>
            </Panel>
          ) : (
            <div className="flex gap-2.5">
              {agent.trialAvailable && (
                <Button variant="secondary" className="flex-1" onClick={async () => {
                  const r = await startTrialAction(agent.agentId);
                  if (r && !r.ok) setTrialError(r.error);
                }}>
                  Try on sample data
                </Button>
              )}
              <ButtonLink href={`/connect/${agent.agentId}/${agent.liveVersion.id}`} className="flex-1">Add to workspace</ButtonLink>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
```

Replace `src/app/(buyer)/store/[agentId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { StoreAgent, TrackRecord } from "@/lib/types";
import { AgentFlowView } from "./AgentFlowView";
import { StoreHeader } from "./StoreHeader";

export default async function AgentPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const [agent, track] = await Promise.all([
    backend.get<StoreAgent>(`/api/v1/catalog/agents/${agentId}`),
    backend.get<TrackRecord>(`/api/v1/catalog/agents/${agentId}/track-record?days=90`).catch(() => null),
  ]);
  return (
    <>
      <StoreHeader agent={agent} active="flow" />
      <AgentFlowView agent={agent} track={track} />
    </>
  );
}
```

- [ ] **Step 7: Retire the old catalog and detail pages**

Replace `src/app/(buyer)/catalog/page.tsx`:

```tsx
import { redirect } from "next/navigation";

export default function CatalogRedirect() {
  redirect("/store");
}
```

Replace `src/app/(buyer)/agents/[agentId]/versions/[versionId]/page.tsx`:

```tsx
import { redirect } from "next/navigation";

export default async function AgentDetailRedirect({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  redirect(`/store/${agentId}`);
}
```

Delete `CatalogList.tsx`, `CatalogList.test.tsx`, `AgentDetailView.tsx` and `AgentDetailView.test.tsx`. In FE-1's `HomeView`, change the "Browse agents" link from `/catalog` to `/store`. In `RunAnAgent`, change the "Browse the store" link to `/store`, and update its test's expected href to `/store`.

- [ ] **Step 8: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 9: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): agent store and the agent-as-a-flow listing with block facts, terms and trials"
```

---

### Task 4: Track record

**Files:**
- Create: `src/app/(buyer)/store/[agentId]/track-record/page.tsx`, `TrackRecordView.tsx`
- Test: create `src/app/(buyer)/store/[agentId]/track-record/TrackRecordView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/catalog/agents/{id}/track-record?days=30|90`, `GET /api/v1/catalog/agents/{id}/disputes?days=90`, `GET /api/v1/publishers/{orgId}`; `StoreHeader` and `fixtures` (Task 3).
- Produces: `TrackRecordView({ agentId, track, disputes, publisher, days })`. The period switch is a link to `?days=30|90`.

- [ ] **Step 1: Write the failing test**

`src/app/(buyer)/store/[agentId]/track-record/TrackRecordView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TrackRecordView } from "./TrackRecordView";
import { trackRecord } from "../fixtures";
import type { Publisher, PublicDisputes } from "@/lib/types";

const publisher: Publisher = {
  displayName: "Ledgerline Labs", legalName: "Ledgerline Labs LLP", city: "Pune", country: "IN",
  verifiedAt: "2026-09-02T00:00:00Z", agentsListed: 2, supportEmail: "help@ledgerline.example", medianReplyHours: null,
};
const disputes: PublicDisputes = {
  openedInWindow: 5,
  decided: [
    { decidedAt: "2026-09-14T00:00:00Z", publicSummary: "6 matches were against the wrong invoice.", outcome: "REFUNDED", amountRefunded: 284 },
    { decidedAt: "2026-08-21T00:00:00Z", publicSummary: "The buyer uploaded July's statement for August.", outcome: "NOT_REFUNDED", amountRefunded: null },
  ],
};

describe("TrackRecordView", () => {
  it("shows what Karya checked before listing", () => {
    render(<TrackRecordView agentId="a1" track={trackRecord} disputes={disputes} publisher={publisher} days={90} />);
    const checked = screen.getByRole("region", { name: "Checked before it was listed" });
    expect(within(checked).getByText("47 of 50")).toBeInTheDocument();
    expect(within(checked).getByText("Missed: A payment split across two invoices.")).toBeInTheDocument();
  });

  it("shows the cost spread and the real-run numbers for the period", () => {
    render(<TrackRecordView agentId="a1" track={trackRecord} disputes={disputes} publisher={publisher} days={90} />);
    expect(screen.getByText("Under ₹100")).toBeInTheDocument();
    expect(screen.getByText("Stopped at ₹500")).toBeInTheDocument();
    expect(screen.getByText("1,204")).toBeInTheDocument();
    expect(screen.getByText("99.1%")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "90 days" })).toHaveAttribute("aria-current", "page");
  });

  it("lists disputes in the open with outcomes", () => {
    render(<TrackRecordView agentId="a1" track={trackRecord} disputes={disputes} publisher={publisher} days={90} />);
    const d = screen.getByRole("region", { name: "Disputes, in the open" });
    expect(within(d).getByText("5 in 90 days")).toBeInTheDocument();
    expect(within(d).getByText("Refunded ₹284")).toBeInTheDocument();
    expect(within(d).getByText("Not refunded")).toBeInTheDocument();
  });

  it("says when there are not enough runs yet", () => {
    render(<TrackRecordView agentId="a1" track={{ ...trackRecord, enoughData: false }} disputes={{ openedInWindow: 0, decided: [] }} publisher={publisher} days={30} />);
    expect(screen.getAllByText("Not enough runs yet")).toHaveLength(2);   // cost panel and real-run panel
    expect(screen.queryByText("99.1%")).not.toBeInTheDocument();
  });

  it("introduces the publisher without star ratings", () => {
    render(<TrackRecordView agentId="a1" track={trackRecord} disputes={disputes} publisher={publisher} days={90} />);
    expect(screen.getByText("Ledgerline Labs LLP")).toBeInTheDocument();
    expect(screen.getByText(/Karya has no star ratings/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(buyer)/store/\[agentId\]/track-record"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(buyer)/store/[agentId]/track-record/TrackRecordView.tsx`:

```tsx
import Link from "next/link";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { dayLabel, inr } from "@/lib/format";
import type { Publisher, PublicDisputes, TrackRecord } from "@/lib/types";

const pct = (v: number) => `${(Math.round(v * 1000) / 10).toString()}%`;

function bucketLabel(b: TrackRecord["cost"]["buckets"][number], prev: number | undefined, cap: number): string {
  if (b.stoppedAtCap) return `Stopped at ${inr(cap)}`;
  if (prev === undefined) return `Under ${inr(b.lt ?? 0)}`;
  return `${inr(prev)} to ${inr((b.lt ?? 0) - 1)}`;
}

export function TrackRecordView({
  agentId, track, disputes, publisher, days,
}: {
  agentId: string; track: TrackRecord; disputes: PublicDisputes; publisher: Publisher; days: 30 | 90;
}) {
  const review = track.review;
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_340px] gap-6 items-start">
      <div className="flex flex-col gap-4 min-w-0">
        {review && (
          <Panel aria-label="Checked before it was listed" className="p-5 flex flex-col gap-3">
            <PanelTitle hint={`By Karya, on ${review.version}`}>Checked before it was listed</PanelTitle>
            <div className="grid grid-cols-2 gap-4">
              <div><b className="font-mono text-2xl font-medium">{review.casesPassed} of {review.casesTotal}</b><div className="text-xs text-ink3">test cases passed</div></div>
              <div><b className="font-mono text-2xl font-medium">{review.probesBlocked} of {review.probesTotal}</b><div className="text-xs text-ink3">attack probes blocked</div></div>
            </div>
            <ul className="flex flex-col gap-1.5 text-[13px]">
              {review.highlights.map((h) => (
                <li key={h.text} className="flex gap-2">
                  <i aria-hidden className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${h.tone === "ok" ? "bg-ok" : "bg-wa-line"}`} />
                  {h.tone === "ok" ? h.text : `Missed: ${h.text}`}
                </li>
              ))}
            </ul>
          </Panel>
        )}

        <Panel className="p-5 flex flex-col gap-3">
          <PanelTitle hint={days === 30 ? "Last 30 days" : "Last 90 days"}>What a run costs</PanelTitle>
          {track.enoughData ? (
            <>
              <div className="flex gap-6">
                <div><b className="font-mono text-2xl font-medium">{inr(track.cost.p50)}</b><div className="text-xs text-ink3">typical run</div></div>
                <div><b className="font-mono text-2xl font-medium">{inr(track.cost.p95)}</b><div className="text-xs text-ink3">19 in 20 runs cost less</div></div>
              </div>
              <ul className="flex flex-col gap-1.5">
                {track.cost.buckets.map((b, i) => {
                  const prev = i === 0 ? undefined : track.cost.buckets[i - 1].lt;
                  return (
                    <li key={i} className="grid grid-cols-[130px_1fr_48px] gap-3 items-center text-[13px]">
                      <span className="text-ink2">{bucketLabel(b, prev, track.cost.cap)}</span>
                      <span className="h-2 rounded-full bg-line2 overflow-hidden"><i className={`block h-full ${b.stoppedAtCap ? "bg-wa-line" : "bg-blue-2"}`} style={{ width: `${b.share * 100}%` }} /></span>
                      <span className="font-mono text-xs text-right">{pct(b.share)}</span>
                    </li>
                  );
                })}
              </ul>
            </>
          ) : (
            <p className="text-[13px] text-ink3">Not enough runs yet</p>
          )}
        </Panel>

        <Panel className="p-5 flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <PanelTitle>On real buyers&apos; books</PanelTitle>
            <nav aria-label="Period" className="flex gap-1 bg-line2 p-[3px] rounded-[10px]">
              {([30, 90] as const).map((d) => (
                <Link key={d} href={`/store/${agentId}/track-record?days=${d}`} aria-current={d === days ? "page" : undefined}
                  className={`h-8 px-3 rounded-lg text-[13px] inline-flex items-center ${d === days ? "bg-surface font-semibold shadow-sm" : "text-ink2"}`}>
                  {d} days
                </Link>
              ))}
            </nav>
          </div>
          {track.enoughData ? (
            <>
              <Kv label="Runs" value={track.runs.toLocaleString("en-IN")} />
              <Kv label="Finished without error" value={pct(track.finishedWithoutError)} />
              {track.draftsApproved !== null && <Kv label="Drafts approved by buyers" value={pct(track.draftsApproved)} />}
              <Kv label="Runs disputed" value={pct(track.disputed)} />
              <Kv label="Typical run time" value={`${Math.floor(track.medianDurationSeconds / 60)}:${String(track.medianDurationSeconds % 60).padStart(2, "0")}`} />
              <Kv label="Workspaces using it" value={track.workspaces} />
            </>
          ) : (
            <p className="text-[13px] text-ink3">Not enough runs yet</p>
          )}
        </Panel>
      </div>

      <div className="flex flex-col gap-4">
        <Panel aria-label="Disputes, in the open" className="p-5 flex flex-col gap-2">
          <PanelTitle hint={`${disputes.openedInWindow} in 90 days`}>Disputes, in the open</PanelTitle>
          {disputes.decided.map((d, i) => (
            <div key={i} className="py-2 border-t border-line2 first:border-t-0 text-[13px] flex flex-col gap-0.5">
              <span className="text-xs text-ink3 font-mono">{dayLabel(d.decidedAt)}</span>
              <span>{d.publicSummary}</span>
              <span className={d.outcome === "REFUNDED" ? "text-ok" : "text-ink3"}>
                {d.outcome === "REFUNDED" ? `Refunded ${inr(d.amountRefunded ?? 0)}` : "Not refunded"}
              </span>
            </div>
          ))}
          <p className="text-xs text-ink3">Karya decides disputes. Refunds come out of the developer&apos;s earnings, not the platform fee.</p>
        </Panel>
        <Panel className="p-5 flex flex-col">
          <PanelTitle>About the publisher</PanelTitle>
          <div className="mt-2 font-semibold text-sm">{publisher.legalName}</div>
          <div className="text-xs text-ink3 mb-1">{[publisher.city, publisher.country === "IN" ? "India" : publisher.country].filter(Boolean).join(", ")}</div>
          <Kv label="Identity" value={publisher.verifiedAt ? `Verified ${dayLabel(publisher.verifiedAt)}` : "Not verified"} mono={false} />
          <Kv label="Agents listed" value={publisher.agentsListed} />
          {publisher.medianReplyHours !== null && <Kv label="Replies to buyers" value={publisher.medianReplyHours <= 24 ? "Within a day" : `Within ${Math.ceil(publisher.medianReplyHours / 24)} days`} mono={false} />}
          {publisher.supportEmail && <Kv label="Support" value={publisher.supportEmail} mono={false} />}
        </Panel>
        <p className="text-xs text-ink3">Karya has no star ratings. Everything here is measured from real runs, so it can&apos;t be bought or gamed.</p>
      </div>
    </div>
  );
}
```

`src/app/(buyer)/store/[agentId]/track-record/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { Publisher, PublicDisputes, StoreAgent, TrackRecord } from "@/lib/types";
import { StoreHeader } from "../StoreHeader";
import { TrackRecordView } from "./TrackRecordView";

export default async function TrackRecordPage({ params, searchParams }: { params: Promise<{ agentId: string }>; searchParams: Promise<{ days?: string }> }) {
  const { agentId } = await params;
  const days = (await searchParams).days === "30" ? 30 : 90;
  const agent = await backend.get<StoreAgent>(`/api/v1/catalog/agents/${agentId}`);
  const [track, disputes, publisher] = await Promise.all([
    backend.get<TrackRecord>(`/api/v1/catalog/agents/${agentId}/track-record?days=${days}`),
    backend.get<PublicDisputes>(`/api/v1/catalog/agents/${agentId}/disputes?days=90`),
    backend.get<Publisher>(`/api/v1/publishers/${agent.publisher.orgId}`),
  ]);
  return (
    <>
      <StoreHeader agent={agent} active="track" />
      <TrackRecordView agentId={agentId} track={track} disputes={disputes} publisher={publisher} days={days} />
    </>
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): agent track record: review highlights, cost spread, real-run stats and public disputes"
```

---

### Task 5: Versions and update consent

**Files:**
- Create: `src/app/(buyer)/store/[agentId]/versions/page.tsx`, `VersionsView.tsx`, `actions.ts`
- Test: create `src/app/(buyer)/store/[agentId]/versions/VersionsView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/connections/{id}/versions`, `POST /api/v1/connections/{id}/upgrades`, `DELETE …/upgrades/{id}`, `POST …/upgrades/{id}/decline`, `PATCH /api/v1/connections/{id} {autoApplySafeUpdates}`, `GET /api/v1/datasets?include=usage`, `POST /api/v1/datasets/{id}/check`.
- Produces:
  - `agreeUpgradeAction(connectionId, targetVersionId, bindings): ActionResult`, `takeBackUpgradeAction(connectionId, upgradeId): ActionResult`, `declineUpgradeAction(connectionId, upgradeId | null, targetVersionId): ActionResult`, `setAutoApplyAction(connectionId, on): ActionResult`, `checkDatasetAction(datasetId, agentVersionId, resource): ActionResult<{ result: CheckResult }>`.
  - `VersionsView({ connectionId, agentName, data, files })`.
  - When the buyer has no connection, the page shows the version list without consent controls.

- [ ] **Step 1: Write the failing test**

`src/app/(buyer)/store/[agentId]/versions/VersionsView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { VersionsView } from "./VersionsView";
import type { ConnectionVersions, Dataset } from "@/lib/types";

vi.mock("./actions", () => ({
  agreeUpgradeAction: vi.fn().mockResolvedValue({ ok: true }),
  takeBackUpgradeAction: vi.fn().mockResolvedValue({ ok: true }),
  declineUpgradeAction: vi.fn().mockResolvedValue({ ok: true }),
  setAutoApplyAction: vi.fn().mockResolvedValue({ ok: true }),
  checkDatasetAction: vi.fn().mockResolvedValue({ ok: true, result: { rowCount: 640, columnsMatch: true, missingColumns: [], rowErrors: [] } }),
}));

const data: ConnectionVersions = {
  autoApplySafeUpdates: true,
  pendingUpgrade: null,
  versions: [
    {
      versionId: "v14", version: "1.4.0", buyerState: "IN_REVIEW", date: "2026-09-22T00:00:00Z",
      summary: "Checks vendor GSTINs first", releaseNotes: "Looks up each vendor's GSTIN before matching.",
      diff: [
        { kind: "READ", text: "Reads vendor master", tag: "NEW" },
        { kind: "READ", text: "Reads bank statements, open invoices", tag: "SAME" },
      ],
      scorecard: null,
      newResources: [{ name: "vendor_master", displayName: "Vendor master", description: "To check vendor GSTINs", format: "xlsx", kind: "tabular", requiredColumns: ["gstin"] }],
    },
    {
      versionId: "v13", version: "1.3.0", buyerState: "YOURS", date: "2026-09-11T00:00:00Z",
      summary: "Better with UPI narrations", releaseNotes: null, diff: [{ kind: "READ", text: "Reads bank statements, open invoices", tag: "SAME" }],
      scorecard: null, newResources: [],
    },
  ],
};
const files: Dataset[] = [{
  id: "d9", displayName: "Vendor master.xlsx", sourceLabel: null, format: "xlsx", rowCount: 640, sizeBytes: 96 * 1024,
  createdAt: "2026-08-28T00:00:00Z", uploadedBy: null, purgeAfter: null, bindings: [], pendingRequests: [],
}];

describe("VersionsView", () => {
  it("lists versions newest first and marks the buyer's", () => {
    render(<VersionsView connectionId="c1" agentName="Bank Reconciliation" data={data} files={files} />);
    const list = screen.getByRole("list", { name: "Versions" });
    const items = within(list).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("1.4.0");
    expect(items[0]).toHaveTextContent("In review");
    expect(items[1]).toHaveTextContent("Yours");
  });

  it("tags what changes", () => {
    render(<VersionsView connectionId="c1" agentName="Bank Reconciliation" data={data} files={files} />);
    const changes = screen.getByRole("region", { name: "What changes for you" });
    expect(within(changes).getByText("Reads vendor master")).toBeInTheDocument();
    expect(within(changes).getByText("New")).toBeInTheDocument();
  });

  it("needs the new file plugged in and an explicit yes before agreeing", async () => {
    const { agreeUpgradeAction } = await import("./actions");
    render(<VersionsView connectionId="c1" agentName="Bank Reconciliation" data={data} files={files} />);
    const agree = screen.getByRole("button", { name: "Agree and update when approved" });
    expect(agree).toBeDisabled();

    await userEvent.selectOptions(screen.getByLabelText("File for Vendor master"), "d9");
    expect(await screen.findByText("640 rows, columns match")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("checkbox", { name: /I allow Bank Reconciliation 1.4.0 to read this file/ }));
    await userEvent.click(agree);

    expect(agreeUpgradeAction).toHaveBeenCalledWith("c1", "v14", { vendor_master: "d9" });
  });

  it("lets the buyer take an agreement back while the version is in review", async () => {
    const { takeBackUpgradeAction } = await import("./actions");
    render(<VersionsView connectionId="c1" agentName="Bank Reconciliation" data={{ ...data, pendingUpgrade: { id: "u1", targetVersionId: "v14", status: "WAITING_FOR_APPROVAL" } }} files={files} />);
    expect(screen.getByText("You're set for 1.4.0")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Take it back" }));
    expect(takeBackUpgradeAction).toHaveBeenCalledWith("c1", "u1");
  });

  it("toggles automatic safe fixes", async () => {
    const { setAutoApplyAction } = await import("./actions");
    render(<VersionsView connectionId="c1" agentName="Bank Reconciliation" data={data} files={files} />);
    await userEvent.click(screen.getByRole("checkbox", { name: "Apply fixes that don't ask for more access automatically" }));
    expect(setAutoApplyAction).toHaveBeenCalledWith("c1", false);
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(buyer)/store/\[agentId\]/versions"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(buyer)/store/[agentId]/versions/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { CheckResult } from "@/lib/types";

async function run(call: () => Promise<unknown>, messages: Record<string, string>, fallback: string): Promise<ActionResult> {
  try {
    await call();
  } catch (e) {
    return actionError(e, messages, fallback);
  }
  revalidatePath("/store", "layout");
  return { ok: true };
}

export async function agreeUpgradeAction(connectionId: string, targetVersionId: string, bindings: Record<string, string>): Promise<ActionResult> {
  return run(() => backend.post(`/api/v1/connections/${connectionId}/upgrades`, { targetVersionId, bindings }),
    { "422": "The file's columns don't match what this version needs." }, "We couldn't record your agreement.");
}

export async function takeBackUpgradeAction(connectionId: string, upgradeId: string): Promise<ActionResult> {
  return run(() => backend.delete(`/api/v1/connections/${connectionId}/upgrades/${upgradeId}`),
    { "409": "This update was already applied." }, "We couldn't take it back.");
}

export async function declineUpgradeAction(connectionId: string, upgradeId: string | null, targetVersionId: string): Promise<ActionResult> {
  return run(async () => {
    const id = upgradeId ?? (await backend.post<{ id: string }>(`/api/v1/connections/${connectionId}/upgrades`, { targetVersionId, bindings: {}, decline: true })).id;
    return backend.post(`/api/v1/connections/${connectionId}/upgrades/${id}/decline`);
  }, {}, "We couldn't save that.");
}

export async function setAutoApplyAction(connectionId: string, on: boolean): Promise<ActionResult> {
  return run(() => backend.patch(`/api/v1/connections/${connectionId}`, { autoApplySafeUpdates: on }), {}, "We couldn't save that.");
}

export async function checkDatasetAction(datasetId: string, agentVersionId: string, resource: string): Promise<ActionResult<{ result: CheckResult }>> {
  try {
    const result = await backend.post<CheckResult>(`/api/v1/datasets/${datasetId}/check`, { agentVersionId, resource });
    return { ok: true, result };
  } catch (e) {
    return actionError(e, {}, "We couldn't check that file.");
  }
}
```

`src/app/(buyer)/store/[agentId]/versions/VersionsView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill, type Tone } from "@/components/ui/Pill";
import { dayLabel } from "@/lib/format";
import type { BuyerVersionState, CheckResult, ConnectionVersions, Dataset, VersionDiffRow } from "@/lib/types";
import { agreeUpgradeAction, checkDatasetAction, declineUpgradeAction, setAutoApplyAction, takeBackUpgradeAction } from "./actions";

const STATE: Record<BuyerVersionState, { tone: Tone; label: string }> = {
  IN_REVIEW: { tone: "wa", label: "In review" },
  AVAILABLE: { tone: "b", label: "Available" },
  YOURS: { tone: "b", label: "Yours" },
  APPLIED_FOR_YOU: { tone: "n", label: "Applied for you" },
  PAST: { tone: "n", label: "Past" },
};
const TAG: Record<VersionDiffRow["tag"], { tone: Tone; label: string }> = {
  NEW: { tone: "b", label: "New" }, SAME: { tone: "n", label: "Same" }, REMOVED: { tone: "n", label: "Removed" }, SAFER: { tone: "ok", label: "Safer" },
};

export function VersionsView({ connectionId, agentName, data, files }: {
  connectionId: string | null; agentName: string; data: ConnectionVersions; files: Dataset[];
}) {
  const [selected, setSelected] = useState(data.versions[0]?.versionId);
  const [bindings, setBindings] = useState<Record<string, string>>({});
  const [checks, setChecks] = useState<Record<string, CheckResult>>({});
  const [allowed, setAllowed] = useState(false);
  const [auto, setAuto] = useState(data.autoApplySafeUpdates);
  const [message, setMessage] = useState<string | null>(null);
  const current = data.versions.find((v) => v.versionId === selected)!;
  const yours = data.versions.find((v) => v.buyerState === "YOURS");
  const pending = data.pendingUpgrade?.status === "WAITING_FOR_APPROVAL" ? data.pendingUpgrade : null;
  const needsConsent = current.diff.some((d) => d.tag === "NEW") && (current.buyerState === "IN_REVIEW" || current.buyerState === "AVAILABLE");
  const allPlugged = current.newResources.every((r) => bindings[r.name] && checks[r.name]?.columnsMatch);

  async function plug(resource: string, datasetId: string) {
    setBindings((b) => ({ ...b, [resource]: datasetId }));
    const r = await checkDatasetAction(datasetId, current.versionId, resource);
    if (r.ok) setChecks((c) => ({ ...c, [resource]: r.result }));
    else setMessage(r.error);
  }

  const say = (r: { ok: boolean; error?: string }) => { if (!r.ok) setMessage(r.error ?? null); };

  return (
    <div className="grid grid-cols-[320px_minmax(0,1fr)] gap-6 items-start">
      <Panel className="p-3">
        <p className="px-2 pb-2 text-xs text-ink3">Newest first. Your workspace runs the one marked yours.</p>
        <ul aria-label="Versions" className="flex flex-col gap-1">
          {data.versions.map((v) => (
            <li key={v.versionId}>
              <button type="button" aria-pressed={v.versionId === selected} onClick={() => setSelected(v.versionId)}
                className={`w-full text-left px-3 py-2.5 rounded-xl border ${v.versionId === selected ? "border-blue shadow-[0_0_0_3px_var(--sky2)]" : "border-transparent hover:bg-sky"}`}>
                <span className="flex items-center gap-2"><b className="font-mono font-medium text-sm">{v.version}</b><Pill tone={STATE[v.buyerState].tone}>{STATE[v.buyerState].label}</Pill></span>
                <span className="block text-xs text-ink3 mt-0.5">{v.summary} · {dayLabel(v.date)}</span>
              </button>
            </li>
          ))}
        </ul>
      </Panel>

      <div className="flex flex-col gap-4 min-w-0">
        <Panel className="p-5 flex flex-col gap-2">
          <div className="flex items-center gap-2"><h2 className="font-mono text-lg">{current.version}</h2><Pill tone={STATE[current.buyerState].tone}>{STATE[current.buyerState].label}</Pill></div>
          {current.releaseNotes && <p className="text-[13px] text-ink2">{current.releaseNotes}</p>}
        </Panel>
        <Panel aria-label={needsConsent ? "What changes for you" : "What it can touch"} className="p-5">
          <PanelTitle>{needsConsent ? "What changes for you" : "What it can touch"}</PanelTitle>
          <ul className="mt-2">
            {current.diff.map((d) => (
              <li key={d.text} className="flex items-center gap-3 py-2 border-t border-line2 first:border-t-0 text-[13px]">
                <span className="flex-1">{d.text}</span><Pill tone={TAG[d.tag].tone} dot={false}>{TAG[d.tag].label}</Pill>
              </li>
            ))}
          </ul>
        </Panel>

        {connectionId && needsConsent && !pending && (
          <Panel className="p-5 flex flex-col gap-3 border-wa-line">
            <PanelTitle>Update to {current.version}</PanelTitle>
            <p className="text-[13px] text-ink2">
              {current.version} wants {current.newResources.length === 1 ? "one more file" : "more files"}.
              {current.buyerState === "IN_REVIEW" ? " Karya is still reviewing it, so you can agree now and switch automatically once it's approved." : ""}
            </p>
            {current.newResources.map((r) => {
              const check = checks[r.name];
              return (
                <div key={r.name} className="flex flex-col gap-1.5">
                  <label className="flex flex-col gap-1 text-[13px]">
                    <span className="text-ink2">File for {r.displayName ?? r.name}</span>
                    <select aria-label={`File for ${r.displayName ?? r.name}`} value={bindings[r.name] ?? ""} onChange={(e) => plug(r.name, e.target.value)} className="h-9 px-2 rounded-lg border border-line bg-surface">
                      <option value="" disabled>Choose from your files</option>
                      {files.map((f) => <option key={f.id} value={f.id}>{f.displayName}</option>)}
                    </select>
                  </label>
                  {check && (check.columnsMatch
                    ? <span className="text-xs text-ok">{check.rowCount} rows, columns match</span>
                    : <span className="text-xs text-bad">Missing columns: {check.missingColumns.join(", ")}</span>)}
                </div>
              );
            })}
            <label className="flex items-start gap-2 text-[13px]">
              <input type="checkbox" className="w-4 h-4 mt-0.5 accent-blue" checked={allowed} onChange={(e) => setAllowed(e.target.checked)} />
              I allow {agentName} {current.version} to read this file. Everything else stays the same.
            </label>
            {message && <p role="alert" className="text-[13px] text-bad">{message}</p>}
            <div className="flex gap-2">
              <Button disabled={!allowed || !allPlugged} onClick={async () => say(await agreeUpgradeAction(connectionId, current.versionId, bindings))}>Agree and update when approved</Button>
              {yours && <Button variant="secondary" onClick={async () => say(await declineUpgradeAction(connectionId, null, current.versionId))}>Stay on {yours.version}</Button>}
            </div>
          </Panel>
        )}

        {connectionId && pending && pending.targetVersionId === current.versionId && (
          <Panel className="p-5 flex flex-col gap-2 border-blue-3">
            <PanelTitle>You&apos;re set for {current.version}</PanelTitle>
            <p className="text-[13px] text-ink2">You keep running {yours?.version} until Karya approves {current.version}, then your next run uses it. You can take this back any time before then.</p>
            <Button variant="secondary" size="sm" onClick={async () => say(await takeBackUpgradeAction(connectionId, pending.id))}>Take it back</Button>
          </Panel>
        )}

        {connectionId && (
          <label className="flex items-center gap-2 text-[13px]">
            <input type="checkbox" className="w-4 h-4 accent-blue" checked={auto} onChange={async (e) => { setAuto(e.target.checked); say(await setAutoApplyAction(connectionId, e.target.checked)); }} />
            Apply fixes that don&apos;t ask for more access automatically
          </label>
        )}
      </div>
    </div>
  );
}
```

`src/app/(buyer)/store/[agentId]/versions/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ConnectionVersions, Dataset, StoreAgent } from "@/lib/types";
import { StoreHeader } from "../StoreHeader";
import { VersionsView } from "./VersionsView";

export default async function VersionsPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agent = await backend.get<StoreAgent>(`/api/v1/catalog/agents/${agentId}`);
  const connectionId = agent.myConnection?.id ?? null;
  const [data, files] = connectionId
    ? await Promise.all([
        backend.get<ConnectionVersions>(`/api/v1/connections/${connectionId}/versions`),
        backend.get<Dataset[]>("/api/v1/datasets?include=usage"),
      ])
    : [{
        autoApplySafeUpdates: true,
        pendingUpgrade: null,
        versions: [{
          versionId: agent.liveVersion.id, version: agent.liveVersion.versionNumber, buyerState: "AVAILABLE" as const,
          date: new Date().toISOString(), summary: agent.liveVersion.manifest.summary ?? null,
          releaseNotes: agent.liveVersion.manifest.releaseNotes ?? null, diff: [], scorecard: null, newResources: [],
        }],
      }, [] as Dataset[]];
  return (
    <>
      <StoreHeader agent={agent} active="versions" />
      <VersionsView connectionId={connectionId} agentName={agent.name} data={data} files={files} />
    </>
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): versions with change tags, and agreeing to an update before it's approved"
```

---

### Task 6: Files and where they go

**Files:**
- Create: `src/app/api/datasets/upload/route.ts`, `src/app/(buyer)/files/page.tsx`, `FilesView.tsx`, `actions.ts`
- Test: create `src/app/(buyer)/files/FilesView.test.tsx`, `src/app/api/datasets/upload/route.test.ts`

**Interfaces:**
- Consumes: `GET /api/v1/datasets?include=usage`, `POST /api/v1/datasets` (multipart), `DELETE /api/v1/datasets/{id}`, `DELETE /api/v1/connections/{id}/bindings/{resource}`, `GET /api/v1/orgs/{orgId}/storage`; `formatBytes` (Task 1).
- Produces:
  - `POST /api/datasets/upload` (Next route), which forwards the multipart body with the session token. Status and body pass through. Uploads over 50 MB get 413.
  - `unplugAction(connectionId, resource): ActionResult`, `deleteFileAction(id): ActionResult`.
  - `FilesView({ files, storage })`.

- [ ] **Step 1: Write the failing tests**

`src/app/api/datasets/upload/route.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (name === "karya_session" ? { value: "tok" } : undefined) }),
}));

import { POST } from "./route";

describe("upload proxy", () => {
  afterEach(() => vi.restoreAllMocks());

  it("forwards the file to the backend with the session token", async () => {
    process.env.BACKEND_URL = "http://backend";
    const fetchSpy = vi.spyOn(global, "fetch").mockResolvedValue(new Response(JSON.stringify({ id: "d1" }), { status: 201 }));
    const form = new FormData();
    form.append("file", new Blob(["date,narration,amount\n"], { type: "text/csv" }), "aug.csv");
    const res = await POST(new Request("http://app/api/datasets/upload", { method: "POST", body: form }));

    expect(res.status).toBe(201);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(url).toBe("http://backend/api/v1/datasets");
    expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer tok");
  });

  it("refuses files over 50 MB before sending them", async () => {
    const fetchSpy = vi.spyOn(global, "fetch");
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(51 * 1024 * 1024)]), "huge.csv");
    const res = await POST(new Request("http://app/api/datasets/upload", { method: "POST", body: form }));
    expect(res.status).toBe(413);
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
```

`src/app/(buyer)/files/FilesView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FilesView } from "./FilesView";
import type { Dataset } from "@/lib/types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("./actions", () => ({
  unplugAction: vi.fn().mockResolvedValue({ ok: true }),
  deleteFileAction: vi.fn().mockResolvedValue({ ok: true }),
}));

const statement: Dataset = {
  id: "d1", displayName: "Aug 2026 statement.csv", sourceLabel: "HDFC current account", format: "csv", rowCount: 412, sizeBytes: 48 * 1024,
  createdAt: "2026-09-01T00:00:00Z", uploadedBy: "Priya Shah", purgeAfter: null,
  bindings: [{ connectionId: "c1", agentName: "Bank Reconciliation", version: "1.3.0", resource: "bank_statements", access: "READ" }],
  pendingRequests: [],
};
const vendorMaster: Dataset = {
  ...statement, id: "d9", displayName: "Vendor master.xlsx", sourceLabel: null, rowCount: 640, sizeBytes: 96 * 1024, bindings: [],
  pendingRequests: [{ connectionId: "c1", agentId: "a1", agentName: "Bank Reconciliation", version: "1.4.0", resource: "vendor_master", reason: "To check vendor GSTINs", upgradeId: null }],
};

describe("FilesView", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ({ id: "d2" }) });
  });

  it("lists files with rows, where they are plugged in, and storage", () => {
    render(<FilesView files={[statement, vendorMaster]} storage={{ usedBytes: 1.1 * 1024 * 1024, limitBytes: 5 * 1024 ** 3 }} />);
    expect(screen.getByText("1.1 MB of 5 GB used")).toBeInTheDocument();
    const row = screen.getByRole("row", { name: /Aug 2026 statement/ });
    expect(within(row).getByText("412")).toBeInTheDocument();
    expect(within(row).getByText("Bank Reconciliation")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Vendor master/ })).getByText("1 request")).toBeInTheDocument();
  });

  it("explains where the selected file goes", async () => {
    render(<FilesView files={[statement, vendorMaster]} storage={{ usedBytes: 0, limitBytes: 1 }} />);
    await userEvent.click(screen.getByRole("button", { name: /Vendor master/ }));
    const panel = screen.getByRole("region", { name: "Where this file goes" });
    expect(within(panel).getByText("Asks to read this to check vendor GSTINs")).toBeInTheDocument();
    expect(within(panel).getByRole("link", { name: "Review the request" })).toHaveAttribute("href", "/store/a1/versions");
  });

  it("uploads through the proxy and reports it", async () => {
    render(<FilesView files={[]} storage={{ usedBytes: 0, limitBytes: 1 }} />);
    const file = new File(["date,narration,amount\n"], "aug.csv", { type: "text/csv" });
    await userEvent.upload(screen.getByLabelText("Upload file"), file);
    expect(fetch).toHaveBeenCalledWith("/api/datasets/upload", expect.objectContaining({ method: "POST" }));
    expect(await screen.findByRole("status")).toHaveTextContent("aug.csv uploaded");
  });

  it("asks before unplugging and deleting", async () => {
    const { unplugAction } = await import("./actions");
    render(<FilesView files={[statement]} storage={{ usedBytes: 0, limitBytes: 1 }} />);
    await userEvent.click(screen.getByRole("button", { name: "Unplug and delete" }));
    await userEvent.click(screen.getByRole("button", { name: "Yes, unplug and delete" }));
    expect(unplugAction).toHaveBeenCalledWith("c1", "bank_statements");
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/app/api/datasets "src/app/(buyer)/files"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/api/datasets/upload/route.ts`:

```ts
import { cookies } from "next/headers";
import { SESSION_COOKIE } from "@/lib/session";

export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

const TOO_LARGE = () => Response.json({ code: "FILE_TOO_LARGE", detail: "Files can be up to 50 MB." }, { status: 413 });

/** Forwards a multipart upload to the backend with the session token; the browser never sees the token. */
export async function POST(request: Request): Promise<Response> {
  // Cheap early refusal when the client declares a size; the file check below is the real one.
  if (Number(request.headers.get("content-length") ?? "0") > MAX_UPLOAD_BYTES + 1024 * 1024) return TOO_LARGE();
  const form = await request.formData();
  for (const value of form.values()) {
    if (value instanceof Blob && value.size > MAX_UPLOAD_BYTES) return TOO_LARGE();
  }
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/datasets`, {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form,
  });
  return new Response(res.body, { status: res.status, headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" } });
}
```

`src/app/(buyer)/files/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";

export async function unplugAction(connectionId: string, resource: string): Promise<ActionResult> {
  try {
    await backend.delete(`/api/v1/connections/${connectionId}/bindings/${resource}`);
  } catch (e) {
    return actionError(e, {}, "We couldn't unplug that file.");
  }
  revalidatePath("/files");
  return { ok: true };
}

export async function deleteFileAction(id: string): Promise<ActionResult> {
  try {
    await backend.delete(`/api/v1/datasets/${id}`);
  } catch (e) {
    return actionError(e, { "409": "Unplug this file from every agent first." }, "We couldn't delete that file.");
  }
  revalidatePath("/files");
  return { ok: true };
}
```

`src/app/(buyer)/files/FilesView.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { formatBytes } from "@/lib/bytes";
import { dayLabel } from "@/lib/format";
import type { Dataset, StorageUse } from "@/lib/types";
import { deleteFileAction, unplugAction } from "./actions";

export function FilesView({ files, storage }: { files: Dataset[]; storage: StorageUse }) {
  const router = useRouter();
  const [selected, setSelected] = useState(files[0]?.id ?? null);
  const [confirming, setConfirming] = useState(false);
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const current = files.find((f) => f.id === selected) ?? null;

  async function upload(file: File) {
    const form = new FormData();
    form.append("file", file);
    form.append("displayName", file.name);
    const res = await fetch("/api/datasets/upload", { method: "POST", body: form });
    if (res.ok) {
      setMessage({ tone: "ok", text: `${file.name} uploaded.` });
      router.refresh();
    } else {
      const body = await res.json().catch(() => null);
      setMessage({ tone: "bad", text: body?.detail ?? (res.status === 413 ? "Files can be up to 50 MB." : "The upload didn't work.") });
    }
  }

  async function unplugAndDelete(file: Dataset) {
    setConfirming(false);
    for (const b of file.bindings) {
      const r = await unplugAction(b.connectionId, b.resource);
      if (!r.ok) return setMessage({ tone: "bad", text: r.error });
    }
    const r = await deleteFileAction(file.id);
    setMessage(r.ok ? { tone: "ok", text: `${file.displayName} is unplugged and will be deleted after 30 days.` } : { tone: "bad", text: r.error });
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight">Files</h1>
          <p className="text-ink2">An agent only sees the files you plug into it.</p>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-xs text-ink3">{formatBytes(storage.usedBytes)} of {formatBytes(storage.limitBytes)} used</span>
          <label className="h-[38px] px-4 rounded-[10px] bg-blue text-white text-[13px] font-semibold grid place-items-center cursor-pointer">
            Upload file
            <input type="file" aria-label="Upload file" accept=".csv,.json,.xlsx,.pdf" className="sr-only"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f); e.target.value = ""; }} />
          </label>
        </div>
      </div>
      {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}

      <div className="grid grid-cols-[minmax(0,1fr)_380px] gap-6 items-start">
        <Panel className="overflow-hidden">
          <table className="w-full text-[13px]">
            <thead><tr className="text-xs text-ink3"><th className="text-left font-normal px-5 py-2">File</th><th className="text-left font-normal py-2">Rows</th><th className="text-left font-normal py-2">Plugged into</th><th className="text-left font-normal py-2 pr-5">Added</th></tr></thead>
            <tbody>
              {files.map((f) => (
                <tr key={f.id} aria-label={f.displayName} className={`border-t border-line2 ${f.id === selected ? "bg-sky" : ""}`}>
                  <td className="px-5 py-3">
                    <button type="button" onClick={() => setSelected(f.id)} className="text-left">
                      <span className="block font-medium">{f.displayName}</span>
                      <span className="text-xs text-ink3">{f.sourceLabel ?? f.format.toUpperCase()}</span>
                    </button>
                  </td>
                  <td className="py-3 font-mono">{f.rowCount?.toLocaleString("en-IN") ?? "—"}</td>
                  <td className="py-3">
                    {f.pendingRequests.length > 0 ? <Pill tone="wa">{f.pendingRequests.length} request{f.pendingRequests.length > 1 ? "s" : ""}</Pill>
                      : f.bindings.length > 0 ? <Pill tone="b">{f.bindings[0].agentName}</Pill>
                      : <span className="text-ink3">Not plugged in</span>}
                  </td>
                  <td className="py-3 pr-5 text-ink3">{dayLabel(f.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {files.length === 0 && <p className="px-5 py-6 text-[13px] text-ink3">No files yet. Upload a statement, an invoice export or any CSV an agent needs.</p>}
        </Panel>

        {current && (
          <Panel aria-label="Where this file goes" className="p-5 flex flex-col gap-3">
            <PanelTitle>Where this file goes</PanelTitle>
            <div className="text-[13px]"><b>{current.displayName}</b><div className="text-xs text-ink3">{current.rowCount !== null ? `${current.rowCount.toLocaleString("en-IN")} rows, ` : ""}{current.sourceLabel ?? current.format.toUpperCase()}</div></div>
            {current.bindings.map((b) => (
              <div key={`${b.connectionId}-${b.resource}`} className="flex items-center gap-3 text-[13px] border-t border-line2 pt-2">
                <Pill tone="b">Reads it</Pill><span className="flex-1">{b.agentName}</span><span className="text-xs text-ink3">Reads rows, cannot change them</span>
              </div>
            ))}
            {current.pendingRequests.map((r) => (
              <div key={`${r.connectionId}-${r.resource}`} className="flex flex-col gap-1 text-[13px] border-t border-line2 pt-2">
                <span className="flex items-center gap-2"><Pill tone="wa">Waiting for your answer</Pill>{r.agentName} {r.version}</span>
                <span className="text-ink2">Asks to read this{r.reason ? ` ${r.reason.replace(/^To /, "to ")}` : ""}</span>
                <Link href={`/store/${r.agentId}/versions`} className="text-blue font-semibold">Review the request</Link>
              </div>
            ))}
            <div className="border-t border-line2 pt-2">
              <Kv label="Size" value={formatBytes(current.sizeBytes)} />
              <Kv label="Used in developer tests" value="Never" mono={false} />
              <Kv label="If you unplug it" value="Deleted after 30 days" mono={false} />
            </div>
            {confirming ? (
              <div className="flex gap-2">
                <Button variant="danger" size="sm" onClick={() => unplugAndDelete(current)}>Yes, unplug and delete</Button>
                <Button variant="secondary" size="sm" onClick={() => setConfirming(false)}>Keep it</Button>
              </div>
            ) : (
              <Button variant="secondary" size="sm" onClick={() => setConfirming(true)}>Unplug and delete</Button>
            )}
          </Panel>
        )}
      </div>
    </div>
  );
}
```

The request line reads "Asks to read this to check vendor GSTINs", which the test matches.

`src/app/(buyer)/files/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { Dataset, StorageUse } from "@/lib/types";
import { FilesView } from "./FilesView";

export default async function FilesPage() {
  const session = await getSession();
  if (!session) return null;
  const [files, storage] = await Promise.all([
    backend.get<Dataset[]>("/api/v1/datasets?include=usage"),
    backend.get<StorageUse>(`/api/v1/orgs/${session.orgId}/storage`),
  ]);
  return <FilesView files={files} storage={storage} />;
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): file library with where each file goes, uploads through a token-safe proxy"
```

---

### Task 7: Plug in data on the add-agent screen

**Files:**
- Create: `src/app/(buyer)/connect/[agentId]/[versionId]/DataSockets.tsx`
- Modify: `ConnectSetup.tsx`, `ConnectSetup.test.tsx`, `page.tsx`, `actions.ts` in `src/app/(buyer)/connect/[agentId]/[versionId]/`
- Test: create `DataSockets.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/agents/{a}/versions/{v}/resources` → `ResourceInfo[]`, `GET /api/v1/datasets?include=usage`, `checkDatasetAction` (Task 5), the upload proxy (Task 6).
- Produces:
  - `DataSockets({ versionId, resources, files, value, onChange })`, where `value` is `Record<resource, { datasetId: string; check: CheckResult }>`.
  - `CreateConnectionInput.bindings: Record<string, string>`.
  - "Add and run now" stays disabled until every READ resource has a file whose columns match.

- [ ] **Step 1: Write the failing test**

`src/app/(buyer)/connect/[agentId]/[versionId]/DataSockets.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { DataSockets, type SocketValue } from "./DataSockets";
import type { Dataset, ResourceInfo } from "@/lib/types";

vi.mock("../../../store/[agentId]/versions/actions", () => ({
  checkDatasetAction: vi.fn()
    .mockResolvedValueOnce({ ok: true, result: { rowCount: 412, columnsMatch: true, missingColumns: [], rowErrors: [] } })
    .mockResolvedValueOnce({ ok: true, result: { rowCount: 389, columnsMatch: false, missingColumns: ["party"], rowErrors: [] } }),
}));

const resources: ResourceInfo[] = [
  { name: "bank_statements", displayName: "Bank statements", description: null, format: "csv", kind: "tabular", requiredColumns: ["date", "narration", "amount"] },
  { name: "invoices", displayName: "Open invoices", description: null, format: "csv", kind: "tabular", requiredColumns: ["number", "party", "amount"] },
];
const file = (id: string, name: string): Dataset => ({
  id, displayName: name, sourceLabel: null, format: "csv", rowCount: 1, sizeBytes: 1, createdAt: "2026-09-01T00:00:00Z",
  uploadedBy: null, purgeAfter: null, bindings: [], pendingRequests: [],
});

function Harness() {
  const [value, setValue] = useState<Record<string, SocketValue>>({});
  return <DataSockets versionId="v13" resources={resources} files={[file("d1", "Aug statement.csv"), file("d2", "Invoices.csv")]} value={value} onChange={setValue} />;
}

describe("DataSockets", () => {
  it("shows what each socket needs and checks a plugged file", async () => {
    render(<Harness />);
    expect(screen.getByText("Needs: date, narration, amount")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("File for Bank statements"), "d1");
    expect(await screen.findByText("412 rows, columns match")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("File for Open invoices"), "d2");
    expect(await screen.findByText("Missing columns: party")).toBeInTheDocument();
  });
});
```

Add to `ConnectSetup.test.tsx`: pass `resources={[]}` and `files={[]}` to every existing render (a manifest whose READ resources are all satisfied, or none), plus this test:

```tsx
  it("keeps Add and run now disabled until every file is plugged in and matches", () => {
    render(
      <ConnectSetup agentId="a1" agentName="Bank Reconciliation" version="1.3.0" versionId="v13" manifest={manifest} approvers={approvers}
        resources={[{ name: "bank_statements", displayName: "Bank statements", description: null, format: "csv", kind: "tabular", requiredColumns: ["date"] }]}
        files={[]} />,
    );
    expect(screen.getByRole("button", { name: "Add and run now" })).toBeDisabled();
    expect(screen.getByText("Plug in every file to continue.")).toBeInTheDocument();
  });
```

and change `renderSetup()` to pass `versionId="v13" resources={[]} files={[]}`. Update the expected `createConnectionAction` call to include `bindings: {}`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(buyer)/connect"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(buyer)/connect/[agentId]/[versionId]/DataSockets.tsx`:

```tsx
"use client";

import { Panel, PanelTitle } from "@/components/ui/Panel";
import type { CheckResult, Dataset, ResourceInfo } from "@/lib/types";
import { checkDatasetAction } from "../../../store/[agentId]/versions/actions";

export interface SocketValue {
  datasetId: string;
  check: CheckResult | null;
}

export function socketsReady(resources: ResourceInfo[], value: Record<string, SocketValue>): boolean {
  return resources.every((r) => value[r.name]?.check?.columnsMatch || (r.kind === "document" && value[r.name]));
}

export function DataSockets({
  versionId, resources, files, value, onChange,
}: {
  versionId: string; resources: ResourceInfo[]; files: Dataset[]; value: Record<string, SocketValue>; onChange: (v: Record<string, SocketValue>) => void;
}) {
  async function plug(resource: ResourceInfo, datasetId: string) {
    const next = { ...value, [resource.name]: { datasetId, check: null } };
    onChange(next);
    if (resource.kind === "document") return;
    const r = await checkDatasetAction(datasetId, versionId, resource.name);
    onChange({ ...next, [resource.name]: { datasetId, check: r.ok ? r.result : { rowCount: null, columnsMatch: false, missingColumns: [], rowErrors: [] } } });
  }

  return (
    <Panel className="p-5 flex flex-col gap-3">
      <PanelTitle>Plug in your files</PanelTitle>
      <p className="text-xs text-ink3">Each socket takes one file. Karya checks the columns before the agent ever sees it.</p>
      {resources.map((r) => {
        const v = value[r.name];
        const label = r.displayName ?? r.name;
        return (
          <div key={r.name} className="flex flex-col gap-1 border-t border-line2 pt-3 first:border-t-0 first:pt-0">
            <label className="flex flex-col gap-1 text-[13px]">
              <span className="font-medium">{label}</span>
              {r.requiredColumns.length > 0 && <span className="text-xs text-ink3">Needs: {r.requiredColumns.join(", ")}</span>}
              <select aria-label={`File for ${label}`} value={v?.datasetId ?? ""} onChange={(e) => plug(r, e.target.value)} className="h-9 px-2 rounded-lg border border-line bg-surface">
                <option value="" disabled>Choose from your files</option>
                {files.map((f) => <option key={f.id} value={f.id}>{f.displayName}</option>)}
              </select>
            </label>
            {v?.check && (v.check.columnsMatch
              ? <span className="text-xs text-ok">{v.check.rowCount?.toLocaleString("en-IN")} rows, columns match</span>
              : <span className="text-xs text-bad">{v.check.missingColumns.length ? `Missing columns: ${v.check.missingColumns.join(", ")}` : "This file couldn't be read. Try another."}</span>)}
          </div>
        );
      })}
      <a href="/files" className="text-[13px] text-blue font-semibold">Upload a file</a>
    </Panel>
  );
}
```

`ConnectSetup` changes:
- Add props `versionId: string`, `resources: ResourceInfo[]` (the READ resources of this version) and `files: Dataset[]`.
- Add state `const [sockets, setSockets] = useState<Record<string, SocketValue>>({});`.
- Render `<DataSockets versionId={versionId} resources={resources} files={files} value={sockets} onChange={setSockets} />` as the first panel in the left column.
- Compute `const ready = socketsReady(resources.filter((r) => granted.includes(r.name)), sockets);`.
- Disable both submit buttons unless `ready` (in addition to `granted.length`), and show `<p className="text-xs text-ink3">Plug in every file to continue.</p>` above them when `!ready`.
- Add `bindings: Object.fromEntries(Object.entries(sockets).map(([k, v]) => [k, v.datasetId]))` to the `createConnectionAction` input.

`actions.ts`: add `bindings: Record<string, string>` to `CreateConnectionInput`, and add `"422": "One of the files doesn't match what the agent needs. The agent was not added."` to the messages.

`page.tsx`: also fetch `GET /api/v1/agents/{agentId}/versions/{versionId}/resources` and `GET /api/v1/datasets?include=usage`. Pass `versionId`, `resources.filter((r) => detail.manifest.scopes.some((s) => s.type === "READ" && s.resource === r.name))` and `files` to `ConnectSetup`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): plug files into an agent's sockets with column checks before it can run"
```

---

### Task 8: The live run as a flow

**Files:**
- Create: `src/app/api/runs/[runId]/flow/route.ts`, `src/app/(buyer)/runs/[runId]/LiveRun.tsx`, `GateCard.tsx`
- Modify: `src/app/(buyer)/runs/[runId]/page.tsx`, `actions.ts`
- Test: create `LiveRun.test.tsx`, `GateCard.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/runs/{id}` (with `agentId`, `agentName`, `displayId`), `GET /api/v1/runs/{id}/flow`, `GET /api/v1/runs/{id}/gates/{gateId}`, `POST …/gates/{gateId}/decision`, `GET /api/v1/catalog/agents/{agentId}` (manifest for the blocks); `flowBlocks`, `blockMetricText`, `FlowCanvas`; FE-1's `RunConsole` pieces (timeline, `DisputePanel`).
- Produces:
  - `/api/runs/{id}/flow` proxy.
  - `decideGateAction(runId, gateId, approve): ActionResult<{ gate: GateDetail }>`.
  - `GateCard({ runId, gate, canDecide, now? })`.
  - `LiveRun({ run, flow, blocks, gate, role })`, which polls the flow every 2 s until the run is terminal.
  - `page.tsx` renders `LiveRun` when the run's agent has a v2 manifest, and FE-1's `RunConsole` otherwise.

- [ ] **Step 1: Write the failing tests**

`src/app/(buyer)/runs/[runId]/GateCard.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { GateCard } from "./GateCard";
import type { GateDetail } from "@/lib/types";

vi.mock("./actions", () => ({
  decideGateAction: vi.fn().mockResolvedValue({ ok: true, gate: { status: "APPROVED" } }),
  decideAction: vi.fn(),
  openDisputeAction: vi.fn(),
}));

const gate: GateDetail = {
  id: "g1", action: "post_journal_entries", summary: "Post 142 journal entries for August", status: "PENDING",
  expiresAt: "2026-09-23T04:41:00Z", estimatedCharge: 284,
  preview: {
    columns: ["bank_line", "invoice_no", "amount"],
    rows: [{ bank_line: "NEFT ACME 0814", invoice_no: "INV-20931", amount: 48200 }, { bank_line: "UPI SHREE TRADERS", invoice_no: "INV-20944", amount: 12760 }],
    totalRows: 142, total: 3841260,
  },
};

describe("GateCard", () => {
  it("previews what will be written, its total, the cost and the time left", () => {
    render(<GateCard runId="r1" gate={gate} canDecide now={new Date("2026-09-23T04:15:00Z")} />);
    const card = screen.getByRole("region", { name: "Your decision" });
    expect(within(card).getByRole("heading", { name: "Post 142 journal entries for August?" })).toBeInTheDocument();
    expect(within(card).getByText("NEFT ACME 0814")).toBeInTheDocument();
    expect(within(card).getByText("140 more")).toBeInTheDocument();
    expect(within(card).getByText(/They total ₹38,41,260/)).toBeInTheDocument();
    expect(within(card).getByText(/Cost if approved: ₹284/)).toBeInTheDocument();
    expect(within(card).getByText(/26 minutes/)).toBeInTheDocument();
  });

  it("decides the gate", async () => {
    const { decideGateAction } = await import("./actions");
    render(<GateCard runId="r1" gate={gate} canDecide />);
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(decideGateAction).toHaveBeenCalledWith("r1", "g1", true);
  });
});
```

`src/app/(buyer)/runs/[runId]/LiveRun.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LiveRun } from "./LiveRun";
import { flowBlocks } from "@/lib/flow";
import type { RunDetailResponse, RunFlow } from "@/lib/types";
import { storeAgent } from "../../store/[agentId]/fixtures";

vi.mock("./actions", () => ({ decideGateAction: vi.fn(), decideAction: vi.fn(), openDisputeAction: vi.fn() }));

const blocks = flowBlocks(storeAgent.liveVersion.manifest);
const run: RunDetailResponse = {
  id: "r1", displayId: "R-2291", agentId: "a1", agentName: "Bank Reconciliation", versionNumber: "1.3.0",
  status: "FAILED", costCap: 500, totalCharged: 0, steps: [],
};
const failedEarly: RunFlow = {
  blocks: [
    { block: "read:bank_statements", state: "DONE", metrics: { rows: 412 } },
    { block: "read:invoices", state: "DONE", metrics: { rows: 389 } },
    { block: "llm", state: "FAILED" },
    { block: "draft:journal_entries", state: "IDLE" },
    { block: "gate:post_journal_entries", state: "IDLE" },
    { block: "write:journal_entries", state: "IDLE" },
    { block: "charge", state: "IDLE" },
  ],
  reachedBlock: "llm", spend: { charged: 0, cap: 500, held: 0 }, deadlineAt: null, blockedAttempts: 0,
};

function renderRun(flow: RunFlow) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } })}>
      <LiveRun run={run} flow={flow} blocks={blocks} gate={null} role="BUYER_ADMIN" />
    </QueryClientProvider>,
  );
}

describe("LiveRun", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => failedEarly });
  });

  it("names the run by its readable id and version", () => {
    renderRun(failedEarly);
    expect(screen.getByRole("heading", { name: /Bank Reconciliation/ })).toBeInTheDocument();
    expect(screen.getByText("R-2291")).toBeInTheDocument();
  });

  it("shows later blocks as not reached when the run fails early", () => {
    renderRun(failedEarly);
    expect(screen.getByText("412 lines read")).toBeInTheDocument();
    expect(screen.getByText("Stopped here")).toBeInTheDocument();
    expect(screen.getByTestId("block-gate:post_journal_entries")).toHaveAttribute("data-state", "IDLE");
    expect(screen.getAllByText("Not reached").length).toBeGreaterThanOrEqual(3);
  });

  it("shows write and charge as skipped after a rejection", () => {
    const rejected: RunFlow = {
      ...failedEarly,
      blocks: failedEarly.blocks.map((b) =>
        b.block === "llm" ? { ...b, state: "DONE", metrics: { calls: 1, tokens: 18420 } }
        : b.block === "draft:journal_entries" ? { ...b, state: "DONE", metrics: { records: 142 } }
        : b.block === "gate:post_journal_entries" ? { ...b, state: "FAILED" }
        : b.block === "write:journal_entries" || b.block === "charge" ? { ...b, state: "SKIPPED" }
        : b),
    };
    renderRun(rejected);
    expect(screen.getByText("Rejected")).toBeInTheDocument();
    expect(screen.getAllByText("Skipped")).toHaveLength(2);
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(buyer)/runs/\[runId\]"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/api/runs/[runId]/flow/route.ts`:

```ts
import { proxyGet } from "../../../proxy";

export async function GET(_request: Request, { params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  return proxyGet(`/api/v1/runs/${runId}/flow`);
}
```

Append to `src/app/(buyer)/runs/[runId]/actions.ts` (add `GateDetail` to the type import):

```ts
export async function decideGateAction(runId: string, gateId: string, approve: boolean): Promise<ActionResult<{ gate: GateDetail }>> {
  try {
    const gate = await backend.post<GateDetail>(`/api/v1/runs/${runId}/gates/${gateId}/decision`, { decision: approve ? "APPROVE" : "REJECT" });
    revalidatePath(`/runs/${runId}`);
    return { ok: true, gate };
  } catch (e) {
    return actionError(e, {
      NOT_AN_APPROVER: "You're not one of the approvers for this agent.",
      "409": "This request has closed. Someone may have decided already, or it ran out of time.",
    }, "We couldn't record your decision. Try again.");
  }
}
```

`src/app/(buyer)/runs/[runId]/GateCard.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { inr, minutesUntil } from "@/lib/format";
import type { GateDetail } from "@/lib/types";
import { decideGateAction } from "./actions";

const cell = (v: unknown) => (typeof v === "number" ? inr(v) : String(v ?? ""));

export function GateCard({ runId, gate, canDecide, now = new Date() }: { runId: string; gate: GateDetail; canDecide: boolean; now?: Date }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [decided, setDecided] = useState<string | null>(null);
  const p = gate.preview;
  const more = p ? p.totalRows - p.rows.length : 0;

  async function decide(approve: boolean) {
    setBusy(true);
    const r = await decideGateAction(runId, gate.id, approve);
    setBusy(false);
    if (r.ok) setDecided(approve ? "You approved. Posting the approved entries now." : "You rejected. The drafts were thrown away and nothing was charged.");
    else setError(r.error);
  }

  return (
    <Panel aria-label="Your decision" className="p-5 border-[#EFD19E] shadow-wait flex flex-col gap-3">
      <h2 className="text-lg font-semibold">{gate.summary}?</h2>
      <p className="text-[13px] text-ink2">
        {p?.total !== null && p?.total !== undefined ? `They total ${inr(p.total)} across ${p.totalRows} rows. ` : ""}
        Nothing is written until someone approves.
      </p>
      {p && (
        <table className="w-full text-xs font-mono tabular-nums">
          <tbody>
            {p.rows.map((row, i) => (
              <tr key={i} className="border-t border-[rgba(158,98,9,.15)]">
                {p.columns.map((c, j) => <td key={c} className={`py-1.5 ${j === p.columns.length - 1 ? "text-right" : ""}`}>{cell(row[c])}</td>)}
              </tr>
            ))}
            {more > 0 && <tr className="border-t border-[rgba(158,98,9,.15)] text-ink3"><td className="py-1.5" colSpan={p.columns.length}>{more} more</td></tr>}
          </tbody>
        </table>
      )}
      <p className="text-xs text-ink3">
        {gate.estimatedCharge !== null ? `Cost if approved: ${inr(gate.estimatedCharge)}. ` : ""}
        The run stops by itself in {minutesUntil(gate.expiresAt, now)} minutes if nobody answers.
      </p>
      {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
      {decided ? <p role="status" className="text-[13px]">{decided}</p> : canDecide ? (
        <div className="flex gap-2.5">
          <Button disabled={busy} onClick={() => decide(true)}>Approve</Button>
          <Button variant="danger" disabled={busy} onClick={() => decide(false)}>Reject</Button>
        </div>
      ) : <p className="text-[13px]">Waiting on an approver.</p>}
    </Panel>
  );
}
```

`src/app/(buyer)/runs/[runId]/LiveRun.tsx`:

```tsx
"use client";

import { useQuery } from "@tanstack/react-query";
import { FlowCanvas } from "@/components/flow/FlowCanvas";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { isTerminalRunStatus, useRunPolling } from "@/components/useRunPolling";
import { clientFetch } from "@/lib/clientFetch";
import { blockMetricText, type FlowBlock } from "@/lib/flow";
import { clockTime, inr, minutesUntil } from "@/lib/format";
import { runStatusPill } from "@/lib/runStatus";
import { stepSentence } from "@/lib/runSteps";
import type { BlockState, GateDetail, RunDetailResponse, RunFlow, UserRole } from "@/lib/types";
import { DisputePanel } from "./DisputePanel";
import { GateCard } from "./GateCard";

export function LiveRun({ run: initialRun, flow: initialFlow, blocks, gate, role }: {
  run: RunDetailResponse; flow: RunFlow; blocks: FlowBlock[]; gate: GateDetail | null; role?: UserRole;
}) {
  const { data: runData } = useRunPolling(initialRun.id, initialRun);
  const run = runData ?? initialRun;
  const terminal = isTerminalRunStatus(run.status);
  const { data: flowData } = useQuery({
    queryKey: ["run-flow", run.id],
    initialData: initialFlow,
    refetchInterval: terminal ? false : 2000,
    queryFn: () => clientFetch<RunFlow>(`/api/runs/${run.id}/flow`),
  });
  const flow = flowData ?? initialFlow;
  const states: Record<string, BlockState> = {};
  const metrics: Record<string, string> = {};
  for (const b of blocks) {
    const f = flow.blocks.find((x) => x.block === b.id);
    const state = f?.state ?? "IDLE";
    states[b.id] = state;
    metrics[b.id] = blockMetricText(b, state, f?.metrics);
  }
  const pill = runStatusPill(run.status);
  const canDecide = role === "BUYER_ADMIN" || role === "BUYER_APPROVER";

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-6 items-start">
      <div className="flex flex-col gap-5 min-w-0">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-[26px] font-semibold tracking-tight">{run.agentName}</h1>
            <Pill tone={pill.tone}>{pill.label}</Pill>
          </div>
          <p className="text-ink2 mt-1">
            Run <span className="font-mono">{run.displayId}</span> on version {run.versionNumber}, the version you approved. Every step is recorded by Karya.
          </p>
        </div>
        <Panel className="p-8 dot-grid overflow-x-auto">
          <FlowCanvas label="What this run is doing" blocks={blocks} states={states} metrics={metrics} />
        </Panel>
        {gate && gate.status === "PENDING" && run.status === "AWAITING_APPROVAL" && <GateCard runId={run.id} gate={gate} canDecide={canDecide} />}
        <Panel className="p-5">
          <PanelTitle hint="Recorded by Karya as it happens, not reported by the agent">Timeline</PanelTitle>
          <ol className="mt-2">
            {run.steps.map((s, i) => (
              <li key={i} className="grid grid-cols-[62px_minmax(0,1fr)] gap-2.5 py-2 border-t border-line2 text-[13px] first:border-t-0">
                <span className="font-mono text-[11.5px] text-ink3 pt-0.5">{clockTime(s.createdAt)}</span>
                <span>{stepSentence(s)}</span>
              </li>
            ))}
          </ol>
        </Panel>
      </div>
      <aside className="flex flex-col gap-4">
        <Panel className="p-5 flex flex-col gap-2">
          <PanelTitle>Spend</PanelTitle>
          <div className="font-mono text-sm">{inr(flow.spend.charged)} of {inr(flow.spend.cap)}</div>
          <ProgressBar value={flow.spend.charged} max={flow.spend.cap} label="Charged against this run's limit" />
          <Kv label="Held in your wallet" value={inr(flow.spend.held)} />
          <Kv label="Time left in sandbox" value={terminal || !flow.deadlineAt ? "Closed" : `${minutesUntil(flow.deadlineAt)} min`} />
          <Kv label="Blocked attempts" value={flow.blockedAttempts} />
        </Panel>
        {role === "BUYER_ADMIN" && terminal && run.totalCharged > 0 && (
          <Panel className="p-5 flex flex-col gap-2">
            <PanelTitle>Something wrong?</PanelTitle>
            <DisputePanel runId={run.id} />
          </Panel>
        )}
      </aside>
    </div>
  );
}
```

Replace `src/app/(buyer)/runs/[runId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { flowBlocks } from "@/lib/flow";
import { getSession } from "@/lib/session";
import type { GateDetail, RunDetailResponse, RunFlow, StoreAgent } from "@/lib/types";
import { LiveRun } from "./LiveRun";
import { RunConsole } from "./RunConsole";

export default async function RunPage({ params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  const [run, session] = await Promise.all([backend.get<RunDetailResponse>(`/api/v1/runs/${runId}`), getSession()]);
  const agent = run.agentId ? await backend.get<StoreAgent>(`/api/v1/catalog/agents/${run.agentId}`).catch(() => null) : null;
  const manifest = agent?.liveVersion.manifest;
  if (!agent || !manifest?.resources) {
    return <RunConsole runId={runId} initial={run} role={session?.role} />;
  }
  const flow = await backend.get<RunFlow>(`/api/v1/runs/${runId}/flow`);
  const gateId = flow.blocks.find((b) => b.gateId && b.state === "WAITING")?.gateId;
  const gate = gateId ? await backend.get<GateDetail>(`/api/v1/runs/${runId}/gates/${gateId}`) : null;
  const names = Object.fromEntries(agent.llmModels.map((m) => [m.id, m.displayName]));
  return <LiveRun run={run} flow={flow} blocks={flowBlocks(manifest, names)} gate={gate} role={session?.role} />;
}
```

The blocks are drawn from the store's LIVE manifest. A run pinned to an older version whose blocks differ still shows its states for the blocks both versions share, and the rest as "Not reached". The contracts table lists the optional `manifest` field that makes this exact; when WS1 provides it, use `run.manifest ?? agent.liveVersion.manifest`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): live run drawn as a flow with block states, approval preview and sandbox limits"
```

---

### Task 9: Runs history with where each run got to

**Files:**
- Create: `src/app/(buyer)/runs/TodayTimeline.tsx`, `src/lib/runReason.ts`
- Modify: `src/app/(buyer)/runs/RunsHistory.tsx`, `RunsHistory.test.tsx`, `page.tsx`
- Test: create `src/lib/runReason.test.ts`, `src/app/(buyer)/runs/TodayTimeline.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/runs` → `RunList` (WS1); `MiniFlow` (FE-1).
- Produces:
  - `reachedText(item: RunListItem): string`, `statusText(item): { tone; label }`, `miniFlowSteps(item): FlowStepState[]`.
  - `TodayTimeline({ runs, now? })`.
  - `RunsHistory({ list })` now takes the server list with counts.

- [ ] **Step 1: Write the failing tests**

`src/lib/runReason.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { miniFlowSteps, reachedText, statusText } from "./runReason";
import type { RunListItem } from "./types";

const base: RunListItem = {
  id: "r", displayId: "R-2291", agentName: "Bank Reconciliation", version: "1.3.0", status: "COMPLETED", terminalReason: "FINISHED",
  reachedBlock: "charge", blocks: ["read:a", "llm", "gate:g", "write:w", "charge"],
  startedAt: "2026-09-23T04:00:00Z", completedAt: "2026-09-23T04:01:00Z", charged: 284, held: 0, decidedBy: { name: "Arjun Mehta" },
};

describe("run reasons", () => {
  it("describes where a run got to and why it stopped", () => {
    expect(reachedText(base)).toBe("Finished, approved by Arjun Mehta");
    expect(reachedText({ ...base, status: "AWAITING_APPROVAL", terminalReason: null, reachedBlock: "gate:g" })).toBe("Waiting at approval");
    expect(reachedText({ ...base, status: "FAILED", terminalReason: "COST_CAP_REACHED", reachedBlock: "llm" })).toBe("Stopped while thinking");
    expect(statusText({ ...base, status: "FAILED", terminalReason: "COST_CAP_REACHED" })).toEqual({ tone: "bad", label: "Stopped at limit" });
    expect(statusText({ ...base, status: "EXPIRED", terminalReason: "GATE_EXPIRED" })).toEqual({ tone: "n", label: "No answer in time" });
  });
  it("draws a mini flow up to the reached block", () => {
    expect(miniFlowSteps({ ...base, status: "RUNNING", terminalReason: null, reachedBlock: "llm" })).toEqual(["done", "run", "idle", "idle", "idle"]);
    expect(miniFlowSteps({ ...base, status: "AWAITING_APPROVAL", terminalReason: null, reachedBlock: "gate:g" })).toEqual(["done", "done", "wait", "idle", "idle"]);
    expect(miniFlowSteps({ ...base, status: "FAILED", terminalReason: "AGENT_FAILED", reachedBlock: "llm" })).toEqual(["done", "bad", "idle", "idle", "idle"]);
    expect(miniFlowSteps(base)).toEqual(["done", "done", "done", "done", "done"]);
  });
});
```

`src/app/(buyer)/runs/TodayTimeline.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TodayTimeline } from "./TodayTimeline";
import type { RunListItem } from "@/lib/types";

const item = (id: string, agent: string, start: string, end: string | null): RunListItem => ({
  id, displayId: id, agentName: agent, version: "1", status: end ? "COMPLETED" : "RUNNING", terminalReason: end ? "FINISHED" : null,
  reachedBlock: null, blocks: [], startedAt: start, completedAt: end, charged: 0, held: 0, decidedBy: null,
});

describe("TodayTimeline", () => {
  it("draws one bar per run today, grouped by agent", () => {
    render(
      <TodayTimeline
        now={new Date("2026-09-23T05:00:00Z")}
        runs={[
          item("R-1", "Bank Reconciliation", "2026-09-23T03:30:00Z", "2026-09-23T03:40:00Z"),
          item("R-2", "Bank Reconciliation", "2026-09-23T04:10:00Z", null),
          item("R-3", "Expense Policy Checker", "2026-09-22T04:10:00Z", "2026-09-22T04:12:00Z"),
        ]}
      />,
    );
    expect(screen.getByText("Bank Reconciliation")).toBeInTheDocument();
    expect(screen.queryByText("Expense Policy Checker")).not.toBeInTheDocument();
    expect(screen.getAllByRole("link")).toHaveLength(2);
    expect(screen.getByRole("link", { name: /R-2, still running/ })).toHaveAttribute("href", "/runs/R-2");
  });
});
```

Replace `src/app/(buyer)/runs/RunsHistory.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RunsHistory } from "./RunsHistory";
import type { RunList } from "@/lib/types";

const list: RunList = {
  counts: { all: 2, needs_you: 1, running: 0, finished: 1, stopped: 0 },
  items: [
    { id: "r1", displayId: "R-2291", agentName: "Bank Reconciliation", version: "1.3.0", status: "AWAITING_APPROVAL", terminalReason: null,
      reachedBlock: "gate:g", blocks: ["read:a", "gate:g", "charge"], startedAt: "2026-09-23T04:11:00Z", completedAt: null, charged: 0, held: 500, decidedBy: null },
    { id: "r2", displayId: "R-2288", agentName: "Expense Policy Checker", version: "3.4.0", status: "COMPLETED", terminalReason: "FINISHED",
      reachedBlock: "charge", blocks: ["read:a", "charge"], startedAt: "2026-09-23T04:30:00Z", completedAt: "2026-09-23T04:31:00Z", charged: 428, held: 0, decidedBy: null },
  ],
};

describe("RunsHistory", () => {
  it("uses the server's counts and filter links", () => {
    render(<RunsHistory list={list} filter="all" />);
    expect(screen.getByRole("link", { name: "Needs you 1" })).toHaveAttribute("href", "/runs?group=needs_you");
    expect(screen.getByRole("link", { name: "All 2" })).toHaveAttribute("aria-current", "page");
  });

  it("shows readable ids, where each run got to, and money held", () => {
    render(<RunsHistory list={list} filter="all" />);
    const row = screen.getByRole("row", { name: /R-2291/ });
    expect(within(row).getByText("Waiting at approval")).toBeInTheDocument();
    expect(within(row).getByText("₹500 held")).toBeInTheDocument();
    expect(within(row).getByLabelText("Waiting at approval, step 2 of 3")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/runReason.test.ts "src/app/(buyer)/runs/RunsHistory" "src/app/(buyer)/runs/TodayTimeline"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/lib/runReason.ts`:

```ts
import type { FlowStepState } from "@/components/ui/MiniFlow";
import type { Tone } from "@/components/ui/Pill";
import type { RunListItem } from "./types";

const PLACE: Record<string, string> = { read: "reading", llm: "thinking", draft: "drafting", gate: "approval", write: "writing", charge: "charging" };
const kindOf = (block: string | null) => (block ?? "").split(":")[0];

export function reachedText(r: RunListItem): string {
  const place = PLACE[kindOf(r.reachedBlock)] ?? "starting";
  switch (r.status) {
    case "AWAITING_APPROVAL":
      return "Waiting at approval";
    case "PENDING":
    case "RUNNING":
      return place === "approval" ? "Waiting at approval" : `${place[0].toUpperCase()}${place.slice(1)}`;
    case "COMPLETED":
      return r.decidedBy ? `Finished, approved by ${r.decidedBy.name}` : "Finished";
    case "EXPIRED":
      return "Stopped at approval";
    case "CANCELLED":
      return r.decidedBy ? `Rejected by ${r.decidedBy.name}` : "Rejected";
    default:
      return place === "approval" ? "Stopped at approval" : `Stopped while ${place}`;
  }
}

export function statusText(r: RunListItem): { tone: Tone; label: string } {
  if (r.terminalReason === "COST_CAP_REACHED") return { tone: "bad", label: "Stopped at limit" };
  switch (r.status) {
    case "AWAITING_APPROVAL": return { tone: "wa", label: "Needs you" };
    case "PENDING": case "RUNNING": return { tone: "b", label: "Running" };
    case "COMPLETED": return { tone: "ok", label: "Completed" };
    case "EXPIRED": return { tone: "n", label: "No answer in time" };
    case "CANCELLED": return { tone: "n", label: "Rejected" };
    case "TIMED_OUT": return { tone: "bad", label: "Ran out of time" };
    default: return { tone: "bad", label: "Stopped" };
  }
}

export function miniFlowSteps(r: RunListItem): FlowStepState[] {
  if (r.status === "COMPLETED") return r.blocks.map(() => "done");
  const at = r.blocks.indexOf(r.reachedBlock ?? "");
  const current: FlowStepState = r.status === "AWAITING_APPROVAL" ? "wait" : r.status === "RUNNING" || r.status === "PENDING" ? "run" : "bad";
  return r.blocks.map((_, i) => (i < at ? "done" : i === at ? current : "idle"));
}
```

`src/app/(buyer)/runs/TodayTimeline.tsx`:

```tsx
import Link from "next/link";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { dayLabel } from "@/lib/format";
import type { RunListItem } from "@/lib/types";

const HOURS = 12;   // the last 12 hours, like the mockup's morning view

export function TodayTimeline({ runs, now = new Date() }: { runs: RunListItem[]; now?: Date }) {
  const today = runs.filter((r) => r.startedAt && dayLabel(r.startedAt, now) === "Today");
  if (today.length === 0) return null;
  const end = now.getTime();
  const start = end - HOURS * 3600_000;
  const x = (iso: string) => Math.max(0, ((new Date(iso).getTime() - start) / (end - start)) * 100);
  const agents = [...new Set(today.map((r) => r.agentName))];
  return (
    <Panel className="p-5 flex flex-col gap-3">
      <PanelTitle hint="Each bar is one run, from start to finish.">Today</PanelTitle>
      {agents.map((agent) => (
        <div key={agent} className="grid grid-cols-[180px_1fr] gap-3 items-center text-[13px]">
          <span className="truncate">{agent}</span>
          <div className="relative h-4 bg-line2 rounded-full">
            {today.filter((r) => r.agentName === agent).map((r) => {
              const left = x(r.startedAt!);
              const right = r.completedAt ? x(r.completedAt) : 100;
              return (
                <Link key={r.id} href={`/runs/${r.id}`}
                  aria-label={`${r.displayId}, ${r.completedAt ? "finished" : "still running"}`}
                  className={`absolute top-0 h-4 rounded-full ${r.completedAt ? "bg-blue-3" : "bg-blue"}`}
                  style={{ left: `${left}%`, width: `${Math.max(1, right - left)}%` }} />
              );
            })}
          </div>
        </div>
      ))}
    </Panel>
  );
}
```

Replace `src/app/(buyer)/runs/RunsHistory.tsx`:

```tsx
import Link from "next/link";
import { MiniFlow } from "@/components/ui/MiniFlow";
import { Panel } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { clockTime, dayLabel, inr } from "@/lib/format";
import { miniFlowSteps, reachedText, statusText } from "@/lib/runReason";
import type { RunList } from "@/lib/types";

const FILTERS = [
  { key: "all", label: "All" },
  { key: "needs_you", label: "Needs you" },
  { key: "running", label: "Running" },
  { key: "finished", label: "Finished" },
  { key: "stopped", label: "Stopped" },
] as const;

export function RunsHistory({ list, filter }: { list: RunList; filter: string }) {
  return (
    <Panel className="overflow-hidden">
      <nav aria-label="Filter runs" className="flex gap-2 p-4">
        {FILTERS.map((f) => (
          <Link key={f.key} href={f.key === "all" ? "/runs" : `/runs?group=${f.key}`} aria-current={filter === f.key ? "page" : undefined}
            className={`h-8 px-3 rounded-full text-[13px] border inline-flex items-center gap-1 ${filter === f.key ? "bg-sky border-blue-3 text-blue font-semibold" : "border-line text-ink2"}`}>
            {f.label} <span className="font-mono text-xs">{list.counts[f.key]}</span>
          </Link>
        ))}
      </nav>
      <table className="w-full text-[13px]">
        <thead>
          <tr className="text-xs text-ink3 border-t border-line2">
            <th className="text-left font-normal px-5 py-2">Run</th><th className="text-left font-normal py-2">Agent</th>
            <th className="text-left font-normal py-2">Reached</th><th className="text-left font-normal py-2">Status</th>
            <th className="text-left font-normal py-2">Started</th><th className="text-left font-normal py-2">Cost</th><th />
          </tr>
        </thead>
        <tbody>
          {list.items.map((r) => {
            const s = statusText(r);
            const reached = reachedText(r);
            const at = r.blocks.indexOf(r.reachedBlock ?? "") + 1;
            return (
              <tr key={r.id} aria-label={`${r.displayId} ${r.agentName}`} className="border-t border-line2">
                <td className="px-5 py-3 font-mono text-xs">{r.displayId}</td>
                <td className="py-3 font-medium">{r.agentName}</td>
                <td className="py-3">
                  <span className="flex items-center gap-3">
                    {r.blocks.length > 0 && <MiniFlow steps={miniFlowSteps(r)} label={`${reached}, step ${at || r.blocks.length} of ${r.blocks.length}`} />}
                    <span className="text-ink2">{reached}</span>
                  </span>
                </td>
                <td className="py-3"><Pill tone={s.tone}>{s.label}</Pill></td>
                <td className="py-3 font-mono text-xs text-ink2">{r.startedAt ? (dayLabel(r.startedAt) === "Today" ? clockTime(r.startedAt) : dayLabel(r.startedAt)) : "—"}</td>
                <td className="py-3 font-mono">{r.held > 0 ? `${inr(r.held)} held` : inr(r.charged)}</td>
                <td className="py-3 pr-5 text-right"><Link href={`/runs/${r.id}`} className="text-blue font-semibold">Open</Link></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {list.items.length === 0 && <p className="px-5 py-6 text-[13px] text-ink3">No runs match this filter.</p>}
    </Panel>
  );
}
```

Replace `src/app/(buyer)/runs/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ConnectionSummaryResponse, RunList } from "@/lib/types";
import { RunAnAgent } from "./RunAnAgent";
import { RunsHistory } from "./RunsHistory";
import { TodayTimeline } from "./TodayTimeline";

const GROUPS = ["needs_you", "running", "finished", "stopped"];

export default async function RunsPage({ searchParams }: { searchParams: Promise<{ group?: string }> }) {
  const group = (await searchParams).group;
  const filter = group && GROUPS.includes(group) ? group : "all";
  const [list, today, connections] = await Promise.all([
    backend.get<RunList>(`/api/v1/runs${filter === "all" ? "" : `?group=${filter}`}`),
    backend.get<RunList>("/api/v1/runs?from=today"),
    backend.get<ConnectionSummaryResponse[]>("/api/v1/connections/mine"),
  ]);
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Runs</h1>
        <p className="text-ink2">Every run your agents made, with the block each one reached.</p>
      </div>
      <TodayTimeline runs={today.items} />
      <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-6 items-start">
        <RunsHistory list={list} filter={filter} />
        <RunAnAgent connections={connections} />
      </div>
    </div>
  );
}
```

`runStatus.ts`'s `RUN_FILTERS`, `countByGroup` and their tests are now unused by the runs page. Leave them: the command bar still uses `runStatusPill`. Delete `countByGroup` and `RUN_FILTERS` (and their test case) if `grep -rn "countByGroup\|RUN_FILTERS" src` shows nothing else uses them.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): runs history with readable ids, where each run got to, and today's timeline"
```

---

### Task 10: End to end, and cleanup

**Files:**
- Modify: `e2e/core-loop.spec.ts`, `e2e/seed.ts` (sample files), `frontend/README.md`

- [ ] **Step 1: Seed a file**

In `e2e/seed.ts`, after the buyer signs up, upload a CSV for the buyer so the sockets have something to plug in:

```ts
  const buyerLogin = (await fetch(`${BACKEND_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: "e2e-buyer@example.com", password: "e2e-password", remember: false }),
  }).then((r) => r.json())) as { token: string };
  const form = new FormData();
  form.append("file", new Blob(["date,narration,amount\n2026-08-01,NEFT ACME 0814,48200\n"], { type: "text/csv" }), "Aug 2026 statement.csv");
  form.append("displayName", "Aug 2026 statement.csv");
  await fetch(`${BACKEND_URL}/api/v1/datasets`, { method: "POST", headers: { Authorization: `Bearer ${buyerLogin.token}` }, body: form });
```

The seeded agent's manifest must declare a v2 `resources` entry for `bank_statement` with required columns `date`, `narration` and `amount`. Update the `manifest` object in `seed.ts` to manifest v2 as WS1/WS2 define it: add `"manifestVersion": 2` and `"resources": [{ "name": "bank_statement", "format": "csv", "schemaRef": "schemas/bank_statement.json", "displayName": "Bank statements" }]`, following whatever schema-reference convention the WS2 plan sets for seeded agents.

- [ ] **Step 2: Extend the core-loop E2E**

In `e2e/core-loop.spec.ts`, change the first test's store steps to the new pages:

```ts
  await page.getByRole("link", { name: "Browse agents" }).click();
  await expect(page).toHaveURL(/\/store$/);
  await page.getByRole("link", { name: /E2E Bank Reconciliation/ }).click();
  await expect(page.getByRole("group", { name: /How E2E Bank Reconciliation works/ })).toBeVisible();
  await page.getByRole("link", { name: "Add to workspace" }).click();

  await expect(page.getByRole("heading", { name: "Add E2E Bank Reconciliation" })).toBeVisible();
  await page.getByLabel("File for Bank statements").selectOption({ label: "Aug 2026 statement.csv" });
  await expect(page.getByText(/rows, columns match/)).toBeVisible();
  await page.getByRole("checkbox", { name: /E2E Approver/ }).check();
  await page.getByRole("button", { name: "Add and run now" }).click();

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]+/);
  await expect(page.getByRole("group", { name: "What this run is doing" })).toBeVisible();
```

and add:

```ts
test("the file library shows where a file is plugged in", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("e2e-buyer@example.com");
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("link", { name: "Files" }).click();
  await expect(page.getByRole("heading", { name: "Files" })).toBeVisible();
  await expect(page.getByRole("row", { name: /Aug 2026 statement/ })).toBeVisible();
});
```

- [ ] **Step 3: Verify**

Run: `npx tsc --noEmit && npm test && npm run build`, then with a fresh backend: `npm run seed:e2e && npm run test:e2e`
Expected: all PASS.

- [ ] **Step 4: Remove leftovers**

Run: `grep -rln "PermissionStatementDoc\|ScopeToggleGrant" src`
Expected: they are now used only by the developer submission wizard and the admin review detail. Keep them until FE-3 and FE-4.

- [ ] **Step 5: Commit**

```bash
git add -A frontend
git commit -m "test(frontend): E2E through the store flow, data sockets and the live flow; seed a sample file"
```
