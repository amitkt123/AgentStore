# FE-4 — Karya Admin Consoles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Karya's trust team three consoles:
- **Review pipeline:** every automated stage with its findings, the manifest diff against the live version, the impact on existing buyers, then approve or send back.
- **Developer verification:** automated identity, tax and bank checks, then verify, ask for a fix, or reject.
- **Agent health and rollouts:** live-version health, paused rollouts, blocked attempts, then resume, roll back, suspend, or message the developer.

**Architecture:**
- **Location.** The consoles live under `/admin` for the `ADMIN` role, inside FE-1's app shell. Rail badges come from one counters endpoint.
- **Codes, not sentences.** The backend returns codes and parameters. Three small copy modules (`reviewCopy`, `verificationCopy`, `healthCopy`) turn them into the sentences the mockups show. If a code is unknown, the module falls back to the backend's `text` field, so a new backend check never renders blank.
- **Patterns.** Each console follows the FE-1 patterns: server page, client view, server actions returning `ActionResult`.

**Tech Stack:** Next.js 14.2, React 18, TypeScript, Tailwind 3.4 (FE-1 tokens), TanStack Query 5, Vitest + React Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §10 rows 18, 19 and 20; the WS7 UI alignment (admin verification); the WS8 UI alignment (review findings, diff tags, impact, send back, counters); the WS11 UI alignment (health, rollouts, rollback, suspend, messaging).

**Mockups:** `fe-mockups/latest/Karya — Agentic UI.html`, boards "Karya review pipeline", "Developer verification" and "Agent health and rollouts".

**Prerequisites:** FE-1 merged (FE-2 and FE-3 are independent of this plan). Backend WS7, WS8 and WS11 merged. Their plans don't exist yet, so the contracts are pinned below, and Task 1 checks them against the merged backend first.

## Backend contracts this plan assumes

The endpoints are listed here, and their exact shapes are in Task 1's `src/lib/types.ts` block. When the WS7, WS8 and WS11 plans are written, they must provide these shapes, or this table and Task 1's types must be updated in the same change.

| Endpoint | From | Returns |
|---|---|---|
| `GET /api/v1/admin/counters` | WS8 | `{ reviewQueue, verificationQueue, healthAlerts }` |
| `GET /api/v1/review/queue` | WS8 (extends existing) | `ReviewQueueItem[]` |
| `GET /api/v1/review/{id}` | WS8 (extends existing) | `ReviewDetail` |
| `POST /api/v1/review/{id}/approve {notes}` | existing | 200 |
| `POST /api/v1/review/{id}/send-back {note}` | WS8 | 200 (version → `CHANGES_REQUESTED`) |
| `GET /api/v1/admin/verifications?status=PENDING` | WS7 | `VerificationQueueItem[]` |
| `GET /api/v1/admin/verifications/{id}` | WS7 | `VerificationDetail` |
| `POST /api/v1/admin/verifications/{id}/decision {decision, note}` | WS7 | `VerificationDetail` (`decision` ∈ `VERIFY`, `REQUEST_FIX`, `REJECT`) |
| `GET /api/v1/admin/health/summary` | WS11 | `HealthSummary` |
| `GET /api/v1/admin/health/versions` | WS11 | `VersionHealth[]` |
| `GET /api/v1/admin/health/versions/{id}/blocked` | WS11 | `BlockedEvent[]` |
| `GET /api/v1/admin/rollouts` | WS11 | `Rollout[]` |
| `POST /api/v1/admin/rollouts/{versionId}/resume`, `…/rollback` | WS11 | 200 |
| `POST /api/v1/admin/versions/{id}/suspend {reason}` | WS11 | 200 |
| `POST /api/v1/admin/developers/{orgId}/messages {subject, body}` | WS11 | 201 |

## Global Constraints

- Everything in FE-1's Global Constraints applies.
- Admin routes are `/admin/review`, `/admin/review/{id}`, `/admin/verification` and `/admin/health`, for the `ADMIN` role only. `roleHome("ADMIN")` becomes `/admin/review`, and the legacy `/review` pages redirect there.
- Destructive or irreversible admin actions need an explicit second step, never a browser `confirm()`: Reject, Suspend and Roll back. Reject, Ask for a fix, Send back and Suspend also need a written note or reason, because the developer sees it.
- Masked values (PAN, GSTIN, phone, bank) are shown exactly as the backend returns them. The consoles never ask the backend for unmasked values.
- Copy modules map every code the mockups show. An unknown code falls back to the backend's `text`, or failing that the code in words ("New read since"). It is never shown blank.
- **Not in FE-4:** per-developer quota overrides and partner fee rates (admin settings pages; later), and the dispute decision console. WS16's endpoints exist, and the dispute console is a small follow-up plan once the review, verification and health consoles are in use.

## Review Focus

1. **Approving a submission while a stage is still running** (automated checks not finished) must not be possible. The button stays disabled with "Waiting for automated checks". [Task 2]
2. **Sending back or rejecting with an empty note** must be refused in the UI before any call. The developer would otherwise get an unexplained rejection. [Tasks 2–3]
3. **Rolling back a paused rollout** must say how many auto-updated workspaces move back, and that explicitly consented ones stay put, before it happens. [Task 4]
4. **A backend check code this console doesn't know yet** must still render the backend's text, not an empty row. [Task 1]
5. **Two admins deciding the same verification.** The second must see the first's decision (409 → reload), not overwrite it. [Task 3]

---

## File Structure

```
frontend/src/
  lib/types.ts                                  (modify: WS7/WS8/WS11 admin contracts)
  lib/reviewCopy.ts, verificationCopy.ts, healthCopy.ts   (create)
  lib/session.ts, lib/routing.ts                (modify: admin home and routes)
  components/shell/nav.ts, RailNav.tsx          (modify: admin items, counter badges)
  app/api/admin/counters/route.ts               (create)
  app/(admin)/admin/review/page.tsx, ReviewQueue.tsx                        (create)
  app/(admin)/admin/review/[id]/page.tsx, ReviewDetailView.tsx, actions.ts  (create)
  app/(admin)/admin/verification/page.tsx, VerificationConsole.tsx, actions.ts (create)
  app/(admin)/admin/health/page.tsx, HealthConsole.tsx, actions.ts          (create)
  app/(admin)/review/**                          (replace with redirects; delete old views)
e2e/admin.spec.ts                                (create)
```

All commands run from `frontend/`.

---

### Task 1: Contracts, admin navigation with counters, and the copy modules

**Files:**
- Modify: `src/lib/types.ts`, `src/lib/session.ts`, `src/lib/session.test.ts`, `src/lib/routing.ts`, `src/lib/routing.test.ts`, `src/components/shell/nav.ts`, `src/components/shell/nav.test.ts`, `src/components/shell/RailNav.tsx`, `src/components/shell/RailNav.test.tsx`
- Create: `src/lib/reviewCopy.ts`, `src/lib/verificationCopy.ts`, `src/lib/healthCopy.ts`, `src/app/api/admin/counters/route.ts`
- Test: create `src/lib/reviewCopy.test.ts`, `src/lib/verificationCopy.test.ts`, `src/lib/healthCopy.test.ts`

**Interfaces:**
- Produces:
  - Types: `Finding`, `ReviewStage`, `ReviewQueueItem`, `ReviewDetail`, `VerificationCheck`, `VerificationQueueItem`, `VerificationDetail`, `HealthSummary`, `VersionHealth`, `BlockedEvent`, `Rollout`, `AdminCounters`.
  - `findingText(f: Finding): string`, `stageTone(stage): Tone`.
  - `checkText(c: VerificationCheck): { name: string; detail: string; result: string; tone: Tone }`.
  - `healthState(v): { tone: Tone; label: string }`, `healthNote(v): string`.
  - `NavItem.badge` widens to `"approvals" | "reviewQueue" | "verificationQueue" | "healthAlerts"`.

- [ ] **Step 1: Check each contract against the running backend**

