# FE-3 — Developer Studio, Sandbox, Verification and Money Implementation Plan


**Goal:** Build the developer side of Karya:
- the studio, which shows what an agent declared against what it actually did in a sandbox run, with fixes one click away;
- sandbox runs with quota;
- test files and profiles;
- "Get verified";
- earnings and payouts.

It also finishes the buyer's Billing screen with adding money (UPI or bank transfer) and GST tax invoices.

**Architecture:**
- **Developer area.** It becomes `/studio` (agent list), with `/studio/{agentId}` (studio), `/studio/{agentId}/sandbox` and `/studio/{agentId}/test-files` behind a shared agent header, plus `/earnings` and `/verify`.
- **Flow canvas.** The studio reuses FE-2's `FlowCanvas` in two modes. "What you declared" shows manifest facts in each block. "What it did" shows sandbox counts, with undeclared reads and hosts as extra dashed blocks.
- **Uploads and downloads.** Uploads (fixtures, the PAN card image) reuse FE-2's upload proxy pattern. Downloads (statements, invoices) go through a streaming proxy route, so the token stays server-side.
- **Wallet top-up.** It opens Razorpay Checkout in the browser. The wallet is credited only by the backend webhook (WS10), so the UI says "updates within a minute" and never updates the balance itself.

**Tech Stack:** Next.js 14.2, React 18, TypeScript, Tailwind 3.4 (FE-1 tokens), TanStack Query 5, Vitest + React Testing Library, Playwright. Razorpay Checkout loaded from `https://checkout.razorpay.com/v1/checkout.js` at runtime (no npm package).

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §10 rows 9 (top-up and invoices), 13, 14, 15, 16 and 17. Also WS5 (builds), the WS6 UI alignment (report, callsite, quick fixes, readiness, quota, profiles, eval runs), the WS7 UI alignment (verification), the WS10 UI alignment (earnings, payouts, top-up, invoices) and WS16 (developer disputes).

**Mockups:** `fe-mockups/latest/Karya — Agentic UI.html`, boards "Developer studio: declared vs actual", "Sandbox runs and quota", "Test files and profiles", "Earnings and payouts", "Developer: get verified" and "Billing and wallet".

**Prerequisites:** FE-1 and FE-2 merged. Backend WS5, WS6, WS7 and WS10 merged, on top of WS0–WS3 and WS13–WS16. Those four have no implementation plans yet, so the contracts are pinned below. Task 1 checks them against the merged backend first.

## Backend contracts this plan assumes

The endpoints are listed here, and their exact field shapes are in Task 1's `src/lib/types.ts` block. When the WS5, WS6, WS7 or WS10 plans are written, they must provide these shapes, or this table and Task 1's types must be updated in the same change.

| Endpoint | From | Returns |
|---|---|---|
| `GET /api/v1/agents/mine` | WS5 | `DevAgent[]` (versions carry `buildStatus`) |
| `POST /api/v1/agents {name, category, description}` | existing | `AgentResponse` |
| `POST /api/v1/agents/{a}/versions/{v}/submit` | WS7/WS8 | 403 `VERIFICATION_REQUIRED` when unverified |
| `GET /api/v1/agents/{a}/versions/{v}/readiness` | WS6 | `Readiness` |
| `POST /api/v1/agents/{a}/versions/{v}/manifest/patches {op, resource?, host?}` | WS6 | `ManifestPatchResult` |
| `GET /api/v1/sandbox/runs?agentId=` | WS6 | `SandboxRunRow[]` |
| `GET /api/v1/sandbox/runs/{id}` | WS6 | `SandboxRunDetail` |
| `POST /api/v1/sandbox/runs {agentVersionId, profileId}` | WS6 | `SandboxRunRow` (409 `QUOTA_EXCEEDED`, `CONCURRENCY_LIMIT`, `BUILD_NOT_READY`) |
| `POST /api/v1/sandbox/runs/{id}/rerun` | WS6 | `SandboxRunRow` |
| `GET /api/v1/developer/quota` | WS6 | `DeveloperQuota` |
| `GET /api/v1/agents/{a}/sandbox-profiles` | WS6 | `SandboxProfile[]` |
| `POST /api/v1/agents/{a}/sandbox-profiles {name, description}` | WS6 | `SandboxProfile` |
| `PATCH /api/v1/agents/{a}/sandbox-profiles/{p} {gatePolicy?, llmMode?, replayRunId?, trialEnabled?, attestNoRealData?, bindings?}` | WS6/WS9 | `SandboxProfile` (400 `PII_FOUND`, `ATTESTATION_REQUIRED`) |
| `GET /api/v1/agents/{a}/eval-cases?profile=` | WS6/WS8 | `EvalCaseRow[]` |
| `POST /api/v1/datasets` multipart with `purpose=FIXTURE` | WS2/WS6 | `Dataset` |
| `GET /api/v1/developer/verification` | WS7 | `VerificationDraft` |
| `PATCH /api/v1/developer/verification {step, fields}` | WS7 | `VerificationDraft` |
| `POST /api/v1/developer/verification/phone/otp {phone}` | WS7 | 202 (429 while cooling down) |
| `POST /api/v1/developer/verification/phone/verify {code}` | WS7 | 204 / 400 |
| `POST /api/v1/developer/verification/pan-document` multipart | WS7 | 204 |
| `POST /api/v1/developer/verification/submit` | WS7 | `VerificationDraft` |
| `GET /api/v1/developer/earnings?month=YYYY-MM` | WS10 | `Earnings` |
| `GET /api/v1/developer/payouts/next` | WS10 | `NextPayout` |
| `GET /api/v1/developer/payouts` | WS10 | `PayoutRow[]` |
| `GET /api/v1/developer/statements/{month}/download` | WS10 | CSV |
| `GET /api/v1/developer/disputes`, `POST /api/v1/disputes/{id}/messages` | WS16 (planned) | `DisputeView[]` / `DisputeView` |
| `POST /api/v1/orgs/{o}/wallet/topups {amount, method: "UPI"}` | WS10 | `TopupOrder` |
| `GET /api/v1/orgs/{o}/wallet/bank-transfer-details` | WS10 | `BankTransferDetails` |
| `GET /api/v1/orgs/{o}/tax-invoices` | WS10 | `TaxInvoice[]` |
| `GET /api/v1/orgs/{o}` | WS10 | `{ id, name, gstin: string \| null }` (the org's billing profile) |
| `GET /api/v1/orgs/{o}/tax-invoices/{id}/download` | WS10 | PDF |

## Global Constraints

- Everything in FE-1's and FE-2's Global Constraints applies.
- Developer routes (`/studio`, `/earnings`, `/verify`) are for the `DEVELOPER` role. `roleHome("DEVELOPER")` becomes `/studio`. The legacy `/dashboard` pages redirect to `/studio`, except `/dashboard/new`, which stays as the "New agent" form.
- Sandbox money is always labelled simulated ("₹284 simulated"). Nothing on these screens implies a buyer was charged.
- Callsites (`match.py:14`) and code lines come from the agent's own code and are shown as hints: rendered as text in a monospace box, never as HTML.
- Tax IDs, phone and bank numbers are only ever shown masked, exactly as the backend returns them. Inputs for new values are `type="text"` with `autoComplete="off"` and are cleared after saving.
- Top-up amounts: presets ₹25,000, ₹50,000 and ₹1,00,000 (the mockup), UPI up to ₹1,00,000 a day. Razorpay's `key` comes from the backend's `TopupOrder`, not from frontend env.
- Downloads go through `GET /api/download?path=` which only accepts backend paths under `/api/v1/developer/statements/` and `/api/v1/orgs/{activeOrg}/tax-invoices/`.
- **Not in FE-3:** "Use your own AI keys" (the BYOK settings page, WS3; the verified-limits table mentions it only as text), the CLI itself (WS5), and the Karya admin consoles (FE-4).

## Review Focus

1. **A developer who isn't verified clicks "Submit for review".** They must be sent to "Get verified" with a sentence saying why, not shown a raw 403. [Task 2]
2. **Leaving "Get verified" halfway, then coming back.** The form must resume at the saved step with saved values (masked), not start over. [Task 6]
3. **Tapping "Resend" on the SMS code during the cooldown.** The button must stay disabled and show the countdown. A 429 from the backend is shown as the same countdown, not as an error. [Task 6]
4. **A fixture that contains a real-looking PAN, offered as a free buyer trial.** It must be refused with the rows named, and the trial toggle must stay off. [Task 4]
5. **Closing Razorpay Checkout without paying.** The wallet must not change and nothing says "added". A completed payment says the wallet updates shortly, because the webhook, not the browser, credits it. [Task 8]

---

## File Structure

```
frontend/src/
  lib/types.ts                          (modify: WS5/WS6/WS7/WS10 contracts)
  lib/sandboxCopy.ts                    (create: result labels, declared-vs-actual text)
  lib/session.ts, lib/routing.ts        (modify: developer home and routes)
  components/shell/nav.ts               (modify: developer items)
  app/api/download/route.ts             (create)
  app/api/datasets/upload/route.ts      (modify: pass purpose through)
  app/(developer)/studio/page.tsx, StudioIndex.tsx                                    (create)
  app/(developer)/studio/[agentId]/layout.tsx, AgentTabs.tsx                          (create)
  app/(developer)/studio/[agentId]/page.tsx, StudioView.tsx, IssueCard.tsx, actions.ts (create)
  app/(developer)/studio/[agentId]/sandbox/page.tsx, SandboxView.tsx, actions.ts      (create)
  app/(developer)/studio/[agentId]/test-files/page.tsx, ProfilesView.tsx, actions.ts  (create)
  app/(developer)/earnings/page.tsx, EarningsView.tsx, actions.ts                     (create)
  app/(developer)/verify/page.tsx, VerifyWizard.tsx, actions.ts                       (create)
  app/(developer)/dashboard/page.tsx                                                  (replace: redirect)
  app/(buyer)/billing/AddMoney.tsx, TaxInvoices.tsx, topupActions.ts                  (create)
  app/(buyer)/billing/BillingView.tsx, page.tsx                                       (modify)
e2e/developer.spec.ts                                                                 (create)
```

All commands run from `frontend/`.

---

### Task 1: Contracts, developer navigation and the download proxy

**Files:**
- Modify: `src/lib/types.ts`, `src/lib/session.ts`, `src/lib/session.test.ts`, `src/lib/routing.ts`, `src/lib/routing.test.ts`, `src/components/shell/nav.ts`, `src/components/shell/nav.test.ts`, `src/app/api/datasets/upload/route.ts`
- Create: `src/lib/sandboxCopy.ts`, `src/app/api/download/route.ts`, `src/app/(developer)/studio/page.tsx`, `StudioIndex.tsx`
- Replace: `src/app/(developer)/dashboard/page.tsx` (redirect)
- Test: create `src/lib/sandboxCopy.test.ts`, `src/app/api/download/route.test.ts`, `src/app/(developer)/studio/StudioIndex.test.tsx`

**Interfaces:**
- Produces:
  - The types listed in Step 3.
  - `sandboxResultPill(result, issueCount)`, `llmModeLabel(mode, replayOf)`, `declaredText(blockId, declared)`, `actualText(block, state, actual)`.
  - `GET /api/download?path=`.
  - Developer nav: Studio `/studio`, Earnings `/earnings`, Verification `/verify`.
  - `StudioIndex({ agents })`.

- [ ] **Step 1: Check each contract against the running backend**

As in FE-2 Task 1: sign in as the seeded developer (`e2e-dev@example.com`), request each endpoint in the contracts table, and bring Step 3's types in line with what the backend actually returns. Record differences in the commit message.

- [ ] **Step 2: Write the failing tests**

`src/lib/sandboxCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { actualText, declaredText, llmModeLabel, sandboxResultPill } from "./sandboxCopy";

describe("sandboxCopy", () => {
  it("labels results the way the mockup does", () => {
    expect(sandboxResultPill("ISSUES", 2)).toEqual({ tone: "bad", label: "2 issues" });
    expect(sandboxResultPill("ISSUES", 1)).toEqual({ tone: "bad", label: "1 issue" });
    expect(sandboxResultPill("CLEAN", 0)).toEqual({ tone: "ok", label: "Clean" });
    expect(sandboxResultPill("OVER_COST_LIMIT", 0)).toEqual({ tone: "wa", label: "Over cost limit" });
    expect(sandboxResultPill(null, 0)).toEqual({ tone: "b", label: "Running" });
  });
  it("names the AI mode", () => {
    expect(llmModeLabel("REPLAY", "S-118")).toBe("Replay of S-118");
    expect(llmModeLabel("MOCK", null)).toBe("Mock");
    expect(llmModeLabel("REAL", null)).toBe("Real");
  });
  it("writes declared and actual sides of a block", () => {
    expect(declaredText("read:bank_statements", { access: "READ", format: "CSV" })).toBe("READ, CSV");
    expect(declaredText("llm", { model: "claude-sonnet-5", tokenBudget: "400k" })).toBe("claude-sonnet-5, 400k tokens");
    expect(declaredText("gate:post", {})).toBe("Gate before posting");
    expect(declaredText("charge", { price: "₹2", max: "₹500" })).toBe("Max ₹500 per run");
    expect(actualText("read:bank_statements", "DONE", { rows: 412 })).toBe("412 rows read");
    expect(actualText("gate:post", "DONE", { autoApproved: 1 })).toBe("Auto-approved by test profile");
    expect(actualText("charge", "DONE", { simulated: 284 })).toBe("₹284 simulated");
    expect(actualText("llm", "DONE", { matches: 142, refused: 1 })).toBe("Done, 1 refused read");
  });
});
```

`src/app/api/download/route.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (n: string) => (n === "karya_session" ? { value: "header.eyJzdWIiOiJ1MSIsIm9yZ0lkIjoibzEiLCJyb2xlIjoiQlVZRVJfQURNSU4ifQ.sig" } : undefined) }),
}));

import { GET } from "./route";

describe("download proxy", () => {
  afterEach(() => vi.restoreAllMocks());

  it("streams an allowed file with its content type and name", async () => {
    process.env.BACKEND_URL = "http://backend";
    vi.spyOn(global, "fetch").mockResolvedValue(new Response("pdf-bytes", {
      status: 200, headers: { "Content-Type": "application/pdf", "Content-Disposition": 'attachment; filename="invoice.pdf"' },
    }));
    const res = await GET(new Request("http://app/api/download?path=/api/v1/orgs/o1/tax-invoices/t1/download"));
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/pdf");
    expect(res.headers.get("Content-Disposition")).toContain("invoice.pdf");
  });

  it("refuses paths outside the allowed set, including another org's invoices", async () => {
    const spy = vi.spyOn(global, "fetch");
    for (const path of ["/api/v1/admin/disputes", "/api/v1/orgs/o2/tax-invoices/t1/download", "/api/v1/orgs/o1/tax-invoices/../wallet"]) {
      const res = await GET(new Request(`http://app/api/download?path=${encodeURIComponent(path)}`));
      expect(res.status).toBe(400);
    }
    expect(spy).not.toHaveBeenCalled();
  });
});
```

`src/app/(developer)/studio/StudioIndex.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StudioIndex } from "./StudioIndex";

