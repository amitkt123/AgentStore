# UX-1 Portal Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every UX-1 finding in the roadmap without touching the backend.

**Architecture:** Changes stay inside `frontend/src`. Shared formatting goes in `lib/format.ts`. Each feature folder keeps its own view and test. Server components fetch, client components hold state, as today.

**Tech Stack:** Next.js 14 app router, React 18, CSS modules, Vitest + Testing Library, Playwright for e2e.

**Spec:** `docs/superpowers/specs/2026-10-22-ux-roadmap.md` (section "UX-1").

## Global Constraints

- No backend, contract or migration change. If a task needs one, stop and move it to UX-2.
- Copy is plain English in sentence case, addressed to "you"; the product name is "Karya".
- No new npm dependencies.
- Tests are co-located `*.test.tsx` / `*.test.ts`. Run from `frontend/`: `npx vitest run <path>`; before each commit run `npx tsc --noEmit`.
- Update `docs/status.md` (UX-1 row and "Last updated") and `docs/frontend-gaps.md` (Known visual differences: remove the fixed entries) in the last task.
- No AI attribution in commits or the PR.

## Review Focus

1. **A buyer with an agent already added opens its store page** and should see "Added", with Run now instead of a second Add. Pinned in Task 4.
2. **A gate preview with mixed cell types** (strings, numbers, null, numeric strings like `"18450.75"`): numbers are right-aligned with Indian grouping, and null renders blank, not "null". Pinned in Task 6.
3. **A run step whose `detail` is empty or very long:** the timeline shows the kind text alone, or truncates the note to 200 characters with the full text in `title`. Pinned in Task 5.
4. **A timestamp from a previous day or year:** it shows a date (`21 Oct, 14:17`) and adds the year if it isn't the current one. Today stays time only. Pinned in Task 2.
5. **Two LIVE versions of one agent whose numbers sort differently as strings** (`0.10.0` vs `0.9.0`): the store keeps `0.10.0`. Pinned in Task 4.

---

### Task 1: Shell: account menu, labelled rail, no dead search

**Files:**
- Create: `ui/AccountMenu.tsx`, `ui/AccountMenu.module.css`, `ui/AccountMenu.test.tsx`
- Modify: `ui/Topbar.tsx`, `ui/Topbar.module.css`, `ui/Rail.tsx`, `ui/Rail.module.css`, `ui/Rail.test.tsx`, `ui/AppShell.tsx`, `app/(app)/layout.tsx`

**Interfaces:**
- Produces: `AccountMenu({ fullName, email, orgName }: { fullName: string; email: string; orgName: string })`. A `<details>`-based menu showing name, email, workspace, and a "Sign out" button in a `<form action={logoutAction}>`.
- Produces: `Topbar({ orgName, initials, fullName, email })`. The search box is removed.

- [ ] **Step 1: Write failing tests**

`AccountMenu.test.tsx`:
```tsx
it("shows who is signed in and offers sign out", async () => {
  render(<AccountMenu fullName="Demo Buyer" email="b@example.com" orgName="Demo buyer Org" />);
  await userEvent.click(screen.getByRole("button", { name: /your account/i })); // the <summary>
  expect(screen.getByText("b@example.com")).toBeVisible();
  expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
});
```
`Rail.test.tsx` (add):
```tsx
it("shows each item's label as visible text", () => {
  render(<Rail items={items} activePath="/home" />);
  expect(screen.getByText("Workspace")).toBeVisible();
});
```
A Topbar test asserts `screen.queryByRole("search")` is null.

- [ ] **Step 2: Run, expect FAIL.** Run: `npx vitest run src/ui`.
- [ ] **Step 3: Implement.** The rail renders `<span className={styles.label}>{item.label}</span>` under the icon. On desktop the rail is 72px wide, with 10px labels below the icons. On mobile the labels show in the bottom bar. Keep `aria-label` for the badge wording. In the Topbar, mock `logoutAction` with `vi.mock("@/app/(auth)/actions")` in the test. The layout passes `me.fullName` and `me.email`.
- [ ] **Step 4: Run, expect PASS.** Run the same command plus `npx tsc --noEmit`.
- [ ] **Step 5: Commit** `feat(frontend): account menu with sign out, labelled rail, drop placeholder search`