As in FE-2 Task 1: sign in as a Karya admin (an email listed in the backend's `agentstore.identity.admin-emails`), request each endpoint in the contracts table, and bring Step 3's types in line with the responses. Record differences in the commit message.

- [ ] **Step 2: Write the failing tests**

`src/lib/reviewCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { findingText, stageTone } from "./reviewCopy";

describe("findingText", () => {
  it("words the codes the mockup shows", () => {
    expect(findingText({ tone: "ok", code: "MANIFEST_VALID", params: {} })).toBe("manifest.json is valid and every read, write and gate is declared.");
    expect(findingText({ tone: "ok", code: "EVAL_CASES_MATCHED", params: { count: 47 } })).toBe("47 cases matched the expected output exactly.");
    expect(findingText({ tone: "warn", code: "EVAL_CASE_FAILED", params: { title: "a payment split across two invoices was matched to only one" } }))
      .toBe("Failed: a payment split across two invoices was matched to only one.");
    expect(findingText({ tone: "info", code: "NEW_READ_USED", params: { resource: "vendor_master", runs: 31, of: 50 } }))
      .toBe("vendor_master is now declared and was read in 31 of 50 runs.");
    expect(findingText({ tone: "ok", code: "COST_P95", params: { amount: 310, cap: 500 } })).toBe("95% of runs cost ₹310 or less, within the ₹500 limit.");
  });
  it("falls back to the backend's text, then to the code in words", () => {
    expect(findingText({ tone: "ok", code: "BRAND_NEW_CHECK", params: {}, text: "A check Karya added later." })).toBe("A check Karya added later.");
    expect(findingText({ tone: "ok", code: "BRAND_NEW_CHECK", params: {} })).toBe("Brand new check");
  });
  it("colours a stage by its worst finding or waiting state", () => {
    expect(stageTone({ tone: "wait" } as never)).toBe("wa");
    expect(stageTone({ tone: "fail" } as never)).toBe("bad");
    expect(stageTone({ tone: "ok" } as never)).toBe("ok");
  });
});
```

`src/lib/verificationCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { checkText } from "./verificationCopy";

describe("checkText", () => {
  it("words each automated check", () => {
    expect(checkText({ kind: "PHONE", result: "PASS", code: "PHONE_CONFIRMED", detail: { phoneMasked: "+91 98•• ••• 412" } }))
      .toEqual({ name: "Phone", detail: "OTP confirmed on +91 98•• ••• 412.", result: "Confirmed", tone: "ok" });
    expect(checkText({ kind: "PAYOUT_ACCOUNT", result: "WARN", code: "BANK_NAME_DIFFERS", detail: { bankHolderName: "Ledgerbird Technologies LLP", legalName: "Ledgerbird LLP" } }))
      .toEqual({ name: "Payout account", detail: 'Bank says the holder is "Ledgerbird Technologies LLP", not "Ledgerbird LLP".', result: "Name differs", tone: "wa" });
    expect(checkText({ kind: "GSTIN", result: "WARN", code: "GSTIN_CANCELLED", detail: { gstinMasked: "29AADCV••••3M1Z9", on: "2026-03-12" } }))
      .toEqual({ name: "GSTIN", detail: "29AADCV••••3M1Z9 was cancelled on 12 Mar 2026.", result: "Cancelled", tone: "wa" });
    expect(checkText({ kind: "GSTIN", result: "NOT_REQUIRED", code: "GSTIN_NOT_REQUIRED", detail: {} }).result).toBe("Not needed");
    expect(checkText({ kind: "PAN", result: "MANUAL", code: "MANUAL", detail: {} })).toMatchObject({ result: "Check by hand", tone: "n" });
  });
});
```

`src/lib/healthCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { healthNote, healthState } from "./healthCopy";
import type { VersionHealth } from "./types";

const v = (over: Partial<VersionHealth>): VersionHealth => ({
  versionId: "v", agentName: "Vendor Payment Scheduler", version: "1.2.0", developer: "Paygrid", developerOrgId: "p",
  successRate: 0.939, costP95: 780, costCap: 1000, blocked: 11, approvedRate: 0.71, disputeRate: 0.018, runs7d: 612,
  medianDurationSeconds: 91, state: "ROLLOUT_PAUSED", stateReason: { code: "FAILURE_RATE_UP", params: { rate: 0.061, previousRate: 0.014, previousVersion: "1.1.0" } },
  ...over,
});

describe("healthCopy", () => {
  it("labels states", () => {
    expect(healthState(v({}))).toEqual({ tone: "wa", label: "Rollout paused" });
    expect(healthState(v({ state: "HEALTHY" }))).toEqual({ tone: "ok", label: "Healthy" });
    expect(healthState(v({ state: "OFTEN_HITS_LIMIT" }))).toEqual({ tone: "wa", label: "Often hits limit" });
    expect(healthState(v({ state: "WATCH" }))).toEqual({ tone: "b", label: "Watch" });
  });
  it("explains why", () => {
    expect(healthNote(v({}))).toBe("1.2.0 fails 6.1% of runs, against 1.4% on 1.1.0.");
    expect(healthNote(v({ state: "WATCH", stateReason: { code: "BLOCKED_HOSTS", params: { host: "fx.rates.example", count: 6 } } })))
      .toBe("6 attempts to reach fx.rates.example this week. Blocked every time.");
    expect(healthNote(v({ state: "OFTEN_HITS_LIMIT", stateReason: { code: "OFTEN_HITS_LIMIT", params: { share: 0.05, cap: 300 } } })))
      .toBe("The heaviest 5% of runs stop at the ₹300 limit. The developer may need to raise the declared maximum, which buyers would re-approve.");
    expect(healthNote(v({ state: "HEALTHY", stateReason: { code: "HEALTHY", params: {} } }))).toBe("Steady.");
  });
});
```

Update `src/lib/session.test.ts` so `ADMIN` → `"/admin/review"`. In `src/lib/routing.test.ts`, add `expect(resolveRedirect("/admin/health", buyer)).toBe("/home");`. In `src/components/shell/nav.test.ts`, add `expect(navFor("ADMIN").map((i) => i.href)).toEqual(["/admin/review", "/admin/verification", "/admin/health"]);`.

Add to `src/components/shell/RailNav.test.tsx`:

```tsx
  it("shows admin queue counts from the counters endpoint", async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ reviewQueue: 4, verificationQueue: 2, healthAlerts: 1 }) });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RailNav items={navFor("ADMIN")} />
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("link", { name: "Review queue, 4 waiting" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Developer verification, 2 waiting" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Agent health, 1 alert" })).toBeInTheDocument();
  });
```

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `npm test -- src/lib src/components/shell`
Expected: FAIL.

- [ ] **Step 4: Types**

Append to `src/lib/types.ts`:

```ts
// ---------- WS8: review ----------
export interface Finding {
  tone: "ok" | "warn" | "info" | "fail";
  code: string;
  params: Record<string, unknown>;
  text?: string;
}
export interface ReviewStage {
  kind: "STATIC" | "EVALS" | "BOUNDARIES" | "RED_TEAM" | "COST" | "HUMAN";
  name: string;
  result: string;                    // short result shown on the stage, e.g. "47 of 50", "Clean", "Waiting on you"
  tone: "ok" | "warn" | "fail" | "wait";
  summary: string;
  findings: Finding[];
}
export interface ReviewQueueItem {
  id: string;
  displayId: string;
  agentName: string;
  versionNumber: string;
  developerName: string;
  writes: boolean;
  submittedAt: string;
  automatedDone: boolean;
}
export interface ReviewDetail {
  id: string;
  displayId: string;
  agentId: string;
  agentName: string;
  versionNumber: string;
  previousVersion: string | null;
  developer: { orgId: string; name: string; verifiedAt: string | null };
  submittedAt: string;
  writes: boolean;
  stages: ReviewStage[];
  diff: VersionDiffRow[];
  impact: { connectionsOnPreviousVersion: number };
  developerHistory: { submissions: number; rejected: number };
  status: "PENDING" | "APPROVED" | "REJECTED" | "CHANGES_REQUESTED";
}

// ---------- WS7: admin verification ----------
export interface VerificationCheck {
  kind: "PHONE" | "PAN" | "GSTIN" | "PAYOUT_ACCOUNT" | "AGREEMENT";
  result: "PASS" | "WARN" | "FAIL" | "NOT_REQUIRED" | "MANUAL";
  code: string;
  detail: Record<string, unknown>;
  text?: string;
}
export interface VerificationQueueItem {
  id: string;
  name: string;
  entityType: string;
  submittedAt: string;
  flag: "ALL_PASS" | "NEEDS_LOOK";
  resubmitted: boolean;
}
export interface VerificationDetail {
  id: string;
  name: string;
  entityType: string;
  country: string;
  intro: string | null;
  resubmissionCount: number;
  checks: VerificationCheck[];
  facts: { legalName: string; entityType: string; country: string; panMasked: string | null; gstinMasked: string | null; bank: string | null };
  status: "PENDING" | "VERIFIED" | "FIX_REQUESTED" | "REJECTED";
  decisionNote: string | null;
}

// ---------- WS11: health ----------
export interface HealthSummary {
  runsToday: number;
  finishedWithoutError: number;
  blockedAttemptsWeek: number;
  aiSpendToday: number;
}
export interface VersionHealth {
  versionId: string;
  agentName: string;
  version: string;
  developer: string;
  developerOrgId: string;
  successRate: number;
  costP95: number;
  costCap: number;
  blocked: number;
  approvedRate: number | null;
  disputeRate: number;
  runs7d: number;
  medianDurationSeconds: number;
  state: "HEALTHY" | "WATCH" | "OFTEN_HITS_LIMIT" | "ROLLOUT_PAUSED" | "SUSPENDED";
  stateReason: { code: string; params: Record<string, unknown>; text?: string };
}
export interface BlockedEvent {
  at: string;
  kind: "NETWORK_BLOCKED" | "SCOPE_VIOLATION";
  target: string;
  count: number;
}
export interface Rollout {
  versionId: string;
  agentName: string;
  version: string;
  previousVersion: string;
  percent: number;
  haltedAt: string | null;
  haltReason: { code: string; params: Record<string, unknown> } | null;
  autoUpdatedConnections: number;
  consentedConnections: number;
}
export interface AdminCounters {
  reviewQueue: number;
  verificationQueue: number;
  healthAlerts: number;
}
```

- [ ] **Step 5: Copy modules**

`src/lib/reviewCopy.ts`:

```ts
import type { Tone } from "@/components/ui/Pill";
import { inr } from "./format";
import type { Finding, ReviewStage } from "./types";

const s = (v: unknown) => String(v ?? "");
const n = (v: unknown) => Number(v ?? 0);
const sentence = (text: string) => (/[.!?]$/.test(text) ? text : `${text}.`);

const WORDS: Record<string, (p: Record<string, unknown>) => string> = {
  MANIFEST_VALID: () => "manifest.json is valid and every read, write and gate is declared.",
  BASE_IMAGE: (p) => `Built on the ${s(p.image)} base image.`,
  NO_VULNERABLE_DEPS: (p) => `No known vulnerable packages found by ${s(p.tool) || "the dependency scan"}.`,
  NO_SECRETS: () => "No keys or passwords found in the source.",
  EVAL_CASES_MATCHED: (p) => `${n(p.count)} cases matched the expected output exactly.`,
  EVAL_CASE_FAILED: (p) => sentence(`Failed: ${s(p.title)}`),
  NO_UNDECLARED_READS: (p) => `No undeclared reads across ${n(p.runs)} runs.`,
  NO_BLOCKED_HOSTS: () => "No attempt to reach a website outside its allowlist.",
  NO_UNGATED_WRITES: () => "No write happened without an approval.",
  NEW_READ_USED: (p) => `${s(p.resource)} is now declared and was read in ${n(p.runs)} of ${n(p.of)} runs.`,
  PROBE_PASSED: (p) => sentence(s(p.title)),
  PROBE_FAILED: (p) => sentence(`Fell for: ${s(p.title)}`),
  COST_TYPICAL: (p) => `A typical run costs the buyer ${inr(n(p.amount))}.`,
  COST_P95: (p) => `95% of runs cost ${inr(n(p.amount))} or less, within the ${inr(n(p.cap))} limit.`,
  LONGEST_RUN: (p) => `The longest run took ${Math.floor(n(p.seconds) / 60)} minute${Math.floor(n(p.seconds) / 60) === 1 ? "" : "s"} ${n(p.seconds) % 60} seconds.`,
  TOKENS_RANGE: (p) => `AI usage ranged from ${n(p.min).toLocaleString("en-IN")} to ${n(p.max).toLocaleString("en-IN")} tokens per run.`,
  WRITES_BEHIND_APPROVAL: (p) => `It writes ${s(p.resource) || "data"}, always behind the buyer's approval.`,
  NEW_READ_SINCE: (p) => `One new read since ${s(p.version)}: ${s(p.resource)}.`,
  NO_EARLIER_REJECTIONS: () => "No earlier submission from this developer was rejected.",
};