describe("StudioIndex", () => {
  it("lists agents with their live and draft versions and build state", () => {
    render(
      <StudioIndex
        verified={false}
        agents={[{
          agentId: "a1", agentName: "Bank Reconciliation", category: "Reconciliation",
          versions: [
            { id: "v14", versionNumber: "1.4.0", status: "DRAFT", buildStatus: "SUCCEEDED" },
            { id: "v13", versionNumber: "1.3.0", status: "LIVE", buildStatus: "SUCCEEDED" },
          ],
        }]}
      />,
    );
    expect(screen.getByRole("link", { name: /Bank Reconciliation/ })).toHaveAttribute("href", "/studio/a1");
    expect(screen.getByText("1.3.0 live")).toBeInTheDocument();
    expect(screen.getByText("1.4.0 draft, build passed")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Get verified" })).toHaveAttribute("href", "/verify");
  });
});
```

In `src/lib/session.test.ts`, change the `DEVELOPER` expectation to `"/studio"`. In `src/lib/routing.test.ts`, change `expect(resolveRedirect("/home", developer)).toBe("/dashboard")` to `"/studio"`, and add `expect(resolveRedirect("/studio/a1", buyer)).toBe("/home");`. In `src/components/shell/nav.test.ts`, change the developer expectations to `["/studio", "/earnings", "/verify"]`, and in the `activeHref` test use `activeHref(items, "/studio/a1/sandbox")` → `"/studio"`.

- [ ] **Step 3: Run the tests and confirm they fail**

Run: `npm test -- src/lib src/app/api/download "src/app/(developer)/studio" src/components/shell`
Expected: FAIL.

- [ ] **Step 4: Types**

Append to `src/lib/types.ts`:

```ts
// ---------- WS5/WS6: developer ----------
export type BuildStatus = "QUEUED" | "BUILDING" | "SUCCEEDED" | "FAILED";
export type DevVersionStatus = VersionStatus | "CHECKING" | "AUTO_REJECTED" | "CHANGES_REQUESTED" | "SUSPENDED" | "DEPRECATED";
export interface DevAgent {
  agentId: string;
  agentName: string;
  category: string;
  versions: { id: string; versionNumber: string; status: DevVersionStatus; buildStatus: BuildStatus | null }[];
}
export type LlmMode = "REAL" | "MOCK" | "REPLAY";
export type SandboxResult = "CLEAN" | "ISSUES" | "TIMED_OUT" | "OVER_COST_LIMIT" | "FAILED";
export interface SandboxRunRow {
  id: string;
  displayId: string;
  versionId: string;
  versionLabel: string;
  profileId: string;
  profileName: string;
  llmMode: LlmMode;
  replayOfDisplayId: string | null;
  status: RunStatus;
  result: SandboxResult | null;
  issueCount: number;
  simulatedCharge: number;
  requestedCharge: number;
  durationSeconds: number;
  aiCost: number;
  startedAt: string;
}
export interface Undeclared {
  kind: "READ" | "HOST";
  target: string;
  count: number;
  afterBlock: string;
  callsite: { file: string; line: number; codeLine: string } | null;
}
export interface BehaviourReport {
  result: SandboxResult;
  issueCount: number;
  blocks: { block: string; state: BlockState; declared: Record<string, string>; actual: Record<string, number> }[];
  undeclared: Undeclared[];
  cost: { simulatedCharge: number; requestedCharge: number; cap: number; aiCost: number; sandboxSeconds: number };
  unusedScopes: string[];
}
export interface SandboxRunDetail extends SandboxRunRow {
  agentId: string;
  manifest: ManifestV2;
  report: BehaviourReport | null;
  steps: RunStepResponse[];
}
export interface QuotaNumbers {
  runMinutes: number;
  aiSpend: number;
  runsAtOnce: number;
  buildsToday: number;
  storageBytes: number;
}
export interface DeveloperQuota {
  verified: boolean;
  used: QuotaNumbers;
  limit: QuotaNumbers;
  verifiedLimit: QuotaNumbers;
}
export interface SandboxProfile {
  id: string;
  name: string;
  description: string | null;
  status: "READY" | "FILE_ERRORS";
  gatePolicy: "MANUAL" | "AUTO_APPROVE" | "AUTO_REJECT";
  llmMode: LlmMode;
  replayRunId: string | null;
  replayRunDisplayId: string | null;
  trialEnabled: boolean;
  attested: boolean;
  source: "PORTAL" | "REPO";
  sockets: { resource: string; datasetId: string | null; fileName: string | null; rowCount: number | null; schemaRef: string | null; check: CheckResult | null }[];
  piiScan: { status: "CLEAN" | "FOUND"; findings: { type: string; file: string; row: number; column: string }[] };
}
export interface EvalCaseRow {
  id: string;
  title: string;
  profile: string;
  latestResult: "PASSING" | "FAILING" | "ERROR" | null;
  detail: string | null;
}
export interface Readiness {
  ready: boolean;
  blockers: { code: string; message: string; runDisplayId: string | null }[];
}
export interface ManifestPatchResult {
  manifest: ManifestV2;
  diff: VersionDiffRow[];
  buyersAffected: number;
}

// ---------- WS7: verification ----------
export type VerificationStep = "ABOUT" | "TAX_IDS" | "PHONE" | "PAYOUT" | "AGREEMENT";
export interface VerificationDraft {
  status: "DRAFT" | "PENDING" | "VERIFIED" | "FIX_REQUESTED" | "REJECTED";
  currentStep: VerificationStep;
  entityType: "INDIVIDUAL" | "LLP" | "PRIVATE_LIMITED" | "PARTNERSHIP" | "OTHER" | null;
  legalName: string | null;
  displayName: string | null;
  country: string | null;
  city: string | null;
  panMasked: string | null;
  gstinMasked: string | null;
  panDocumentUploaded: boolean;
  phoneMasked: string | null;
  phoneVerified: boolean;
  bankHolderName: string | null;
  bankAccountLast4: string | null;
  bankIfsc: string | null;
  bankName: string | null;
  bankVerified: boolean;
  agreementVersion: string | null;
  agreementAcceptedAt: string | null;
  currentAgreementVersion: string;
  decisionNote: string | null;
}

// ---------- WS10: money ----------
export interface Earnings {
  month: string;
  paidRuns: number;
  buyersPaid: number;
  platformFee: number;
  feePercent: number;
  aiUsage: number;
  earned: number;
  byAgent: { agentId: string; agent: string; versionsLabel: string; runs: number; buyersPaid: number; fee: number; aiUsage: number; earned: number; perRun: number }[];
}
export interface NextPayout {
  date: string;
  amount: number;
  earned: number;
  holds: { disputeId: string; amount: number }[];
  account: { bankName: string; last4: string; holderName: string; verified: boolean } | null;
}
export interface PayoutRow {
  period: string;
  paidAt: string | null;
  amount: number;
  status: "PENDING" | "PAID";
}
export interface TopupOrder {
  topupId: string;
  checkout: { key: string; orderId: string; amount: number; currency: "INR"; name: string };
}
export interface BankTransferDetails {
  accountName: string;
  accountNumber: string;
  ifsc: string;
  bankName: string;
}
export interface TaxInvoice {
  id: string;
  period: string;
  status: "PENDING" | "ISSUED";
  amountInclGst: number | null;
  issuedAt: string | null;
}
```

- [ ] **Step 5: Copy helpers, routing, nav, proxies**

`src/lib/sandboxCopy.ts`:

```ts
import type { Tone } from "@/components/ui/Pill";
import { inr } from "./format";
import type { BlockState, LlmMode, SandboxResult } from "./types";

export function sandboxResultPill(result: SandboxResult | null, issueCount: number): { tone: Tone; label: string } {
  switch (result) {
    case null: return { tone: "b", label: "Running" };
    case "CLEAN": return { tone: "ok", label: "Clean" };
    case "ISSUES": return { tone: "bad", label: `${issueCount} issue${issueCount === 1 ? "" : "s"}` };
    case "TIMED_OUT": return { tone: "wa", label: "Timed out" };
    case "OVER_COST_LIMIT": return { tone: "wa", label: "Over cost limit" };
    case "FAILED": return { tone: "bad", label: "Failed" };
  }
}

export function llmModeLabel(mode: LlmMode, replayOf: string | null): string {
  if (mode === "REPLAY") return replayOf ? `Replay of ${replayOf}` : "Replay";
  return mode === "MOCK" ? "Mock" : "Real";
}

const kind = (blockId: string) => blockId.split(":")[0];

export function declaredText(blockId: string, d: Record<string, string>): string {
  switch (kind(blockId)) {
    case "read": return [d.access ?? "READ", d.format].filter(Boolean).join(", ");
    case "llm": return `${d.model ?? "AI"}${d.tokenBudget ? `, ${d.tokenBudget} tokens` : ""}`;
    case "draft": return "WRITE, needs approval";
    case "gate": return "Gate before posting";
    case "write": return "Only after approval";
    case "charge": return `Max ${d.max ?? "?"} per run`;
    default: return "";
  }
}

export function actualText(blockId: string, state: BlockState, a: Record<string, number>): string {
  if (state === "IDLE") return "Not reached";
  switch (kind(blockId)) {
    case "read": return `${(a.rows ?? 0).toLocaleString("en-IN")} rows read`;
    case "llm": return a.refused ? `Done, ${a.refused} refused read` : "Done";
    case "draft": return `${a.records ?? 0} drafted`;
    case "gate": return a.autoApproved ? "Auto-approved by test profile" : a.autoRejected ? "Auto-rejected by test profile" : state === "WAITING" ? "Waiting for you" : "Approved by you";
    case "write": return `${a.records ?? 0} committed`;
    case "charge": return a.requested && a.requested > (a.simulated ?? 0) ? `${inr(a.requested)} asked, ${inr(a.simulated ?? 0)} kept` : `${inr(a.simulated ?? 0)} simulated`;
    default: return "";
  }
}
```

In `src/lib/session.ts`, change `roleHome`: `if (role === "DEVELOPER") return "/studio";`.

In `src/lib/routing.ts`, add to `ROLE_PREFIXES`: `["/studio", ["DEVELOPER"]]`, `["/earnings", ["DEVELOPER"]]`, `["/verify", ["DEVELOPER"]]`.

In `src/components/shell/nav.ts`, replace the `DEVELOPER` case:

```ts
    case "DEVELOPER":
      return [
        { href: "/studio", label: "Studio", icon: "code" },
        { href: "/earnings", label: "Earnings", icon: "billing" },
        { href: "/verify", label: "Verification", icon: "gate" },
      ];
```

In `src/app/api/datasets/upload/route.ts` nothing changes: the form is forwarded as is, so a `purpose` field sent by the client reaches the backend.

`src/app/api/download/route.ts`:

```ts
import { cookies } from "next/headers";
import { decodeSessionToken, SESSION_COOKIE } from "@/lib/session";

/** Streams a statement or invoice from the backend. Only these paths, and only the caller's own org. */
export async function GET(request: Request): Promise<Response> {
  const path = new URL(request.url).searchParams.get("path") ?? "";
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const claims = token ? decodeSessionToken(token) : null;
  const allowed = [
    /^\/api\/v1\/developer\/statements\/\d{4}-\d{2}\/download$/,
    new RegExp(`^/api/v1/orgs/${claims?.orgId ?? "none"}/tax-invoices/[0-9a-zA-Z-]+/download$`),
  ];
  if (!claims || !allowed.some((re) => re.test(path))) {
    return Response.json({ detail: "Not a downloadable file" }, { status: 400 });
  }
  const res = await fetch(`${process.env.BACKEND_URL}${path}`, { headers: { Authorization: `Bearer ${token}` }, cache: "no-store" });
  const headers = new Headers();
  for (const h of ["Content-Type", "Content-Disposition", "Content-Length"]) {
    const v = res.headers.get(h);
    if (v) headers.set(h, v);
  }
  return new Response(res.body, { status: res.status, headers });
}
```

The test's fake session token decodes to `orgId: "o1"`. `decodeSessionToken` accepts it because it has no `exp`.

`src/app/(developer)/studio/StudioIndex.tsx`:

```tsx
import Link from "next/link";
import { ButtonLink } from "@/components/ui/Button";
import { Panel } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import type { DevAgent } from "@/lib/types";

const BUILD: Record<string, string> = { SUCCEEDED: "build passed", FAILED: "build failed", BUILDING: "building", QUEUED: "build queued" };

export function StudioIndex({ agents, verified }: { agents: DevAgent[]; verified: boolean }) {
  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight">Studio</h1>
          <p className="text-ink2">Build with <span className="font-mono">agentstore push</span>, test in a real sandbox, and submit when it&apos;s clean.</p>
        </div>
        <ButtonLink href="/dashboard/new" variant="secondary">New agent</ButtonLink>
      </div>
      {!verified && (
        <Panel className="p-4 flex items-center justify-between bg-sky border-blue-3 text-[13px]">
          <span>You can build and test for free. Get verified to list agents and get paid.</span>
          <ButtonLink href="/verify" size="sm">Get verified</ButtonLink>
        </Panel>
      )}
      <div className="grid grid-cols-2 gap-4">
        {agents.map((a) => {
          const live = a.versions.find((v) => v.status === "LIVE");
          const draft = a.versions.find((v) => v.status === "DRAFT");
          return (
            <Link key={a.agentId} href={`/studio/${a.agentId}`}>
              <Panel as="div" className="p-5 flex flex-col gap-2 hover:border-blue-3">
                <span className="text-xs text-ink3">{a.category}</span>
                <span className="text-base font-semibold">{a.agentName}</span>
                <span className="flex gap-2 flex-wrap">
                  {live && <Pill tone="ok">{live.versionNumber} live</Pill>}
                  {draft && <Pill tone={draft.buildStatus === "FAILED" ? "bad" : "b"}>{`${draft.versionNumber} draft${draft.buildStatus ? `, ${BUILD[draft.buildStatus]}` : ""}`}</Pill>}
                </span>
              </Panel>
            </Link>
          );
        })}
      </div>
      {agents.length === 0 && (
        <Panel className="p-6 text-[13px] text-ink2">
          No agents yet. Create one, then run <span className="font-mono">agentstore init</span> and <span className="font-mono">agentstore push</span>.
        </Panel>
      )}
    </div>
  );
}
```

`src/app/(developer)/studio/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { DevAgent, DeveloperQuota } from "@/lib/types";
import { StudioIndex } from "./StudioIndex";

export default async function StudioPage() {
  const [agents, quota] = await Promise.all([
    backend.get<DevAgent[]>("/api/v1/agents/mine"),
    backend.get<DeveloperQuota>("/api/v1/developer/quota"),
  ]);
  return <StudioIndex agents={agents} verified={quota.verified} />;
}
```

Replace `src/app/(developer)/dashboard/page.tsx`:

```tsx
import { redirect } from "next/navigation";

export default function DashboardRedirect() {
  redirect("/studio");
}
```

(`/dashboard/new` and the legacy `/dashboard/agents/...` pages stay until Task 9.)

- [ ] **Step 6: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS. The legacy `DashboardList` test still passes; the component is unused now and is deleted in Task 9.

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): developer contracts, studio index, developer navigation and a safe download proxy"
```

---

### Task 2: The studio: declared against actual

**Files:**
- Create: `src/app/(developer)/studio/[agentId]/layout.tsx`, `AgentTabs.tsx`, `page.tsx`, `StudioView.tsx`, `IssueCard.tsx`, `actions.ts`
- Test: create `StudioView.test.tsx`, `IssueCard.test.tsx` in `src/app/(developer)/studio/[agentId]/`

**Interfaces:**
- Consumes:
  - `GET /api/v1/agents/mine`, `GET /api/v1/sandbox/runs?agentId=`, `GET /api/v1/sandbox/runs/{id}`, `GET /api/v1/agents/{a}/versions/{v}/readiness`, `GET /api/v1/developer/quota`;
  - `POST /api/v1/agents/{a}/versions/{v}/manifest/patches`, `POST /api/v1/sandbox/runs`, `POST /api/v1/agents/{a}/versions/{v}/submit`;
  - `flowBlocks`, `FlowCanvas` (FE-2); `declaredText`, `actualText` (Task 1).
- Produces:
  - `AgentTabs({ agentId, active })`.
  - `patchManifestAction(agentId, versionId, op, target): ActionResult<{ result: ManifestPatchResult }>`, `runSandboxAction(agentId, versionId, profileId): ActionResult`, `submitAction(agentId, versionId): ActionResult`.
  - `IssueCard({ issue, agentId, versionId, onFixed })`.
  - `StudioView({ agent, version, run, readiness, quota })`.

- [ ] **Step 1: Write the failing tests**

