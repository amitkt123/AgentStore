# P3b My Agents and Studio Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A developer can list their agents, create an agent and a version, open a version's Studio, see what it declares and whether it is ready, fix the manifest with one-click patches, and submit it.

**Architecture:** Frontend only (Next 14 app router, React 18.3, Vitest). Two new routes inside `app/(app)/developer/`: `agents` (list) and `agents/[agentId]/versions/[versionId]` (Studio). Pages are server components that fetch through `lib/api/*`. Mutations are server actions that map `ApiError` to plain-words failures. The Studio's interactive parts share one client context (`FeedbackProvider`) that holds the top banner message and a single in-flight guard.

**Tech Stack:** Next 14.2, React 18.3, TypeScript, CSS modules, Vitest + Testing Library + user-event.

**Spec:** `docs/superpowers/specs/2026-10-01-p3b-agents-and-studio-design.md`. Backend behaviour was read from `CatalogController`, `SubmissionController`, `PublishingService`, `ManifestPatchService` and `ManifestApiErrors` on master.

## Deviations from the approved spec (flag these in the PR)

1. **Unverified submit returns `BYOK_NOT_VERIFIED`, not `VERIFICATION_REQUIRED`.** `PublishingService.submit` only blocks unverified orgs for `byok_*` manifests, with 403 `BYOK_NOT_VERIFIED`. No other verification gate exists on master. The UI treats both codes as "Get verified" and links to Get verified.
2. **The backend hides the reason on plain HTTP errors.** `createVersion` 400 (validator messages) and `submit` 403/409 (build not ready, lint failed) carry no message; `http.ts` turns them into `Request failed (N)`. The UI therefore shows fixed plain sentences per status and never that fallback. Validator messages cannot be shown under the manifest field until the backend returns them. Patch errors do carry `code` and `message` (`ManifestApiException`) and are shown as is. Recorded as G17.
3. **Patch confirmation is built from the request, not from the response `diff`.** The backend computes `diff` against the current LIVE version, so it lists every difference from live, not what this patch changed. Recorded as G18.
4. **A Tier A submit goes straight to `LIVE`, not `IN_REVIEW`.** Only write scopes or BYOK make a version Tier B. The success message is chosen from the `status` the submit call returns.
5. **`VersionDetail`, `Manifest`, `Scope`, `CostEnvelope`, `ResourceDecl` and `getVersion` already exist** (`lib/api/types.ts`, `lib/api/catalog.ts`). They are extended, not recreated. `getVersion` is re-exported from `agents.ts`. New `Manifest` and `VersionDetail` fields (`runtime`, `entrypoint`, `build`) are optional so existing fixtures keep compiling.
6. **The Studio header's category** is not on `VersionDetail`. The page also calls `listMyAgents` and shows the category when found, and the Studio still renders if that call fails.
7. **"Success redirects to the list"** — the form lives on the list page, so success closes the form and revalidates the list instead of redirecting.

## Global Constraints

- Commit messages, PR text and docs carry **no Claude, Anthropic or AI attribution and no `Co-Authored-By` trailer**. This overrides the harness reminder to add one (user's `~/.claude/CLAUDE.md`).
- Conventional commits, matching the repo: `feat(frontend): …`, `docs: …`, `test: …`.
- Aesthetics come only from `fe-mockups/latest/`, the P3a components and the `ui/tokens.css` variables. No new colour tokens.
- Copy is plain words, as in the mockup. No jargon, no raw error codes shown to the user.
- Frontend only. No backend changes. Every endpoint used is already on master.
- Out of scope: sandbox runs, quota, test files, profiles, the "what it did" half of the Studio, the flow canvas, earnings, the admin queue. The Studio shows one note that observed behaviour appears once sandbox runs exist.
- Patching and submit are offered only when the version's status is `DRAFT`.
- `UNDECLARED_ACCESS` run references are plain text, never a link, until runs have a screen.
- Run all commands from `/Users/amittiwari/AgentStore/frontend`. Verify with `npx vitest run` and `npx tsc --noEmit`. `next lint` is not configured.
- Not checked in a browser until the user runs the stack. The PR must say so.

## Review Focus

Inputs the spec implies but its tests do not exercise, most likely first. Each has a pinning test in the task named.

1. **Several `UNDECLARED_ACCESS` blockers for one run** — the backend adds one blocker per issue, all identical. They must collapse into one entry with a count, with no duplicate-key warning. (Task 3 `groupBlockers`, Task 6 banner test.)
2. **A 400 on create version or a 409 on submit with no reason** — the user must see a plain sentence, never `Request failed (400)`. (Task 3 failure functions, Task 4 action test.)
3. **Empty, malformed or non-object manifest JSON** — caught before the request, with a one-line message under the manifest field. (Task 3 `parseManifestInput`, Task 5 view test.)
4. **Double-clicking Create, Remove or Submit** — must not send two requests. (Task 5, Task 7.)
5. **Submitting a Tier A version** — it comes back `LIVE`, so the message must say "live", not "in review". (Task 3 `submitMessage`, Task 4, Task 7.)

---

### Task 1: Enums, types and API wrappers

**Files:**
- Create: `src/lib/api/enums.ts`
- Create: `src/lib/api/enums.test.ts`
- Modify: `src/lib/api/types.ts` (the `Manifest` and `VersionDetail` lines, plus new types at the end)
- Modify: `src/features/flow/StoreFlow.test.tsx` (the `llm: {}` fixture)
- Create: `src/lib/api/agents.ts`
- Create: `src/lib/api/agents.test.ts`

**Interfaces:**
- Produces (`enums.ts`): `SCOPE_TYPES`, `DATA_FORMATS`, `PRICING_UNITS`, `LLM_MODES`, `VERSION_STATUSES`, `BUILD_STATUSES` (readonly tuples of upper-case wire names); types `LlmMode`, `VersionStatus`, `BuildStatus`.
- Produces (`types.ts`): `VersionSummary {id, versionNumber, status}`, `MyAgent`, `CreatedAgent`, `BuildSummary`, `LlmDecl`, `BlockerCode`, `Blocker`, `Readiness`, `ManifestPatchOp`, `ManifestPatchRequest`, `ManifestPatchResult`; `Manifest` gains `llm: LlmDecl | null`, `runtime?`, `entrypoint?`; `VersionDetail.status: VersionStatus` and `build?: BuildSummary | null`.
- Produces (`agents.ts`): `listMyAgents()`, `createAgent(body)`, `createVersion(agentId, body)`, `getReadiness(agentId, versionId)`, `patchManifest(agentId, versionId, body)`, `submitVersion(agentId, versionId)`, and re-exported `getVersion`.

- [ ] **Step 0: Create the implementation branch (from the spec branch so the spec travels)**

```bash
git switch -c feat/p3b-agents-studio
```

- [ ] **Step 1: Write the failing enum pin test**

Create `src/lib/api/enums.test.ts`:

```ts
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { BUILD_STATUSES, DATA_FORMATS, LLM_MODES, PRICING_UNITS, SCOPE_TYPES, VERSION_STATUSES } from "./enums";

// Vitest runs from frontend/, so the backend sits one level up.
const platform = resolve(process.cwd(), "../backend/src/main/java/com/agentstore/platform");

function javaEnum(file: string, name: string): string[] {
  const src = readFileSync(resolve(platform, `${file}.java`), "utf8");
  const body = src.slice(src.indexOf("{", src.indexOf(`enum ${name}`)) + 1).split(/[;}]/)[0];
  return body.split(",").map((s) => s.trim().match(/^[A-Z_]+/)?.[0]).filter((s): s is string => !!s);
}

it.each([
  ["ScopeType", "manifest/ScopeType", SCOPE_TYPES],
  ["DataFormat", "manifest/DataFormat", DATA_FORMATS],
  ["PricingUnit", "manifest/PricingUnit", PRICING_UNITS],
  ["LlmMode", "manifest/LlmMode", LLM_MODES],
  ["VersionStatus", "catalog/VersionStatus", VERSION_STATUSES],
  ["BuildStatus", "build/BuildStatus", BUILD_STATUSES],
])("%s matches the backend enum", (name, file, values) => {
  expect([...values]).toEqual(javaEnum(file, name));
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `npx vitest run src/lib/api/enums.test.ts`
Expected: FAIL, cannot resolve `./enums`.

- [ ] **Step 3: Create `src/lib/api/enums.ts`**

```ts
// Wire names of the backend enums the developer screens read or send. enums.test.ts pins each list to the Java source.
export const SCOPE_TYPES = ["READ", "WRITE"] as const;
export const DATA_FORMATS = ["CSV", "JSON", "PDF"] as const;
export const PRICING_UNITS = ["METERED", "OUTCOME", "SUBSCRIPTION"] as const;
export const LLM_MODES = ["PLATFORM", "BYOK_PROXY", "BYOK_DIRECT"] as const;
export const VERSION_STATUSES = ["DRAFT", "IN_REVIEW", "LIVE", "REJECTED"] as const;
export const BUILD_STATUSES = ["QUEUED", "BUILDING", "SUCCEEDED", "FAILED"] as const;

export type LlmMode = (typeof LLM_MODES)[number];
export type VersionStatus = (typeof VERSION_STATUSES)[number];
export type BuildStatus = (typeof BUILD_STATUSES)[number];
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `npx vitest run src/lib/api/enums.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Extend `src/lib/api/types.ts`**

At the top of the file, under the existing imports, add:

```ts
import type { BuildStatus, LlmMode, VersionStatus } from "./enums";
export type { BuildStatus, LlmMode, VersionStatus } from "./enums";
```

Replace the `Manifest` line (currently ending `resources: ResourceDecl[] | null; llm: unknown | null }`) with:

```ts
export interface LlmDecl { mode: LlmMode; models: string[]; maxTokensPerRun: number; provider: string | null }
export interface Manifest { name: string; version: string; scopes: Scope[]; networkAllowlist: string[]; costEnvelope: CostEnvelope; approvalGates: ApprovalGateDecl[]; dataClasses: string[]; resources: ResourceDecl[] | null; llm: LlmDecl | null; runtime?: string | null; entrypoint?: string | null }
```

Replace the `VersionDetail` line with:

```ts
export interface BuildSummary { buildId: string; status: BuildStatus; failureCode: string | null; failureReason: string | null; logUrl: string | null }
export interface VersionDetail { id: string; agentId: string; agentName: string; versionNumber: string; status: VersionStatus; manifest: Manifest; build?: BuildSummary | null }
```

Append at the end of the file:

```ts
export interface VersionSummary { id: string; versionNumber: string; status: VersionStatus }
export interface MyAgent { agentId: string; agentName: string; category: string; versions: VersionSummary[] }
export interface CreatedAgent { id: string; name: string; category: string; description: string | null }
export type BlockerCode = "BUILD_NOT_READY" | "UNDECLARED_ACCESS" | "COST_ENVELOPE_EXCEEDED";
export interface Blocker { code: BlockerCode; message: string; runId: string | null }
export interface Readiness { ready: boolean; blockers: Blocker[] }
export type ManifestPatchOp = "add_read_scope" | "add_resource" | "add_allowlist_host" | "remove_scope";
export interface ManifestPatchRequest { op: ManifestPatchOp; resource?: string; description?: string; requiresApproval?: boolean; name?: string; format?: string; schema?: unknown; host?: string; type?: string }
export interface ManifestPatchResult { manifestRevision: number; diff: { addedScopes: string[]; removedScopes: string[]; addedHosts: string[] } }
```

- [ ] **Step 6: Run the type check and fix what it reports**

Run: `npx tsc --noEmit`
Expected: one error in `src/features/flow/StoreFlow.test.tsx` (`llm: {}` is not an `LlmDecl`). Fix it by replacing `llm: {}` with `llm: { mode: "PLATFORM", models: ["m"], maxTokensPerRun: 1000, provider: null }` (keeps the value truthy, which is what that fixture relied on). Fix any other errors that name `VersionDetail.status` being compared to a string. Re-run until clean.

- [ ] **Step 7: Write the failing API wrapper tests**

Create `src/lib/api/agents.test.ts`:

```ts
import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import { createAgent, createVersion, getReadiness, listMyAgents, patchManifest, submitVersion } from "./agents";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });
const stub = (body: unknown, status = 200) => {
  const f = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }));
  vi.stubGlobal("fetch", f);
  return f;
};