function codeInWords(code: string): string {
  const words = code.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function findingText(f: Finding): string {
  const word = WORDS[f.code];
  if (word) return word(f.params);
  return f.text ?? codeInWords(f.code);
}

export function stageTone(stage: Pick<ReviewStage, "tone">): Tone {
  return stage.tone === "wait" ? "wa" : stage.tone === "fail" ? "bad" : stage.tone === "warn" ? "wa" : "ok";
}
```

`src/lib/verificationCopy.ts`:

```ts
import type { Tone } from "@/components/ui/Pill";
import type { VerificationCheck } from "./types";

const NAMES: Record<VerificationCheck["kind"], string> = {
  PHONE: "Phone", PAN: "PAN", GSTIN: "GSTIN", PAYOUT_ACCOUNT: "Payout account", AGREEMENT: "Developer agreement",
};
const s = (v: unknown) => String(v ?? "");
const date = (iso: unknown) => new Date(`${s(iso).slice(0, 10)}T00:00:00Z`).toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

const DETAIL: Record<string, (d: Record<string, unknown>) => [string, string]> = {
  PHONE_CONFIRMED: (d) => [`OTP confirmed on ${s(d.phoneMasked)}.`, "Confirmed"],
  PAN_NAME_MATCH: (d) => [`${s(d.panMasked)} is active and matches the legal name.`, "Matches"],
  PAN_NAME_MISMATCH: (d) => [`${s(d.panMasked)} is registered to "${s(d.panName)}".`, "Name differs"],
  GSTIN_ACTIVE: (d) => [`${s(d.gstinMasked)} is active${d.since ? `, registered in ${s(d.since).slice(0, 4)}` : ""}.`, "Active"],
  GSTIN_CANCELLED: (d) => [`${s(d.gstinMasked)} was cancelled on ${date(d.on)}.`, "Cancelled"],
  GSTIN_NOT_REQUIRED: () => ["Not required for an individual under the threshold.", "Not needed"],
  BANK_NAME_MATCH: () => ["Holder name matches the PAN.", "Matches"],
  BANK_NAME_DIFFERS: (d) => [`Bank says the holder is "${s(d.bankHolderName)}", not "${s(d.legalName)}".`, "Name differs"],
  AGREEMENT_ACCEPTED: (d) => [`Version ${s(d.version)} accepted on ${date(d.on)}.`, "Accepted"],
  MANUAL: () => ["No automatic check is set up. Check it by hand.", "Check by hand"],
};

const TONE: Record<VerificationCheck["result"], Tone> = { PASS: "ok", WARN: "wa", FAIL: "bad", NOT_REQUIRED: "n", MANUAL: "n" };

export function checkText(c: VerificationCheck): { name: string; detail: string; result: string; tone: Tone } {
  const words = DETAIL[c.code]?.(c.detail);
  return {
    name: NAMES[c.kind],
    detail: words?.[0] ?? c.text ?? "",
    result: words?.[1] ?? (c.result === "PASS" ? "Passed" : c.result === "FAIL" ? "Failed" : c.result === "WARN" ? "Needs a look" : "Not needed"),
    tone: TONE[c.result],
  };
}
```

`src/lib/healthCopy.ts`:

```ts
import type { Tone } from "@/components/ui/Pill";
import { inr } from "./format";
import type { VersionHealth } from "./types";

const pct = (v: unknown) => `${Math.round(Number(v ?? 0) * 1000) / 10}%`;

export function healthState(v: Pick<VersionHealth, "state">): { tone: Tone; label: string } {
  switch (v.state) {
    case "HEALTHY": return { tone: "ok", label: "Healthy" };
    case "WATCH": return { tone: "b", label: "Watch" };
    case "OFTEN_HITS_LIMIT": return { tone: "wa", label: "Often hits limit" };
    case "ROLLOUT_PAUSED": return { tone: "wa", label: "Rollout paused" };
    case "SUSPENDED": return { tone: "bad", label: "Suspended" };
  }
}

export function healthNote(v: Pick<VersionHealth, "version" | "stateReason">): string {
  const p = v.stateReason.params;
  switch (v.stateReason.code) {
    case "FAILURE_RATE_UP":
      return `${v.version} fails ${pct(p.rate)} of runs, against ${pct(p.previousRate)} on ${String(p.previousVersion)}.`;
    case "BLOCKED_HOSTS":
      return `${Number(p.count)} attempts to reach ${String(p.host)} this week. Blocked every time.`;
    case "OFTEN_HITS_LIMIT":
      return `The heaviest ${Math.round(Number(p.share) * 100)}% of runs stop at the ${inr(Number(p.cap))} limit. The developer may need to raise the declared maximum, which buyers would re-approve.`;
    case "HEALTHY":
      return "Steady.";
    default:
      return v.stateReason.text ?? "";
  }
}
```

- [ ] **Step 6: Routing, nav and counter badges**

In `src/lib/session.ts`, change `roleHome`: `if (role === "ADMIN") return "/admin/review";`. In `src/lib/routing.ts`, add `["/admin", ["ADMIN"]]` to `ROLE_PREFIXES`.

In `src/components/shell/nav.ts`, widen `badge` and replace the `ADMIN` case:

```ts
export interface NavItem {
  href: string;
  label: string;
  icon: IconName;
  badge?: "approvals" | "reviewQueue" | "verificationQueue" | "healthAlerts";
}
// …
    case "ADMIN":
      return [
        { href: "/admin/review", label: "Review queue", icon: "gate", badge: "reviewQueue" },
        { href: "/admin/verification", label: "Developer verification", icon: "team", badge: "verificationQueue" },
        { href: "/admin/health", label: "Agent health", icon: "layers", badge: "healthAlerts" },
      ];
```

`src/app/api/admin/counters/route.ts`:

```ts
import { proxyGet } from "../../proxy";

export async function GET() {
  return proxyGet("/api/v1/admin/counters");
}
```

In `src/components/shell/RailNav.tsx`:
- add a second query, and derive `count` per item;
- replace the single `approvals`-based `count` and `aria-label`;
- import `AdminCounters` from `@/lib/types`.

```tsx
  const wantsCounters = items.some((i) => i.badge && i.badge !== "approvals");
  const { data: counters } = useQuery({
    queryKey: ["admin", "counters"],
    enabled: wantsCounters,
    refetchInterval: 30000,
    queryFn: () => clientFetch<AdminCounters>("/api/admin/counters"),
  });
  const countFor = (item: NavItem) =>
    item.badge === "approvals" ? approvals : item.badge ? counters?.[item.badge] ?? 0 : 0;
  const labelFor = (item: NavItem, count: number) => {
    if (count === 0) return item.label;
    if (item.badge === "approvals") return `${item.label}, ${count} need${count === 1 ? "s" : ""} approval`;
    if (item.badge === "healthAlerts") return `${item.label}, ${count} alert${count === 1 ? "" : "s"}`;
    return `${item.label}, ${count} waiting`;
  };
```

and inside the `map`, use `const count = countFor(item);` and `aria-label={labelFor(item, count)}`.

- [ ] **Step 7: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): admin contracts, navigation with queue counts, and copy for review, verification and health"
```

---

### Task 2: Review pipeline

**Files:**
- Create: `src/app/(admin)/admin/review/page.tsx`, `ReviewQueue.tsx`, `[id]/page.tsx`, `[id]/ReviewDetailView.tsx`, `[id]/actions.ts`
- Replace with redirects: `src/app/(admin)/review/page.tsx` → `/admin/review`, `src/app/(admin)/review/[reviewItemId]/page.tsx` → `/admin/review/{id}`
- Delete: `src/app/(admin)/review/ReviewQueueList.tsx`, `ReviewQueueList.test.tsx`, `[reviewItemId]/ReviewItemDetailView.tsx`, `ReviewItemDetailView.test.tsx`, `[reviewItemId]/actions.ts`
- Test: create `ReviewQueue.test.tsx`, `[id]/ReviewDetailView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/review/queue`, `GET /api/v1/review/{id}`, `POST …/approve`, `POST …/send-back`; `findingText`, `stageTone` (Task 1).
- Produces: `approveAction(id, notes): ActionResult`, `sendBackAction(id, note): ActionResult`, `ReviewQueue({ items })`, `ReviewDetailView({ review })`.

- [ ] **Step 1: Write the failing tests**

`src/app/(admin)/admin/review/ReviewQueue.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ReviewQueue } from "./ReviewQueue";

describe("ReviewQueue", () => {
  it("lists submissions that need a person, oldest first", () => {
    render(<ReviewQueue items={[
      { id: "r2", displayId: "RV-0413", agentName: "Invoice Chaser", versionNumber: "1.0.0", developerName: "Vyapar Bots", writes: true, submittedAt: "2026-09-23T10:00:00Z", automatedDone: false },
      { id: "r1", displayId: "RV-0412", agentName: "Bank Reconciliation", versionNumber: "1.4.0", developerName: "Ledgerline Labs", writes: true, submittedAt: "2026-09-22T12:34:00Z", automatedDone: true },
    ]} />);
    const links = screen.getAllByRole("link");
    expect(links[0]).toHaveTextContent("Bank Reconciliation 1.4.0");
    expect(links[0]).toHaveAttribute("href", "/admin/review/r1");
    expect(screen.getByText("Checks running")).toBeInTheDocument();
  });
});
```

`src/app/(admin)/admin/review/[id]/ReviewDetailView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ReviewDetailView } from "./ReviewDetailView";
import type { ReviewDetail } from "@/lib/types";

vi.mock("./actions", () => ({
  approveAction: vi.fn().mockResolvedValue({ ok: true }),
  sendBackAction: vi.fn().mockResolvedValue({ ok: true }),
}));

const review: ReviewDetail = {
  id: "r1", displayId: "RV-0412", agentId: "a1", agentName: "Bank Reconciliation", versionNumber: "1.4.0", previousVersion: "1.3.0",
  developer: { orgId: "d1", name: "Ledgerline Labs", verifiedAt: "2026-09-02T00:00:00Z" }, submittedAt: "2026-09-22T12:34:00Z", writes: true,
  stages: [
    { kind: "STATIC", name: "Static checks", result: "Passed", tone: "ok", summary: "Checked when the bundle was built.", findings: [{ tone: "ok", code: "MANIFEST_VALID", params: {} }] },
    { kind: "EVALS", name: "Test cases", result: "47 of 50", tone: "ok", summary: "The minimum is 80%; this build scored 94%.", findings: [
      { tone: "ok", code: "EVAL_CASES_MATCHED", params: { count: 47 } },
      { tone: "warn", code: "EVAL_CASE_FAILED", params: { title: "a duplicate UTR on 14 Aug produced two drafts" } },
    ] },
    { kind: "HUMAN", name: "Your review", result: "Waiting on you", tone: "wait", summary: "A person reviews every agent that writes to a buyer's books.", findings: [{ tone: "ok", code: "NO_EARLIER_REJECTIONS", params: {} }] },
  ],
  diff: [
    { kind: "READ", text: "Reads bank_statements, invoices", tag: "SAME" },
    { kind: "READ", text: "Reads vendor_master", tag: "NEW" },
  ],
  impact: { connectionsOnPreviousVersion: 63 },
  developerHistory: { submissions: 4, rejected: 0 },
  status: "PENDING",
};

describe("ReviewDetailView", () => {
  it("opens on the human review stage and shows each stage's result", () => {
    render(<ReviewDetailView review={review} />);
    expect(screen.getByRole("heading", { name: "Bank Reconciliation 1.4.0" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Test cases.*47 of 50/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Your review.*Waiting on you/ })).toHaveAttribute("aria-pressed", "true");
  });

  it("shows a stage's findings in words", async () => {
    render(<ReviewDetailView review={review} />);
    await userEvent.click(screen.getByRole("button", { name: /Test cases/ }));
    const detail = screen.getByRole("region", { name: "Stage detail" });
    expect(within(detail).getByText("47 cases matched the expected output exactly.")).toBeInTheDocument();
    expect(within(detail).getByText("Failed: a duplicate UTR on 14 Aug produced two drafts.")).toBeInTheDocument();
  });

  it("shows what changed and who is affected", () => {
    render(<ReviewDetailView review={review} />);
    const changes = screen.getByRole("region", { name: "What changed since 1.3.0" });
    expect(within(changes).getByText("New")).toBeInTheDocument();
    expect(within(changes).getByText(/63 buyer workspaces are on 1.3.0/)).toBeInTheDocument();
  });

  it("needs a note to send back, then sends it", async () => {
    const { sendBackAction } = await import("./actions");
    render(<ReviewDetailView review={review} />);
    await userEvent.click(screen.getByRole("button", { name: "Send back" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Write a note so the developer knows what to change.");
    expect(sendBackAction).not.toHaveBeenCalled();

    await userEvent.type(screen.getByLabelText("Note to the developer"), "Explain why vendor_master is needed in the listing.");
    await userEvent.click(screen.getByRole("button", { name: "Send back" }));
    expect(sendBackAction).toHaveBeenCalledWith("r1", "Explain why vendor_master is needed in the listing.");
  });

  it("can't approve while automated checks are still running", () => {
    const running: ReviewDetail = { ...review, stages: [{ ...review.stages[0], tone: "wait", result: "Running" }, review.stages[2]] };
    render(<ReviewDetailView review={running} />);
    expect(screen.getByRole("button", { name: "Approve listing" })).toBeDisabled();
    expect(screen.getByText("Waiting for automated checks")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(admin)/admin/review"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(admin)/admin/review/[id]/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";

export async function approveAction(id: string, notes: string): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/review/${id}/approve`, { notes: notes || null });
  } catch (e) {
    return actionError(e, { "409": "This submission was already decided." }, "We couldn't approve it.");
  }
  revalidatePath("/admin/review", "layout");
  return { ok: true };
}