`src/app/(developer)/studio/[agentId]/IssueCard.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { IssueCard } from "./IssueCard";

vi.mock("./actions", () => ({
  patchManifestAction: vi.fn().mockResolvedValue({
    ok: true,
    result: { manifest: {}, diff: [{ kind: "READ", text: "Reads vendor_master", tag: "NEW" }], buyersAffected: 63 },
  }),
  runSandboxAction: vi.fn(),
  submitAction: vi.fn(),
}));

describe("IssueCard", () => {
  it("shows where in the code the undeclared read happened, as plain text", () => {
    render(
      <IssueCard agentId="a1" versionId="v14" onFixed={vi.fn()}
        issue={{ kind: "READ", target: "vendor_master", count: 1, afterBlock: "read:invoices", callsite: { file: "match.py", line: 14, codeLine: 'vendors = ctx.data.records("vendor_master")' } }} />,
    );
    expect(screen.getByRole("heading", { name: "Read a file it didn't declare" })).toBeInTheDocument();
    expect(screen.getByText("match.py:14")).toBeInTheDocument();
    expect(screen.getByText('vendors = ctx.data.records("vendor_master")')).toBeInTheDocument();
  });

  it("declares the read and says buyers will be asked to agree", async () => {
    const onFixed = vi.fn();
    const { patchManifestAction } = await import("./actions");
    render(<IssueCard agentId="a1" versionId="v14" onFixed={onFixed}
      issue={{ kind: "READ", target: "vendor_master", count: 1, afterBlock: "read:invoices", callsite: null }} />);
    await userEvent.click(screen.getByRole("button", { name: "Declare it as a read" }));

    expect(patchManifestAction).toHaveBeenCalledWith("a1", "v14", "add_read_scope", "vendor_master");
    expect(await screen.findByRole("status")).toHaveTextContent("vendor_master added to your manifest");
    expect(screen.getByRole("status")).toHaveTextContent("63 buyer workspaces will be asked to agree");
    expect(onFixed).toHaveBeenCalledWith("vendor_master");
  });

  it("offers the allowlist for a blocked host, with the buyer-visibility warning", () => {
    render(<IssueCard agentId="a1" versionId="v14" onFixed={vi.fn()}
      issue={{ kind: "HOST", target: "hooks.slack.com", count: 2, afterBlock: "llm", callsite: { file: "notify.py", line: 8, codeLine: "httpx.post(SLACK_WEBHOOK, json=summary)" } }} />);
    expect(screen.getByRole("heading", { name: "Tried to reach a website" })).toBeInTheDocument();
    expect(screen.getByText(/The sandbox blocked 2 calls to/)).toBeInTheDocument();
    expect(screen.getByText("hooks.slack.com")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add to allowlist" })).toBeInTheDocument();
    expect(screen.getByText(/they'll see it on your listing/)).toBeInTheDocument();
  });
});
```

`src/app/(developer)/studio/[agentId]/StudioView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { StudioView } from "./StudioView";
import { storeAgent } from "../../../(buyer)/store/[agentId]/fixtures";
import type { DeveloperQuota, SandboxRunDetail } from "@/lib/types";

vi.mock("./actions", () => ({
  patchManifestAction: vi.fn(),
  runSandboxAction: vi.fn().mockResolvedValue({ ok: true }),
  submitAction: vi.fn().mockResolvedValue({ ok: false, code: "VERIFICATION_REQUIRED", error: "Get verified before you submit an agent for listing." }),
}));

const manifest = storeAgent.liveVersion.manifest;
const run: SandboxRunDetail = {
  id: "s121", displayId: "S-121", versionId: "v14", versionLabel: "1.4.0 draft", profileId: "p1", profileName: "august-fixtures",
  llmMode: "REPLAY", replayOfDisplayId: "S-118", status: "COMPLETED", result: "ISSUES", issueCount: 2,
  simulatedCharge: 284, requestedCharge: 284, durationSeconds: 48, aiCost: 0, startedAt: "2026-09-23T04:00:00Z",
  agentId: "a1", manifest, steps: [],
  report: {
    result: "ISSUES", issueCount: 2,
    blocks: [
      { block: "read:bank_statements", state: "DONE", declared: { access: "READ", format: "CSV" }, actual: { rows: 412 } },
      { block: "read:invoices", state: "DONE", declared: { access: "READ", format: "CSV" }, actual: { rows: 389 } },
      { block: "llm", state: "DONE", declared: { model: "claude-sonnet-5", tokenBudget: "400k" }, actual: { refused: 1 } },
      { block: "draft:journal_entries", state: "DONE", declared: {}, actual: { records: 142 } },
      { block: "gate:post_journal_entries", state: "DONE", declared: {}, actual: { autoApproved: 1 } },
      { block: "write:journal_entries", state: "DONE", declared: {}, actual: { records: 142 } },
      { block: "charge", state: "DONE", declared: { max: "₹500" }, actual: { simulated: 284 } },
    ],
    undeclared: [
      { kind: "READ", target: "vendor_master", count: 1, afterBlock: "read:invoices", callsite: null },
      { kind: "HOST", target: "hooks.slack.com", count: 2, afterBlock: "llm", callsite: null },
    ],
    cost: { simulatedCharge: 284, requestedCharge: 284, cap: 500, aiCost: 0, sandboxSeconds: 48 },
    unusedScopes: [],
  },
};
const quota: DeveloperQuota = {
  verified: false,
  used: { runMinutes: 34, aiSpend: 38, runsAtOnce: 0, buildsToday: 3, storageBytes: 0 },
  limit: { runMinutes: 120, aiSpend: 200, runsAtOnce: 1, buildsToday: 20, storageBytes: 500 * 1024 ** 2 },
  verifiedLimit: { runMinutes: 1000, aiSpend: 2000, runsAtOnce: 5, buildsToday: 100, storageBytes: 5 * 1024 ** 3 },
};

function renderStudio() {
  return render(
    <StudioView agentId="a1" agentName="Bank Reconciliation" version={{ id: "v14", versionNumber: "1.4.0", status: "DRAFT", buildStatus: "SUCCEEDED" }}
      run={run} readiness={{ ready: false, blockers: [{ code: "UNDECLARED_READ", message: "", runDisplayId: "S-121" }, { code: "BLOCKED_HOST", message: "", runDisplayId: "S-121" }] }}
      quota={quota} defaultProfileId="p1" />,
  );
}

describe("StudioView", () => {
  it("shows what it did by default, with undeclared attempts as extra blocks", () => {
    renderStudio();
    expect(screen.getByRole("button", { name: "What it did" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("412 rows read")).toBeInTheDocument();
    expect(screen.getByText("Auto-approved by test profile")).toBeInTheDocument();
    expect(screen.getByTestId("block-x:read:vendor_master")).toHaveAttribute("data-tone", "bad");
    expect(screen.getByTestId("block-x:host:hooks.slack.com")).toBeInTheDocument();
  });

  it("switches to what was declared", async () => {
    renderStudio();
    await userEvent.click(screen.getByRole("button", { name: "What you declared" }));
    expect(screen.getByText("claude-sonnet-5, 400k tokens")).toBeInTheDocument();
    expect(screen.getByText("Max ₹500 per run")).toBeInTheDocument();
  });

  it("warns before submitting a build review would reject, and sends unverified developers to verification", async () => {
    renderStudio();
    expect(screen.getByText("Fix 2 issues first. Review would reject this build.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Get verified");
    expect(screen.getByRole("link", { name: "Get verified" })).toHaveAttribute("href", "/verify");
  });

  it("shows the cost against the declared limit and sandbox minutes", () => {
    renderStudio();
    expect(screen.getByText("₹284 of ₹500")).toBeInTheDocument();
    expect(screen.getByText("34 of 120")).toBeInTheDocument();
    expect(screen.getByText("AI answers replayed from S-118, cost ₹0")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(developer)/studio/\[agentId\]"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(developer)/studio/[agentId]/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { ManifestPatchResult } from "@/lib/types";

type PatchOp = "add_read_scope" | "add_allowlist_host" | "remove_scope";

export async function patchManifestAction(agentId: string, versionId: string, op: PatchOp, target: string): Promise<ActionResult<{ result: ManifestPatchResult }>> {
  try {
    const body = op === "add_allowlist_host" ? { op, host: target } : { op, resource: target };
    const result = await backend.post<ManifestPatchResult>(`/api/v1/agents/${agentId}/versions/${versionId}/manifest/patches`, body);
    revalidatePath(`/studio/${agentId}`);
    return { ok: true, result };
  } catch (e) {
    return actionError(e, {
      "409": "Only a draft version can be changed. Push a new version to change a submitted one.",
      "400": "That change would make the manifest invalid.",
    }, "We couldn't change the manifest.");
  }
}

export async function runSandboxAction(agentId: string, versionId: string, profileId: string): Promise<ActionResult> {
  try {
    await backend.post("/api/v1/sandbox/runs", { agentVersionId: versionId, profileId });
  } catch (e) {
    return actionError(e, {
      QUOTA_EXCEEDED: "You've used this month's sandbox minutes. Get verified for more.",
      CONCURRENCY_LIMIT: "A sandbox run is already going. Wait for it to finish.",
      BUILD_NOT_READY: "This version hasn't built yet. Check the build log from agentstore push.",
    }, "The sandbox run couldn't start.");
  }
  revalidatePath(`/studio/${agentId}`, "layout");
  return { ok: true };
}

export async function submitAction(agentId: string, versionId: string): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/agents/${agentId}/versions/${versionId}/submit`);
  } catch (e) {
    return actionError(e, {
      VERIFICATION_REQUIRED: "Get verified before you submit an agent for listing.",
      "403": "Get verified before you submit an agent for listing.",
      "409": "This version can't be submitted: its build hasn't passed.",
    }, "We couldn't submit this version.");
  }
  revalidatePath(`/studio/${agentId}`);
  return { ok: true };
}
```

`src/app/(developer)/studio/[agentId]/IssueCard.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import type { Undeclared } from "@/lib/types";
import { patchManifestAction } from "./actions";

export function IssueCard({ issue, agentId, versionId, onFixed }: {
  issue: Undeclared; agentId: string; versionId: string; onFixed: (target: string) => void;
}) {
  const [fixed, setFixed] = useState<string | null>(null);
  const [explain, setExplain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const read = issue.kind === "READ";

  async function fix() {
    const r = await patchManifestAction(agentId, versionId, read ? "add_read_scope" : "add_allowlist_host", issue.target);
    if (!r.ok) return setError(r.error);
    setFixed(
      `${issue.target} added to your ${read ? "manifest" : "allowlist"}.` +
      (r.result.buyersAffected > 0 ? ` ${r.result.buyersAffected} buyer workspaces will be asked to agree before they update.` : ""),
    );
    onFixed(issue.target);
  }

  return (
    <div className="border border-[#F1CFCA] bg-[#FFFAF9] rounded-xl p-3.5 flex flex-col gap-2 text-[13px]">
      <h3 className="font-semibold">{read ? "Read a file it didn't declare" : "Tried to reach a website"}</h3>
      <p className="text-ink2">
        {read
          ? <>It asked for <span className="font-mono">{issue.target}</span> {issue.count === 1 ? "once" : `${issue.count} times`} and Karya refused. Automated review rejects this.</>
          : <>The sandbox blocked {issue.count} call{issue.count === 1 ? "" : "s"} to <span className="font-mono">{issue.target}</span>. Add it to your allowlist only if buyers need it; they&apos;ll see it on your listing.</>}
      </p>
      {issue.callsite && (
        <div className="font-mono text-[11.5px] bg-[#F4F6FB] border border-line rounded-lg px-2.5 py-2 overflow-hidden text-ellipsis whitespace-nowrap">
          <span className="text-ink3 mr-2.5">{issue.callsite.file}:{issue.callsite.line}</span>
          <span>{issue.callsite.codeLine}</span>
        </div>
      )}
      {error && <p role="alert" className="text-bad">{error}</p>}
      {fixed ? (
        <p role="status" className="text-ok">{fixed}</p>
      ) : (
        <div className="flex gap-2">
          <Button size="sm" onClick={fix}>{read ? "Declare it as a read" : "Add to allowlist"}</Button>
          <Button size="sm" variant="secondary" onClick={() => setExplain(true)}>Remove the call</Button>
        </div>
      )}
      {explain && !fixed && (
        <p className="text-xs text-ink3">
          Delete {issue.callsite ? `line ${issue.callsite.line} of ${issue.callsite.file}` : "the call"}, then run <span className="font-mono">agentstore push</span> and a new sandbox run.
        </p>
      )}
    </div>
  );
}
```

`src/app/(developer)/studio/[agentId]/AgentTabs.tsx`:

```tsx
import Link from "next/link";

export function AgentTabs({ agentId, agentName, active }: { agentId: string; agentName: string; active: "studio" | "sandbox" | "files" }) {
  const tabs = [
    { key: "studio", href: `/studio/${agentId}`, label: "Studio" },
    { key: "sandbox", href: `/studio/${agentId}/sandbox`, label: "Sandbox runs" },
    { key: "files", href: `/studio/${agentId}/test-files`, label: "Test files" },
  ] as const;
  return (
    <div className="flex items-end justify-between mb-6">
      <h1 className="text-[26px] font-semibold tracking-tight">{agentName}</h1>
      <nav aria-label="Agent sections" className="flex gap-1 bg-line2 p-[3px] rounded-[10px]">
        {tabs.map((t) => (
          <Link key={t.key} href={t.href} aria-current={active === t.key ? "page" : undefined}
            className={`h-[34px] px-3 rounded-lg text-[13px] inline-flex items-center ${active === t.key ? "bg-surface font-semibold shadow-sm" : "text-ink2"}`}>
            {t.label}
          </Link>
        ))}
      </nav>
    </div>
  );
}
```

`src/app/(developer)/studio/[agentId]/layout.tsx`:

```tsx
export default function StudioAgentLayout({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}
```

`src/app/(developer)/studio/[agentId]/StudioView.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { FlowCanvas, type ExtraBlock } from "@/components/flow/FlowCanvas";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { Segmented } from "@/components/ui/Segmented";
import { flowBlocks } from "@/lib/flow";
import { inr } from "@/lib/format";
import { actualText, declaredText, sandboxResultPill } from "@/lib/sandboxCopy";
import type { BlockState, DevAgent, DeveloperQuota, Readiness, SandboxRunDetail } from "@/lib/types";
import { runSandboxAction, submitAction } from "./actions";
import { IssueCard } from "./IssueCard";

type Version = DevAgent["versions"][number];

export function StudioView({ agentId, agentName, version, run, readiness, quota, defaultProfileId }: {
  agentId: string; agentName: string; version: Version; run: SandboxRunDetail | null; readiness: Readiness; quota: DeveloperQuota; defaultProfileId: string | null;
}) {
  const [view, setView] = useState<"actual" | "declared">("actual");
  const [fixedTargets, setFixedTargets] = useState<string[]>([]);
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string; verify?: boolean } | null>(null);
  const report = run?.report ?? null;
  const blocks = run ? flowBlocks(run.manifest) : [];
  const open = (report?.undeclared ?? []).filter((u) => !fixedTargets.includes(u.target));
  const issues = open.length;

  const states: Record<string, BlockState> = {};
  const metrics: Record<string, string> = {};
  for (const b of report?.blocks ?? []) {
    states[b.block] = view === "actual" ? b.state : "DONE";
    metrics[b.block] = view === "actual" ? actualText(b.block, b.state, b.actual) : declaredText(b.block, b.declared);
  }
  const extras: ExtraBlock[] = view === "actual"
    ? (report?.undeclared ?? []).map((u) => ({
        id: `x:${u.kind === "READ" ? "read" : "host"}:${u.target}`,
        title: u.target,
        subtitle: fixedTargets.includes(u.target) ? (u.kind === "READ" ? "Added to manifest.json" : "Added to allowlist") : u.kind === "READ" ? `${u.count} request, refused` : `${u.count} attempts, blocked`,
        tone: fixedTargets.includes(u.target) ? "fixed" : "bad",
        after: u.afterBlock,
      }))
    : [];

  async function startRun() {
    if (!defaultProfileId) return setMessage({ tone: "bad", text: "Create a test profile first, in Test files." });
    const r = await runSandboxAction(agentId, version.id, defaultProfileId);
    setMessage(r.ok ? { tone: "ok", text: "Sandbox run started. It shows up in Sandbox runs." } : { tone: "bad", text: r.error });
  }

  async function submit() {
    const r = await submitAction(agentId, version.id);
    setMessage(r.ok ? { tone: "ok", text: "Submitted. Karya's checks start now." } : { tone: "bad", text: r.error, verify: r.code === "VERIFICATION_REQUIRED" || r.error.startsWith("Get verified") });
  }

  const submitNote = issues === 0 && readiness.ready ? null : `Fix ${issues || readiness.blockers.length} issue${(issues || readiness.blockers.length) === 1 ? "" : "s"} first. Review would reject this build.`;

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_360px] gap-6 items-start">
      <div className="flex flex-col gap-4 min-w-0">
        <div className="flex items-center gap-3">
          <Pill tone="b">{version.versionNumber} {version.status.toLowerCase()}</Pill>
          {version.buildStatus && <Pill tone={version.buildStatus === "SUCCEEDED" ? "ok" : version.buildStatus === "FAILED" ? "bad" : "n"}>{version.buildStatus === "SUCCEEDED" ? "Build passed" : version.buildStatus === "FAILED" ? "Build failed" : "Building"}</Pill>}
          {run && <span className="text-[13px] text-ink2">Sandbox run <span className="font-mono">{run.displayId}</span> on your {run.profileName} files. No money moved.</span>}
        </div>
        <div className="flex items-center justify-between">
          <Segmented label="Compare" value={view} onChange={setView} options={[{ value: "declared", label: "What you declared" }, { value: "actual", label: "What it did" }]} />
          <Button onClick={startRun}>Run in sandbox</Button>
        </div>
        <Panel className="p-8 dot-grid overflow-x-auto">
          {run ? <FlowCanvas label={view === "actual" ? "What it did" : "What you declared"} blocks={blocks} states={states} metrics={metrics} extraBlocks={extras} />
            : <p className="text-[13px] text-ink3">No sandbox run yet. Run one to compare what you declared with what it does.</p>}
        </Panel>
      </div>

      <aside className="flex flex-col gap-4">
        {report && (
          <Panel className="p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <PanelTitle>Behaviour report</PanelTitle>
              <Pill tone={issues ? "bad" : "ok"}>{issues ? `${issues} issue${issues === 1 ? "" : "s"}` : "Clean"}</Pill>
            </div>
            {run?.llmMode === "REPLAY" && <p className="text-xs text-ink3">AI answers replayed from {run.replayOfDisplayId}, cost {inr(report.cost.aiCost)}</p>}
            {(report.undeclared ?? []).map((u) => (
              <IssueCard key={`${u.kind}:${u.target}`} issue={u} agentId={agentId} versionId={version.id} onFixed={(t) => setFixedTargets((f) => [...f, t])} />
            ))}
          </Panel>
        )}
        {report && (
          <Panel className="p-5">
            <PanelTitle>Cost against your declared limit</PanelTitle>
            <div className="font-mono text-sm mt-2">{inr(report.cost.simulatedCharge)} of {inr(report.cost.cap)}</div>
            <Kv label={run?.llmMode === "REPLAY" ? "AI tokens, replayed" : "AI cost, from your sandbox budget"} value={inr(report.cost.aiCost)} />
            <Kv label="Sandbox time" value={`${Math.floor(report.cost.sandboxSeconds / 60)}:${String(report.cost.sandboxSeconds % 60).padStart(2, "0")}`} />
          </Panel>
        )}
        <Panel className="p-5">
          <Kv label="Sandbox minutes this month" value={`${quota.used.runMinutes} of ${quota.limit.runMinutes}`} />
        </Panel>
        {message && (
          <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>
            {message.text} {message.verify && <Link href="/verify" className="text-blue font-semibold">Get verified</Link>}
          </p>
        )}
        <Button onClick={submit}>Submit for review</Button>
        {submitNote && <p className="text-xs text-ink3">{submitNote}</p>}
      </aside>
    </div>
  );
}
```

In the test, the refusal's text starts with "Get verified", and the alert contains a link whose name is also "Get verified". `getByRole("link", { name: "Get verified" })` finds that link. The `Segmented` options' labels double as button names.

`src/app/(developer)/studio/[agentId]/page.tsx`:

```tsx
import { notFound } from "next/navigation";
import { backend } from "@/lib/backend";
import type { DevAgent, DeveloperQuota, Readiness, SandboxProfile, SandboxRunDetail, SandboxRunRow } from "@/lib/types";
import { AgentTabs } from "./AgentTabs";
import { StudioView } from "./StudioView";