it("lists my agents", async () => {
  const f = stub([]);
  expect(await listMyAgents()).toEqual([]);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/mine");
});

it("creates an agent", async () => {
  const f = stub({ id: "a1", name: "Bot", category: "Finance", description: null });
  await createAgent({ name: "Bot", category: "Finance" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ name: "Bot", category: "Finance" });
});

it("creates a version under its agent", async () => {
  const f = stub({ id: "v1", versionNumber: "1.0.0", status: "DRAFT" });
  await createVersion("a1", { versionNumber: "1.0.0", manifestJson: "{}", changelog: "first" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ versionNumber: "1.0.0", manifestJson: "{}", changelog: "first" });
});

it("reads readiness", async () => {
  const f = stub({ ready: true, blockers: [] });
  expect(await getReadiness("a1", "v1")).toEqual({ ready: true, blockers: [] });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions/v1/readiness");
});

it("posts a manifest patch", async () => {
  const f = stub({ manifestRevision: 2, diff: { addedScopes: [], removedScopes: [], addedHosts: ["h"] } });
  await patchManifest("a1", "v1", { op: "add_allowlist_host", host: "h" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions/v1/manifest/patches");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ op: "add_allowlist_host", host: "h" });
});

it("submits a version with no body", async () => {
  const f = stub({ id: "v1", versionNumber: "1.0.0", status: "LIVE" });
  expect((await submitVersion("a1", "v1")).status).toBe("LIVE");
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/agents/a1/versions/v1/submit");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(f.mock.calls[0][1].body).toBeUndefined();
});
```

- [ ] **Step 8: Run to confirm it fails**

Run: `npx vitest run src/lib/api/agents.test.ts`
Expected: FAIL, cannot resolve `./agents`.

- [ ] **Step 9: Create `src/lib/api/agents.ts`**

```ts
import { api, json } from "./http";
import type { CreatedAgent, ManifestPatchRequest, ManifestPatchResult, MyAgent, Readiness, VersionSummary } from "./types";

export { getVersion } from "./catalog";

const base = "/api/v1/agents";
const versionPath = (agentId: string, versionId: string) => `${base}/${agentId}/versions/${versionId}`;

export const listMyAgents = () => api<MyAgent[]>(`${base}/mine`);
export const createAgent = (body: { name: string; category: string; description?: string }) =>
  api<CreatedAgent>(base, { method: "POST", body: json(body) });
export const createVersion = (agentId: string, body: { versionNumber: string; manifestJson: string; changelog?: string }) =>
  api<VersionSummary>(`${base}/${agentId}/versions`, { method: "POST", body: json(body) });
export const getReadiness = (agentId: string, versionId: string) => api<Readiness>(`${versionPath(agentId, versionId)}/readiness`);
export const patchManifest = (agentId: string, versionId: string, body: ManifestPatchRequest) =>
  api<ManifestPatchResult>(`${versionPath(agentId, versionId)}/manifest/patches`, { method: "POST", body: json(body) });
export const submitVersion = (agentId: string, versionId: string) =>
  api<VersionSummary>(`${versionPath(agentId, versionId)}/submit`, { method: "POST" });
```

- [ ] **Step 10: Run tests and type check**

Run: `npx vitest run src/lib/api && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 11: Commit**

```bash
git add src/lib/api src/features/flow/StoreFlow.test.tsx
git commit -m "feat(frontend): agents API wrappers, manifest types and enum pins"
```

---

### Task 2: Agents on the developer rail

**Files:**
- Modify: `src/ui/Icon.tsx`
- Modify: `src/ui/Rail.tsx`
- Modify: `src/ui/Rail.test.tsx`
- Modify: `src/lib/gate.test.ts`

**Interfaces:**
- Produces: `IconName` gains `"agents"`; `developerRailItems()` returns Agents, Get verified, API keys in that order.

- [ ] **Step 1: Update the rail test (failing)**

In `src/ui/Rail.test.tsx`, change the developer test's first expectation to:

```ts
expect(developerRailItems().map((i) => i.href)).toEqual(["/developer/agents", "/developer/verification", "/developer/api-keys"]);
```

Add this test to the same file:

```tsx
it("keeps Agents active inside a Studio route", () => {
  render(<Rail items={developerRailItems()} activePath="/developer/agents/a1/versions/v1" />);
  expect(screen.getByRole("link", { name: "Agents" })).toHaveAttribute("aria-current", "page");
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `npx vitest run src/ui/Rail.test.tsx`
Expected: FAIL (list lacks `/developer/agents`).

- [ ] **Step 3: Add the icon and the rail item**

In `src/ui/Icon.tsx`, replace the `IconName` union line's `| "shield";` ending with `| "shield" | "agents";` and add this entry after the `shield:` entry in `paths`:

```ts
  agents: "M12 3l2.2 5.8L20 11l-5.8 2.2L12 19l-2.2-5.8L4 11l5.8-2.2z",
```

In `src/ui/Rail.tsx`, replace `developerRailItems` and the comment under it:

```ts
export function developerRailItems(): RailItem[] {
  return [
    { href: "/developer/agents", label: "Agents", icon: "agents" },
    { href: "/developer/verification", label: "Get verified", icon: "shield" },
    { href: "/developer/api-keys", label: "API keys", icon: "key" },
  ];
}

// Sandbox runs and Test files join developerRailItems in P3c; the admin rail arrives in P4.
```

- [ ] **Step 4: Pin role gating for the new routes**

Append to `src/lib/gate.test.ts` (`gateTarget` is already imported). This passes already, because gating is by the `/developer` prefix; it pins that:

```ts
it("lets a developer, and only a developer, into the agents screens", () => {
  expect(gateTarget("DEVELOPER", "/developer/agents")).toBeNull();
  expect(gateTarget("DEVELOPER", "/developer/agents/a1/versions/v1")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/developer/agents/a1/versions/v1")).toBe("/home");
  expect(gateTarget("BUYER_APPROVER", "/developer/agents")).toBe("/home");
});
```

- [ ] **Step 5: Run tests**

Run: `npx vitest run src/ui/Rail.test.tsx src/lib/gate.test.ts src/lib/roles.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/ui src/lib/gate.test.ts
git commit -m "feat(frontend): Agents item on the developer rail"
```

---

### Task 3: Model helpers (status, failures, blockers, manifest input)

**Files:**
- Create: `src/features/agents/model.ts`
- Create: `src/features/agents/model.test.ts`

**Interfaces:**
- Consumes: `ApiError` (`status`, `code`, `message`) from `lib/api/http`; `Tone` from `ui/Pill`; types from Task 1.
- Produces:
  - `interface Failure { text: string; field?: "name" | "category" | "versionNumber" | "manifest"; verify?: boolean }`
  - `statusLabel: Record<VersionStatus, string>`, `statusTone: Record<VersionStatus, Tone>`
  - `createAgentFailure(e)`, `createVersionFailure(e)`, `patchFailure(e)`, `submitFailure(e)`: each `(e: ApiError) => Failure`
  - `submitMessage(status: VersionStatus): string`
  - `patchConfirmation(req: ManifestPatchRequest): string`
  - `interface BlockerRow { code: BlockerCode; message: string; runId: string | null; count: number }`, `groupBlockers(blockers: Blocker[]): BlockerRow[]`
  - `parseManifestInput(text: string): { ok: true; text: string } | { ok: false; error: string }`

- [ ] **Step 1: Write the failing tests**

Create `src/features/agents/model.test.ts`:

```ts
import { ApiError } from "@/lib/api/http";
import { createAgentFailure, createVersionFailure, groupBlockers, parseManifestInput, patchConfirmation, patchFailure, statusLabel, statusTone, submitFailure, submitMessage } from "./model";

const hidden = (status: number) => new ApiError(status, null, `Request failed (${status})`);

it("labels and tones every status", () => {
  expect(statusLabel).toEqual({ DRAFT: "Draft", IN_REVIEW: "In review", LIVE: "Live", REJECTED: "Rejected" });
  expect(statusTone).toEqual({ DRAFT: "neutral", IN_REVIEW: "warn", LIVE: "ok", REJECTED: "bad" });
});

it("never shows the hidden-reason fallback for a create-version 400", () => {
  const f = createVersionFailure(hidden(400));
  expect(f.field).toBe("manifest");
  expect(f.text).not.toMatch(/Request failed/);
  expect(f.text).toMatch(/couldn't accept this manifest/);
});

it("maps create-version ownership and missing-agent errors", () => {
  expect(createVersionFailure(hidden(403)).text).toBe("That agent belongs to another organisation.");
  expect(createVersionFailure(hidden(404)).text).toBe("That agent no longer exists.");
  expect(createVersionFailure(hidden(500)).text).not.toMatch(/Request failed/);
});

it("maps create-agent errors", () => {
  expect(createAgentFailure(hidden(400)).text).toBe("Give the agent a name and a category.");
  expect(createAgentFailure(hidden(500)).text).toBe("Couldn't create the agent. Try again.");
});

it("shows a patch error's own message when the backend sent a code", () => {
  expect(patchFailure(new ApiError(409, "SCOPE_ALREADY_DECLARED", "A READ scope for 'x' is already declared")).text).toBe("A READ scope for 'x' is already declared");
  expect(patchFailure(new ApiError(409, "VERSION_NOT_EDITABLE", "Only a DRAFT version's manifest can be patched")).text).toBe("This version can no longer be edited.");
  expect(patchFailure(hidden(403)).text).toBe("You can't edit this version.");
  expect(patchFailure(hidden(500)).text).not.toMatch(/Request failed/);
});

it("sends an unverified submit to Get verified, for both codes", () => {
  const byok = submitFailure(new ApiError(403, "BYOK_NOT_VERIFIED", "Only verified developer organisations can use llm.mode byok_proxy"));
  expect(byok.verify).toBe(true);
  expect(byok.text).toMatch(/own LLM key/);
  const generic = submitFailure(new ApiError(403, "VERIFICATION_REQUIRED", "m"));
  expect(generic.verify).toBe(true);
  expect(generic.text).toBe("Get verified before you submit an agent for listing.");
});

it("never shows the hidden-reason fallback for a submit 409", () => {
  const f = submitFailure(hidden(409));
  expect(f.verify).toBeUndefined();
  expect(f.text).toMatch(/can't be submitted yet/);
  expect(f.text).not.toMatch(/Request failed/);
});

it("words the submit result from the status the backend returned", () => {
  expect(submitMessage("LIVE")).toBe("Submitted and published. This version is live.");
  expect(submitMessage("IN_REVIEW")).toBe("Submitted. Karya will review this version.");
  expect(submitMessage("DRAFT")).toBe("Submitted.");
});

it("builds the patch confirmation from the request", () => {
  expect(patchConfirmation({ op: "add_allowlist_host", host: "api.example.com" })).toBe("Added host api.example.com");
  expect(patchConfirmation({ op: "add_read_scope", resource: "vendor_master" })).toBe("Added read scope vendor_master");
  expect(patchConfirmation({ op: "add_resource", name: "vendors", format: "csv" })).toBe("Added resource vendors");
  expect(patchConfirmation({ op: "remove_scope", type: "WRITE", resource: "journal_entries" })).toBe("Removed write scope journal_entries");
});

it("collapses identical blockers into one row with a count, keeping order", () => {
  const u = { code: "UNDECLARED_ACCESS" as const, message: "outside manifest", runId: "run-1" };
  const rows = groupBlockers([{ code: "BUILD_NOT_READY", message: "no build", runId: null }, u, u, u, { ...u, runId: "run-2" }]);
  expect(rows.map((r) => [r.code, r.runId, r.count])).toEqual([["BUILD_NOT_READY", null, 1], ["UNDECLARED_ACCESS", "run-1", 3], ["UNDECLARED_ACCESS", "run-2", 1]]);
});

it("accepts a JSON object and sends it trimmed", () => {
  expect(parseManifestInput('  {"name":"x"}\n')).toEqual({ ok: true, text: '{"name":"x"}' });
});

it.each([
  ["", "Paste the manifest JSON."],
  ["   ", "Paste the manifest JSON."],
  ["{not json", "That isn't valid JSON."],
  ["[1,2]", "The manifest must be a JSON object."],
  ["null", "The manifest must be a JSON object."],
  ["42", "The manifest must be a JSON object."],
])("rejects manifest input %j", (text, error) => {
  expect(parseManifestInput(text)).toEqual({ ok: false, error });
});
```

- [ ] **Step 2: Run to confirm it fails**

Run: `npx vitest run src/features/agents/model.test.ts`
Expected: FAIL, cannot resolve `./model`.

- [ ] **Step 3: Create `src/features/agents/model.ts`**

```ts
import type { Tone } from "@/ui/Pill";
import type { ApiError } from "@/lib/api/http";
import type { Blocker, BlockerCode, ManifestPatchRequest, VersionStatus } from "@/lib/api/types";

export interface Failure { text: string; field?: "name" | "category" | "versionNumber" | "manifest"; verify?: boolean }

export const statusLabel: Record<VersionStatus, string> = { DRAFT: "Draft", IN_REVIEW: "In review", LIVE: "Live", REJECTED: "Rejected" };
export const statusTone: Record<VersionStatus, Tone> = { DRAFT: "neutral", IN_REVIEW: "warn", LIVE: "ok", REJECTED: "bad" };

// The platform hides the reason on plain HTTP errors and http.ts then falls back to "Request failed (N)". That is never worth showing.
const reason = (e: ApiError): string | null => (/^Request failed \(\d+\)$/.test(e.message) ? null : e.message);

export function createAgentFailure(e: ApiError): Failure {
  if (e.status === 400) return { text: "Give the agent a name and a category." };
  return { text: reason(e) ?? "Couldn't create the agent. Try again." };
}

export function createVersionFailure(e: ApiError): Failure {
  if (e.status === 400) return { text: "Karya couldn't accept this manifest. Check that it has every required block and try again.", field: "manifest" };
  if (e.status === 403) return { text: "That agent belongs to another organisation." };
  if (e.status === 404) return { text: "That agent no longer exists." };
  return { text: reason(e) ?? "Couldn't create the version. Try again." };
}

export function patchFailure(e: ApiError): Failure {
  if (e.code === "VERSION_NOT_EDITABLE") return { text: "This version can no longer be edited." };
  if (e.code) return { text: e.message };
  if (e.status === 403 || e.status === 404) return { text: "You can't edit this version." };
  return { text: reason(e) ?? "That change didn't go through. Try again." };
}

export function submitFailure(e: ApiError): Failure {
  if (e.code === "BYOK_NOT_VERIFIED") return { text: "Only verified developers can submit an agent that uses its own LLM key. Get verified first.", verify: true };
  if (e.code === "VERIFICATION_REQUIRED") return { text: "Get verified before you submit an agent for listing.", verify: true };
  if (e.status === 409) return { text: "This version can't be submitted yet. Check that it has a successful build and a valid manifest, then try again." };
  if (e.status === 403 || e.status === 404) return { text: "You can't submit this version." };
  return { text: reason(e) ?? "Couldn't submit this version. Try again." };
}

// A read-only agent goes straight to LIVE; one with write scopes or its own LLM key waits for review.
export function submitMessage(status: VersionStatus): string {
  if (status === "LIVE") return "Submitted and published. This version is live.";
  if (status === "IN_REVIEW") return "Submitted. Karya will review this version.";
  return "Submitted.";
}

// Built from what the developer asked for: the response diff is against the LIVE version, not against this edit.
export function patchConfirmation(req: ManifestPatchRequest): string {
  switch (req.op) {
    case "add_read_scope": return `Added read scope ${req.resource}`;
    case "add_resource": return `Added resource ${req.name}`;
    case "add_allowlist_host": return `Added host ${req.host}`;
    case "remove_scope": return `Removed ${(req.type ?? "").toLowerCase()} scope ${req.resource}`;
  }
}

export interface BlockerRow { code: BlockerCode; message: string; runId: string | null; count: number }

// The backend adds one identical blocker per sandbox issue; show each distinct one once with a count.
export function groupBlockers(blockers: Blocker[]): BlockerRow[] {
  const rows = new Map<string, BlockerRow>();
  for (const b of blockers) {
    const key = `${b.code}|${b.runId ?? ""}|${b.message}`;
    const row = rows.get(key);
    if (row) row.count += 1;
    else rows.set(key, { code: b.code, message: b.message, runId: b.runId, count: 1 });
  }
  return [...rows.values()];
}

export function parseManifestInput(text: string): { ok: true; text: string } | { ok: false; error: string } {
  const trimmed = text.trim();
  if (!trimmed) return { ok: false, error: "Paste the manifest JSON." };
  let parsed: unknown;
  try { parsed = JSON.parse(trimmed); } catch { return { ok: false, error: "That isn't valid JSON." }; }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return { ok: false, error: "The manifest must be a JSON object." };
  return { ok: true, text: trimmed };
}
```

- [ ] **Step 4: Run tests and type check**

Run: `npx vitest run src/features/agents/model.test.ts && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 5: Commit**

```bash
git add src/features/agents
git commit -m "feat(frontend): agent status, failure wording, blocker grouping and manifest input checks"
```

---

### Task 4: Server actions

**Files:**
- Create: `src/app/(app)/developer/agents/actions.ts`
- Create: `src/app/(app)/developer/agents/actions.test.ts`

**Interfaces:**
- Consumes: `createAgent`, `createVersion`, `patchManifest`, `submitVersion` (Task 1); failure and message helpers (Task 3).
- Produces: `type Outcome = { ok: true; message?: string; status?: VersionStatus } | { ok: false; failure: Failure }` and
  - `createAgentAction(input: { name: string; category: string; description: string }): Promise<Outcome>`
  - `createVersionAction(agentId: string, input: { versionNumber: string; manifestJson: string; changelog: string }): Promise<Outcome>`
  - `patchAction(agentId: string, versionId: string, req: ManifestPatchRequest): Promise<Outcome>`
  - `submitAction(agentId: string, versionId: string): Promise<Outcome>`

- [ ] **Step 1: Write the failing tests**

Create `src/app/(app)/developer/agents/actions.test.ts`:

```ts
import { vi } from "vitest";

const api = vi.hoisted(() => ({ createAgent: vi.fn(), createVersion: vi.fn(), patchManifest: vi.fn(), submitVersion: vi.fn() }));
const revalidate = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/agents", () => api);
vi.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidate(...a) }));
import { ApiError } from "@/lib/api/http";
import { createAgentAction, createVersionAction, patchAction, submitAction } from "./actions";

beforeEach(() => { Object.values(api).forEach((m) => m.mockReset()); revalidate.mockReset(); });

it("creates an agent with trimmed fields, leaves a blank description out, and refreshes the list", async () => {
  api.createAgent.mockResolvedValue({ id: "a1" });
  expect(await createAgentAction({ name: " Bot ", category: " Finance ", description: "  " })).toEqual({ ok: true });
  expect(api.createAgent).toHaveBeenCalledWith({ name: "Bot", category: "Finance" });
  expect(revalidate).toHaveBeenCalledWith("/developer/agents");
});

it("sends a description when there is one", async () => {
  api.createAgent.mockResolvedValue({ id: "a1" });
  await createAgentAction({ name: "Bot", category: "Finance", description: "Matches invoices" });
  expect(api.createAgent).toHaveBeenCalledWith({ name: "Bot", category: "Finance", description: "Matches invoices" });
});

it("creates a version and leaves a blank changelog out", async () => {
  api.createVersion.mockResolvedValue({ id: "v1" });
  expect(await createVersionAction("a1", { versionNumber: " 1.0.0 ", manifestJson: "{}", changelog: "" })).toEqual({ ok: true });
  expect(api.createVersion).toHaveBeenCalledWith("a1", { versionNumber: "1.0.0", manifestJson: "{}" });
  expect(revalidate).toHaveBeenCalledWith("/developer/agents");
});

it("turns a hidden 400 on create version into a sentence under the manifest field, with no refresh", async () => {
  api.createVersion.mockRejectedValue(new ApiError(400, null, "Request failed (400)"));
  const res = await createVersionAction("a1", { versionNumber: "1.0.0", manifestJson: "{}", changelog: "" });
  expect(res).toMatchObject({ ok: false, failure: { field: "manifest" } });
  expect(JSON.stringify(res)).not.toMatch(/Request failed/);
  expect(revalidate).not.toHaveBeenCalled();
});

it("patches, confirms in plain words, and refreshes that Studio", async () => {
  api.patchManifest.mockResolvedValue({ manifestRevision: 2, diff: { addedScopes: [], removedScopes: [], addedHosts: [] } });
  const req = { op: "add_allowlist_host" as const, host: "api.example.com" };
  expect(await patchAction("a1", "v1", req)).toEqual({ ok: true, message: "Added host api.example.com" });
  expect(api.patchManifest).toHaveBeenCalledWith("a1", "v1", req);
  expect(revalidate).toHaveBeenCalledWith("/developer/agents/a1/versions/v1");
});

it("shows a patch conflict's own message", async () => {
  api.patchManifest.mockRejectedValue(new ApiError(409, "SCOPE_ALREADY_DECLARED", "A READ scope for 'x' is already declared"));
  expect(await patchAction("a1", "v1", { op: "add_read_scope", resource: "x" })).toEqual({ ok: false, failure: { text: "A READ scope for 'x' is already declared" } });
});

it("submits and reports the status that came back, refreshing the Studio and the list", async () => {
  api.submitVersion.mockResolvedValue({ id: "v1", versionNumber: "1.0.0", status: "LIVE" });
  expect(await submitAction("a1", "v1")).toEqual({ ok: true, message: "Submitted and published. This version is live.", status: "LIVE" });
  expect(revalidate).toHaveBeenCalledWith("/developer/agents/a1/versions/v1");
  expect(revalidate).toHaveBeenCalledWith("/developer/agents");
});

it("returns the verify flag when the backend says the org is not verified", async () => {
  api.submitVersion.mockRejectedValue(new ApiError(403, "BYOK_NOT_VERIFIED", "m"));
  expect(await submitAction("a1", "v1")).toMatchObject({ ok: false, failure: { verify: true } });
});

it("rethrows anything that is not an ApiError", async () => {
  api.submitVersion.mockRejectedValue(new Error("boom"));
  await expect(submitAction("a1", "v1")).rejects.toThrow("boom");
});
```

- [ ] **Step 2: Run to confirm it fails**

Run: `npx vitest run "src/app/(app)/developer/agents/actions.test.ts"`
Expected: FAIL, cannot resolve `./actions`.

- [ ] **Step 3: Create `src/app/(app)/developer/agents/actions.ts`**

```ts
"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { createAgent, createVersion, patchManifest, submitVersion } from "@/lib/api/agents";
import { createAgentFailure, createVersionFailure, patchConfirmation, patchFailure, submitFailure, submitMessage, type Failure } from "@/features/agents/model";
import type { ManifestPatchRequest, VersionStatus } from "@/lib/api/types";

export type Outcome = { ok: true; message?: string; status?: VersionStatus } | { ok: false; failure: Failure };

const list = "/developer/agents";
const studio = (agentId: string, versionId: string) => `${list}/${agentId}/versions/${versionId}`;

async function run(fn: () => Promise<{ message?: string; status?: VersionStatus }>, toFailure: (e: ApiError) => Failure, paths: string[]): Promise<Outcome> {
  try {
    const rest = await fn();
    paths.forEach((p) => revalidatePath(p));
    return { ok: true, ...rest };
  } catch (e) {
    if (e instanceof ApiError) return { ok: false, failure: toFailure(e) };
    throw e;
  }
}

export async function createAgentAction(input: { name: string; category: string; description: string }): Promise<Outcome> {
  const description = input.description.trim();
  return run(async () => {
    await createAgent({ name: input.name.trim(), category: input.category.trim(), ...(description ? { description } : {}) });
    return {};
  }, createAgentFailure, [list]);
}

export async function createVersionAction(agentId: string, input: { versionNumber: string; manifestJson: string; changelog: string }): Promise<Outcome> {
  const changelog = input.changelog.trim();
  return run(async () => {
    await createVersion(agentId, { versionNumber: input.versionNumber.trim(), manifestJson: input.manifestJson, ...(changelog ? { changelog } : {}) });
    return {};
  }, createVersionFailure, [list]);
}

export async function patchAction(agentId: string, versionId: string, req: ManifestPatchRequest): Promise<Outcome> {
  return run(async () => {
    await patchManifest(agentId, versionId, req);
    return { message: patchConfirmation(req) };
  }, patchFailure, [studio(agentId, versionId)]);
}

export async function submitAction(agentId: string, versionId: string): Promise<Outcome> {
  return run(async () => {
    const version = await submitVersion(agentId, versionId);
    return { message: submitMessage(version.status), status: version.status };
  }, submitFailure, [studio(agentId, versionId), list]);
}
```

- [ ] **Step 4: Run tests and type check**

Run: `npx vitest run "src/app/(app)/developer/agents/actions.test.ts" && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 5: Commit**

```bash
git add "src/app/(app)/developer/agents"
git commit -m "feat(frontend): agent, version, patch and submit server actions"
```

---

### Task 5: My agents list, new agent and new version forms

**Files:**
- Create: `src/ui/TextArea.tsx`, `src/ui/TextArea.module.css`, `src/ui/TextArea.test.tsx`
- Create: `src/features/agents/useGuarded.ts`
- Create: `src/features/agents/AgentForms.tsx`
- Create: `src/features/agents/AgentsView.tsx`
- Create: `src/features/agents/agents.module.css`
- Create: `src/features/agents/AgentsView.test.tsx`
- Create: `src/app/(app)/developer/agents/page.tsx`

**Interfaces:**
- Consumes: `createAgentAction`, `createVersionAction` (Task 4); `parseManifestInput`, `statusLabel`, `statusTone` (Task 3); `MyAgent` (Task 1).
- Produces: `TextArea` (props like `Field`: `label`, `error?`, `hint?` plus textarea attributes); `useGuarded(): { busy: boolean; run: (fn: () => Promise<void>) => Promise<void> }` (a second call while one is running is dropped); `AgentsView({ agents: MyAgent[] })`.

- [ ] **Step 1: Write the failing `TextArea` test**

Create `src/ui/TextArea.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { TextArea } from "./TextArea";

it("labels the box and ties its error to it", () => {
  render(<TextArea label="Manifest JSON" error="That isn't valid JSON." />);
  const box = screen.getByLabelText("Manifest JSON");
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box).toHaveAttribute("aria-describedby");
  expect(screen.getByText("That isn't valid JSON.")).toBeInTheDocument();
});

it("is valid when there is no error", () => {
  render(<TextArea label="Notes" hint="Optional" />);
  expect(screen.getByLabelText("Notes")).toHaveAttribute("aria-invalid", "false");
});
```

- [ ] **Step 2: Run to confirm it fails**

Run: `npx vitest run src/ui/TextArea.test.tsx`
Expected: FAIL, cannot resolve `./TextArea`.

- [ ] **Step 3: Create `TextArea`**

`src/ui/TextArea.tsx`:

```tsx
import { useId } from "react";
import styles from "./TextArea.module.css";

type Props = React.TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; error?: string; hint?: string };

export function TextArea({ label, error, hint, id, ...rest }: Props) {
  const auto = useId();
  const areaId = id ?? auto;
  const noteId = `${areaId}-note`;
  return (
    <div className={styles.field}>
      <label htmlFor={areaId}>{label}</label>
      <textarea id={areaId} aria-invalid={!!error} aria-describedby={error || hint ? noteId : undefined} {...rest} />
      {(error || hint) && (
        <span id={noteId} className={error ? styles.err : styles.hint}>
          {error ?? hint}
        </span>
      )}
    </div>
  );
}
```

`src/ui/TextArea.module.css`:

```css
.field { display: flex; flex-direction: column; gap: 6px; }
.field label { font-size: 13px; font-weight: 600; color: var(--ink2); }
.field textarea { min-height: 180px; padding: 10px 12px; border: 1px solid var(--l); border-radius: 10px; font: inherit; background: var(--s); color: var(--ink); resize: vertical; }
.field textarea[aria-invalid="true"] { border-color: var(--bad); }
.err { color: var(--bad); font-size: 12px; }
.hint { color: var(--ink3); font-size: 12px; }
```

- [ ] **Step 4: Run it**

Run: `npx vitest run src/ui/TextArea.test.tsx`
Expected: PASS.

- [ ] **Step 5: Create `useGuarded`**

`src/features/agents/useGuarded.ts`:

```ts
import { useRef, useState } from "react";

// React 18 does not hold isPending across an async transition, so a second click is dropped by hand.
export function useGuarded() {
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false);
  const run = async (fn: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try { await fn(); } finally { inFlight.current = false; setBusy(false); }
  };
  return { busy, run };
}
```

- [ ] **Step 6: Write the failing view tests**

Create `src/features/agents/AgentsView.test.tsx`:

```tsx
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const createAgent = vi.hoisted(() => vi.fn());
const createVersion = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/agents/actions", () => ({
  createAgentAction: (...a: unknown[]) => createAgent(...a),
  createVersionAction: (...a: unknown[]) => createVersion(...a),
}));
import { AgentsView } from "./AgentsView";
import type { MyAgent } from "@/lib/api/types";