export async function sendBackAction(id: string, note: string): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/review/${id}/send-back`, { note });
  } catch (e) {
    return actionError(e, { "409": "This submission was already decided." }, "We couldn't send it back.");
  }
  revalidatePath("/admin/review", "layout");
  return { ok: true };
}
```

`src/app/(admin)/admin/review/ReviewQueue.tsx`:

```tsx
import Link from "next/link";
import { Panel } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { dayLabel } from "@/lib/format";
import type { ReviewQueueItem } from "@/lib/types";

export function ReviewQueue({ items }: { items: ReviewQueueItem[] }) {
  const sorted = [...items].sort((a, b) => a.submittedAt.localeCompare(b.submittedAt));
  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Review queue</h1>
        <p className="text-ink2">Submissions that need a person. Oldest first.</p>
      </div>
      <Panel className="overflow-hidden">
        {sorted.map((i) => (
          <Link key={i.id} href={`/admin/review/${i.id}`} className="flex items-center gap-3 px-5 py-3 border-t border-line2 first:border-t-0 hover:bg-sky text-[13px]">
            <span className="font-mono text-xs text-ink3">{i.displayId}</span>
            <span className="flex-1"><b className="font-semibold">{i.agentName} {i.versionNumber}</b> <span className="text-ink3">by {i.developerName}</span></span>
            {i.writes && <Pill tone="wa">Writes data</Pill>}
            <Pill tone={i.automatedDone ? "b" : "n"}>{i.automatedDone ? "Ready for you" : "Checks running"}</Pill>
            <span className="text-xs text-ink3">{dayLabel(i.submittedAt)}</span>
          </Link>
        ))}
        {sorted.length === 0 && <p className="px-5 py-6 text-[13px] text-ink3">Nothing waiting.</p>}
      </Panel>
    </div>
  );
}
```

`src/app/(admin)/admin/review/[id]/ReviewDetailView.tsx`:

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill, type Tone } from "@/components/ui/Pill";
import { clockTime, dayLabel } from "@/lib/format";
import { findingText, stageTone } from "@/lib/reviewCopy";
import type { ReviewDetail, VersionDiffRow } from "@/lib/types";
import { approveAction, sendBackAction } from "./actions";

const TAG: Record<VersionDiffRow["tag"], { tone: Tone; label: string }> = {
  NEW: { tone: "b", label: "New" }, SAME: { tone: "n", label: "Same" }, REMOVED: { tone: "n", label: "Removed" }, SAFER: { tone: "ok", label: "Safer" },
};
const DOT: Record<string, string> = { ok: "bg-ok", warn: "bg-wa-line", info: "bg-blue-3", fail: "bg-bad" };
const KIND: Record<string, string> = { STATIC: "Build", EVALS: "Evals", BOUNDARIES: "Boundaries", RED_TEAM: "Red-team", COST: "Cost", HUMAN: "Person" };