export default async function AgentStudioPage({ params, searchParams }: { params: Promise<{ agentId: string }>; searchParams: Promise<{ version?: string }> }) {
  const { agentId } = await params;
  const wanted = (await searchParams).version;
  const agents = await backend.get<DevAgent[]>("/api/v1/agents/mine");
  const agent = agents.find((a) => a.agentId === agentId);
  if (!agent || agent.versions.length === 0) notFound();
  const version = agent.versions.find((v) => v.id === wanted) ?? agent.versions.find((v) => v.status === "DRAFT") ?? agent.versions[0];
  const [runs, readiness, quota, profiles] = await Promise.all([
    backend.get<SandboxRunRow[]>(`/api/v1/sandbox/runs?agentId=${agentId}`),
    backend.get<Readiness>(`/api/v1/agents/${agentId}/versions/${version.id}/readiness`),
    backend.get<DeveloperQuota>("/api/v1/developer/quota"),
    backend.get<SandboxProfile[]>(`/api/v1/agents/${agentId}/sandbox-profiles`),
  ]);
  const latest = runs.find((r) => r.versionId === version.id);
  const run = latest ? await backend.get<SandboxRunDetail>(`/api/v1/sandbox/runs/${latest.id}`) : null;
  return (
    <>
      <AgentTabs agentId={agentId} agentName={agent.agentName} active="studio" />
      <StudioView agentId={agentId} agentName={agent.agentName} version={version} run={run} readiness={readiness} quota={quota} defaultProfileId={profiles[0]?.id ?? null} />
    </>
  );
}
```

In `StudioView.test.tsx`, the render passes `agentId="a1" agentName="Bank Reconciliation"`, matching these props.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): developer studio comparing declared and actual behaviour, with one-click fixes"
```

---

### Task 3: Sandbox runs and quota

**Files:**
- Create: `src/app/(developer)/studio/[agentId]/sandbox/page.tsx`, `SandboxView.tsx`, `actions.ts`
- Test: create `SandboxView.test.tsx` in the same folder

**Interfaces:**
- Consumes: `GET /api/v1/sandbox/runs?agentId=`, `GET /api/v1/sandbox/runs/{id}`, `POST /api/v1/sandbox/runs/{id}/rerun`, `POST /api/v1/sandbox/runs`, `GET /api/v1/developer/quota`, `GET /api/v1/agents/{a}/sandbox-profiles`; `sandboxResultPill`, `llmModeLabel` (Task 1); `runSandboxAction` (Task 2).
- Produces: `rerunAction(agentId, runId): ActionResult`, `SandboxView({ agentId, runs, quota, profiles, versions })`. Row details are fetched on selection through `/api/sandbox/runs/{id}`.

- [ ] **Step 1: Write the failing test**

`src/app/(developer)/studio/[agentId]/sandbox/SandboxView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SandboxView } from "./SandboxView";
import type { DeveloperQuota, SandboxRunRow } from "@/lib/types";

vi.mock("./actions", () => ({ rerunAction: vi.fn().mockResolvedValue({ ok: true }) }));
vi.mock("../actions", () => ({ runSandboxAction: vi.fn().mockResolvedValue({ ok: false, error: "A sandbox run is already going. Wait for it to finish." }) }));

const row = (over: Partial<SandboxRunRow>): SandboxRunRow => ({
  id: "s121", displayId: "S-121", versionId: "v14", versionLabel: "1.4.0 draft", profileId: "p1", profileName: "august-fixtures",
  llmMode: "REPLAY", replayOfDisplayId: "S-118", status: "COMPLETED", result: "ISSUES", issueCount: 2,
  simulatedCharge: 284, requestedCharge: 284, durationSeconds: 48, aiCost: 0, startedAt: "2026-09-23T04:00:00Z", ...over,
});
const quota: DeveloperQuota = {
  verified: false,
  used: { runMinutes: 34, aiSpend: 38, runsAtOnce: 0, buildsToday: 3, storageBytes: 0 },
  limit: { runMinutes: 120, aiSpend: 200, runsAtOnce: 1, buildsToday: 20, storageBytes: 1 },
  verifiedLimit: { runMinutes: 1000, aiSpend: 2000, runsAtOnce: 5, buildsToday: 100, storageBytes: 1 },
};

describe("SandboxView", () => {
  const runs = [row({}), row({ id: "s115", displayId: "S-115", llmMode: "MOCK", replayOfDisplayId: null, result: "OVER_COST_LIMIT", simulatedCharge: 500, requestedCharge: 620 })];

  it("shows the quota tiles", () => {
    render(<SandboxView agentId="a1" runs={runs} quota={quota} profiles={[{ id: "p1", name: "august-fixtures" }]} versions={[{ id: "v14", label: "1.4.0 draft" }]} />);
    expect(screen.getByText("34 of 120")).toBeInTheDocument();
    expect(screen.getByText("₹38 of ₹200")).toBeInTheDocument();
    expect(screen.getByText("0 of 1")).toBeInTheDocument();
    expect(screen.getByText("3 of 20")).toBeInTheDocument();
  });

  it("lists runs with AI mode, result and simulated charge", () => {
    render(<SandboxView agentId="a1" runs={runs} quota={quota} profiles={[]} versions={[]} />);
    const r121 = screen.getByRole("row", { name: /S-121/ });
    expect(within(r121).getByText("Replay of S-118")).toBeInTheDocument();
    expect(within(r121).getByText("2 issues")).toBeInTheDocument();
    const r115 = screen.getByRole("row", { name: /S-115/ });
    expect(within(r115).getByText("₹620 asked, ₹500 kept")).toBeInTheDocument();
    expect(screen.getByText(/Sandbox charges are simulated/)).toBeInTheDocument();
  });

  it("reruns with the same answers", async () => {
    const { rerunAction } = await import("./actions");
    render(<SandboxView agentId="a1" runs={runs} quota={quota} profiles={[]} versions={[]} />);
    await userEvent.click(screen.getByRole("button", { name: "Re-run, same answers" }));
    expect(rerunAction).toHaveBeenCalledWith("a1", "s121");
  });

  it("explains a refused new run", async () => {
    render(<SandboxView agentId="a1" runs={runs} quota={quota} profiles={[{ id: "p1", name: "august-fixtures" }]} versions={[{ id: "v14", label: "1.4.0 draft" }]} />);
    await userEvent.click(screen.getByRole("button", { name: "New sandbox run" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("already going");
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(developer)/studio/\[agentId\]/sandbox"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(developer)/studio/[agentId]/sandbox/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";

export async function rerunAction(agentId: string, runId: string): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/sandbox/runs/${runId}/rerun`);
  } catch (e) {
    return actionError(e, {
      QUOTA_EXCEEDED: "You've used this month's sandbox minutes. Get verified for more.",
      CONCURRENCY_LIMIT: "A sandbox run is already going. Wait for it to finish.",
    }, "The rerun couldn't start.");
  }
  revalidatePath(`/studio/${agentId}/sandbox`);
  return { ok: true };
}
```

`src/app/(developer)/studio/[agentId]/sandbox/SandboxView.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button, ButtonLink } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { inr } from "@/lib/format";
import { llmModeLabel, sandboxResultPill } from "@/lib/sandboxCopy";
import type { DeveloperQuota, SandboxRunRow } from "@/lib/types";
import { runSandboxAction } from "../actions";
import { rerunAction } from "./actions";

const mmss = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
const charge = (r: SandboxRunRow) => (r.requestedCharge > r.simulatedCharge ? `${inr(r.requestedCharge)} asked, ${inr(r.simulatedCharge)} kept` : inr(r.simulatedCharge));