const agent = (over: Partial<MyAgent> = {}): MyAgent => ({
  agentId: "a1", agentName: "Invoice bot", category: "Finance",
  versions: [{ id: "v1", versionNumber: "1.0.0", status: "DRAFT" }, { id: "v2", versionNumber: "1.1.0", status: "LIVE" }],
  ...over,
});

beforeEach(() => { createAgent.mockReset(); createVersion.mockReset(); });

const openNewAgent = () => userEvent.click(screen.getByRole("button", { name: "New agent" }));
const openNewVersion = () => userEvent.click(screen.getByRole("button", { name: "New version for Invoice bot" }));
const pasteManifest = async (text: string) => {
  await userEvent.click(screen.getByLabelText("Manifest JSON"));
  await userEvent.paste(text);
};

it("shows an empty state when there are no agents", () => {
  render(<AgentsView agents={[]} />);
  expect(screen.getByText("No agents yet")).toBeInTheDocument();
});

it("lists each agent with its versions as links to the Studio", () => {
  render(<AgentsView agents={[agent()]} />);
  expect(screen.getByText("Invoice bot")).toBeInTheDocument();
  expect(screen.getByText("Finance")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Open version 1.0.0, Draft" })).toHaveAttribute("href", "/developer/agents/a1/versions/v1");
  expect(screen.getByRole("link", { name: "Open version 1.1.0, Live" })).toHaveAttribute("href", "/developer/agents/a1/versions/v2");
});

it("says so when an agent has no versions", () => {
  render(<AgentsView agents={[agent({ versions: [] })]} />);
  expect(screen.getByText("No versions yet.")).toBeInTheDocument();
});

it("asks for a name and a category before creating an agent", async () => {
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.click(screen.getByRole("button", { name: "Create agent" }));
  expect(screen.getByText("Give the agent a name.")).toBeInTheDocument();
  expect(screen.getByText("Add a category, for example Finance.")).toBeInTheDocument();
  expect(createAgent).not.toHaveBeenCalled();
});

it("creates an agent and closes the form", async () => {
  createAgent.mockResolvedValue({ ok: true });
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.type(screen.getByLabelText("Name"), "Invoice bot");
  await userEvent.type(screen.getByLabelText("Category"), "Finance");
  await userEvent.click(screen.getByRole("button", { name: "Create agent" }));
  expect(createAgent).toHaveBeenCalledWith({ name: "Invoice bot", category: "Finance", description: "" });
  await waitFor(() => expect(screen.queryByRole("form", { name: "New agent" })).not.toBeInTheDocument());
});

it("shows the failure text when creating an agent fails", async () => {
  createAgent.mockResolvedValue({ ok: false, failure: { text: "Couldn't create the agent. Try again." } });
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.type(screen.getByLabelText("Name"), "Invoice bot");
  await userEvent.type(screen.getByLabelText("Category"), "Finance");
  await userEvent.click(screen.getByRole("button", { name: "Create agent" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Couldn't create the agent. Try again.");
});

it("sends one request when Create agent is clicked twice", async () => {
  let release!: (v: unknown) => void;
  createAgent.mockReturnValue(new Promise((r) => { release = r; }));
  render(<AgentsView agents={[]} />);
  await openNewAgent();
  await userEvent.type(screen.getByLabelText("Name"), "Invoice bot");
  await userEvent.type(screen.getByLabelText("Category"), "Finance");
  const create = screen.getByRole("button", { name: "Create agent" });
  await userEvent.click(create);
  await userEvent.click(create);
  expect(createAgent).toHaveBeenCalledTimes(1);
  release({ ok: true });
});

it("rejects an empty or invalid manifest before calling the backend", async () => {
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await userEvent.type(screen.getByLabelText("Version number"), "2.0.0");
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(screen.getByText("Paste the manifest JSON.")).toBeInTheDocument();
  await pasteManifest("{not json");
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(screen.getByText("That isn't valid JSON.")).toBeInTheDocument();
  expect(createVersion).not.toHaveBeenCalled();
});

it("asks for a version number", async () => {
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await pasteManifest('{"name":"x"}');
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(screen.getByText("Add a version number, for example 1.0.0.")).toBeInTheDocument();
  expect(createVersion).not.toHaveBeenCalled();
});

it("creates a version with the manifest text as pasted", async () => {
  createVersion.mockResolvedValue({ ok: true });
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await userEvent.type(screen.getByLabelText("Version number"), "2.0.0");
  await pasteManifest('{"name":"x"}');
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(createVersion).toHaveBeenCalledWith("a1", { versionNumber: "2.0.0", manifestJson: '{"name":"x"}', changelog: "" });
  await waitFor(() => expect(screen.queryByRole("form", { name: "New version" })).not.toBeInTheDocument());
});

it("shows a manifest failure under the manifest field", async () => {
  createVersion.mockResolvedValue({ ok: false, failure: { text: "Karya couldn't accept this manifest. Check that it has every required block and try again.", field: "manifest" } });
  render(<AgentsView agents={[agent()]} />);
  await openNewVersion();
  await userEvent.type(screen.getByLabelText("Version number"), "2.0.0");
  await pasteManifest('{"name":"x"}');
  await userEvent.click(screen.getByRole("button", { name: "Create version" }));
  expect(await screen.findByText(/couldn't accept this manifest/)).toBeInTheDocument();
  expect(screen.getByLabelText("Manifest JSON")).toHaveAttribute("aria-invalid", "true");
});
```

- [ ] **Step 7: Run to confirm it fails**

Run: `npx vitest run src/features/agents/AgentsView.test.tsx`
Expected: FAIL, cannot resolve `./AgentsView`.

- [ ] **Step 8: Create the forms**

`src/features/agents/AgentForms.tsx`:

```tsx
"use client";
import { useState } from "react";
import styles from "./agents.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { TextArea } from "@/ui/TextArea";
import { createAgentAction, createVersionAction } from "@/app/(app)/developer/agents/actions";
import { parseManifestInput } from "./model";
import { useGuarded } from "./useGuarded";

export function NewAgentForm({ onDone }: { onDone: () => void }) {
  const [name, setName] = useState("");
  const [category, setCategory] = useState("");
  const [description, setDescription] = useState("");
  const [errors, setErrors] = useState<{ name?: string; category?: string; form?: string }>({});
  const { busy, run } = useGuarded();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const next: typeof errors = {};
    if (!name.trim()) next.name = "Give the agent a name.";
    if (!category.trim()) next.category = "Add a category, for example Finance.";
    setErrors(next);
    if (next.name || next.category) return;
    void run(async () => {
      const res = await createAgentAction({ name, category, description });
      if (!res.ok) { setErrors({ form: res.failure.text }); return; }
      onDone();
    });
  };

  return (
    <form className={styles.form} aria-label="New agent" onSubmit={submit}>
      <Field label="Name" value={name} error={errors.name} onChange={(e) => setName(e.target.value)} />
      <Field label="Category" value={category} error={errors.category} onChange={(e) => setCategory(e.target.value)} />
      <Field label="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} />
      {errors.form && <p role="alert" className={styles.err}>{errors.form}</p>}
      <div className={styles.actions}>
        <Button type="submit" loading={busy}>Create agent</Button>
        <Button type="button" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </form>
  );
}

export function NewVersionForm({ agentId, onDone }: { agentId: string; onDone: () => void }) {
  const [versionNumber, setVersionNumber] = useState("");
  const [changelog, setChangelog] = useState("");
  const [manifest, setManifest] = useState("");
  const [errors, setErrors] = useState<{ versionNumber?: string; manifest?: string; form?: string }>({});
  const { busy, run } = useGuarded();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const next: typeof errors = {};
    if (!versionNumber.trim()) next.versionNumber = "Add a version number, for example 1.0.0.";
    const parsed = parseManifestInput(manifest);
    if (!parsed.ok) next.manifest = parsed.error;
    setErrors(next);
    if (next.versionNumber || !parsed.ok) return;
    void run(async () => {
      const res = await createVersionAction(agentId, { versionNumber, manifestJson: parsed.text, changelog });
      if (!res.ok) {
        setErrors(res.failure.field === "manifest" ? { manifest: res.failure.text } : { form: res.failure.text });
        return;
      }
      onDone();
    });
  };

  return (
    <form className={styles.form} aria-label="New version" onSubmit={submit}>
      <Field label="Version number" value={versionNumber} placeholder="1.0.0" error={errors.versionNumber} onChange={(e) => setVersionNumber(e.target.value)} />
      <Field label="Changelog (optional)" value={changelog} onChange={(e) => setChangelog(e.target.value)} />
      <TextArea label="Manifest JSON" className="mono" value={manifest} error={errors.manifest} onChange={(e) => setManifest(e.target.value)} />
      {errors.form && <p role="alert" className={styles.err}>{errors.form}</p>}
      <div className={styles.actions}>
        <Button type="submit" loading={busy}>Create version</Button>
        <Button type="button" variant="ghost" onClick={onDone}>Cancel</Button>
      </div>
    </form>
  );
}
```

- [ ] **Step 9: Create the view and styles**

`src/features/agents/AgentsView.tsx`:

```tsx
"use client";
import Link from "next/link";
import { useState } from "react";
import styles from "./agents.module.css";
import { Button } from "@/ui/Button";
import { EmptyState } from "@/ui/EmptyState";
import { Pill } from "@/ui/Pill";
import { NewAgentForm, NewVersionForm } from "./AgentForms";
import { statusLabel, statusTone } from "./model";
import type { MyAgent } from "@/lib/api/types";

export function AgentsView({ agents }: { agents: MyAgent[] }) {
  const [creating, setCreating] = useState(false);
  const [versionFor, setVersionFor] = useState<string | null>(null);

  return (
    <div className={styles.wrap}>
      {!creating && (
        <div className={styles.bar}>
          <Button onClick={() => setCreating(true)}>New agent</Button>
        </div>
      )}
      {creating && (
        <section className={styles.panel}>
          <h2 className={styles.h}>New agent</h2>
          <NewAgentForm onDone={() => setCreating(false)} />
        </section>
      )}
      {agents.length === 0 && !creating && <EmptyState title="No agents yet" body="Create your first agent, then add a version with its manifest." />}
      <ul className={styles.list}>
        {agents.map((a) => (
          <li key={a.agentId} className={styles.card}>
            <div className={styles.head}>
              <div>
                <strong>{a.agentName}</strong>
                <span className={styles.cat}>{a.category}</span>
              </div>
              <Button
                variant="ghost"
                aria-label={`New version for ${a.agentName}`}
                aria-expanded={versionFor === a.agentId}
                onClick={() => setVersionFor(versionFor === a.agentId ? null : a.agentId)}
              >
                New version
              </Button>
            </div>
            {a.versions.length === 0 ? (
              <p className={styles.muted}>No versions yet.</p>
            ) : (
              <ul className={styles.chips}>
                {a.versions.map((v) => (
                  <li key={v.id}>
                    <Link
                      href={`/developer/agents/${a.agentId}/versions/${v.id}`}
                      className={styles.chip}
                      aria-label={`Open version ${v.versionNumber}, ${statusLabel[v.status]}`}
                    >
                      <span className="mono">{v.versionNumber}</span>
                      <Pill tone={statusTone[v.status]}>{statusLabel[v.status]}</Pill>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            {versionFor === a.agentId && <NewVersionForm agentId={a.agentId} onDone={() => setVersionFor(null)} />}
          </li>
        ))}
      </ul>
    </div>
  );
}
```

`src/features/agents/agents.module.css`:

```css
.wrap { display: flex; flex-direction: column; gap: 16px; }
.bar { display: flex; }
.panel { background: var(--s); border: 1px solid var(--l); border-radius: 16px; padding: 20px; max-width: 640px; }
.h { font-size: 14px; font-weight: 600; margin: 0 0 12px; }
.form { display: flex; flex-direction: column; gap: 14px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.err { color: var(--bad); font-size: 13px; margin: 0; }
.list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.card { background: var(--s); border: 1px solid var(--l); border-radius: 16px; padding: 18px 20px; display: flex; flex-direction: column; gap: 12px; }
.head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.cat { margin-left: 10px; color: var(--ink3); font-size: 13px; }
.muted { margin: 0; color: var(--ink3); font-size: 13px; }
.chips { list-style: none; margin: 0; padding: 0; display: flex; gap: 8px; flex-wrap: wrap; }
.chip { display: inline-flex; align-items: center; gap: 8px; min-height: 44px; padding: 0 12px; border: 1px solid var(--l); border-radius: 12px; color: var(--ink); text-decoration: none; font-size: 13px; }
.chip:hover { border-color: var(--b3); background: var(--sky); }
```

- [ ] **Step 10: Create the page**

`src/app/(app)/developer/agents/page.tsx`:

```tsx
import { listMyAgents } from "@/lib/api/agents";
import { ApiError } from "@/lib/api/http";
import { AgentsView } from "@/features/agents/AgentsView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const agents = await listMyAgents();
    return (
      <>
        <h1>Agents</h1>
        <p>Your agents and their versions. Open a version to see what it declares and submit it.</p>
        <AgentsView agents={agents} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
```

- [ ] **Step 11: Run tests and type check**

Run: `npx vitest run src/features/agents src/ui/TextArea.test.tsx && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 12: Commit**

```bash
git add src/ui "src/features/agents" "src/app/(app)/developer/agents"
git commit -m "feat(frontend): my agents list with new agent and new version forms"
```

---

### Task 6: Studio, read-only half (declared cards and readiness banner)

**Files:**
- Create: `src/features/studio/fixtures.ts`
- Create: `src/features/studio/studio.module.css`
- Create: `src/features/studio/ReadinessBanner.tsx`, `src/features/studio/ReadinessBanner.test.tsx`
- Create: `src/features/studio/Declared.tsx`, `src/features/studio/Declared.test.tsx`
- Create: `src/app/(app)/developer/agents/[agentId]/versions/[versionId]/page.tsx`

**Interfaces:**
- Consumes: `groupBlockers`, `statusLabel`, `statusTone` (Task 3); `getVersion`, `getReadiness`, `listMyAgents` (Task 1); `Manifest`, `Readiness`, `BuildSummary` types.
- Produces: `manifest(over?)` and `empty` fixtures; `ReadinessBanner({ readiness: Readiness; build: BuildSummary | null })`; `Declared({ manifest: Manifest })`. Each declared card is a `<section>` named by its heading (role `region`): Scopes, Allowed hosts, Resources, Approval gates, Cost envelope, Data classes, Runtime, LLM access.

- [ ] **Step 1: Create the fixtures**

`src/features/studio/fixtures.ts`:

```ts
import type { Manifest } from "@/lib/api/types";

export const manifest = (over: Partial<Manifest> = {}): Manifest => ({
  name: "Bank Reconciliation",
  version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Read the statement", requiresApproval: false },
    { type: "WRITE", resource: "journal_entries", description: "Post entries", requiresApproval: true },
  ],
  networkAllowlist: ["api.example.com"],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "row" },
  approvalGates: [{ trigger: "post_entries", description: "Approve before posting", timeoutMinutes: 60, preview: null }],
  dataClasses: ["FINANCIAL"],
  resources: [{ name: "vendor_master", format: "CSV", schema: null }],
  llm: null,
  runtime: "python-3.12",
  entrypoint: "main.py",
  ...over,
});

export const empty = manifest({ scopes: [], networkAllowlist: [], approvalGates: [], dataClasses: [], resources: null, llm: null, runtime: null, entrypoint: null });
```

- [ ] **Step 2: Write the failing banner tests**

Create `src/features/studio/ReadinessBanner.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { ReadinessBanner } from "./ReadinessBanner";
import type { BuildSummary, Readiness } from "@/lib/api/types";

const build = (over: Partial<BuildSummary> = {}): BuildSummary => ({ buildId: "b1", status: "SUCCEEDED", failureCode: null, failureReason: null, logUrl: null, ...over });
const blocked = (...blockers: Readiness["blockers"]): Readiness => ({ ready: false, blockers });

it("says ready when nothing blocks", () => {
  render(<ReadinessBanner readiness={{ ready: true, blockers: [] }} build={build()} />);
  expect(screen.getByText("Ready to submit")).toBeInTheDocument();
});

it("shows a failed build with its reason and a log link", () => {
  render(<ReadinessBanner readiness={blocked({ code: "BUILD_NOT_READY", message: "no build", runId: null })} build={build({ status: "FAILED", failureReason: "pip install failed", logUrl: "https://logs.example.com/b1" })} />);
  expect(screen.getByText(/Build status: failed/)).toBeInTheDocument();
  expect(screen.getByText("pip install failed")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "View build log" })).toHaveAttribute("href", "https://logs.example.com/b1");
});

it("says there is no build yet when the version has none", () => {
  render(<ReadinessBanner readiness={blocked({ code: "BUILD_NOT_READY", message: "no build", runId: null })} build={null} />);
  expect(screen.getByText(/Build status: no build yet/)).toBeInTheDocument();
});

it("does not link a log address that is not http or a path", () => {
  render(<ReadinessBanner readiness={blocked({ code: "BUILD_NOT_READY", message: "x", runId: null })} build={build({ status: "FAILED", logUrl: "javascript:alert(1)" })} />);
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

it("shows repeated undeclared-access blockers once, with a count and the run as plain text", () => {
  const u = { code: "UNDECLARED_ACCESS" as const, message: "The last sandbox run attempted access outside its declared manifest", runId: "run-1" };
  render(<ReadinessBanner readiness={blocked(u, u, u)} build={build()} />);
  expect(screen.getAllByText(/attempted access outside/)).toHaveLength(1);
  expect(screen.getByText(/3 attempts/)).toBeInTheDocument();
  expect(screen.getByText("run-1")).toBeInTheDocument();
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

it("shows the cost envelope message", () => {
  render(<ReadinessBanner readiness={blocked({ code: "COST_ENVELOPE_EXCEEDED", message: "The last sandbox run asked for more than its cost envelope allows", runId: "run-9" })} build={build()} />);
  expect(screen.getByText("Over the cost envelope")).toBeInTheDocument();
  expect(screen.getByText(/more than its cost envelope allows/)).toBeInTheDocument();
});
```

- [ ] **Step 3: Run to confirm it fails**

Run: `npx vitest run src/features/studio/ReadinessBanner.test.tsx`
Expected: FAIL, cannot resolve `./ReadinessBanner`.

- [ ] **Step 4: Create the styles and the banner**

`src/features/studio/studio.module.css`:

```css
.back { display: inline-flex; align-items: center; min-height: 44px; font-size: 13px; color: var(--b); text-decoration: none; }
.head h1 { margin: 0 0 4px; }
.meta { margin: 0 0 16px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: var(--ink2); font-size: 14px; }
.banner { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 14px; border-radius: 12px; font-size: 13.5px; line-height: 1.5; margin-bottom: 16px; }
.banner a { color: inherit; font-weight: 600; text-decoration: underline; }
.ok { background: var(--okS); color: var(--ok); }
.bad { background: var(--badS); color: var(--bad); }
.blockers { list-style: none; margin: 0 0 16px; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.blocker { padding: 12px 14px; border-radius: 12px; background: var(--waS); color: var(--ink); font-size: 13.5px; line-height: 1.5; }
.blocker strong { display: block; color: var(--wa); }
.blocker p { margin: 4px 0 0; color: var(--ink2); }
.blocker a { color: var(--b); }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; margin: 16px 0; }
.card { background: var(--s); border: 1px solid var(--l); border-radius: 16px; padding: 18px 20px; display: flex; flex-direction: column; gap: 10px; }
.card h3 { margin: 0; font-size: 14px; font-weight: 600; }
.list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.row { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; flex-wrap: wrap; font-size: 13.5px; }
.actions { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sub { display: block; color: var(--ink3); font-size: 12px; }
.muted { margin: 0; color: var(--ink3); font-size: 13px; }
.pills { display: flex; gap: 6px; flex-wrap: wrap; }
.note { color: var(--ink3); font-size: 13px; margin: 8px 0 24px; }
.fix { background: var(--s); border: 1px solid var(--l); border-radius: 16px; padding: 20px; margin-bottom: 16px; }
.fix h2 { margin: 0 0 4px; font-size: 14px; font-weight: 600; }
.forms { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; margin-top: 12px; }
.form { display: flex; flex-direction: column; gap: 12px; }
.form h3 { margin: 0; font-size: 13px; font-weight: 600; }
.pick { display: flex; flex-direction: column; gap: 6px; }
.pick label { font-size: 13px; font-weight: 600; color: var(--ink2); }
.select { font: inherit; min-height: 44px; padding: 0 12px; border: 1px solid var(--l); border-radius: 10px; background: var(--s); color: var(--ink); }
.chk { display: flex; align-items: flex-start; gap: 10px; font-size: 13px; line-height: 1.45; }
.chk input { width: 16px; height: 16px; accent-color: var(--b); margin: 2px 0 0; flex-shrink: 0; }
.err { color: var(--bad); font-size: 12px; margin: 0; }
.submit { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 16px; }
.hint { margin: 0; font-size: 13px; color: var(--ink3); }
```

`src/features/studio/ReadinessBanner.tsx`:

```tsx
import styles from "./studio.module.css";
import { groupBlockers } from "@/features/agents/model";
import type { BlockerCode, BuildSummary, Readiness } from "@/lib/api/types";

const TITLES: Record<BlockerCode, string> = {
  BUILD_NOT_READY: "Build isn't ready",
  UNDECLARED_ACCESS: "Undeclared access",
  COST_ENVELOPE_EXCEEDED: "Over the cost envelope",
};
const BUILD_WORDS = { QUEUED: "queued", BUILDING: "building", SUCCEEDED: "succeeded", FAILED: "failed" } as const;

// Only an http(s) address or a site path is worth linking.
const safeHref = (url: string | null | undefined): string | null => (url && /^(https?:\/\/|\/)/.test(url) ? url : null);

export function ReadinessBanner({ readiness, build }: { readiness: Readiness; build: BuildSummary | null }) {
  if (readiness.ready) {
    return <div role="status" className={`${styles.banner} ${styles.ok}`}>Ready to submit</div>;
  }
  const logHref = safeHref(build?.logUrl);
  return (
    <ul className={styles.blockers} aria-label="Before you can submit">
      {groupBlockers(readiness.blockers).map((b) => (
        <li key={`${b.code}|${b.runId ?? ""}|${b.message}`} className={styles.blocker}>
          <strong>{TITLES[b.code]}</strong>
          {b.code === "BUILD_NOT_READY" && (
            <>
              <p>Build status: {build ? BUILD_WORDS[build.status] : "no build yet"}</p>
              {build?.status === "FAILED" && <p>{build.failureReason ?? "No reason was recorded."}</p>}
              {build?.status === "FAILED" && logHref && <p><a href={logHref} target="_blank" rel="noreferrer">View build log</a></p>}
            </>
          )}
          {b.code === "UNDECLARED_ACCESS" && (
            <>
              <p>{b.message}{b.count > 1 ? ` (${b.count} attempts)` : ""}</p>
              {b.runId && <p>Run <span className="mono">{b.runId}</span></p>}
            </>
          )}
          {b.code === "COST_ENVELOPE_EXCEEDED" && <p>{b.message}</p>}
        </li>
      ))}
    </ul>
  );
}
```

- [ ] **Step 5: Run the banner tests**

Run: `npx vitest run src/features/studio/ReadinessBanner.test.tsx`
Expected: PASS.

- [ ] **Step 6: Write the failing declared-cards tests**

Create `src/features/studio/Declared.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import { Declared } from "./Declared";
import { empty, manifest } from "./fixtures";

const card = (name: string) => screen.getByRole("region", { name });

it("lists every declared block", () => {
  render(<Declared manifest={manifest()} />);
  expect(within(card("Scopes")).getByText("bank_statement")).toBeInTheDocument();
  expect(within(card("Scopes")).getByText("Needs approval")).toBeInTheDocument();
  expect(within(card("Allowed hosts")).getByText("api.example.com")).toBeInTheDocument();
  expect(within(card("Resources")).getByText("vendor_master")).toBeInTheDocument();
  expect(within(card("Resources")).getByText("CSV")).toBeInTheDocument();
  expect(within(card("Approval gates")).getByText("post_entries")).toBeInTheDocument();
  expect(within(card("Approval gates")).getByText("60 min")).toBeInTheDocument();
  expect(within(card("Cost envelope")).getByText(/per row/)).toBeInTheDocument();
  expect(within(card("Cost envelope")).getByText("Pay per use")).toBeInTheDocument();
  expect(within(card("Data classes")).getByText("FINANCIAL")).toBeInTheDocument();
  expect(within(card("Runtime")).getByText("python-3.12")).toBeInTheDocument();
  expect(within(card("Runtime")).getByText("main.py")).toBeInTheDocument();
  expect(within(card("LLM access")).getByText("None")).toBeInTheDocument();
});

it.each(["Scopes", "Allowed hosts", "Resources", "Approval gates", "Data classes", "Runtime"])("says Not declared for an absent %s block", (name) => {
  render(<Declared manifest={empty} />);
  expect(within(card(name)).getByText("Not declared")).toBeInTheDocument();
});

it("describes an llm block", () => {
  render(<Declared manifest={manifest({ llm: { mode: "BYOK_PROXY", models: ["claude-x"], maxTokensPerRun: 50000, provider: "ANTHROPIC" } })} />);
  const llm = card("LLM access");
  expect(within(llm).getByText("Your key, through Karya")).toBeInTheDocument();
  expect(within(llm).getByText("claude-x")).toBeInTheDocument();
  expect(within(llm).getByText("50000")).toBeInTheDocument();
  expect(within(llm).getByText("ANTHROPIC")).toBeInTheDocument();
});

it("reads enum values in either case", () => {
  render(<Declared manifest={manifest({ resources: [{ name: "v", format: "csv", schema: null }], llm: { mode: "platform" as never, models: [], maxTokensPerRun: 1, provider: null } })} />);
  expect(within(card("Resources")).getByText("CSV")).toBeInTheDocument();
  expect(within(card("LLM access")).getByText("Karya's models")).toBeInTheDocument();
});
```

- [ ] **Step 7: Run to confirm it fails**

Run: `npx vitest run src/features/studio/Declared.test.tsx`
Expected: FAIL, cannot resolve `./Declared`.

- [ ] **Step 8: Create `Declared`**

`src/features/studio/Declared.tsx`:

```tsx
import styles from "./studio.module.css";
import { KeyValue } from "@/ui/KeyValue";
import { Pill } from "@/ui/Pill";
import { formatMoney } from "@/lib/format";
import type { Manifest } from "@/lib/api/types";

const PRICING: Record<string, string> = { METERED: "Pay per use", OUTCOME: "Pay per outcome", SUBSCRIPTION: "Subscription" };
const LLM: Record<string, string> = { PLATFORM: "Karya's models", BYOK_PROXY: "Your key, through Karya", BYOK_DIRECT: "Your key, direct" };
// The backend writes enum names in capitals; read them in either case so a lower-case wire value still gets its label.
const label = (table: Record<string, string>, value: string) => table[value.toUpperCase()] ?? value;

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  const id = `declared-${title.toLowerCase().replace(/\W+/g, "-")}`;
  return (
    <section className={styles.card} aria-labelledby={id}>
      <h3 id={id}>{title}</h3>
      {children}
    </section>
  );
}

const NotDeclared = () => <p className={styles.muted}>Not declared</p>;

export function Declared({ manifest: m }: { manifest: Manifest }) {
  const resources = m.resources ?? [];
  return (
    <div className={styles.grid}>
      <Card title="Scopes">
        {m.scopes.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {m.scopes.map((s) => (
              <li key={`${s.type}:${s.resource}`} className={styles.row}>
                <span>
                  {s.type === "READ" ? "Read" : "Write"} <span className="mono">{s.resource}</span>
                  {s.description && <span className={styles.sub}>{s.description}</span>}
                </span>
                {s.requiresApproval && <Pill tone="warn">Needs approval</Pill>}
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Allowed hosts">
        {m.networkAllowlist.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {m.networkAllowlist.map((h) => <li key={h} className="mono">{h}</li>)}
          </ul>
        )}
      </Card>
      <Card title="Resources">
        {resources.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {resources.map((r) => (
              <li key={r.name} className={styles.row}>
                <span className="mono">{r.name}</span>
                <Pill>{r.format.toUpperCase()}</Pill>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Approval gates">
        {m.approvalGates.length === 0 ? <NotDeclared /> : (
          <ul className={styles.list}>
            {m.approvalGates.map((g) => (
              <li key={g.trigger} className={styles.row}>
                <span>
                  <span className="mono">{g.trigger}</span>
                  <span className={styles.sub}>{g.description}</span>
                </span>
                <span>{g.timeoutMinutes} min</span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Cost envelope">
        {m.costEnvelope ? (
          <>
            <KeyValue label="Price">{`${formatMoney(m.costEnvelope.unitPrice, m.costEnvelope.currency)} per ${m.costEnvelope.unitLabel.replace(/_/g, " ")}`}</KeyValue>
            <KeyValue label="Most a run can cost">{formatMoney(m.costEnvelope.maxPerRun, m.costEnvelope.currency)}</KeyValue>
            <KeyValue label="Pricing">{label(PRICING, m.costEnvelope.pricingUnit)}</KeyValue>
          </>
        ) : <NotDeclared />}
      </Card>
      <Card title="Data classes">
        {m.dataClasses.length === 0 ? <NotDeclared /> : (
          <div className={styles.pills}>
            {m.dataClasses.map((d) => <Pill key={d}>{d}</Pill>)}
          </div>
        )}
      </Card>
      <Card title="Runtime">
        {m.runtime || m.entrypoint ? (
          <>
            <KeyValue label="Runtime"><span className="mono">{m.runtime ?? "-"}</span></KeyValue>
            <KeyValue label="Entrypoint"><span className="mono">{m.entrypoint ?? "-"}</span></KeyValue>
          </>
        ) : <NotDeclared />}
      </Card>
      <Card title="LLM access">
        {m.llm ? (
          <>
            <KeyValue label="Mode">{label(LLM, m.llm.mode)}</KeyValue>
            <KeyValue label="Models">{m.llm.models.length ? m.llm.models.join(", ") : "-"}</KeyValue>
            <KeyValue label="Most tokens per run">{String(m.llm.maxTokensPerRun)}</KeyValue>
            {m.llm.provider && <KeyValue label="Provider">{m.llm.provider}</KeyValue>}
          </>
        ) : <p className={styles.muted}>None</p>}
      </Card>
    </div>
  );
}
```

Note: the `Runtime` card heading and the `Runtime` key-value label share a word; the tests look inside the card by region name, so that is fine.

- [ ] **Step 9: Run the declared tests**

Run: `npx vitest run src/features/studio/Declared.test.tsx`
Expected: PASS. If `getByText("claude-x")` or `getByText("50000")` fails because the value shares a node with other text, keep the value in its own `KeyValue` child as written above.

- [ ] **Step 10: Create the Studio page (read-only for now)**

`src/app/(app)/developer/agents/[agentId]/versions/[versionId]/page.tsx`:

```tsx
import Link from "next/link";
import { notFound } from "next/navigation";
import { getReadiness, getVersion, listMyAgents } from "@/lib/api/agents";
import { ApiError } from "@/lib/api/http";
import { Declared } from "@/features/studio/Declared";
import { ReadinessBanner } from "@/features/studio/ReadinessBanner";
import { statusLabel, statusTone } from "@/features/agents/model";
import styles from "@/features/studio/studio.module.css";
import { ErrorState } from "@/ui/ErrorState";
import { Pill } from "@/ui/Pill";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const { agentId, versionId } = params;
  try {
    const [detail, readiness, agents] = await Promise.all([
      getVersion(agentId, versionId),
      getReadiness(agentId, versionId),
      // The category is a nicety. Losing it must not take the Studio down, but a sign-in redirect must still go through.
      listMyAgents().catch((e) => { if (e instanceof ApiError) return []; throw e; }),
    ]);
    const category = agents.find((a) => a.agentId === agentId)?.category;
    return (
      <>
        <Link href="/developer/agents" className={styles.back}>← Agents</Link>
        <header className={styles.head}>
          <h1>{detail.agentName}</h1>
          <p className={styles.meta}>
            {category && <span>{category} ·</span>}
            <span>Version <span className="mono">{detail.versionNumber}</span></span>
            <Pill tone={statusTone[detail.status]}>{statusLabel[detail.status]}</Pill>
          </p>
        </header>
        <ReadinessBanner readiness={readiness} build={detail.build ?? null} />
        <Declared manifest={detail.manifest} />
        <p className={styles.note}>What the agent actually did will appear here once sandbox runs are available.</p>
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) {
      // The backend answers 403 or 404 for a version that is not yours; both look like "not found".
      if (e.status === 403 || e.status === 404) notFound();
      return <ErrorState message={e.message} />;
    }
    throw e;
  }
}
```

- [ ] **Step 11: Run tests and type check**

Run: `npx vitest run src/features/studio && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 12: Commit**

```bash
git add src/features/studio "src/app/(app)/developer/agents"
git commit -m "feat(frontend): Studio with readiness banner and declared cards"
```

---

### Task 7: Studio actions (patch forms, remove scope, submit)

**Files:**
- Create: `src/features/studio/feedback.tsx`
- Create: `src/features/studio/StudioActions.tsx`
- Create: `src/features/studio/StudioActions.test.tsx`
- Modify: `src/features/studio/Declared.tsx` (Remove button on scope rows)
- Modify: `src/app/(app)/developer/agents/[agentId]/versions/[versionId]/page.tsx`

**Interfaces:**
- Consumes: `patchAction`, `submitAction` (Task 4); `useGuarded` (Task 5); `DATA_FORMATS` (Task 1); `Declared`, fixtures (Task 6).
- Produces:
  - `feedback.tsx`: `FeedbackProvider({ children })`, `FeedbackBanner()`, `useStudio(): { message: Message | null; setMessage(m: Message | null): void; busy: boolean; run(fn): Promise<void> }`, `type Message = { tone: "ok" | "bad"; text: string; verify?: boolean }`. One `run` guards every Studio mutation, so only one is in flight at a time.
  - `StudioActions.tsx`: `usePatch(agentId, versionId)`, `RemoveScopeButton({ agentId, versionId, scope })`, `PatchForms({ agentId, versionId })`, `SubmitBar({ agentId, versionId, status, ready })`.
  - `Declared` gains optional `editable?: { agentId: string; versionId: string }`; when set, each scope row shows Remove.

- [ ] **Step 1: Write the failing tests**

Create `src/features/studio/StudioActions.test.tsx`:

```tsx
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const patch = vi.hoisted(() => vi.fn());
const submit = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/agents/actions", () => ({
  patchAction: (...a: unknown[]) => patch(...a),
  submitAction: (...a: unknown[]) => submit(...a),
}));
import { Declared } from "./Declared";
import { FeedbackBanner, FeedbackProvider } from "./feedback";
import { manifest } from "./fixtures";
import { PatchForms, SubmitBar } from "./StudioActions";

beforeEach(() => { patch.mockReset(); submit.mockReset(); });

const ui = (children: React.ReactNode) => render(<FeedbackProvider><FeedbackBanner />{children}</FeedbackProvider>);
const form = (name: string) => screen.getByRole("form", { name });

it("adds an allowed host, confirms it and clears the field", async () => {
  patch.mockResolvedValue({ ok: true, message: "Added host api.example.com" });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add an allowed host");
  await userEvent.type(within(f).getByLabelText("Host"), " api.example.com ");
  await userEvent.click(within(f).getByRole("button", { name: "Add host" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "add_allowlist_host", host: "api.example.com" });
  expect(await screen.findByRole("status")).toHaveTextContent("Added host api.example.com");
  expect(within(f).getByLabelText("Host")).toHaveValue("");
});

it("rejects a host with a scheme, a path or spaces without calling the backend", async () => {
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add an allowed host");
  await userEvent.type(within(f).getByLabelText("Host"), "https://api.example.com/x");
  await userEvent.click(within(f).getByRole("button", { name: "Add host" }));
  expect(within(f).getByText("Enter just the host, like api.example.com.")).toBeInTheDocument();
  expect(patch).not.toHaveBeenCalled();
});

it("asks for the missing field in each form", async () => {
  ui(<PatchForms agentId="a1" versionId="v1" />);
  await userEvent.click(within(form("Add a read scope")).getByRole("button", { name: "Add scope" }));
  await userEvent.click(within(form("Add a resource")).getByRole("button", { name: "Add resource" }));
  await userEvent.click(within(form("Add an allowed host")).getByRole("button", { name: "Add host" }));
  expect(screen.getByText("Enter the resource to read, for example bank_statement.")).toBeInTheDocument();
  expect(screen.getByText("Enter a name for the resource.")).toBeInTheDocument();
  expect(screen.getByText("Enter a host, like api.example.com.")).toBeInTheDocument();
  expect(patch).not.toHaveBeenCalled();
});

it("adds a read scope with its approval flag", async () => {
  patch.mockResolvedValue({ ok: true, message: "Added read scope vendor_master" });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add a read scope");
  await userEvent.type(within(f).getByLabelText("Resource"), "vendor_master");
  await userEvent.type(within(f).getByLabelText("Description"), "Read vendors");
  await userEvent.click(within(f).getByLabelText("Needs approval before it runs"));
  await userEvent.click(within(f).getByRole("button", { name: "Add scope" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "add_read_scope", resource: "vendor_master", description: "Read vendors", requiresApproval: true });
});

it("adds a resource with its format in lower case", async () => {
  patch.mockResolvedValue({ ok: true, message: "Added resource vendor_master" });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add a resource");
  await userEvent.type(within(f).getByLabelText("Name"), "vendor_master");
  await userEvent.selectOptions(within(f).getByLabelText("Format"), "PDF");
  await userEvent.click(within(f).getByRole("button", { name: "Add resource" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "add_resource", name: "vendor_master", format: "pdf" });
});

it("shows a patch failure in the banner and keeps what was typed", async () => {
  patch.mockResolvedValue({ ok: false, failure: { text: "A READ scope for 'x' is already declared" } });
  ui(<PatchForms agentId="a1" versionId="v1" />);
  const f = form("Add a read scope");
  await userEvent.type(within(f).getByLabelText("Resource"), "x");
  await userEvent.click(within(f).getByRole("button", { name: "Add scope" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("A READ scope for 'x' is already declared");
  expect(within(f).getByLabelText("Resource")).toHaveValue("x");
});

it("removes a scope from the declared card", async () => {
  patch.mockResolvedValue({ ok: true, message: "Removed read scope bank_statement" });
  ui(<Declared manifest={manifest()} editable={{ agentId: "a1", versionId: "v1" }} />);
  await userEvent.click(screen.getByRole("button", { name: "Remove read scope bank_statement" }));
  expect(patch).toHaveBeenCalledWith("a1", "v1", { op: "remove_scope", type: "READ", resource: "bank_statement" });
  expect(await screen.findByRole("status")).toHaveTextContent("Removed read scope bank_statement");
});

it("offers no Remove buttons when the version is not editable", () => {
  render(<Declared manifest={manifest()} />);
  expect(screen.queryByRole("button", { name: /Remove/ })).not.toBeInTheDocument();
});

it("disables Submit until the version is ready, and says why", () => {
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready={false} />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  expect(screen.getByText("Fix the items above before you submit.")).toBeInTheDocument();
});

it.each([
  ["IN_REVIEW", /in review/],
  ["LIVE", /is live/],
  ["REJECTED", /was rejected/],
] as const)("keeps Submit disabled for a %s version, even when ready", (status, note) => {
  ui(<SubmitBar agentId="a1" versionId="v1" status={status} ready />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  expect(screen.getByText(note)).toBeInTheDocument();
});

it("submits a ready draft and words the result from the returned status", async () => {
  submit.mockResolvedValue({ ok: true, message: "Submitted and published. This version is live.", status: "LIVE" });
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready />);
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(submit).toHaveBeenCalledWith("a1", "v1");
  expect(await screen.findByRole("status")).toHaveTextContent("Submitted and published. This version is live.");
});

it("links to Get verified when the org is not verified", async () => {
  submit.mockResolvedValue({ ok: false, failure: { text: "Only verified developers can submit an agent that uses its own LLM key. Get verified first.", verify: true } });
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready />);
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Get verified first.");
  expect(screen.getByRole("link", { name: "Get verified" })).toHaveAttribute("href", "/developer/verification");
});

it("sends one request when Submit is clicked twice", async () => {
  let release!: (v: unknown) => void;
  submit.mockReturnValue(new Promise((r) => { release = r; }));
  ui(<SubmitBar agentId="a1" versionId="v1" status="DRAFT" ready />);
  const button = screen.getByRole("button", { name: "Submit for review" });
  await userEvent.click(button);
  await userEvent.click(button);
  expect(submit).toHaveBeenCalledTimes(1);
  release({ ok: true, message: "Submitted.", status: "IN_REVIEW" });
  await waitFor(() => expect(button).toBeEnabled());
});
```

- [ ] **Step 2: Run to confirm it fails**

Run: `npx vitest run src/features/studio/StudioActions.test.tsx`
Expected: FAIL, cannot resolve `./feedback`.

- [ ] **Step 3: Create `feedback.tsx`**

`src/features/studio/feedback.tsx`:

```tsx
"use client";
import Link from "next/link";
import { createContext, useContext, useEffect, useRef, useState } from "react";
import styles from "./studio.module.css";
import { useGuarded } from "@/features/agents/useGuarded";

export type Message = { tone: "ok" | "bad"; text: string; verify?: boolean };
interface Studio {
  message: Message | null;
  setMessage: (m: Message | null) => void;
  busy: boolean;
  run: (fn: () => Promise<void>) => Promise<void>;
}

const Ctx = createContext<Studio | null>(null);

// One provider holds the banner message and a single in-flight guard, so a patch and a submit can never overlap.
export function FeedbackProvider({ children }: { children: React.ReactNode }) {
  const [message, setMessage] = useState<Message | null>(null);
  const { busy, run } = useGuarded();
  return <Ctx.Provider value={{ message, setMessage, busy, run }}>{children}</Ctx.Provider>;
}

export function useStudio(): Studio {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useStudio must be used inside FeedbackProvider");
  return ctx;
}

export function FeedbackBanner() {
  const { message } = useStudio();
  const ref = useRef<HTMLDivElement>(null);
  // The Submit button is at the bottom of the page; bring the result into view.
  useEffect(() => { if (message) ref.current?.scrollIntoView?.({ block: "nearest" }); }, [message]);
  if (!message) return null;
  const bad = message.tone === "bad";
  return (
    <div ref={ref} role={bad ? "alert" : "status"} className={`${styles.banner} ${bad ? styles.bad : styles.ok}`}>
      <span>{message.text}</span>
      {message.verify && <Link href="/developer/verification">Get verified</Link>}
    </div>
  );
}
```

- [ ] **Step 4: Create `StudioActions.tsx`**

`src/features/studio/StudioActions.tsx`:

```tsx
"use client";
import { useState } from "react";
import styles from "./studio.module.css";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { patchAction, submitAction } from "@/app/(app)/developer/agents/actions";
import { DATA_FORMATS } from "@/lib/api/enums";
import type { ManifestPatchRequest, Scope, VersionStatus } from "@/lib/api/types";
import { useStudio } from "./feedback";

type Patch = (req: ManifestPatchRequest, onOk?: () => void) => Promise<void>;

export function usePatch(agentId: string, versionId: string): Patch {
  const { setMessage, run } = useStudio();
  return (req, onOk) =>
    run(async () => {
      setMessage(null);
      const res = await patchAction(agentId, versionId, req);
      if (!res.ok) { setMessage({ tone: "bad", text: res.failure.text }); return; }
      setMessage({ tone: "ok", text: res.message ?? "Saved." });
      onOk?.();
    });
}

export function RemoveScopeButton({ agentId, versionId, scope }: { agentId: string; versionId: string; scope: Scope }) {
  const patch = usePatch(agentId, versionId);
  const { busy } = useStudio();
  return (
    <Button
      variant="ghost"
      loading={busy}
      aria-label={`Remove ${scope.type.toLowerCase()} scope ${scope.resource}`}
      onClick={() => void patch({ op: "remove_scope", type: scope.type, resource: scope.resource })}
    >
      Remove
    </Button>
  );
}

function AddScopeForm({ patch }: { patch: Patch }) {
  const { busy } = useStudio();
  const [resource, setResource] = useState("");
  const [description, setDescription] = useState("");
  const [approval, setApproval] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!resource.trim()) { setError("Enter the resource to read, for example bank_statement."); return; }
    setError(null);
    void patch({ op: "add_read_scope", resource: resource.trim(), description: description.trim(), requiresApproval: approval }, () => {
      setResource(""); setDescription(""); setApproval(false);
    });
  };

  return (
    <form className={styles.form} aria-label="Add a read scope" onSubmit={submit}>
      <h3>Add a read scope</h3>
      <Field label="Resource" value={resource} error={error ?? undefined} onChange={(e) => setResource(e.target.value)} />
      <Field label="Description" value={description} onChange={(e) => setDescription(e.target.value)} />
      <label className={styles.chk}>
        <input type="checkbox" checked={approval} onChange={(e) => setApproval(e.target.checked)} />
        Needs approval before it runs
      </label>
      <div><Button type="submit" loading={busy}>Add scope</Button></div>
    </form>
  );
}

function AddResourceForm({ patch }: { patch: Patch }) {
  const { busy } = useStudio();
  const [name, setName] = useState("");
  const [format, setFormat] = useState<string>(DATA_FORMATS[0]);
  const [error, setError] = useState<string | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) { setError("Enter a name for the resource."); return; }
    setError(null);
    void patch({ op: "add_resource", name: name.trim(), format: format.toLowerCase() }, () => setName(""));
  };

  return (
    <form className={styles.form} aria-label="Add a resource" onSubmit={submit}>
      <h3>Add a resource</h3>
      <Field label="Name" value={name} error={error ?? undefined} onChange={(e) => setName(e.target.value)} />
      <div className={styles.pick}>
        <label htmlFor="resource-format">Format</label>
        <select id="resource-format" className={styles.select} value={format} onChange={(e) => setFormat(e.target.value)}>
          {DATA_FORMATS.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
      </div>
      <div><Button type="submit" loading={busy}>Add resource</Button></div>
    </form>
  );
}

function AddHostForm({ patch }: { patch: Patch }) {
  const { busy } = useStudio();
  const [host, setHost] = useState("");
  const [error, setError] = useState<string | null>(null);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const value = host.trim();
    if (!value) { setError("Enter a host, like api.example.com."); return; }
    if (/\s/.test(value) || value.includes("/")) { setError("Enter just the host, like api.example.com."); return; }
    setError(null);
    void patch({ op: "add_allowlist_host", host: value }, () => setHost(""));
  };

  return (
    <form className={styles.form} aria-label="Add an allowed host" onSubmit={submit}>
      <h3>Add an allowed host</h3>
      <Field label="Host" value={host} error={error ?? undefined} onChange={(e) => setHost(e.target.value)} />
      <div><Button type="submit" loading={busy}>Add host</Button></div>
    </form>
  );
}

export function PatchForms({ agentId, versionId }: { agentId: string; versionId: string }) {
  const patch = usePatch(agentId, versionId);
  return (
    <section className={styles.fix} aria-labelledby="fix-heading">
      <h2 id="fix-heading">Fix the manifest</h2>
      <p className={styles.hint}>Each change updates the manifest straight away.</p>
      <div className={styles.forms}>
        <AddScopeForm patch={patch} />
        <AddResourceForm patch={patch} />
        <AddHostForm patch={patch} />
      </div>
    </section>
  );
}

const NOTES: Record<VersionStatus, string | null> = {
  DRAFT: null,
  IN_REVIEW: "This version is in review. It can't be edited or submitted again.",
  LIVE: "This version is live and can't be edited.",
  REJECTED: "This version was rejected and can't be edited.",
};

export function SubmitBar({ agentId, versionId, status, ready }: { agentId: string; versionId: string; status: VersionStatus; ready: boolean }) {
  const { setMessage, busy, run } = useStudio();
  const draft = status === "DRAFT";

  const submit = () =>
    void run(async () => {
      setMessage(null);
      const res = await submitAction(agentId, versionId);
      if (!res.ok) { setMessage({ tone: "bad", text: res.failure.text, verify: res.failure.verify }); return; }
      setMessage({ tone: "ok", text: res.message ?? "Submitted." });
    });

  return (
    <div className={styles.submit}>
      <Button onClick={submit} loading={busy} disabled={!draft || !ready}>Submit for review</Button>
      {draft && !ready && <p className={styles.hint}>Fix the items above before you submit.</p>}
      {NOTES[status] && <p className={styles.hint}>{NOTES[status]}</p>}
    </div>
  );
}
```

- [ ] **Step 5: Add Remove to `Declared`**

In `src/features/studio/Declared.tsx`:

Add the import under the existing imports:

```tsx
import { RemoveScopeButton } from "./StudioActions";
```

Replace the signature `export function Declared({ manifest: m }: { manifest: Manifest }) {` with:

```tsx
export function Declared({ manifest: m, editable }: { manifest: Manifest; editable?: { agentId: string; versionId: string } }) {
```

Replace `{s.requiresApproval && <Pill tone="warn">Needs approval</Pill>}` with:

```tsx
<span className={styles.actions}>
                  {s.requiresApproval && <Pill tone="warn">Needs approval</Pill>}
                  {editable && <RemoveScopeButton agentId={editable.agentId} versionId={editable.versionId} scope={s} />}
                </span>
```

- [ ] **Step 6: Wire the Studio page**

Replace `src/app/(app)/developer/agents/[agentId]/versions/[versionId]/page.tsx` with:

```tsx
import Link from "next/link";
import { notFound } from "next/navigation";
import { getReadiness, getVersion, listMyAgents } from "@/lib/api/agents";
import { ApiError } from "@/lib/api/http";
import { Declared } from "@/features/studio/Declared";
import { ReadinessBanner } from "@/features/studio/ReadinessBanner";
import { FeedbackBanner, FeedbackProvider } from "@/features/studio/feedback";
import { PatchForms, SubmitBar } from "@/features/studio/StudioActions";
import { statusLabel, statusTone } from "@/features/agents/model";
import styles from "@/features/studio/studio.module.css";
import { ErrorState } from "@/ui/ErrorState";
import { Pill } from "@/ui/Pill";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const { agentId, versionId } = params;
  try {
    const [detail, readiness, agents] = await Promise.all([
      getVersion(agentId, versionId),
      getReadiness(agentId, versionId),
      // The category is a nicety. Losing it must not take the Studio down, but a sign-in redirect must still go through.
      listMyAgents().catch((e) => { if (e instanceof ApiError) return []; throw e; }),
    ]);
    const category = agents.find((a) => a.agentId === agentId)?.category;
    const draft = detail.status === "DRAFT";
    return (
      <FeedbackProvider>
        <Link href="/developer/agents" className={styles.back}>← Agents</Link>
        <header className={styles.head}>
          <h1>{detail.agentName}</h1>
          <p className={styles.meta}>
            {category && <span>{category} ·</span>}
            <span>Version <span className="mono">{detail.versionNumber}</span></span>
            <Pill tone={statusTone[detail.status]}>{statusLabel[detail.status]}</Pill>
          </p>
        </header>
        <FeedbackBanner />
        <ReadinessBanner readiness={readiness} build={detail.build ?? null} />
        <Declared manifest={detail.manifest} editable={draft ? { agentId, versionId } : undefined} />
        <p className={styles.note}>What the agent actually did will appear here once sandbox runs are available.</p>
        {draft && <PatchForms agentId={agentId} versionId={versionId} />}
        <SubmitBar agentId={agentId} versionId={versionId} status={detail.status} ready={readiness.ready} />
      </FeedbackProvider>
    );
  } catch (e) {
    if (e instanceof ApiError) {
      // The backend answers 403 or 404 for a version that is not yours; both look like "not found".
      if (e.status === 403 || e.status === 404) notFound();
      return <ErrorState message={e.message} />;
    }
    throw e;
  }
}
```

- [ ] **Step 7: Run tests and type check**

Run: `npx vitest run src/features && npx tsc --noEmit`
Expected: PASS, no type errors. If a label query is ambiguous (for example two fields named "Name" on the page), the tests scope every query with `within(form(...))`, so it should not be; fix the test query, not the label, if it happens.

- [ ] **Step 8: Commit**

```bash
git add src/features/studio "src/app/(app)/developer/agents"
git commit -m "feat(frontend): Studio patch forms, remove scope and submit"
```

---

### Task 8: Docs and final verification

**Files:**
- Modify: `docs/frontend-gaps.md`

- [ ] **Step 1: Record the gaps**

In `docs/frontend-gaps.md`, add these rows to the gaps table directly after the `G15` row:

```
| G16 | Studio "what it did" half and flow canvas | Need sandbox run endpoints that are not on master; deferred to P3c. The Studio shows a note instead |
| G17 | Validator and submit-failure reasons | `POST /agents/{a}/versions` 400 and `POST …/submit` 403/409 carry no message (the app hides `ResponseStatusException` reasons). The UI shows fixed sentences per status; a `{code, message}` body from the backend would let it show the real reason |
| G18 | "What this patch changed" | `ManifestPatchResponse.diff` is computed against the LIVE version, not the previous draft, so the UI builds its confirmation from the request. A diff against the prior draft would let it show real before/after |
```

Under "Known visual differences", add:

```
- The Studio has no flow canvas and no observed-behaviour half (G16).
```

- [ ] **Step 2: Run the full suite and type check**

Run: `npx vitest run && npx tsc --noEmit`
Expected: all tests pass, no type errors. Report the exact counts. If anything outside this slice fails, say so; do not claim a clean run.

- [ ] **Step 3: Commit**

```bash
git add ../docs/frontend-gaps.md
git commit -m "docs: record Studio gaps G16 to G18"
```

- [ ] **Step 4: Hand back**

Do not push or open the PR. Report: branch `feat/p3b-agents-studio`, test and type-check results, the seven deviations above, and that nothing was checked in a browser.

---

## Self-review

**Spec coverage:**
- §2 routes and rail → Task 2 (rail, gating pin), Tasks 5 and 6 (pages); ownership 403/404 → `notFound()` in Task 6/7 page.
- §3 data layer and types → Task 1 (wrappers, types, enum pins read from the Java source); server actions → Task 4.
- §4 My agents (rows, chips, New agent, New version with manifest field errors, empty state) → Task 5.
- §5 Studio (header, readiness banner per blocker, declared cards with "Not declared", behaviour note, actions) → Tasks 6 and 7.
- §6 patches and submit (DRAFT only, Remove, three inline forms, confirmation, submit enablement, verification banner, other errors in the top banner) → Task 7, with deviations 1 to 4 where the backend differs from the spec.
- §7 testing: wrappers, enum pins, error mapping (Tasks 1, 3, 4); components (Tasks 5 to 7); actions incl. revalidation (Task 4); rail and gating (Task 2); `tsc` (each task and Task 8). Browser check stated in Task 8.
- §8 docs → Task 8.

**Placeholder scan:** no TBD, TODO or "similar to"; every code step has its code.

**Type consistency:** `Failure`, `Outcome`, `Message`, `Patch`, `BlockerRow`, `VersionSummary` are defined once and used with the same names later. `submitMessage` and `patchConfirmation` are defined in Task 3 and used in Task 4. `useGuarded` (Task 5) is used by `feedback.tsx` (Task 7). `Declared`'s `editable` prop is added in Task 7 and used by the Task 7 page.