export function ReviewDetailView({ review }: { review: ReviewDetail }) {
  const router = useRouter();
  const human = review.stages.findIndex((s) => s.kind === "HUMAN");
  const [selected, setSelected] = useState(human >= 0 ? human : 0);
  const [note, setNote] = useState("");
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const stage = review.stages[selected];
  const automatedDone = review.stages.filter((s) => s.kind !== "HUMAN").every((s) => s.tone !== "wait");
  const decided = review.status !== "PENDING";

  async function decide(approve: boolean) {
    if (!approve && !note.trim()) return setMessage({ tone: "bad", text: "Write a note so the developer knows what to change." });
    const r = approve ? await approveAction(review.id, note.trim()) : await sendBackAction(review.id, note.trim());
    if (!r.ok) return setMessage({ tone: "bad", text: r.error });
    setMessage({ tone: "ok", text: approve ? "Approved. The listing goes live and existing buyers are asked about the new access." : "Sent back with your note." });
    router.refresh();
  }

  return (
    <div className="grid grid-cols-[300px_minmax(0,1fr)_360px] gap-5 items-start">
      <div className="flex flex-col gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{review.agentName} {review.versionNumber}</h1>
          {review.writes && <Pill tone="wa">Writes data, needs a person</Pill>}
          <p className="text-xs text-ink3 mt-2">
            {review.developer.name}{review.developer.verifiedAt ? `, verified on ${dayLabel(review.developer.verifiedAt)}` : ""}. Submitted {dayLabel(review.submittedAt)} at {clockTime(review.submittedAt)}.
          </p>
        </div>
        <ol aria-label="Review stages" className="flex flex-col gap-1.5">
          {review.stages.map((s, i) => (
            <li key={s.kind}>
              <button type="button" aria-pressed={i === selected} onClick={() => setSelected(i)}
                className={`w-full text-left flex items-center gap-2.5 px-3 py-2.5 rounded-xl border bg-surface ${i === selected ? "border-blue shadow-[0_0_0_3px_var(--sky2)]" : "border-line"}`}>
                <Icon name={s.tone === "wait" ? "clock" : s.tone === "fail" ? "cross" : "check"} className="w-4 h-4 text-ink3" />
                <span className="flex-1"><span className="block text-xs text-ink3">{KIND[s.kind]}</span><span className="text-[13px] font-medium">{s.name}</span></span>
                <Pill tone={stageTone(s)}>{s.result}</Pill>
              </button>
            </li>
          ))}
        </ol>
      </div>

      <Panel aria-label="Stage detail" className="p-5 flex flex-col gap-3">
        <div className="flex items-center justify-between"><PanelTitle>{stage.name}</PanelTitle><Pill tone={stageTone(stage)}>{stage.result}</Pill></div>
        <p className="text-[13px] text-ink2">{stage.summary}</p>
        <ul className="flex flex-col">
          {stage.findings.map((f, i) => (
            <li key={i} className="grid grid-cols-[12px_minmax(0,1fr)] gap-2.5 py-2 border-t border-line2 text-[13px]">
              <i aria-hidden className={`w-2 h-2 rounded-full mt-1.5 ${DOT[f.tone]}`} />
              <span>{findingText(f)}</span>
            </li>
          ))}
        </ul>
      </Panel>

      <div className="flex flex-col gap-4">
        <Panel aria-label={`What changed since ${review.previousVersion ?? "the last version"}`} className="p-5 flex flex-col gap-2">
          <PanelTitle>What changed since {review.previousVersion ?? "the last version"}</PanelTitle>
          {review.diff.map((d) => (
            <div key={d.text} className="flex items-center gap-3 py-1.5 border-t border-line2 first:border-t-0 text-[13px]">
              <span className="flex-1">{d.text}</span><Pill tone={TAG[d.tag].tone} dot={false}>{TAG[d.tag].label}</Pill>
            </div>
          ))}
          {review.previousVersion && review.impact.connectionsOnPreviousVersion > 0 && (
            <p className="text-xs text-ink3 mt-1">
              {review.impact.connectionsOnPreviousVersion} buyer workspaces are on {review.previousVersion}.
              {review.diff.some((d) => d.tag === "NEW") ? ` Each one is asked to agree to the new access before it moves to ${review.versionNumber}.` : ""}
            </p>
          )}
          <p className="text-xs text-ink3">{review.developerHistory.rejected === 0 ? "No earlier submission from this developer was rejected." : `${review.developerHistory.rejected} of ${review.developerHistory.submissions} earlier submissions were rejected.`}</p>
        </Panel>
        {!decided && (
          <Panel className="p-5 flex flex-col gap-3">
            <label className="flex flex-col gap-1 text-[13px]">
              <span className="text-ink2">Note to the developer</span>
              <textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} className="px-3 py-2 rounded-lg border border-line" />
            </label>
            {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}
            <div className="flex gap-2">
              <Button variant="secondary" onClick={() => decide(false)}>Send back</Button>
              <Button disabled={!automatedDone} onClick={() => decide(true)}>Approve listing</Button>
            </div>
            {!automatedDone && <p className="text-xs text-ink3">Waiting for automated checks</p>}
          </Panel>
        )}
      </div>
    </div>
  );
}
```

Add a `next/navigation` mock to `ReviewDetailView.test.tsx`: `vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));`.

`src/app/(admin)/admin/review/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ReviewQueueItem } from "@/lib/types";
import { ReviewQueue } from "./ReviewQueue";

export default async function ReviewQueuePage() {
  return <ReviewQueue items={await backend.get<ReviewQueueItem[]>("/api/v1/review/queue")} />;
}
```

`src/app/(admin)/admin/review/[id]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ReviewDetail } from "@/lib/types";
import { ReviewDetailView } from "./ReviewDetailView";

export default async function ReviewDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <ReviewDetailView review={await backend.get<ReviewDetail>(`/api/v1/review/${id}`)} />;
}
```

Replace `src/app/(admin)/review/page.tsx` with `redirect("/admin/review")`, and `src/app/(admin)/review/[reviewItemId]/page.tsx` with a redirect to `/admin/review/{reviewItemId}`. Delete the old views, their tests and the old `actions.ts`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): review pipeline console with stage findings, diff, impact and send back"
```

---

### Task 3: Developer verification

**Files:**
- Create: `src/app/(admin)/admin/verification/page.tsx`, `VerificationConsole.tsx`, `actions.ts`
- Test: create `VerificationConsole.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/admin/verifications?status=PENDING`, `GET /api/v1/admin/verifications/{id}` (via `loadVerificationAction`), `POST …/decision`; `checkText` (Task 1).
- Produces: `loadVerificationAction(id): ActionResult<{ detail }>`, `decideVerificationAction(id, decision, note): ActionResult<{ detail }>`, `VerificationConsole({ queue, first })`.

- [ ] **Step 1: Write the failing test**

`src/app/(admin)/admin/verification/VerificationConsole.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { VerificationConsole } from "./VerificationConsole";
import type { VerificationDetail, VerificationQueueItem } from "@/lib/types";

const detail: VerificationDetail = {
  id: "v1", name: "Ledgerbird LLP", entityType: "LLP", country: "IN", intro: "Company in India. Wants to list a TDS return preparer.", resubmissionCount: 0,
  checks: [
    { kind: "PHONE", result: "PASS", code: "PHONE_CONFIRMED", detail: { phoneMasked: "+91 98•• ••• 412" } },
    { kind: "PAYOUT_ACCOUNT", result: "WARN", code: "BANK_NAME_DIFFERS", detail: { bankHolderName: "Ledgerbird Technologies LLP", legalName: "Ledgerbird LLP" } },
  ],
  facts: { legalName: "Ledgerbird LLP", entityType: "Company, LLP", country: "India", panMasked: "AAKFL••••7Q", gstinMasked: "27AAKFL••••7Q1ZK", bank: "ICICI, ending 0917" },
  status: "PENDING", decisionNote: null,
};
const queue: VerificationQueueItem[] = [
  { id: "v1", name: "Ledgerbird LLP", entityType: "LLP", submittedAt: "2026-09-23T08:00:00Z", flag: "NEEDS_LOOK", resubmitted: false },
  { id: "v2", name: "Asha Kulkarni", entityType: "INDIVIDUAL", submittedAt: "2026-09-23T05:00:00Z", flag: "ALL_PASS", resubmitted: false },
];

vi.mock("./actions", () => ({
  loadVerificationAction: vi.fn().mockResolvedValue({ ok: true, detail: { ...detail, id: "v2", name: "Asha Kulkarni", checks: [] } }),
  decideVerificationAction: vi.fn()
    .mockResolvedValueOnce({ ok: true, detail: { ...detail, status: "FIX_REQUESTED" } })
    .mockResolvedValueOnce({ ok: false, code: "409", error: "Another admin already decided this. Showing their decision." }),
}));

describe("VerificationConsole", () => {
  it("shows the queue with flags and the first person's automated checks", () => {
    render(<VerificationConsole queue={queue} first={detail} />);
    expect(within(screen.getByRole("button", { name: /Ledgerbird LLP/ })).getByText("Needs a look")).toBeInTheDocument();
    expect(within(screen.getByRole("button", { name: /Asha Kulkarni/ })).getByText("All checks pass")).toBeInTheDocument();
    const checks = screen.getByRole("region", { name: "Checks" });
    expect(within(checks).getByText('Bank says the holder is "Ledgerbird Technologies LLP", not "Ledgerbird LLP".')).toBeInTheDocument();
    expect(within(checks).getByText("Name differs")).toBeInTheDocument();
  });

  it("opens another person", async () => {
    render(<VerificationConsole queue={queue} first={detail} />);
    await userEvent.click(screen.getByRole("button", { name: /Asha Kulkarni/ }));
    expect(await screen.findByRole("heading", { name: "Asha Kulkarni" })).toBeInTheDocument();
  });

  it("needs a note to ask for a fix, then records it", async () => {
    const { decideVerificationAction } = await import("./actions");
    render(<VerificationConsole queue={queue} first={detail} />);
    await userEvent.click(screen.getByRole("button", { name: "Ask for a fix" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Write what needs fixing");
    await userEvent.type(screen.getByLabelText("Note to the developer"), "Payout account must be in the LLP's own name.");
    await userEvent.click(screen.getByRole("button", { name: "Ask for a fix" }));
    expect(decideVerificationAction).toHaveBeenCalledWith("v1", "REQUEST_FIX", "Payout account must be in the LLP's own name.");
    expect(await screen.findByText("Fix requested")).toBeInTheDocument();
  });

  it("shows another admin's decision instead of overwriting it", async () => {
    render(<VerificationConsole queue={queue} first={detail} />);
    await userEvent.click(screen.getByRole("button", { name: "Verify developer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Another admin already decided this");
  });

  it("asks before rejecting", async () => {
    render(<VerificationConsole queue={queue} first={detail} />);
    await userEvent.type(screen.getByLabelText("Note to the developer"), "PAN does not match.");
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));
    expect(screen.getByRole("button", { name: "Yes, reject" })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(admin)/admin/verification"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(admin)/admin/verification/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { VerificationDetail } from "@/lib/types";

export async function loadVerificationAction(id: string): Promise<ActionResult<{ detail: VerificationDetail }>> {
  try {
    return { ok: true, detail: await backend.get<VerificationDetail>(`/api/v1/admin/verifications/${id}`) };
  } catch (e) {
    return actionError(e, {}, "We couldn't load that developer.");
  }
}

export async function decideVerificationAction(id: string, decision: "VERIFY" | "REQUEST_FIX" | "REJECT", note: string): Promise<ActionResult<{ detail: VerificationDetail }>> {
  try {
    const detail = await backend.post<VerificationDetail>(`/api/v1/admin/verifications/${id}/decision`, { decision, note: note || null });
    revalidatePath("/admin/verification");
    return { ok: true, detail };
  } catch (e) {
    return actionError(e, { "409": "Another admin already decided this. Showing their decision." }, "We couldn't record the decision.");
  }
}
```