export function SandboxView({ agentId, runs, quota, profiles, versions }: {
  agentId: string; runs: SandboxRunRow[]; quota: DeveloperQuota; profiles: { id: string; name: string }[]; versions: { id: string; label: string }[];
}) {
  const [selected, setSelected] = useState(runs[0]?.id ?? null);
  const [versionId, setVersionId] = useState(versions[0]?.id ?? "");
  const [profileId, setProfileId] = useState(profiles[0]?.id ?? "");
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const cur = runs.find((r) => r.id === selected) ?? null;
  const tiles: [string, string][] = [
    [`${quota.used.runMinutes} of ${quota.limit.runMinutes}`, "Sandbox minutes this month"],
    [`${inr(quota.used.aiSpend)} of ${inr(quota.limit.aiSpend)}`, "AI spend in sandbox"],
    [`${quota.used.runsAtOnce} of ${quota.limit.runsAtOnce}`, "Running at once"],
    [`${quota.used.buildsToday} of ${quota.limit.buildsToday}`, "Builds today"],
  ];

  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-4 gap-4">
        {tiles.map(([value, label]) => (
          <Panel key={label} className="p-4"><b className="font-mono font-medium text-lg">{value}</b><div className="text-xs text-ink3">{label}</div></Panel>
        ))}
      </div>
      <Panel className="p-4 flex items-end gap-3">
        <label className="flex flex-col gap-1 text-[13px]"><span className="text-ink2">Version</span>
          <select value={versionId} onChange={(e) => setVersionId(e.target.value)} className="h-9 px-2 rounded-lg border border-line bg-surface">{versions.map((v) => <option key={v.id} value={v.id}>{v.label}</option>)}</select>
        </label>
        <label className="flex flex-col gap-1 text-[13px]"><span className="text-ink2">Test profile</span>
          <select value={profileId} onChange={(e) => setProfileId(e.target.value)} className="h-9 px-2 rounded-lg border border-line bg-surface">{profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
        </label>
        <Button onClick={async () => { const r = await runSandboxAction(agentId, versionId, profileId); setMessage(r.ok ? { tone: "ok", text: "Sandbox run started." } : { tone: "bad", text: r.error }); }}>New sandbox run</Button>
        <span className="text-xs text-ink3 ml-auto">Or from your terminal: <span className="font-mono">agentstore sandbox run</span></span>
      </Panel>
      {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}

      <div className="grid grid-cols-[minmax(0,1fr)_340px] gap-5 items-start">
        <Panel className="overflow-hidden">
          <table className="w-full text-[13px]">
            <thead><tr className="text-xs text-ink3">{["Run", "Version", "Test profile", "AI answers", "Result", "Charge", "Time"].map((h) => <th key={h} className="text-left font-normal px-3 py-2 first:pl-5">{h}</th>)}</tr></thead>
            <tbody>
              {runs.map((r) => {
                const pill = sandboxResultPill(r.result, r.issueCount);
                return (
                  <tr key={r.id} aria-label={r.displayId} onClick={() => setSelected(r.id)} className={`border-t border-line2 cursor-pointer ${r.id === selected ? "bg-sky" : ""}`}>
                    <td className="pl-5 px-3 py-2.5 font-mono text-xs">{r.displayId}</td>
                    <td className="px-3 py-2.5">{r.versionLabel}</td>
                    <td className="px-3 py-2.5 font-mono text-xs">{r.profileName}</td>
                    <td className="px-3 py-2.5">{llmModeLabel(r.llmMode, r.replayOfDisplayId)}</td>
                    <td className="px-3 py-2.5"><Pill tone={pill.tone}>{pill.label}</Pill></td>
                    <td className="px-3 py-2.5 font-mono">{charge(r)}</td>
                    <td className="px-3 py-2.5 font-mono">{mmss(r.durationSeconds)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="px-5 py-3 text-xs text-ink3 border-t border-line2">Sandbox charges are simulated. They show what a buyer would pay and never reach a ledger.</p>
        </Panel>
        {cur && (
          <Panel className="p-5 flex flex-col gap-2">
            <PanelTitle>Run {cur.displayId}</PanelTitle>
            <p className="text-xs text-ink3">{cur.versionLabel}, {cur.profileName}, {llmModeLabel(cur.llmMode, cur.replayOfDisplayId)}</p>
            <Kv label="AI cost, from your sandbox budget" value={inr(cur.aiCost)} />
            <Kv label="Simulated buyer charge" value={charge(cur)} />
            <Kv label="Sandbox time" value={mmss(cur.durationSeconds)} />
            <div className="flex gap-2 mt-2">
              <Button size="sm" variant="secondary" onClick={async () => { const r = await rerunAction(agentId, cur.id); setMessage(r.ok ? { tone: "ok", text: "Rerun started with the same AI answers." } : { tone: "bad", text: r.error }); }}>Re-run, same answers</Button>
              <ButtonLink size="sm" href={`/studio/${agentId}?version=${cur.versionId}`}>Open in studio</ButtonLink>
            </div>
          </Panel>
        )}
        {runs.length === 0 && <Panel className="p-5 text-[13px] text-ink3">No sandbox runs yet. <Link href={`/studio/${agentId}/test-files`} className="text-blue font-semibold">Set up test files</Link> first.</Panel>}
      </div>
    </div>
  );
}
```

`src/app/(developer)/studio/[agentId]/sandbox/page.tsx`:

```tsx
import { notFound } from "next/navigation";
import { backend } from "@/lib/backend";
import type { DevAgent, DeveloperQuota, SandboxProfile, SandboxRunRow } from "@/lib/types";
import { AgentTabs } from "../AgentTabs";
import { SandboxView } from "./SandboxView";

export default async function SandboxPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agent = (await backend.get<DevAgent[]>("/api/v1/agents/mine")).find((a) => a.agentId === agentId);
  if (!agent) notFound();
  const [runs, quota, profiles] = await Promise.all([
    backend.get<SandboxRunRow[]>(`/api/v1/sandbox/runs?agentId=${agentId}`),
    backend.get<DeveloperQuota>("/api/v1/developer/quota"),
    backend.get<SandboxProfile[]>(`/api/v1/agents/${agentId}/sandbox-profiles`),
  ]);
  const versions = agent.versions.filter((v) => v.buildStatus === "SUCCEEDED").map((v) => ({ id: v.id, label: `${v.versionNumber} ${v.status.toLowerCase()}` }));
  return (
    <>
      <AgentTabs agentId={agentId} agentName={agent.agentName} active="sandbox" />
      <SandboxView agentId={agentId} runs={runs} quota={quota} profiles={profiles.map((p) => ({ id: p.id, name: p.name }))} versions={versions} />
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
git commit -m "feat(frontend): sandbox runs with quota, simulated charges and reruns with the same AI answers"
```

---

### Task 4: Test files and profiles

**Files:**
- Create: `src/app/(developer)/studio/[agentId]/test-files/page.tsx`, `ProfilesView.tsx`, `actions.ts`
- Test: create `ProfilesView.test.tsx` in the same folder

**Interfaces:**
- Consumes: `GET/POST/PATCH /api/v1/agents/{a}/sandbox-profiles…`, `GET /api/v1/agents/{a}/eval-cases?profile=`, the upload proxy with `purpose=FIXTURE`.
- Produces: `updateProfileAction(agentId, profileId, patch): ActionResult<{ profile: SandboxProfile }>`, `createProfileAction(agentId, name, description): ActionResult`, `ProfilesView({ agentId, profiles, cases, aiBudgetLeft })`.

- [ ] **Step 1: Write the failing test**

`src/app/(developer)/studio/[agentId]/test-files/ProfilesView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ProfilesView } from "./ProfilesView";
import type { EvalCaseRow, SandboxProfile } from "@/lib/types";

vi.mock("./actions", () => ({
  updateProfileAction: vi.fn()
    .mockResolvedValueOnce({ ok: true, profile: {} })
    .mockResolvedValueOnce({ ok: false, code: "PII_FOUND", error: "These files contain what look like real PAN or account numbers: vendors_sample.csv row 12 (PAN)." }),
  createProfileAction: vi.fn(),
}));

const ok = { rowCount: 412, columnsMatch: true, missingColumns: [], rowErrors: [] };
const profile = (over: Partial<SandboxProfile>): SandboxProfile => ({
  id: "p1", name: "august-fixtures", description: "A normal month, 142 expected matches", status: "READY",
  gatePolicy: "AUTO_APPROVE", llmMode: "REPLAY", replayRunId: "s118", replayRunDisplayId: "S-118", trialEnabled: false, attested: false, source: "REPO",
  sockets: [{ resource: "bank_statements", datasetId: "d1", fileName: "aug_statement_sample.csv", rowCount: 412, schemaRef: "schemas/bank_statement.json", check: ok }],
  piiScan: { status: "CLEAN", findings: [] },
  ...over,
});
const broken = profile({
  id: "p3", name: "large-month", description: "Stress test, 48k lines", status: "FILE_ERRORS",
  sockets: [{ resource: "bank_statements", datasetId: "d3", fileName: "march_big.csv", rowCount: 48210, schemaRef: "schemas/bank_statement.json",
    check: { rowCount: 48210, columnsMatch: true, missingColumns: [], rowErrors: [{ row: 18442, column: "amount", message: 'amount "12,40O.00" is not a number' }] } }],
});
const cases: EvalCaseRow[] = [
  { id: "case-01", title: "Matches all 142 invoices", profile: "august-fixtures", latestResult: "PASSING", detail: null },
  { id: "case-17", title: "Nets bank charges into one entry", profile: "august-fixtures", latestResult: "FAILING", detail: null },
];

describe("ProfilesView", () => {
  it("shows each profile's state and the files plugged into its sockets", () => {
    render(<ProfilesView agentId="a1" profiles={[profile({}), broken]} cases={cases} aiBudgetLeft={162} />);
    expect(within(screen.getByRole("button", { name: /large-month/ })).getByText("1 file has errors")).toBeInTheDocument();
    expect(screen.getByText("aug_statement_sample.csv")).toBeInTheDocument();
    expect(screen.getByText("Matches schema")).toBeInTheDocument();
  });

  it("names the bad rows in a broken profile", async () => {
    render(<ProfilesView agentId="a1" profiles={[profile({}), broken]} cases={cases} aiBudgetLeft={162} />);
    await userEvent.click(screen.getByRole("button", { name: /large-month/ }));
    expect(screen.getByText('march_big.csv row 18,442: amount "12,40O.00" is not a number')).toBeInTheDocument();
  });

  it("changes the approval and AI answer policies", async () => {
    const { updateProfileAction } = await import("./actions");
    render(<ProfilesView agentId="a1" profiles={[profile({})]} cases={cases} aiBudgetLeft={162} />);
    await userEvent.click(screen.getByRole("button", { name: "Ask me" }));
    expect(updateProfileAction).toHaveBeenCalledWith("a1", "p1", { gatePolicy: "MANUAL" });
    expect(screen.getByText("The run waits for you in the sandbox page.")).toBeInTheDocument();
  });

  it("refuses a trial when the files look like real data, and keeps the toggle off", async () => {
    render(<ProfilesView agentId="a1" profiles={[profile({})]} cases={cases} aiBudgetLeft={162} />);
    await userEvent.click(screen.getByRole("checkbox", { name: "These files contain no real customer or personal data" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "Use this profile for trials" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("vendors_sample.csv row 12");
    expect(screen.getByRole("checkbox", { name: "Use this profile for trials" })).not.toBeChecked();
  });

  it("lists the test cases that use the profile with their results", () => {
    render(<ProfilesView agentId="a1" profiles={[profile({})]} cases={cases} aiBudgetLeft={162} />);
    const row = screen.getByRole("row", { name: /case-17/ });
    expect(within(row).getByText("Failing")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(developer)/studio/\[agentId\]/test-files"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(developer)/studio/[agentId]/test-files/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { SandboxProfile } from "@/lib/types";

export type ProfilePatch = Partial<Pick<SandboxProfile, "gatePolicy" | "llmMode" | "replayRunId" | "trialEnabled">> & { attestNoRealData?: boolean };

export async function updateProfileAction(agentId: string, profileId: string, patch: ProfilePatch): Promise<ActionResult<{ profile: SandboxProfile }>> {
  try {
    const profile = await backend.patch<SandboxProfile>(`/api/v1/agents/${agentId}/sandbox-profiles/${profileId}`, patch);
    revalidatePath(`/studio/${agentId}/test-files`);
    return { ok: true, profile };
  } catch (e) {
    return actionError(e, {
      ATTESTATION_REQUIRED: "Confirm the files contain no real customer or personal data first.",
      FILE_ERRORS: "Fix the file errors before offering this profile as a trial.",
    }, "We couldn't save that.");
  }
}

export async function createProfileAction(agentId: string, name: string, description: string): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/agents/${agentId}/sandbox-profiles`, { name, description });
  } catch (e) {
    return actionError(e, { "409": "A profile with that name already exists." }, "We couldn't create the profile.");
  }
  revalidatePath(`/studio/${agentId}/test-files`);
  return { ok: true };
}
```

When the backend refuses with `PII_FOUND`, it names the rows in its `detail`. `actionError` passes that sentence through, because there is no fixed message for that code.

`src/app/(developer)/studio/[agentId]/test-files/ProfilesView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { Segmented } from "@/components/ui/Segmented";
import { inr } from "@/lib/format";
import type { EvalCaseRow, LlmMode, SandboxProfile } from "@/lib/types";
import { createProfileAction, updateProfileAction } from "./actions";

const GATES = [{ value: "MANUAL", label: "Ask me" }, { value: "AUTO_APPROVE", label: "Approve" }, { value: "AUTO_REJECT", label: "Reject" }] as const;
const LLMS = [{ value: "REAL", label: "Real" }, { value: "MOCK", label: "Mock" }, { value: "REPLAY", label: "Replay" }] as const;
const GATE_NOTE = { MANUAL: "The run waits for you in the sandbox page.", AUTO_APPROVE: "Every approval request is approved at once.", AUTO_REJECT: "Every approval request is rejected, to test your cleanup." };

export function ProfilesView({ agentId, profiles: initial, cases, aiBudgetLeft }: {
  agentId: string; profiles: SandboxProfile[]; cases: EvalCaseRow[]; aiBudgetLeft: number;
}) {
  const [profiles, setProfiles] = useState(initial);
  const [selected, setSelected] = useState(initial[0]?.id ?? null);
  const [attest, setAttest] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [newName, setNewName] = useState("");
  const cur = profiles.find((p) => p.id === selected) ?? null;

  function local(patch: Partial<SandboxProfile>) {
    setProfiles((ps) => ps.map((p) => (p.id === selected ? { ...p, ...patch } : p)));
  }

  async function save(patch: Parameters<typeof updateProfileAction>[2], optimistic: Partial<SandboxProfile>, undo: Partial<SandboxProfile>) {
    if (!cur) return;
    setError(null);
    local(optimistic);
    const r = await updateProfileAction(agentId, cur.id, patch);
    if (!r.ok) {
      local(undo);
      setError(r.error);
    }
  }

  const llmNote = (m: LlmMode) => m === "REAL" ? `Uses your sandbox AI budget, ${inr(aiBudgetLeft)} left.` : m === "MOCK" ? "Canned answers from mocks/, free." : `Reuses answers recorded in ${cur?.replayRunDisplayId ?? "an earlier run"}, free and repeatable.`;

  return (
    <div className="grid grid-cols-[280px_minmax(0,1fr)_340px] gap-5 items-start">
      <div className="flex flex-col gap-2">
        <p className="text-xs text-ink3">A profile is a set of test files plus how the sandbox answers approvals and AI calls.</p>
        {profiles.map((p) => (
          <button key={p.id} type="button" aria-pressed={p.id === selected} onClick={() => setSelected(p.id)}
            className={`text-left px-3.5 py-3 rounded-xl border bg-surface ${p.id === selected ? "border-blue shadow-[0_0_0_3px_var(--sky2)]" : "border-line hover:border-blue-3"}`}>
            <b className="block font-mono text-[13px] font-medium">{p.name}</b>
            <small className="block text-xs text-ink3">{p.description}</small>
            <span className="flex gap-1.5 mt-1.5">
              <Pill tone={p.status === "READY" ? "ok" : "bad"}>{p.status === "READY" ? "Ready" : "1 file has errors"}</Pill>
              {p.trialEnabled && <Pill tone="b">Buyer trial</Pill>}
            </span>
          </button>
        ))}
        <form className="flex gap-2" onSubmit={async (e) => { e.preventDefault(); const r = await createProfileAction(agentId, newName, ""); if (!r.ok) setError(r.error); else setNewName(""); }}>
          <input aria-label="New profile name" value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="new-profile" className="flex-1 h-8 px-2 rounded-lg border border-line font-mono text-xs" required />
          <Button type="submit" size="sm" variant="secondary">New profile</Button>
        </form>
      </div>

      {cur && (
        <div className="flex flex-col gap-4 min-w-0">
          <Panel className="p-5 flex flex-col gap-3">
            <PanelTitle>Plug test files into your agent</PanelTitle>
            <p className="text-xs text-ink3">Each socket is a read declared in <span className="font-mono">manifest.json</span>. Karya checks every file against its schema.</p>
            {cur.sockets.map((s) => {
              const bad = s.check && (!s.check.columnsMatch || s.check.rowErrors.length > 0);
              return (
                <div key={s.resource} className={`flex items-center gap-3 rounded-xl px-3 py-2.5 border ${bad ? "border-[#E19A91] bg-[#FFFAF9]" : "border-blue-3"}`}>
                  <span className="flex-1 min-w-0">
                    <b className="block font-mono text-xs font-medium truncate">{s.fileName ?? "No file yet"}</b>
                    <small className="text-xs text-ink3">{s.rowCount !== null ? `${s.rowCount.toLocaleString("en-IN")} rows` : ""}</small>
                  </span>
                  <Pill tone={bad ? "bad" : "ok"}>{bad ? `${s.check!.rowErrors.length || "Some"} bad rows` : "Matches schema"}</Pill>
                  <span className="text-xs text-ink3 font-mono">{s.resource}</span>
                </div>
              );
            })}
          </Panel>
          <Panel className="p-5 flex flex-col gap-3">
            <span className="text-[13px] text-ink2">When it asks for approval</span>
            <Segmented label="When it asks for approval" value={cur.gatePolicy} options={[...GATES]} onChange={(v) => save({ gatePolicy: v }, { gatePolicy: v }, { gatePolicy: cur.gatePolicy })} />
            <span className="text-xs text-ink3">{GATE_NOTE[cur.gatePolicy]}</span>
            <span className="text-[13px] text-ink2 mt-2">AI answers</span>
            <Segmented label="AI answers" value={cur.llmMode} options={[...LLMS]} onChange={(v) => save({ llmMode: v }, { llmMode: v }, { llmMode: cur.llmMode })} />
            <span className="text-xs text-ink3">{llmNote(cur.llmMode)}</span>
            <p className="text-xs text-ink3">Drafted records go to your sandbox output. They never reach a real ledger.</p>
          </Panel>
          <Panel className="overflow-hidden">
            <div className="px-5 py-3"><PanelTitle hint="evals/">Test cases that use this profile</PanelTitle></div>
            <table className="w-full text-[13px]">
              <tbody>
                {cases.filter((c) => c.profile === cur.name).map((c) => (
                  <tr key={c.id} aria-label={c.id} className="border-t border-line2">
                    <td className="pl-5 py-2 font-mono text-xs">{c.id}</td>
                    <td className="py-2">{c.title}</td>
                    <td className="py-2 pr-5 text-right">
                      <Pill tone={c.latestResult === "PASSING" ? "ok" : c.latestResult === null ? "n" : "bad"}>{c.latestResult === "PASSING" ? "Passing" : c.latestResult === "FAILING" ? "Failing" : c.latestResult === "ERROR" ? "Error" : "Not run"}</Pill>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
        </div>
      )}

      {cur && (
        <div className="flex flex-col gap-4">
          <Panel className="p-5 flex flex-col gap-2">
            <PanelTitle>File checks</PanelTitle>
            {cur.sockets.flatMap((s) => (s.check?.rowErrors ?? []).map((er) => (
              <p key={`${s.fileName}-${er.row}`} className="text-[13px] flex gap-2"><i aria-hidden className="w-2 h-2 rounded-full bg-bad mt-1.5 shrink-0" />{`${s.fileName} row ${er.row.toLocaleString("en-IN")}: ${er.message}`}</p>
            )))}
            {cur.piiScan.status === "CLEAN" && <p className="text-[13px] flex gap-2"><i aria-hidden className="w-2 h-2 rounded-full bg-ok mt-1.5 shrink-0" />No real PAN, GSTIN or account numbers found.</p>}
          </Panel>
          <Panel className="p-5 flex flex-col gap-2">
            <PanelTitle>Offer as a free buyer trial</PanelTitle>
            <p className="text-xs text-ink3">Buyers can try your agent on this profile&apos;s files before connecting their own. Trials are free for them; AI cost comes from your trial budget.</p>
            <label className="flex items-start gap-2 text-[13px]">
              <input type="checkbox" className="w-4 h-4 mt-0.5 accent-blue" checked={attest || cur.attested} onChange={(e) => setAttest(e.target.checked)} />
              These files contain no real customer or personal data
            </label>
            <label className="flex items-start gap-2 text-[13px]">
              <input type="checkbox" className="w-4 h-4 mt-0.5 accent-blue" checked={cur.trialEnabled}
                onChange={(e) => save({ trialEnabled: e.target.checked, attestNoRealData: attest || cur.attested }, { trialEnabled: e.target.checked }, { trialEnabled: cur.trialEnabled })} />
              Use this profile for trials
            </label>
            {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
          </Panel>
          <Panel className="p-5 text-xs text-ink3 flex flex-col gap-1.5">
            Profiles also live in your repo and sync when you push.
            <span className="font-mono bg-[#F4F6FB] border border-line rounded-lg px-2.5 py-2 text-ink">$ agentstore push <span className="text-ink3">synced fixtures/profiles/{cur.name}.yaml</span></span>
          </Panel>
        </div>
      )}
    </div>
  );
}
```

In the test, the second `updateProfileAction` result is the PII refusal. The first call happens in the "changes the approval policy" test and the second in the trial test, so the mocked queue is consumed in file order.

`src/app/(developer)/studio/[agentId]/test-files/page.tsx`:

```tsx
import { notFound } from "next/navigation";
import { backend } from "@/lib/backend";
import type { DevAgent, DeveloperQuota, EvalCaseRow, SandboxProfile } from "@/lib/types";
import { AgentTabs } from "../AgentTabs";
import { ProfilesView } from "./ProfilesView";

export default async function TestFilesPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const agent = (await backend.get<DevAgent[]>("/api/v1/agents/mine")).find((a) => a.agentId === agentId);
  if (!agent) notFound();
  const [profiles, cases, quota] = await Promise.all([
    backend.get<SandboxProfile[]>(`/api/v1/agents/${agentId}/sandbox-profiles`),
    backend.get<EvalCaseRow[]>(`/api/v1/agents/${agentId}/eval-cases`),
    backend.get<DeveloperQuota>("/api/v1/developer/quota"),
  ]);
  return (
    <>
      <AgentTabs agentId={agentId} agentName={agent.agentName} active="files" />
      <ProfilesView agentId={agentId} profiles={profiles} cases={cases} aiBudgetLeft={quota.limit.aiSpend - quota.used.aiSpend} />
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
git commit -m "feat(frontend): test profiles with schema checks, policies, eval results and PII-safe trials"
```

---

### Task 5: Earnings and payouts

**Files:**
- Create: `src/app/(developer)/earnings/page.tsx`, `EarningsView.tsx`, `actions.ts`
- Test: create `src/app/(developer)/earnings/EarningsView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/developer/earnings`, `GET /api/v1/developer/payouts/next`, `GET /api/v1/developer/payouts`, `GET /api/v1/developer/disputes`, `POST /api/v1/disputes/{id}/messages`; the `/api/download` route (Task 1).
- Produces: `addSideAction(disputeId, body): ActionResult`, `EarningsView({ earnings, next, payouts, disputes })`.

- [ ] **Step 1: Write the failing test**

`src/app/(developer)/earnings/EarningsView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { EarningsView } from "./EarningsView";
import type { DisputeView, Earnings, NextPayout, PayoutRow } from "@/lib/types";

vi.mock("./actions", () => ({ addSideAction: vi.fn().mockResolvedValue({ ok: true }) }));

const earnings: Earnings = {
  month: "2026-09", paidRuns: 1123, buyersPaid: 230375, platformFee: 46075, feePercent: 20, aiUsage: 9480, earned: 174820,
  byAgent: [{ agentId: "a1", agent: "Bank Reconciliation", versionsLabel: "1.3.0 live, 1.4.0 in review", runs: 812, buyersPaid: 172140, fee: 34428, aiUsage: 7310, earned: 130402, perRun: 161 }],
};
const next: NextPayout = {
  date: "2026-10-01T00:00:00Z", amount: 174536, earned: 174820, holds: [{ disputeId: "d1", amount: 284 }],
  account: { bankName: "HDFC Bank", last4: "4821", holderName: "Ledgerline Labs LLP", verified: true },
};
const payouts: PayoutRow[] = [{ period: "2026-08", paidAt: "2026-09-01T00:00:00Z", amount: 152340, status: "PAID" }];
const dispute: DisputeView = {
  id: "d1", runId: "r88213", agentId: "a1", agentName: "Bank Reconciliation", buyerOrgName: "Kaveri Foods", status: "OPEN",
  reason: "WRONG_OUTPUT", details: "6 matches were wrong", amountDisputed: 284, amountHeld: 284, amountRefunded: null, publicSummary: null,
  respondBy: "2026-09-30T00:00:00Z", decideBy: "2026-10-07T00:00:00Z", createdAt: "2026-09-23T00:00:00Z", decidedAt: null, messages: [],
};

describe("EarningsView", () => {
  it("breaks the month into what buyers paid, Karya's fee, AI usage and earnings", () => {
    render(<EarningsView earnings={earnings} next={next} payouts={payouts} disputes={[dispute]} />);
    const statement = screen.getByRole("region", { name: "Monthly statement" });
    expect(within(statement).getByText("₹2,30,375")).toBeInTheDocument();
    expect(within(statement).getByText("Karya fee, 20%")).toBeInTheDocument();
    expect(within(statement).getByText("−₹46,075")).toBeInTheDocument();
    expect(within(statement).getByText("₹1,74,820")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Download statement" })).toHaveAttribute("href", "/api/download?path=%2Fapi%2Fv1%2Fdeveloper%2Fstatements%2F2026-09%2Fdownload");
  });

  it("shows the next payout with what is held and why", () => {
    render(<EarningsView earnings={earnings} next={next} payouts={payouts} disputes={[dispute]} />);
    const payout = screen.getByRole("region", { name: "Next payout" });
    expect(within(payout).getByText("₹1,74,536")).toBeInTheDocument();
    expect(within(payout).getByText("−₹284")).toBeInTheDocument();
    expect(within(payout).getByText("HDFC Bank, ending 4821")).toBeInTheDocument();
  });

  it("lets the developer add their side to an open dispute", async () => {
    const { addSideAction } = await import("./actions");
    render(<EarningsView earnings={earnings} next={next} payouts={payouts} disputes={[dispute]} />);
    expect(screen.getByText("Kaveri Foods")).toBeInTheDocument();
    expect(screen.getByText(/disputed run/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Add your side" }));
    await userEvent.type(screen.getByLabelText("Your side"), "The statement had duplicate UTRs.");
    await userEvent.click(screen.getByRole("button", { name: "Send to Karya" }));
    expect(addSideAction).toHaveBeenCalledWith("d1", "The statement had duplicate UTRs.");
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(developer)/earnings"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(developer)/earnings/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";

export async function addSideAction(disputeId: string, body: string): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/disputes/${disputeId}/messages`, { body });
  } catch (e) {
    return actionError(e, { ALREADY_DECIDED: "Karya has already decided this dispute." }, "We couldn't send your reply.");
  }
  revalidatePath("/earnings");
  return { ok: true };
}
```

`src/app/(developer)/earnings/EarningsView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { dayLabel, inr, shortId } from "@/lib/format";
import type { DisputeView, Earnings, NextPayout, PayoutRow } from "@/lib/types";
import { addSideAction } from "./actions";

const monthName = (ym: string) => new Date(`${ym}-01T00:00:00Z`).toLocaleString("en-GB", { month: "long", year: "numeric", timeZone: "UTC" });

function DisputeCard({ d }: { d: DisputeView }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [state, setState] = useState<{ ok?: boolean; error?: string }>({});
  return (
    <Panel className="p-4 border-wa-line flex flex-col gap-2 text-[13px]">
      <p><b>{d.buyerOrgName}</b> disputed run <span className="font-mono">{shortId(d.runId)}</span>: {d.details}. {inr(d.amountHeld)} is held from your earnings until Karya decides, by {dayLabel(d.decideBy)}. You can add your side until {dayLabel(d.respondBy)}.</p>
      {state.ok ? <p role="status" className="text-ok">Sent. Karya will read it before deciding.</p> : open ? (
        <form className="flex flex-col gap-2" onSubmit={async (e) => { e.preventDefault(); const r = await addSideAction(d.id, text.trim()); setState(r.ok ? { ok: true } : { error: r.error }); }}>
          <label className="flex flex-col gap-1"><span className="text-ink2">Your side</span><textarea required rows={3} value={text} onChange={(e) => setText(e.target.value)} className="px-3 py-2 rounded-lg border border-line" /></label>
          {state.error && <p role="alert" className="text-bad">{state.error}</p>}
          <Button type="submit" size="sm">Send to Karya</Button>
        </form>
      ) : <Button size="sm" variant="secondary" onClick={() => setOpen(true)}>Add your side</Button>}
    </Panel>
  );
}

export function EarningsView({ earnings, next, payouts, disputes }: { earnings: Earnings; next: NextPayout; payouts: PayoutRow[]; disputes: DisputeView[] }) {
  const open = disputes.filter((d) => d.status === "OPEN" || d.status === "AWAITING_DECISION");
  const statementHref = `/api/download?path=${encodeURIComponent(`/api/v1/developer/statements/${earnings.month}/download`)}`;
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Earnings</h1>
        <p className="text-ink2">{monthName(earnings.month)} so far, across {earnings.paidRuns.toLocaleString("en-IN")} paid runs.</p>
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_360px] gap-6 items-start">
        <div className="flex flex-col gap-4 min-w-0">
          <Panel aria-label="Monthly statement" className="p-5 flex flex-col">
            <div className="flex items-center justify-between"><PanelTitle>Monthly statement</PanelTitle><a href={statementHref} className="text-blue font-semibold text-[13px]">Download statement</a></div>
            <Kv label="Buyers paid" value={inr(earnings.buyersPaid)} />
            <Kv label={`Karya fee, ${earnings.feePercent}%`} value={`−${inr(earnings.platformFee)}`} />
            <Kv label="AI usage through Karya" value={`−${inr(earnings.aiUsage)}`} />
            <div className="border-t border-line2 mt-1 pt-1"><Kv label="You earn" value={inr(earnings.earned)} /></div>
            <p className="text-xs text-ink3 mt-2">Buyers only ever see your per-result price. AI usage is at cost, so an efficient agent keeps more.</p>
          </Panel>
          <Panel className="overflow-hidden">
            <div className="px-5 py-3"><PanelTitle hint={monthName(earnings.month)}>By agent</PanelTitle></div>
            <table className="w-full text-[13px]">
              <thead><tr className="text-xs text-ink3">{["Agent", "Runs", "Buyers paid", "Karya fee", "AI usage", "You earn", "Per run"].map((h) => <th key={h} className="text-left font-normal px-3 py-2 first:pl-5">{h}</th>)}</tr></thead>
              <tbody>
                {earnings.byAgent.map((a) => (
                  <tr key={a.agentId} className="border-t border-line2">
                    <td className="pl-5 px-3 py-2.5"><span className="block font-medium">{a.agent}</span><span className="text-xs text-ink3">{a.versionsLabel}</span></td>
                    {[a.runs.toLocaleString("en-IN"), inr(a.buyersPaid), inr(a.fee), inr(a.aiUsage), inr(a.earned), inr(a.perRun)].map((v, i) => <td key={i} className="px-3 py-2.5 font-mono">{v}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </Panel>
          {open.map((d) => <DisputeCard key={d.id} d={d} />)}
        </div>
        <aside className="flex flex-col gap-4">
          <Panel aria-label="Next payout" className="p-5 flex flex-col">
            <PanelTitle hint={dayLabel(next.date)}>Next payout</PanelTitle>
            <b className="font-mono text-2xl font-medium my-2">{inr(next.amount)}</b>
            <Kv label="Earned this month" value={inr(next.earned)} />
            {next.holds.map((h) => <Kv key={h.disputeId} label="Held for an open dispute" value={`−${inr(h.amount)}`} />)}
            {next.account && (
              <div className="flex items-center justify-between border-t border-line2 mt-2 pt-2 text-[13px]">
                <span><span className="block">{next.account.bankName}, ending {next.account.last4}</span><span className="text-xs text-ink3">{next.account.holderName}</span></span>
                <Pill tone={next.account.verified ? "ok" : "wa"}>{next.account.verified ? "Verified" : "Checking"}</Pill>
              </div>
            )}
          </Panel>
          <Panel className="p-5">
            <PanelTitle>Paid out</PanelTitle>
            {payouts.map((p) => <Kv key={p.period} label={`${monthName(p.period)}${p.paidAt ? `, paid ${dayLabel(p.paidAt)}` : ""}`} value={inr(p.amount)} />)}
            <p className="text-xs text-ink3 mt-2">Payouts go out on the 1st when you have at least ₹1,000 due. Each statement lists every run, fee and tax deduction.</p>
          </Panel>
        </aside>
      </div>
    </div>
  );
}
```

`src/app/(developer)/earnings/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { DisputeView, Earnings, NextPayout, PayoutRow } from "@/lib/types";
import { EarningsView } from "./EarningsView";

export default async function EarningsPage() {
  const [earnings, next, payouts, disputes] = await Promise.all([
    backend.get<Earnings>("/api/v1/developer/earnings"),
    backend.get<NextPayout>("/api/v1/developer/payouts/next"),
    backend.get<PayoutRow[]>("/api/v1/developer/payouts"),
    backend.get<DisputeView[]>("/api/v1/developer/disputes"),
  ]);
  return <EarningsView earnings={earnings} next={next} payouts={payouts} disputes={disputes} />;
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): developer earnings, payouts with dispute holds, and replying to disputes"
```

---

### Task 6: Get verified

**Files:**
- Create: `src/app/(developer)/verify/page.tsx`, `VerifyWizard.tsx`, `actions.ts`
- Test: create `src/app/(developer)/verify/VerifyWizard.test.tsx`

**Interfaces:**
- Consumes: WS7 verification endpoints (Task 1 table), `GET /api/v1/developer/quota` (for the now-vs-verified table), the upload proxy pattern (Task 1, FE-2) for the PAN image via `/api/verification/pan-document`.
- Produces:
  - `saveStepAction(step, fields): ActionResult<{ draft: VerificationDraft }>`, `sendOtpAction(phone): ActionResult<{ retryAfter?: number }>`, `verifyOtpAction(code): ActionResult`, `submitVerificationAction(): ActionResult<{ draft }>`.
  - `/api/verification/pan-document` route.
  - `VerifyWizard({ draft, quota })`.

- [ ] **Step 1: Write the failing test**

`src/app/(developer)/verify/VerifyWizard.test.tsx`:

```tsx
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { VerifyWizard } from "./VerifyWizard";
import type { DeveloperQuota, VerificationDraft } from "@/lib/types";

vi.mock("./actions", () => ({
  saveStepAction: vi.fn(async (step: string) => ({ ok: true, draft: { ...draft, currentStep: step === "ABOUT" ? "TAX_IDS" : "PHONE", panMasked: "BKQPK••••2L" } })),
  sendOtpAction: vi.fn().mockResolvedValue({ ok: true, retryAfter: 30 }),
  verifyOtpAction: vi.fn().mockResolvedValue({ ok: false, error: "That code didn't match. Check the SMS and try again." }),
  submitVerificationAction: vi.fn(),
}));

const draft: VerificationDraft = {
  status: "DRAFT", currentStep: "ABOUT", entityType: null, legalName: null, displayName: null, country: "IN", city: null,
  panMasked: null, gstinMasked: null, panDocumentUploaded: false, phoneMasked: null, phoneVerified: false,
  bankHolderName: null, bankAccountLast4: null, bankIfsc: null, bankName: null, bankVerified: false,
  agreementVersion: null, agreementAcceptedAt: null, currentAgreementVersion: "2.1", decisionNote: null,
};
const quota: DeveloperQuota = {
  verified: false,
  used: { runMinutes: 0, aiSpend: 0, runsAtOnce: 0, buildsToday: 0, storageBytes: 0 },
  limit: { runMinutes: 120, aiSpend: 200, runsAtOnce: 1, buildsToday: 20, storageBytes: 500 * 1024 ** 2 },
  verifiedLimit: { runMinutes: 1000, aiSpend: 2000, runsAtOnce: 5, buildsToday: 100, storageBytes: 5 * 1024 ** 3 },
};

describe("VerifyWizard", () => {
  afterEach(() => vi.useRealTimers());

  it("resumes at the saved step with masked values", () => {
    render(<VerifyWizard draft={{ ...draft, currentStep: "TAX_IDS", legalName: "Asha Kulkarni", panMasked: "BKQPK••••2L" }} quota={quota} />);
    expect(screen.getByRole("heading", { name: "Tax IDs" })).toBeInTheDocument();
    expect(screen.getByText("Saved: BKQPK••••2L")).toBeInTheDocument();
    expect(screen.getByRole("listitem", { name: /About you, done/ })).toBeInTheDocument();
  });

  it("saves a step and moves on", async () => {
    const { saveStepAction } = await import("./actions");
    render(<VerifyWizard draft={draft} quota={quota} />);
    await userEvent.click(screen.getByRole("radio", { name: "An individual" }));
    await userEvent.type(screen.getByLabelText("Legal name, as on your PAN"), "Asha Kulkarni");
    await userEvent.type(screen.getByLabelText("Name shown on listings"), "Asha Kulkarni");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));
    expect(saveStepAction).toHaveBeenCalledWith("ABOUT", expect.objectContaining({ entityType: "INDIVIDUAL", legalName: "Asha Kulkarni" }));
    expect(await screen.findByRole("heading", { name: "Tax IDs" })).toBeInTheDocument();
  });

  it("counts down before a code can be resent", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<VerifyWizard draft={{ ...draft, currentStep: "PHONE" }} quota={quota} />);
    await user.type(screen.getByLabelText("Mobile number"), "9900112208");
    await user.click(screen.getByRole("button", { name: "Send code" }));
    expect(await screen.findByRole("button", { name: /Resend in 0:30/ })).toBeDisabled();
    await act(async () => { vi.advanceTimersByTime(31_000); });
    expect(screen.getByRole("button", { name: "Resend code" })).toBeEnabled();
  });

  it("explains a wrong code", async () => {
    render(<VerifyWizard draft={{ ...draft, currentStep: "PHONE" }} quota={quota} />);
    await userEvent.type(screen.getByLabelText("Code we sent by SMS"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Confirm code" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("didn't match");
  });

  it("needs the agreement ticked before sending", () => {
    render(<VerifyWizard draft={{ ...draft, currentStep: "AGREEMENT" }} quota={quota} />);
    expect(screen.getByRole("button", { name: "Send for verification" })).toBeDisabled();
  });

  it("compares limits now and once verified", () => {
    render(<VerifyWizard draft={draft} quota={quota} />);
    const row = screen.getByRole("row", { name: /Sandbox minutes a month/ });
    expect(row).toHaveTextContent("120");
    expect(row).toHaveTextContent("1,000");
  });

  it("shows the waiting state after sending", () => {
    render(<VerifyWizard draft={{ ...draft, status: "PENDING" }} quota={quota} />);
    expect(screen.getByRole("heading", { name: "Sent to Karya" })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(developer)/verify"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(developer)/verify/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend, BackendError } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { VerificationDraft, VerificationStep } from "@/lib/types";

export async function saveStepAction(step: VerificationStep, fields: Record<string, unknown>): Promise<ActionResult<{ draft: VerificationDraft }>> {
  try {
    const draft = await backend.patch<VerificationDraft>("/api/v1/developer/verification", { step, fields });
    return { ok: true, draft };
  } catch (e) {
    return actionError(e, { "400": "Check the highlighted details and try again." }, "We couldn't save this step.");
  }
}

export async function sendOtpAction(phone: string): Promise<ActionResult<{ retryAfter?: number }>> {
  try {
    await backend.post("/api/v1/developer/verification/phone/otp", { phone });
    return { ok: true, retryAfter: 30 };
  } catch (e) {
    if (e instanceof BackendError && e.status === 429) {
      const after = Number((e.body as { retryAfterSeconds?: number } | null)?.retryAfterSeconds ?? 30);
      return { ok: true, retryAfter: after };   // still cooling down: show the countdown, not an error
    }
    return actionError(e, { "400": "Enter a 10-digit Indian mobile number." }, "We couldn't send the code.");
  }
}

export async function verifyOtpAction(code: string): Promise<ActionResult> {
  try {
    await backend.post("/api/v1/developer/verification/phone/verify", { code });
    return { ok: true };
  } catch (e) {
    return actionError(e, { "400": "That code didn't match. Check the SMS and try again." }, "We couldn't check the code.");
  }
}

export async function submitVerificationAction(): Promise<ActionResult<{ draft: VerificationDraft }>> {
  try {
    const draft = await backend.post<VerificationDraft>("/api/v1/developer/verification/submit");
    revalidatePath("/verify");
    return { ok: true, draft };
  } catch (e) {
    return actionError(e, { "400": "Some steps still need details before Karya can check them." }, "We couldn't send it.");
  }
}
```

Create `src/app/api/verification/pan-document/route.ts`, following FE-2's upload route but targeting `/api/v1/developer/verification/pan-document` with a 5 MB limit:

```ts
import { cookies } from "next/headers";
import { SESSION_COOKIE } from "@/lib/session";

const MAX = 5 * 1024 * 1024;

export async function POST(request: Request): Promise<Response> {
  const form = await request.formData();
  for (const v of form.values()) {
    if (v instanceof Blob && v.size > MAX) return Response.json({ detail: "The image can be up to 5 MB." }, { status: 413 });
  }
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/developer/verification/pan-document`, {
    method: "POST", headers: token ? { Authorization: `Bearer ${token}` } : {}, body: form,
  });
  return new Response(res.body, { status: res.status, headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" } });
}
```

`src/app/(developer)/verify/VerifyWizard.tsx`:

```tsx
"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { formatBytes } from "@/lib/bytes";
import { inr } from "@/lib/format";
import type { DeveloperQuota, VerificationDraft, VerificationStep } from "@/lib/types";
import { saveStepAction, sendOtpAction, submitVerificationAction, verifyOtpAction } from "./actions";

const STEPS: [VerificationStep, string, string][] = [
  ["ABOUT", "About you", "Who buyers see as the publisher"],
  ["TAX_IDS", "Tax IDs", "PAN, GSTIN if any"],
  ["PHONE", "Phone", "One-time code"],
  ["PAYOUT", "Payout account", "Where earnings go"],
  ["AGREEMENT", "Agreement", "Developer agreement"],
];

function Field({ label, value, onChange, type = "text", hint }: { label: string; value: string; onChange: (v: string) => void; type?: string; hint?: string }) {
  return (
    <label className="flex flex-col gap-1 text-[13px]">
      <span className="text-ink2">{label}</span>
      <input type={type} value={value} onChange={(e) => onChange(e.target.value)} autoComplete="off" className="h-10 px-3 rounded-[10px] border border-line" />
      {hint && <span className="text-xs text-ink3">{hint}</span>}
    </label>
  );
}

export function VerifyWizard({ draft: initial, quota }: { draft: VerificationDraft; quota: DeveloperQuota }) {
  const [draft, setDraft] = useState(initial);
  const [step, setStep] = useState<VerificationStep>(initial.currentStep);
  const [f, setF] = useState<Record<string, string>>({
    entityType: initial.entityType ?? "", legalName: initial.legalName ?? "", displayName: initial.displayName ?? "",
    country: initial.country ?? "IN", city: initial.city ?? "", pan: "", gstin: "", phone: "", code: "",
    bankHolderName: initial.bankHolderName ?? "", bankAccountNumber: "", bankIfsc: initial.bankIfsc ?? "",
  });
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cooldown, setCooldown] = useState(0);
  const [phoneOk, setPhoneOk] = useState(initial.phoneVerified);
  const [sent, setSent] = useState(false);
  const at = STEPS.findIndex(([s]) => s === step);
  const set = (k: string) => (v: string) => setF((x) => ({ ...x, [k]: v }));

  useEffect(() => {
    if (cooldown <= 0) return;
    const t = setInterval(() => setCooldown((c) => Math.max(0, c - 1)), 1000);
    return () => clearInterval(t);
  }, [cooldown]);

  if (draft.status === "PENDING") {
    return (
      <Panel className="p-8 max-w-[560px] flex flex-col gap-2">
        <h1 className="text-xl font-semibold">Sent to Karya</h1>
        <p className="text-[13px] text-ink2">Karya usually replies within 2 working days. Keep building meanwhile; your sandbox and test files work exactly as before.</p>
      </Panel>
    );
  }

  const fieldsFor: Record<VerificationStep, () => Record<string, unknown>> = {
    ABOUT: () => ({ entityType: f.entityType, legalName: f.legalName, displayName: f.displayName, country: f.country, city: f.city }),
    TAX_IDS: () => ({ ...(f.pan ? { pan: f.pan } : {}), ...(f.gstin ? { gstin: f.gstin } : {}) }),
    PHONE: () => ({}),
    PAYOUT: () => ({ bankHolderName: f.bankHolderName, bankIfsc: f.bankIfsc, ...(f.bankAccountNumber ? { bankAccountNumber: f.bankAccountNumber } : {}) }),
    AGREEMENT: () => ({ agreementVersion: draft.currentAgreementVersion, accept: true }),
  };

  async function next() {
    setError(null);
    if (step === "AGREEMENT") {
      const saved = await saveStepAction("AGREEMENT", fieldsFor.AGREEMENT());
      if (!saved.ok) return setError(saved.error);
      const r = await submitVerificationAction();
      return r.ok ? setDraft(r.draft) : setError(r.error);
    }
    const r = await saveStepAction(step, fieldsFor[step]());
    if (!r.ok) return setError(r.error);
    setDraft(r.draft);
    setF((x) => ({ ...x, pan: "", gstin: "", bankAccountNumber: "" }));   // never keep raw numbers around
    setStep(STEPS[at + 1][0]);
  }

  const rows: [string, string, string][] = [
    ["List agents in the store", "No", "Yes"],
    ["Get paid", "No", "Monthly"],
    ["Use your own AI keys", "No", "Yes"],
    ["Sandbox minutes a month", quota.limit.runMinutes.toLocaleString("en-IN"), quota.verifiedLimit.runMinutes.toLocaleString("en-IN")],
    ["Sandbox runs at once", String(quota.limit.runsAtOnce), String(quota.verifiedLimit.runsAtOnce)],
    ["Sandbox AI budget", inr(quota.limit.aiSpend), inr(quota.verifiedLimit.aiSpend)],
    ["Test file storage", formatBytes(quota.limit.storageBytes), formatBytes(quota.verifiedLimit.storageBytes)],
    ["Builds a day", String(quota.limit.buildsToday), String(quota.verifiedLimit.buildsToday)],
  ];

  return (
    <div className="grid grid-cols-[240px_minmax(0,1fr)_320px] gap-6 items-start">
      <ol aria-label="Steps" className="flex flex-col gap-1">
        {STEPS.map(([s, name, hint], i) => (
          <li key={s} aria-label={`${name}${i < at ? ", done" : i === at ? ", current" : ""}`} aria-current={i === at ? "step" : undefined}
            className={`px-3 py-2 rounded-xl ${i === at ? "bg-sky" : ""}`}>
            <span className="text-[13px] font-medium">{i + 1}. {name}</span>
            <span className="block text-xs text-ink3">{hint}</span>
          </li>
        ))}
      </ol>

      <Panel className="p-6 flex flex-col gap-4">
        {draft.status === "FIX_REQUESTED" && draft.decisionNote && <p className="text-[13px] bg-wa-soft text-wa rounded-lg p-3">Karya asked for a fix: {draft.decisionNote}</p>}
        <h2 className="text-lg font-semibold">{STEPS[at][1]}</h2>
        {step === "ABOUT" && (
          <>
            <div role="radiogroup" aria-label="You are" className="flex gap-2">
              {[["INDIVIDUAL", "An individual"], ["PRIVATE_LIMITED", "A company"]].map(([v, label]) => (
                <label key={v} className={`flex-1 border rounded-xl px-3 py-2 text-[13px] cursor-pointer ${f.entityType === v ? "border-blue" : "border-line"}`}>
                  <input type="radio" name="entityType" value={v} checked={f.entityType === v} onChange={() => set("entityType")(v)} className="mr-2" aria-label={label} />{label}
                </label>
              ))}
            </div>
            <Field label="Legal name, as on your PAN" value={f.legalName} onChange={set("legalName")} />
            <Field label="Name shown on listings" value={f.displayName} onChange={set("displayName")} />
            <Field label="City" value={f.city} onChange={set("city")} />
          </>
        )}
        {step === "TAX_IDS" && (
          <>
            <p className="text-xs text-ink3">Karya checks these against government records. We store them encrypted and show only the last few characters.</p>
            <Field label="PAN" value={f.pan} onChange={set("pan")} hint={draft.panMasked ? `Saved: ${draft.panMasked}` : undefined} />
            <Field label="GSTIN, if you have one" value={f.gstin} onChange={set("gstin")} hint={draft.gstinMasked ? `Saved: ${draft.gstinMasked}` : undefined} />
            <label className="flex flex-col gap-1 text-[13px]">
              <span className="text-ink2">PAN card image (JPG or PDF)</span>
              <input type="file" accept="image/jpeg,application/pdf" onChange={async (e) => {
                const file = e.target.files?.[0];
                if (!file) return;
                const form = new FormData();
                form.append("file", file);
                const res = await fetch("/api/verification/pan-document", { method: "POST", body: form });
                if (res.ok) setDraft((d) => ({ ...d, panDocumentUploaded: true }));
                else setError((await res.json().catch(() => null))?.detail ?? "The image didn't upload.");
              }} />
              {draft.panDocumentUploaded && <span className="text-xs text-ok">Uploaded</span>}
            </label>
          </>
        )}
        {step === "PHONE" && (
          <>
            <p className="text-xs text-ink3">Buyers never see it. Karya uses it only for account security.</p>
            <div className="flex items-end gap-2">
              <div className="flex-1"><Field label="Mobile number" value={f.phone} onChange={set("phone")} type="tel" hint={draft.phoneMasked ? `Saved: ${draft.phoneMasked}` : undefined} /></div>
              <Button variant="secondary" disabled={cooldown > 0} onClick={async () => {
                const r = await sendOtpAction(f.phone);
                if (r.ok) { setSent(true); setCooldown(r.retryAfter ?? 30); } else setError(r.error);
              }}>
                {cooldown > 0 ? `Resend in 0:${String(cooldown).padStart(2, "0")}` : sent ? "Resend code" : "Send code"}
              </Button>
            </div>
            <div className="flex items-end gap-2">
              <div className="flex-1"><Field label="Code we sent by SMS" value={f.code} onChange={set("code")} /></div>
              <Button variant="secondary" onClick={async () => { const r = await verifyOtpAction(f.code); if (r.ok) setPhoneOk(true); else setError(r.error); }}>Confirm code</Button>
            </div>
            {phoneOk && <p role="status" className="text-[13px] text-ok">Phone confirmed.</p>}
          </>
        )}
        {step === "PAYOUT" && (
          <>
            <p className="text-xs text-ink3">Where your earnings go on the 1st of each month. The holder name must match your PAN.</p>
            <Field label="Account holder" value={f.bankHolderName} onChange={set("bankHolderName")} />
            <Field label="Account number" value={f.bankAccountNumber} onChange={set("bankAccountNumber")} hint={draft.bankAccountLast4 ? `Saved: ending ${draft.bankAccountLast4}` : undefined} />
            <Field label="IFSC" value={f.bankIfsc} onChange={set("bankIfsc")} />
            <p className="text-xs text-ink3">We&apos;ll send ₹1 to check the account. It shows up as KARYA VERIFY.</p>
          </>
        )}
        {step === "AGREEMENT" && (
          <>
            <p className="text-[13px] text-ink2">Version {draft.currentAgreementVersion}. The main points in plain words:</p>
            <ul className="list-disc pl-5 text-[13px] flex flex-col gap-1">
              <li>Your agents only touch what their manifest declares, and buyers see exactly that.</li>
              <li>Karya keeps 20% of what buyers pay. AI usage through Karya is charged at cost.</li>
              <li>Buyer data is never yours to keep, copy or train on.</li>
              <li>Karya can pause a version that misbehaves, and decides buyer disputes.</li>
            </ul>
            <label className="flex items-start gap-2 text-[13px]">
              <input type="checkbox" className="w-4 h-4 mt-0.5 accent-blue" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
              I&apos;ve read the full Developer Distribution Agreement {draft.currentAgreementVersion} and accept it
            </label>
          </>
        )}
        {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
        <div className="flex justify-between">
          <Button variant="secondary" disabled={at === 0} onClick={() => setStep(STEPS[Math.max(0, at - 1)][0])}>Back</Button>
          <Button disabled={step === "AGREEMENT" && !agreed} onClick={next}>{step === "AGREEMENT" ? "Send for verification" : "Continue"}</Button>
        </div>
      </Panel>

      <Panel className="p-5">
        <PanelTitle>What changes when you&apos;re verified</PanelTitle>
        <table className="w-full text-[13px] mt-2">
          <thead><tr className="text-xs text-ink3"><th className="text-left font-normal" /><th className="font-normal">Now</th><th className="font-normal">Verified</th></tr></thead>
          <tbody>
            {rows.map(([label, now, later]) => (
              <tr key={label} aria-label={label} className="border-t border-line2"><td className="py-1.5">{label}</td><td className="text-center font-mono">{now}</td><td className="text-center font-mono">{later}</td></tr>
            ))}
          </tbody>
        </table>
        <p className="text-xs text-ink3 mt-2">Verified publishers get a badge on every listing. Buyers see the legal name, never your tax IDs, phone or bank details.</p>
      </Panel>
    </div>
  );
}
```

`src/app/(developer)/verify/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { DeveloperQuota, VerificationDraft } from "@/lib/types";
import { VerifyWizard } from "./VerifyWizard";

export default async function VerifyPage() {
  const [draft, quota] = await Promise.all([
    backend.get<VerificationDraft>("/api/v1/developer/verification"),
    backend.get<DeveloperQuota>("/api/v1/developer/quota"),
  ]);
  if (draft.status === "VERIFIED") {
    return <div className="text-[13px]"><h1 className="text-[26px] font-semibold tracking-tight mb-2">You&apos;re verified</h1><p className="text-ink2">Your agents can be listed and you get paid monthly.</p></div>;
  }
  return (
    <>
      <div className="mb-6">
        <h1 className="text-[26px] font-semibold tracking-tight">Get verified</h1>
        <p className="text-ink2">About 10 minutes. You can stop and come back.</p>
      </div>
      <VerifyWizard draft={draft} quota={quota} />
    </>
  );
}
```

The page's `h1` is "Get verified"; the wizard names each step with an `h2`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): resumable get-verified flow with OTP cooldown, masked IDs and payout account"
```

---

### Task 7: Buyer: add money and tax invoices

**Files:**
- Create: `src/app/(buyer)/billing/AddMoney.tsx`, `TaxInvoices.tsx`, `topupActions.ts`
- Modify: `src/app/(buyer)/billing/BillingView.tsx`, `page.tsx`
- Test: create `AddMoney.test.tsx`, `TaxInvoices.test.tsx` in `src/app/(buyer)/billing/`

**Interfaces:**
- Consumes: `POST /api/v1/orgs/{o}/wallet/topups`, `GET …/wallet/bank-transfer-details`, `GET …/tax-invoices`, the `/api/download` route (Task 1).
- Produces:
  - `createTopupAction(amount): ActionResult<{ order: TopupOrder }>`.
  - `AddMoney({ bank })`, `TaxInvoices({ orgId, invoices, gstin })`. `BillingView` gains `bank`, `invoices`, `orgId` and `gstin` props.

- [ ] **Step 1: Write the failing tests**

`src/app/(buyer)/billing/AddMoney.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AddMoney } from "./AddMoney";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("./topupActions", () => ({
  createTopupAction: vi.fn().mockResolvedValue({ ok: true, order: { topupId: "t1", checkout: { key: "rzp_test_x", orderId: "order_1", amount: 5000000, currency: "INR", name: "Karya" } } }),
}));

const bank = { accountName: "Karya Wallet Meridian", accountNumber: "2223330001234567", ifsc: "RATN0VAAPIS", bankName: "RBL Bank" };

describe("AddMoney", () => {
  afterEach(() => { delete (window as { Razorpay?: unknown }).Razorpay; });

  it("defaults to ₹50,000 and opens checkout for the chosen amount", async () => {
    const open = vi.fn();
    let options: Record<string, unknown> = {};
    (window as unknown as { Razorpay: unknown }).Razorpay = vi.fn().mockImplementation((o) => { options = o; return { open, on: vi.fn() }; });
    const { createTopupAction } = await import("./topupActions");
    render(<AddMoney bank={bank} />);

    expect(screen.getByRole("button", { name: "₹50,000" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "Add ₹50,000" }));
    expect(createTopupAction).toHaveBeenCalledWith(50000);
    expect(options.order_id).toBe("order_1");
    expect(open).toHaveBeenCalled();
  });

  it("says the wallet updates shortly after payment, not that money was added", async () => {
    let handler: (() => void) | undefined;
    (window as unknown as { Razorpay: unknown }).Razorpay = vi.fn().mockImplementation((o) => { handler = o.handler; return { open: vi.fn(), on: vi.fn() }; });
    render(<AddMoney bank={bank} />);
    await userEvent.click(screen.getByRole("button", { name: "Add ₹50,000" }));
    handler?.();
    expect(await screen.findByRole("status")).toHaveTextContent("Payment received. Your wallet updates within a minute.");
  });

  it("shows bank transfer details", async () => {
    render(<AddMoney bank={bank} />);
    await userEvent.click(screen.getByRole("button", { name: /Bank transfer/ }));
    expect(screen.getByText("2223330001234567")).toBeInTheDocument();
    expect(screen.getByText("RATN0VAAPIS")).toBeInTheDocument();
  });
});
```

`src/app/(buyer)/billing/TaxInvoices.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TaxInvoices } from "./TaxInvoices";

describe("TaxInvoices", () => {
  it("lists invoices with download links through the proxy", () => {
    render(
      <TaxInvoices orgId="o1" gstin="27AAACM4821K1Z5" invoices={[
        { id: "t9", period: "2026-09", status: "PENDING", amountInclGst: null, issuedAt: null },
        { id: "t8", period: "2026-08", status: "ISSUED", amountInclGst: 41230, issuedAt: "2026-09-01T00:00:00Z" },
      ]} />,
    );
    expect(screen.getByText("Pending")).toBeInTheDocument();
    expect(screen.getByText("₹41,230 incl. GST")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Download Aug 2026 invoice" }))
      .toHaveAttribute("href", "/api/download?path=%2Fapi%2Fv1%2Forgs%2Fo1%2Ftax-invoices%2Ft8%2Fdownload");
    expect(screen.getByText("Billed to GSTIN 27AAACM4821K1Z5")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(buyer)/billing/AddMoney" "src/app/(buyer)/billing/TaxInvoices"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(buyer)/billing/topupActions.ts`:

```ts
"use server";

import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import { getSession } from "@/lib/session";
import type { TopupOrder } from "@/lib/types";

export async function createTopupAction(amount: number): Promise<ActionResult<{ order: TopupOrder }>> {
  const session = await getSession();
  if (!session) return { ok: false, error: "Sign in again." };
  try {
    const order = await backend.post<TopupOrder>(`/api/v1/orgs/${session.orgId}/wallet/topups`, { amount, method: "UPI" });
    return { ok: true, order };
  } catch (e) {
    return actionError(e, { DAILY_UPI_LIMIT: "UPI top-ups are limited to ₹1,00,000 a day. Use a bank transfer for more." }, "We couldn't start the payment.");
  }
}
```

`src/app/(buyer)/billing/AddMoney.tsx`:

```tsx
"use client";

import Script from "next/script";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Segmented } from "@/components/ui/Segmented";
import { inr } from "@/lib/format";
import type { BankTransferDetails } from "@/lib/types";
import { createTopupAction } from "./topupActions";

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open(): void; on(event: string, cb: () => void): void };
  }
}

const AMOUNTS = [25000, 50000, 100000];

export function AddMoney({ bank }: { bank: BankTransferDetails }) {
  const router = useRouter();
  const [method, setMethod] = useState<"upi" | "bank">("upi");
  const [amount, setAmount] = useState(String(AMOUNTS[1]));
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);

  async function pay() {
    setMessage(null);
    const r = await createTopupAction(Number(amount));
    if (!r.ok) return setMessage({ tone: "bad", text: r.error });
    if (!window.Razorpay) return setMessage({ tone: "bad", text: "The payment window didn't load. Check your connection and try again." });
    const checkout = new window.Razorpay({
      key: r.order.checkout.key,
      order_id: r.order.checkout.orderId,
      amount: r.order.checkout.amount,
      currency: r.order.checkout.currency,
      name: r.order.checkout.name,
      // The backend credits the wallet from Razorpay's webhook; the browser only says thanks.
      handler: () => {
        setMessage({ tone: "ok", text: "Payment received. Your wallet updates within a minute." });
        setTimeout(() => router.refresh(), 15000);
      },
      modal: { ondismiss: () => setMessage({ tone: "bad", text: "Payment cancelled. Nothing was charged." }) },
    });
    checkout.open();
  }

  return (
    <Panel className="p-5 flex flex-col gap-3">
      <Script src="https://checkout.razorpay.com/v1/checkout.js" strategy="lazyOnload" />
      <PanelTitle>Add money</PanelTitle>
      <div className="flex flex-col gap-2">
        {([["upi", "UPI", "Instant, up to ₹1,00,000 a day"], ["bank", "Bank transfer", "NEFT or RTGS to your Karya account, usually within an hour"]] as const).map(([k, label, hint]) => (
          <button key={k} type="button" aria-pressed={method === k} onClick={() => setMethod(k)} className={`text-left rounded-xl border px-3 py-2 ${method === k ? "border-blue" : "border-line"}`}>
            <span className="block text-[13px] font-medium">{label}</span><span className="text-xs text-ink3">{hint}</span>
          </button>
        ))}
      </div>
      {method === "upi" ? (
        <>
          <Segmented label="Amount" value={amount} onChange={setAmount} options={AMOUNTS.map((a) => ({ value: String(a), label: inr(a) }))} />
          <Button onClick={pay}>Add {inr(Number(amount))}</Button>
        </>
      ) : (
        <div>
          <Kv label="Account name" value={bank.accountName} mono={false} />
          <Kv label="Account number" value={bank.accountNumber} />
          <Kv label="IFSC" value={bank.ifsc} />
          <Kv label="Bank" value={bank.bankName} mono={false} />
        </div>
      )}
      {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}
    </Panel>
  );
}
```

`src/app/(buyer)/billing/TaxInvoices.tsx`:

```tsx
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { inr } from "@/lib/format";
import type { TaxInvoice } from "@/lib/types";

const label = (ym: string) => new Date(`${ym}-01T00:00:00Z`).toLocaleString("en-GB", { month: "short", year: "numeric", timeZone: "UTC" });

export function TaxInvoices({ orgId, invoices, gstin }: { orgId: string; invoices: TaxInvoice[]; gstin: string | null }) {
  return (
    <Panel className="p-5 flex flex-col gap-2">
      <PanelTitle>Tax invoices</PanelTitle>
      {invoices.map((i) => (
        <div key={i.id} className="flex items-center gap-3 py-1.5 border-t border-line2 first:border-t-0 text-[13px]">
          <span className="flex-1">{label(i.period)}</span>
          {i.status === "PENDING" ? <span className="text-ink3">Pending</span> : (
            <>
              <span className="font-mono">{inr(i.amountInclGst ?? 0)} incl. GST</span>
              <a className="text-blue font-semibold" aria-label={`Download ${label(i.period)} invoice`}
                href={`/api/download?path=${encodeURIComponent(`/api/v1/orgs/${orgId}/tax-invoices/${i.id}/download`)}`}>Download</a>
            </>
          )}
        </div>
      ))}
      {gstin && <p className="text-xs text-ink3">Billed to GSTIN {gstin}</p>}
    </Panel>
  );
}
```

In `BillingView`:
- Add the props `bank: BankTransferDetails`, `invoices: TaxInvoice[]`, `orgId: string` and `gstin: string | null`.
- Render `<AddMoney bank={bank} />` as the first panel in the aside, and `<TaxInvoices orgId={orgId} invoices={invoices} gstin={gstin} />` after the Disputes panel.
- The statement keeps its "All time" label.

In `BillingView.test.tsx`, add `vi.mock("./topupActions", () => ({ createTopupAction: vi.fn() }));` and `vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));`. Pass `bank={{ accountName: "x", accountNumber: "1", ifsc: "I", bankName: "B" }} invoices={[]} orgId="o1" gstin={null}` in `renderBilling`.

Replace `src/app/(buyer)/billing/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { BankTransferDetails, DisputeView, StatementLineResponse, TaxInvoice, WalletResponse, WorkspaceHome } from "@/lib/types";
import { BillingView } from "./BillingView";

export default async function BillingPage() {
  const session = await getSession();
  if (!session) return null;
  const org = `/api/v1/orgs/${session.orgId}`;
  const [wallet, lines, home, disputes, bank, invoices, profile] = await Promise.all([
    backend.get<WalletResponse>(`${org}/wallet`),
    backend.get<StatementLineResponse[]>(`${org}/statement`),
    backend.get<WorkspaceHome>(`${org}/home`),
    backend.get<DisputeView[]>(`${org}/disputes`),
    backend.get<BankTransferDetails>(`${org}/wallet/bank-transfer-details`),
    backend.get<TaxInvoice[]>(`${org}/tax-invoices`),
    backend.get<{ gstin: string | null }>(org),
  ]);
  return (
    <BillingView wallet={wallet} spend={home.spend} lines={lines} disputes={disputes}
      bank={bank} invoices={invoices} orgId={session.orgId} gstin={profile.gstin} />
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): add money by UPI or bank transfer, and download GST tax invoices"
```

---

### Task 8: Developer end to end, and cleanup

**Files:**
- Create: `e2e/developer.spec.ts`
- Delete: `src/app/(developer)/dashboard/DashboardList.tsx`, `DashboardList.test.tsx`, `src/app/(developer)/dashboard/agents/**` (ManageView, ReviewStatusView and their tests and pages)
- Modify: `frontend/README.md`

- [ ] **Step 1: Retire the legacy developer pages**

Replace `src/app/(developer)/dashboard/agents/[agentId]/page.tsx` with a redirect to `/studio/{agentId}`, and `…/versions/[versionId]/status/page.tsx` with a redirect to `/studio/{agentId}?version={versionId}`. Delete `DashboardList`, `ManageView`, `ReviewStatusView` and their tests. Keep `/dashboard/new` and `/dashboard/agents/[agentId]/versions/new` (the manual submission wizard) until WS5's CLI push is the only way in. They still use `SubmissionWizard`, `WizardRail` and `PermissionStatementDoc`.

- [ ] **Step 2: Developer E2E**

`e2e/developer.spec.ts`:

```ts
import { expect, test } from "@playwright/test";

test("a developer sees their agent in the studio and the verification steps", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("e2e-dev@example.com");
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/studio$/);
  await expect(page.getByRole("heading", { name: "Studio" })).toBeVisible();
  await page.getByRole("link", { name: /E2E Bank Reconciliation/ }).click();
  await expect(page.getByRole("navigation", { name: "Agent sections" })).toBeVisible();

  await page.getByRole("link", { name: "Verification" }).click();
  await expect(page.getByRole("heading", { name: "Get verified" })).toBeVisible();
  await expect(page.getByRole("row", { name: /Sandbox minutes a month/ })).toBeVisible();
});
```

- [ ] **Step 3: Verify**

Run: `npx tsc --noEmit && npm test && npm run build`, then with a fresh backend: `npm run seed:e2e && npm run test:e2e`
Expected: all PASS.

- [ ] **Step 4: Document**

In `frontend/README.md`, add: developer pages live under `/studio`, `/earnings` and `/verify`; downloads go through `/api/download`; top-ups open Razorpay Checkout; and the wallet balance changes only after the backend's payment webhook.

- [ ] **Step 5: Commit**

```bash
git add -A frontend
git commit -m "test(frontend): developer E2E; retire legacy dashboard pages the studio replaced"
```