### Task 2: Dates, route boundaries, auth page fixes

**Files:**
- Modify: `lib/format.ts`, `lib/format.test.ts` (create if missing), `features/runs/RunsTable.tsx`, `features/files/FilesView.tsx`, `features/runs/LiveRun.tsx`, `middleware.ts`, `lib/gate.ts`, `lib/gate.test.ts`, `app/(auth)/LoginForm.tsx`, `features/home/HomeView.tsx`
- Create: `app/(app)/error.tsx`, `app/(app)/loading.tsx`, `app/(app)/not-found.tsx`

**Interfaces:**
- Produces: `formatWhen(iso: string | null, now?: Date): string`. Today gives `14:17`; another day this year gives `21 Oct, 14:17`; another year gives `21 Oct 2025, 14:17`; null gives `–`. Later tasks use it wherever `formatTime` showed a past timestamp. `formatTime` stays for countdowns ("Sandbox closes").
- Produces: `gateTarget(role, path)` also returns the role's home for `/login` and `/signup` when a role exists: `/developer/agents` for DEVELOPER, `/admin/money` for ADMIN, `/home` otherwise.

- [ ] **Step 1: Write failing tests**
```ts
const now = new Date("2026-10-22T10:00:00");
expect(formatWhen("2026-10-22T08:47:00", now)).toBe("08:47");
expect(formatWhen("2026-10-21T14:17:00", now)).toBe("21 Oct, 14:17");
expect(formatWhen("2025-10-21T14:17:00", now)).toBe("21 Oct 2025, 14:17");
expect(formatWhen(null, now)).toBe("–");
expect(gateTarget("BUYER_ADMIN", "/login")).toBe("/home");
expect(gateTarget("DEVELOPER", "/signup")).toBe("/developer/agents");
expect(gateTarget(undefined, "/login")).toBeNull();
```
A `HomeView` test asserts that ADMIN sees a link to `/admin/money` and no "being rebuilt" text.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** The middleware's public-path early return must still run `gateFor` when a valid session cookie is present on `/login` or `/signup`. `error.tsx` is a client component rendering `ErrorState` with `onRetry={reset}`. `loading.tsx` renders `Skeleton`. `not-found.tsx` has "We can't find that page" and a link home. Remove the Google button, the "or with email" divider and the SSO note from `LoginForm`. The admin Home shows "Go to Money" instead of the developer text.
- [ ] **Step 4: Run, expect PASS** (`npx vitest run src/lib src/features/home src/features/runs src/features/files`, `npx tsc --noEmit`).
- [ ] **Step 5: Commit** `fix(frontend): dates on past timestamps, route error/loading pages, send signed-in users past login`

### Task 3: Runs list wording

**Files:**
- Modify: `features/runs/RunsTable.tsx`, `features/flow/blocks.ts`, `features/flow/blocks.test.ts` (create if missing)

**Interfaces:**
- Produces: `reachedText(blockId: string | null): string` exported from `features/flow/blocks.ts`. `read:x` gives "Read X"; `draft:x` gives "Drafted X"; `write:x` gives "Wrote X"; `gate:x` gives "Asked you"; `llm` gives "Used AI"; `charge` gives "Charged"; null gives "–". This replaces the local `reached` in `RunsTable`.

- [ ] **Step 1: Failing test**: `expect(reachedText("charge")).toBe("Charged"); expect(reachedText("draft:ledger")).toBe("Drafted Ledger"); expect(reachedText(null)).toBe("–");`
- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5: Commit** `fix(frontend): readable "Reached" column on runs`

### Task 4: Store: one card per agent, "Added", honest labels, add without running

**Files:**
- Create: `features/store/listings.ts`, `features/store/listings.test.ts`, `features/store/StoreGrid.tsx`, `features/store/store.module.css`, `features/store/StoreGrid.test.tsx`
- Modify: `app/(app)/store/page.tsx`, `app/(app)/store/[agentId]/[versionId]/page.tsx`, `features/flow/StoreFlow.tsx`, `features/flow/StoreFlow.module.css`, `features/flow/FlowBlocks.tsx`, `features/sockets/ConnectForm.tsx`, `features/sockets/ConnectForm.test.tsx`, `features/sockets/sockets.ts`