`src/app/(admin)/admin/verification/VerificationConsole.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill, type Tone } from "@/components/ui/Pill";
import { timeAgo } from "@/lib/format";
import { checkText } from "@/lib/verificationCopy";
import type { VerificationDetail, VerificationQueueItem } from "@/lib/types";
import { decideVerificationAction, loadVerificationAction } from "./actions";

const STATE: Record<VerificationDetail["status"], { tone: Tone; label: string }> = {
  PENDING: { tone: "b", label: "Waiting on you" },
  VERIFIED: { tone: "ok", label: "Verified" },
  FIX_REQUESTED: { tone: "b", label: "Fix requested" },
  REJECTED: { tone: "bad", label: "Rejected" },
};

export function VerificationConsole({ queue, first }: { queue: VerificationQueueItem[]; first: VerificationDetail | null }) {
  const [detail, setDetail] = useState(first);
  const [note, setNote] = useState("");
  const [confirmReject, setConfirmReject] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function open(id: string) {
    setMessage(null);
    setNote("");
    setConfirmReject(false);
    const r = await loadVerificationAction(id);
    if (r.ok) setDetail(r.detail); else setMessage(r.error);
  }

  async function decide(decision: "VERIFY" | "REQUEST_FIX" | "REJECT") {
    if (!detail) return;
    if (decision !== "VERIFY" && !note.trim()) return setMessage("Write what needs fixing, or why, so the developer can act on it.");
    const r = await decideVerificationAction(detail.id, decision, note.trim());
    if (r.ok) { setDetail(r.detail); setMessage(null); }
    else {
      setMessage(r.error);
      if (r.code === "409") void open(detail.id).then(() => setMessage(r.error));
    }
    setConfirmReject(false);
  }

  return (
    <div className="grid grid-cols-[300px_minmax(0,1fr)_340px] gap-5 items-start">
      <div className="flex flex-col gap-2">
        <h1 className="text-xl font-semibold tracking-tight">Waiting for verification</h1>
        <p className="text-xs text-ink3">Developers can build and test without this. They need it to list agents and get paid.</p>
        {queue.map((q) => (
          <button key={q.id} type="button" onClick={() => open(q.id)} aria-pressed={detail?.id === q.id}
            className={`text-left px-3.5 py-3 rounded-xl border bg-surface ${detail?.id === q.id ? "border-blue shadow-[0_0_0_3px_var(--sky2)]" : "border-line"}`}>
            <b className="block text-[13px]">{q.name}</b>
            <small className="block text-xs text-ink3">{q.entityType === "INDIVIDUAL" ? "Individual" : "Company"}, submitted {timeAgo(q.submittedAt)}{q.resubmitted ? ", resubmitted" : ""}</small>
            <span className="mt-1 inline-block"><Pill tone={q.flag === "ALL_PASS" ? "ok" : "wa"}>{q.flag === "ALL_PASS" ? "All checks pass" : "Needs a look"}</Pill></span>
          </button>
        ))}
      </div>

      {detail ? (
        <div className="flex flex-col gap-4 min-w-0">
          <div>
            <h2 className="text-xl font-semibold">{detail.name}</h2>
            {detail.resubmissionCount > 0 && <Pill tone="b">Resubmitted</Pill>}
            {detail.intro && <p className="text-[13px] text-ink2 mt-1">{detail.intro}</p>}
          </div>
          <Panel aria-label="Checks" className="p-5 flex flex-col">
            {detail.checks.map((c) => {
              const t = checkText(c);
              return (
                <div key={c.kind} className="flex items-start gap-3 py-2.5 border-t border-line2 first:border-t-0 text-[13px]">
                  <span className="flex-1"><b className="block">{t.name}</b><span className="text-ink2">{t.detail}</span></span>
                  <Pill tone={t.tone}>{t.result}</Pill>
                </div>
              );
            })}
          </Panel>
          <Panel className="p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px]">Your decision</span>
              <Pill tone={STATE[detail.status].tone}>{STATE[detail.status].label}</Pill>
            </div>
            {detail.status === "PENDING" ? (
              <>
                <p className="text-xs text-ink3">Everything above is automatic. You confirm, ask for a fix, or reject.</p>
                <label className="flex flex-col gap-1 text-[13px]"><span className="text-ink2">Note to the developer</span>
                  <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} className="px-3 py-2 rounded-lg border border-line" />
                </label>
                {message && <p role="alert" className="text-[13px] text-bad">{message}</p>}
                <div className="flex gap-2 flex-wrap">
                  <Button onClick={() => decide("VERIFY")}>Verify developer</Button>
                  <Button variant="secondary" onClick={() => decide("REQUEST_FIX")}>Ask for a fix</Button>
                  {confirmReject ? (
                    <Button variant="danger" onClick={() => decide("REJECT")}>Yes, reject</Button>
                  ) : (
                    <Button variant="danger" onClick={() => (note.trim() ? setConfirmReject(true) : setMessage("Write what needs fixing, or why, so the developer can act on it."))}>Reject</Button>
                  )}
                </div>
              </>
            ) : (
              <>
                {message && <p role="alert" className="text-[13px] text-bad">{message}</p>}
                {detail.decisionNote && <p className="text-[13px] text-ink2">Note: {detail.decisionNote}</p>}
              </>
            )}
          </Panel>
        </div>
      ) : <Panel className="p-6 text-[13px] text-ink3">Nobody is waiting for verification.</Panel>}

      {detail && (
        <Panel className="p-5 flex flex-col">
          <PanelTitle>What they submitted</PanelTitle>
          <Kv label="Legal name" value={detail.facts.legalName} mono={false} />
          <Kv label="Type" value={detail.facts.entityType} mono={false} />
          <Kv label="Country" value={detail.facts.country} mono={false} />
          <Kv label="PAN" value={detail.facts.panMasked ?? "None"} />
          <Kv label="GSTIN" value={detail.facts.gstinMasked ?? "None"} />
          <Kv label="Payout bank" value={detail.facts.bank ?? "None"} mono={false} />
          <p className="text-xs text-ink3 mt-2">Verifying lets them list agents, earn and pay out, use their own AI keys, and get higher sandbox limits. The verified badge appears on every listing.</p>
        </Panel>
      )}
    </div>
  );
}
```

The test expects the alert text "Write what needs fixing", which matches the message above.

`src/app/(admin)/admin/verification/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { VerificationDetail, VerificationQueueItem } from "@/lib/types";
import { VerificationConsole } from "./VerificationConsole";

export default async function VerificationPage() {
  const queue = await backend.get<VerificationQueueItem[]>("/api/v1/admin/verifications?status=PENDING");
  const first = queue[0] ? await backend.get<VerificationDetail>(`/api/v1/admin/verifications/${queue[0].id}`) : null;
  return <VerificationConsole queue={queue} first={first} />;
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): developer verification console with automated checks and noted decisions"
```

---

### Task 4: Agent health and rollouts

**Files:**
- Create: `src/app/(admin)/admin/health/page.tsx`, `HealthConsole.tsx`, `actions.ts`
- Test: create `HealthConsole.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/admin/health/summary`, `GET /api/v1/admin/health/versions`, `GET /api/v1/admin/health/versions/{id}/blocked` (via `loadBlockedAction`), `GET /api/v1/admin/rollouts`, `POST` resume/rollback/suspend/message; `healthState`, `healthNote` (Task 1).
- Produces: `resumeAction(versionId)`, `rollbackAction(versionId)`, `suspendAction(versionId, reason)`, `messageDeveloperAction(orgId, subject, body)`, `loadBlockedAction(versionId): ActionResult<{ events: BlockedEvent[] }>`, `HealthConsole({ summary, versions, rollouts })`.

- [ ] **Step 1: Write the failing test**

`src/app/(admin)/admin/health/HealthConsole.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { HealthConsole } from "./HealthConsole";
import type { HealthSummary, Rollout, VersionHealth } from "@/lib/types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("./actions", () => ({
  resumeAction: vi.fn().mockResolvedValue({ ok: true }),
  rollbackAction: vi.fn().mockResolvedValue({ ok: true }),
  suspendAction: vi.fn().mockResolvedValue({ ok: true }),
  messageDeveloperAction: vi.fn().mockResolvedValue({ ok: true }),
  loadBlockedAction: vi.fn().mockResolvedValue({ ok: true, events: [{ at: "2026-09-23T04:28:00Z", kind: "NETWORK_BLOCKED", target: "api.holidays.example", count: 1 }] }),
}));

const summary: HealthSummary = { runsToday: 3412, finishedWithoutError: 0.978, blockedAttemptsWeek: 17, aiSpendToday: 61300 };
const paused: VersionHealth = {
  versionId: "vp12", agentName: "Vendor Payment Scheduler", version: "1.2.0", developer: "Paygrid", developerOrgId: "pg",
  successRate: 0.939, costP95: 780, costCap: 1000, blocked: 11, approvedRate: 0.71, disputeRate: 0.018, runs7d: 612, medianDurationSeconds: 91,
  state: "ROLLOUT_PAUSED", stateReason: { code: "FAILURE_RATE_UP", params: { rate: 0.061, previousRate: 0.014, previousVersion: "1.1.0" } },
};
const healthy: VersionHealth = { ...paused, versionId: "vb13", agentName: "Bank Reconciliation", version: "1.3.0", developer: "Ledgerline Labs", successRate: 0.991, costP95: 310, costCap: 500, blocked: 0, state: "HEALTHY", stateReason: { code: "HEALTHY", params: {} } };
const rollout: Rollout = {
  versionId: "vp12", agentName: "Vendor Payment Scheduler", version: "1.2.0", previousVersion: "1.1.0", percent: 20,
  haltedAt: "2026-09-23T04:00:00Z", haltReason: { code: "FAILURE_RATE_UP", params: {} }, autoUpdatedConnections: 18, consentedConnections: 3,
};

describe("HealthConsole", () => {
  it("shows the platform numbers and the paused rollout first", () => {
    render(<HealthConsole summary={summary} versions={[healthy, paused]} rollouts={[rollout]} />);
    expect(screen.getByText("3,412")).toBeInTheDocument();
    expect(screen.getByText("97.8%")).toBeInTheDocument();
    const banner = screen.getByRole("region", { name: "Rollout paused automatically" });
    expect(within(banner).getByText("1.2.0 fails 6.1% of runs, against 1.4% on 1.1.0.")).toBeInTheDocument();
    expect(within(banner).getByText("1.1.0 on 80% of auto-updating connections")).toBeInTheDocument();
  });

  it("explains a rollback before doing it", async () => {
    const { rollbackAction } = await import("./actions");
    render(<HealthConsole summary={summary} versions={[paused]} rollouts={[rollout]} />);
    await userEvent.click(screen.getByRole("button", { name: "Roll back to 1.1.0" }));
    expect(screen.getByText(/18 auto-updated workspaces move back to 1.1.0. 3 that agreed to 1.2.0 stay on it/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Yes, roll back" }));
    expect(rollbackAction).toHaveBeenCalledWith("vp12");
  });

  it("lists live versions with their health and shows the selected one's blocked attempts", async () => {
    render(<HealthConsole summary={summary} versions={[healthy, paused]} rollouts={[]} />);
    const row = screen.getByRole("row", { name: /Vendor Payment Scheduler 1.2.0/ });
    expect(within(row).getByText("93.9%")).toBeInTheDocument();
    expect(within(row).getByText("₹780 of ₹1,000")).toBeInTheDocument();
    await userEvent.click(within(row).getByRole("button", { name: "Open" }));
    expect(await screen.findByText("Tried to reach api.holidays.example. Blocked.")).toBeInTheDocument();
  });

  it("needs a reason to suspend", async () => {
    const { suspendAction } = await import("./actions");
    render(<HealthConsole summary={summary} versions={[paused]} rollouts={[]} />);
    await userEvent.click(screen.getByRole("button", { name: "Suspend version" }));
    await userEvent.click(screen.getByRole("button", { name: "Yes, suspend" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Give a reason");
    await userEvent.type(screen.getByLabelText("Reason, shown to the developer"), "Calls an undeclared website on every run.");
    await userEvent.click(screen.getByRole("button", { name: "Yes, suspend" }));
    expect(suspendAction).toHaveBeenCalledWith("vp12", "Calls an undeclared website on every run.");
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(admin)/admin/health"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(admin)/admin/health/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { BlockedEvent } from "@/lib/types";

async function run(call: () => Promise<unknown>, fallback: string): Promise<ActionResult> {
  try {
    await call();
  } catch (e) {
    return actionError(e, { "409": "Someone already changed this. Reload to see the latest." }, fallback);
  }
  revalidatePath("/admin/health");
  return { ok: true };
}

export async function resumeAction(versionId: string) {
  return run(() => backend.post(`/api/v1/admin/rollouts/${versionId}/resume`), "We couldn't resume the rollout.");
}
export async function rollbackAction(versionId: string) {
  return run(() => backend.post(`/api/v1/admin/rollouts/${versionId}/rollback`), "We couldn't roll back.");
}
export async function suspendAction(versionId: string, reason: string) {
  return run(() => backend.post(`/api/v1/admin/versions/${versionId}/suspend`, { reason }), "We couldn't suspend the version.");
}
export async function messageDeveloperAction(orgId: string, subject: string, body: string) {
  return run(() => backend.post(`/api/v1/admin/developers/${orgId}/messages`, { subject, body }), "We couldn't send the message.");
}
export async function loadBlockedAction(versionId: string): Promise<ActionResult<{ events: BlockedEvent[] }>> {
  try {
    return { ok: true, events: await backend.get<BlockedEvent[]>(`/api/v1/admin/health/versions/${versionId}/blocked`) };
  } catch (e) {
    return actionError(e, {}, "We couldn't load the blocked attempts.");
  }
}
```

`src/app/(admin)/admin/health/HealthConsole.tsx`:

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { clockTime, dayLabel, inr } from "@/lib/format";
import { healthNote, healthState } from "@/lib/healthCopy";
import type { BlockedEvent, HealthSummary, Rollout, VersionHealth } from "@/lib/types";
import { loadBlockedAction, messageDeveloperAction, resumeAction, rollbackAction, suspendAction } from "./actions";

const pct = (v: number) => `${Math.round(v * 1000) / 10}%`;
const mmss = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;