**Interfaces:**
- Produces: `latestPerAgent(listings: CatalogListing[]): CatalogListing[]`, which keeps the highest `versionNumber` per `agentId`, compared numerically by dot-separated parts.
- Produces: `writesOf(m: Manifest): { resource: string; label: string; description: string; requiresApproval: boolean }[]` in `sockets.ts`.
- Produces: each store card's `<li>` has `id="agent-{agentId}"` (Home links to it in Task 7).
- Produces: `FlowBlocks` takes `showState?: boolean` (default `true`). The store passes `false`, so the "Not started" line is hidden.
- Consumes: `listMyConnections()` (`lib/api/connections.ts`). An agent counts as added when an ACTIVE connection has the same `agentId`.

- [ ] **Step 1: Failing tests**
```ts
expect(latestPerAgent([l("a","0.9.0"), l("a","0.10.0"), l("b","1.0.0")]).map(x => x.versionNumber)).toEqual(["0.10.0", "1.0.0"]);
```
`StoreGrid.test.tsx`: given one listing with `addedAgentIds={new Set(["a"])}`, the card shows an "Added" pill.
`StoreFlow` test: with `added` set, it shows "Run now" and no "Add to workspace". Without a selection, the panel shows the first block's title. The "Outside websites" and "Data it handles" rows carry the text "Declared by the developer".
`ConnectForm.test.tsx`: for a manifest with a WRITE scope `ledger` (`requiresApproval: true`), the page shows "Can write: Ledger, only after your approval". The primary button reads "Add to workspace". After success it calls `createConnectionAction` and `bindSocketAction` but not `startRunAction`, then pushes `/home?added=<agentId>`.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implement.** Move the inline store styles into `store.module.css`. The connect page gets a "What it can do" section listing reads and writes before the files, and the limit input gets a "₹" prefix. Remove "Add and run now". Run now lives on Home (Task 7) and on the store page when the agent is already added. That button calls `startRunAction` with the existing connection id and pushes to the run.
- [ ] **Step 4: PASS** (`npx vitest run src/features/store src/features/flow src/features/sockets`, `npx tsc --noEmit`).
- [ ] **Step 5: Commit** `feat(frontend): one store card per agent, show added agents and writes, add without running`

### Task 5: Run page: notes, collapsed decided gates

**Files:**
- Modify: `features/runs/LiveRun.tsx`, `features/runs/LiveRun.module.css`, `features/runs/LiveRun.test.tsx`, `features/runs/stepText.ts`, `features/runs/stepText.test.ts`

**Interfaces:**
- Produces: `stepNote(s: RunStep): string | null`. For `AGENT_LOG` with a non-empty `detail`, it returns the detail cut to 200 characters with `…` added when cut; otherwise null.

- [ ] **Step 1: Failing tests**: `stepNote({kind:"AGENT_LOG", detail:"Matched 10 lines", ...})` returns "Matched 10 lines"; empty detail returns null; a 300-character detail returns 201 characters ending in "…". The `LiveRun` test asserts that on a FINISHED run with an APPROVED gate, the gate renders inside a `<details>` element whose `<summary>` contains the gate summary, and no Approve button exists.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implement.** The timeline row shows `stepText(s)` and then the note in `--ink2`. Set `title` to the full detail. Pending gates stay full cards above the flow. Decided or expired gates render below the flow, inside `<details>`. Timeline rows use `formatWhen`.
- [ ] **Step 4: PASS. Step 5: Commit** `fix(frontend): show agent notes on the timeline, fold decided approvals`

### Task 6: Approval card: readable preview and honest decision text

**Files:**
- Create: `features/gates/previewCells.ts`, `features/gates/previewCells.test.ts`
- Modify: `features/gates/GateCard.tsx`, `features/gates/GateCard.module.css`, `features/gates/GateCard.test.tsx`, `features/runs/LiveRun.tsx`, `app/(app)/runs/[id]/page.tsx`