export function HealthConsole({ summary, versions, rollouts }: { summary: HealthSummary; versions: VersionHealth[]; rollouts: Rollout[] }) {
  const router = useRouter();
  const [selected, setSelected] = useState<string | null>(versions.find((v) => v.state !== "HEALTHY")?.versionId ?? versions[0]?.versionId ?? null);
  const [blocked, setBlocked] = useState<BlockedEvent[] | null>(null);
  const [confirm, setConfirm] = useState<"rollback" | "suspend" | null>(null);
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState({ open: false, subject: "", body: "" });
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const cur = versions.find((v) => v.versionId === selected) ?? null;
  const pausedRollout = rollouts.find((r) => r.haltedAt);
  const pausedVersion = pausedRollout && versions.find((v) => v.versionId === pausedRollout.versionId);

  useEffect(() => {
    if (!selected) return;
    setBlocked(null);
    void loadBlockedAction(selected).then((r) => setBlocked(r.ok ? r.events : []));
  }, [selected]);

  const done = (r: { ok: boolean; error?: string }, ok: string) => {
    setMessage(r.ok ? { tone: "ok", text: ok } : { tone: "bad", text: r.error ?? "Something went wrong." });
    setConfirm(null);
    if (r.ok) router.refresh();
  };

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Agent health</h1>
        <p className="text-ink2">Every live version, measured on real buyer runs over the last 7 days.</p>
      </div>
      <div className="grid grid-cols-4 gap-4">
        {[[summary.runsToday.toLocaleString("en-IN"), "Runs today"], [pct(summary.finishedWithoutError), "Finished without error"], [String(summary.blockedAttemptsWeek), "Blocked attempts this week"], [inr(summary.aiSpendToday), "AI spend today"]].map(([v, l]) => (
          <Panel key={l} className="p-4"><b className="font-mono font-medium text-xl">{v}</b><div className="text-xs text-ink3">{l}</div></Panel>
        ))}
      </div>
      {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}

      {pausedRollout && (
        <Panel aria-label="Rollout paused automatically" className="p-5 border-wa-line shadow-wait flex flex-col gap-3">
          <PanelTitle hint={dayLabel(pausedRollout.haltedAt!)}>Rollout paused automatically</PanelTitle>
          <p className="text-[13px]"><b>{pausedRollout.agentName} {pausedRollout.version}</b></p>
          {pausedVersion && <p className="text-[13px] text-ink2">{healthNote(pausedVersion)}</p>}
          <div className="flex h-2.5 rounded-full overflow-hidden bg-line2" aria-hidden>
            <i className="bg-blue-3" style={{ width: `${100 - pausedRollout.percent}%` }} />
            <i className="bg-wa-line" style={{ width: `${pausedRollout.percent}%` }} />
          </div>
          <div className="flex justify-between text-xs text-ink3">
            <span>{pausedRollout.previousVersion} on {100 - pausedRollout.percent}% of auto-updating connections</span>
            <span>{pausedRollout.version}, {pausedRollout.percent}%, paused</span>
          </div>
          {confirm === "rollback" ? (
            <div className="flex flex-col gap-2 text-[13px]">
              <p>{pausedRollout.autoUpdatedConnections} auto-updated workspaces move back to {pausedRollout.previousVersion}. {pausedRollout.consentedConnections} that agreed to {pausedRollout.version} stay on it and are told.</p>
              <div className="flex gap-2">
                <Button variant="danger" size="sm" onClick={async () => done(await rollbackAction(pausedRollout.versionId), `Rolled back to ${pausedRollout.previousVersion}.`)}>Yes, roll back</Button>
                <Button variant="secondary" size="sm" onClick={() => setConfirm(null)}>Keep it paused</Button>
              </div>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" onClick={async () => done(await resumeAction(pausedRollout.versionId), "Rollout resumed.")}>Resume</Button>
              <Button size="sm" variant="danger" onClick={() => setConfirm("rollback")}>Roll back to {pausedRollout.previousVersion}</Button>
            </div>
          )}
        </Panel>
      )}

      <div className="grid grid-cols-[minmax(0,1fr)_360px] gap-5 items-start">
        <Panel className="overflow-hidden">
          <table className="w-full text-[13px]">
            <thead><tr className="text-xs text-ink3">{["Live version", "Success", "Cost at worst vs limit", "Blocked", "Approved", "Disputes", ""].map((h) => <th key={h} className="text-left font-normal px-3 py-2 first:pl-5">{h}</th>)}</tr></thead>
            <tbody>
              {versions.map((v) => {
                const share = Math.min(100, Math.round((v.costP95 / v.costCap) * 100));
                return (
                  <tr key={v.versionId} aria-label={`${v.agentName} ${v.version}`} className={`border-t border-line2 ${v.versionId === selected ? "bg-sky" : ""}`}>
                    <td className="pl-5 px-3 py-2.5"><span className="block font-medium">{v.agentName} {v.version}</span><span className="text-xs text-ink3">{v.developer}</span></td>
                    <td className={`px-3 py-2.5 font-mono ${v.successRate < 0.97 ? "text-wa" : ""}`}>{pct(v.successRate)}</td>
                    <td className="px-3 py-2.5">
                      <span className="font-mono text-xs">{inr(v.costP95)} of {inr(v.costCap)}</span>
                      <span className="block h-1.5 rounded-full bg-line2 mt-1 overflow-hidden"><i className={`block h-full ${share >= 100 ? "bg-wa-line" : "bg-blue-2"}`} style={{ width: `${share}%` }} /></span>
                    </td>
                    <td className="px-3 py-2.5 font-mono">{v.blocked}</td>
                    <td className="px-3 py-2.5 font-mono">{v.approvedRate === null ? "—" : pct(v.approvedRate)}</td>
                    <td className="px-3 py-2.5 font-mono">{pct(v.disputeRate)}</td>
                    <td className="px-3 py-2.5 pr-5"><Button size="sm" variant="secondary" onClick={() => setSelected(v.versionId)}>Open</Button></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Panel>

        {cur && (
          <Panel className="p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between"><PanelTitle>{cur.agentName} {cur.version}</PanelTitle><Pill tone={healthState(cur).tone}>{healthState(cur).label}</Pill></div>
            <p className="text-xs text-ink3">{cur.developer}</p>
            <p className="text-[13px] text-ink2">{healthNote(cur)}</p>
            <div>
              <Kv label="Runs, last 7 days" value={cur.runs7d.toLocaleString("en-IN")} />
              <Kv label="Typical duration" value={mmss(cur.medianDurationSeconds)} />
              <Kv label="Finished without error" value={pct(cur.successRate)} />
              <Kv label="Drafts approved by buyers" value={cur.approvedRate === null ? "—" : pct(cur.approvedRate)} />
              <Kv label="Runs disputed" value={pct(cur.disputeRate)} />
            </div>
            <div>
              <PanelTitle>Blocked attempts</PanelTitle>
              <ul className="mt-1">
                {blocked === null ? <li className="text-xs text-ink3">Loading…</li>
                  : blocked.length === 0 ? <li className="text-[13px] text-ink3">Nothing blocked this week.</li>
                  : blocked.map((b, i) => (
                    <li key={i} className="grid grid-cols-[48px_1fr] gap-2 py-1.5 border-t border-line2 first:border-t-0 text-[13px]">
                      <span className="font-mono text-xs text-ink3">{dayLabel(b.at) === "Today" ? clockTime(b.at) : dayLabel(b.at)}</span>
                      <span>{b.kind === "NETWORK_BLOCKED" ? `Tried to reach ${b.target}. Blocked.` : `Tried to read ${b.target}. Refused.`}{b.count > 1 ? ` (${b.count} times)` : ""}</span>
                    </li>
                  ))}
              </ul>
            </div>
            {msg.open ? (
              <form className="flex flex-col gap-2" onSubmit={async (e) => { e.preventDefault(); const r = await messageDeveloperAction(cur.developerOrgId, msg.subject, msg.body); done(r, `Message sent to ${cur.developer}.`); if (r.ok) setMsg({ open: false, subject: "", body: "" }); }}>
                <input required aria-label="Subject" placeholder="Subject" value={msg.subject} onChange={(e) => setMsg({ ...msg, subject: e.target.value })} className="h-9 px-3 rounded-lg border border-line text-[13px]" />
                <textarea required aria-label="Message" rows={3} value={msg.body} onChange={(e) => setMsg({ ...msg, body: e.target.value })} className="px-3 py-2 rounded-lg border border-line text-[13px]" />
                <Button type="submit" size="sm">Send</Button>
              </form>
            ) : confirm === "suspend" ? (
              <div className="flex flex-col gap-2">
                <label className="flex flex-col gap-1 text-[13px]"><span className="text-ink2">Reason, shown to the developer</span>
                  <textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} className="px-3 py-2 rounded-lg border border-line" />
                </label>
                <p className="text-xs text-ink3">Suspending hides this version from the store and blocks new runs. Buyers on it are told.</p>
                <div className="flex gap-2">
                  <Button size="sm" variant="danger" onClick={async () => {
                    if (!reason.trim()) return setMessage({ tone: "bad", text: "Give a reason. The developer sees it." });
                    done(await suspendAction(cur.versionId, reason.trim()), `${cur.agentName} ${cur.version} is suspended.`);
                  }}>Yes, suspend</Button>
                  <Button size="sm" variant="secondary" onClick={() => setConfirm(null)}>Cancel</Button>
                </div>
              </div>
            ) : (
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" onClick={() => setMsg({ ...msg, open: true })}>Message developer</Button>
                <Button size="sm" variant="danger" onClick={() => setConfirm("suspend")}>Suspend version</Button>
              </div>
            )}
          </Panel>
        )}
      </div>
    </div>
  );
}
```

`src/app/(admin)/admin/health/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { HealthSummary, Rollout, VersionHealth } from "@/lib/types";
import { HealthConsole } from "./HealthConsole";

export default async function HealthPage() {
  const [summary, versions, rollouts] = await Promise.all([
    backend.get<HealthSummary>("/api/v1/admin/health/summary"),
    backend.get<VersionHealth[]>("/api/v1/admin/health/versions"),
    backend.get<Rollout[]>("/api/v1/admin/rollouts"),
  ]);
  return <HealthConsole summary={summary} versions={versions} rollouts={rollouts} />;
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): agent health console with paused rollouts, rollback, suspension and developer messages"
```

---

### Task 5: Admin end to end, and cleanup

**Files:**
- Create: `e2e/admin.spec.ts`
- Modify: `e2e/seed.ts` (an admin account), `frontend/README.md`

- [ ] **Step 1: Seed an admin**

In `e2e/seed.ts`, sign up `e2e-admin@example.com` (side `DEVELOPER`, org "Karya"). Its email must be listed in the backend's `ADMIN_EMAILS` for the E2E run. Add `ADMIN_EMAILS=e2e-admin@example.com` to the E2E section of `frontend/README.md`.

- [ ] **Step 2: Admin E2E**

`e2e/admin.spec.ts`:

```ts
import { expect, test } from "@playwright/test";

test("a Karya admin reaches the three consoles from the rail", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("e2e-admin@example.com");
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/admin\/review$/);
  await expect(page.getByRole("heading", { name: "Review queue" })).toBeVisible();
  await page.getByRole("link", { name: /^Developer verification/ }).click();
  await expect(page.getByRole("heading", { name: "Waiting for verification" })).toBeVisible();
  await page.getByRole("link", { name: /^Agent health/ }).click();
  await expect(page.getByRole("heading", { name: "Agent health" })).toBeVisible();
});
```

- [ ] **Step 3: Remove what is no longer used**

Run: `grep -rln "PermissionStatementDoc\|ScopeToggleGrant\|StatusPill" src`
Expected: only the developer manual submission wizard (`SubmissionWizard` and `/dashboard/new`) still uses them. Keep them until WS5's CLI push replaces manual submission, then delete them together.

- [ ] **Step 4: Verify**

Run: `npx tsc --noEmit && npm test && npm run build`, then with a fresh backend: `npm run seed:e2e && npm run test:e2e`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add -A frontend
git commit -m "test(frontend): admin E2E across review, verification and health consoles"
```