**Interfaces:**
- Produces: `cell(value: unknown): { text: string; numeric: boolean }`. Numbers and numeric strings (`/^-?\d+(\.\d+)?$/`) get `numeric: true` and `en-IN` grouping with at most 2 decimals (`185000` gives `1,85,000`; `"18450.75"` gives `18,450.75`). null and undefined give `""`. Anything else gives `String(value)`.
- Produces: `decisionText(g: GateDetail, viewerUserId: string): string`. If `decidedByUserId === viewerUserId`: "You approved this at 14:17." or "You rejected this at 14:17.". Otherwise "Approved at 14:17." / "Rejected at 14:17.", with " by email link" added when `decidedVia === "EMAIL_LINK"`. Times use `formatWhen`.
- Consumes: `GateCard` gains the prop `viewerUserId: string`. The run page passes `(await getMe()).userId` through `LiveRun`.

- [ ] **Step 1: Failing tests** for each `cell` case above and each `decisionText` case. The `GateCard` test asserts headers are humanized (`debit` shows as "Debit") and numeric cells have the class `num`.
- [ ] **Step 2: FAIL. Step 3: Implement** (`.num { text-align: right; }`, and headers use `humanize`). **Step 4: PASS.**
- [ ] **Step 5: Commit** `fix(frontend): format approval previews and say who decided`

### Task 7: Home: added agents you can run

**Files:**
- Modify: `features/home/HomeView.tsx`, `features/home/HomeView.module.css`, `features/home/HomeView.test.tsx`, `app/(app)/home/page.tsx`
- Create: `features/home/RunNow.tsx`

**Interfaces:**
- Consumes: `startRunAction(connectionId)` from `app/(app)/connect/actions.ts`, and `ConnectionSummary`.
- Produces: `RunNow({ connectionId }: { connectionId: string })`, a client button that starts a run and pushes to `/runs/{displayId}`. It shows the action's error inline.

- [ ] **Step 1: Failing tests**: "Your agents" lists only ACTIVE connections. Each row links its name to `/store#agent-{agentId}` (`ConnectionSummary` has no versionId; Task 4's cards carry that id). Each row has a "Run now" button. With `searchParams.added` set, a status line reads "Added. Run it when you're ready." BUYER_APPROVER sees no Run now (`canCreateConnections` is false).
- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5: Commit** `feat(frontend): run an added agent from Home`

### Task 8: Developer and money copy, background, docs

**Files:**
- Modify: `features/studio/StudioActions.tsx` (+ test), `features/earnings/EarningsView.tsx` (+ test), `features/billing/BillingView.tsx` (+ test), `app/(app)/billing/page.tsx`, `app/globals.css`, `ui/AppShell.module.css`, `docs/status.md`, `docs/frontend-gaps.md`

**Interfaces:**
- Consumes: `PaymentPanels` (`BillingView.tsx`). It gains `failed: string[]`, the names of the optional calls that failed, set by `optional()` in the billing page.

- [ ] **Step 1: Failing tests**
  - Studio: a LIVE version renders no "Submit for review" button.
  - Earnings: the heading reads "Your balance with Karya", followed by the text "Withdrawals open once payouts are live."
  - Billing: with `failed: ["options"]`, the view shows "We couldn't load Add money. Refresh to try again."
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implement.** Move the dotted background from `body` to the auth layout and to `ui/states.module.css`'s empty box. `.content` keeps the plain `--g` background.
- [ ] **Step 4: PASS.** Run the full suite: `npx vitest run` and `npx tsc --noEmit`.
- [ ] **Step 5: Update docs.** In `docs/status.md`, add a Frontend row "UX-1 portal polish" with this plan, and bump "Last updated". In `docs/frontend-gaps.md`, delete the "Files Added", "Runs Started" time-only entries and mark G10's button as hidden.
- [ ] **Step 6: Manual check.** With the backend restarted on master (`scripts/dev-down.sh && scripts/dev-up.sh`), go through the app as demo-buyer: store, connect, Home Run now, a run page, and Billing. Then as demo-developer: Studio on a LIVE version, and Earnings. Confirm each roadmap UX-1 item is gone.
- [ ] **Step 7: Commit** `chore(frontend): studio, earnings and billing copy; quieter background; docs`
