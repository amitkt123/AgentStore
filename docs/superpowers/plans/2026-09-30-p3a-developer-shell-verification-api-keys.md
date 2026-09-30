# P3a Developer Shell, Get Verified and API Keys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A developer signs in, lands in a developer area with its own rail, completes and submits identity verification, and manages API keys. Buyers never see these screens.

**Architecture:** Frontend only (Next 14 app router, React 18.3, Vitest). Two routes go inside the existing `app/(app)/` group: `/developer/verification` and `/developer/api-keys`. Each is a server page that fetches through `lib/api/*` and hands props to a client view that calls server actions, exactly like `files`. Role gating moves into `middleware.ts` (see Deviations).

**Tech Stack:** Next 14.2, React 18.3, TypeScript, CSS modules, Vitest + Testing Library + user-event.

**Spec:** `docs/superpowers/specs/2026-09-30-p3a-developer-shell-verification-api-keys-design.md`. Mockup copy and CSS were read from the decoded "Get verified" screen of `fe-mockups/latest/Karya — Agentic UI.html`.

## Deviations from the approved spec (flag these in the PR)

1. **Gating lives in `middleware.ts`, not the `(app)` layout.** A Next app-router layout does not re-render on client-side navigation, and it cannot see the request path without a header hack. Middleware runs on every navigation, already has the token, and already has a test file. The layout still picks the rail by role.
2. **The spec says `railFor` is used by the layout.** `railFor` exists in `lib/roles.ts`, but the layout currently uses an inline `isBuyer` ternary. Task 3 replaces it with `railItemsFor`.
3. **Reviewer note for `FIX_REQUESTED` / `REJECTED`.** The backend stores `decisionNote` but `DeveloperVerificationView` does not return it. The frontend type has an optional `decisionNote` and shows a fallback sentence when it is absent. The backend gap is recorded in `docs/frontend-gaps.md` (G14). The backend is not touched.
4. **Server action body limit.** Next's default is 1 MB and the PAN image can be 5 MB, so `next.config.mjs` raises it to 6 MB (Task 6). This also fixes `files` uploads over 1 MB.

## Global Constraints

- Commit messages, PR text and docs carry **no Claude, Anthropic or AI attribution and no `Co-Authored-By` trailer**. This overrides the harness reminder to add one (user's `~/.claude/CLAUDE.md`).
- Conventional commits, matching the repo: `feat(frontend): …`, `docs: …`, `test: …`.
- Aesthetics come only from `fe-mockups/latest/` and the existing `ui/tokens.css` variables. No new colour tokens.
- Copy is plain words, as in the mockup. No em-dash-heavy or jargon copy.
- Developer agreement version is exactly `"2.1"`. PAN image limit is 5 MB, JPEG/PNG/PDF.
- Steps are exactly `ABOUT → TAX_IDS → PHONE → AGREEMENT`. No SMS OTP, no payout step.
- The raw PAN, GSTIN and phone are never read back from the API and never re-displayed. Only masked values (`panMasked`, `gstinMasked`, `phoneMasked`) are shown.
- A created API key's full value lives in component state only. Never in a URL, cookie, `localStorage` or `sessionStorage`.
- Run all commands from `/Users/amittiwari/AgentStore/frontend`. Verify with `npx vitest run` and `npx tsc --noEmit`. `next lint` is not configured.
- Not checked in a browser until the user runs the stack. The PR must say so.

## Review Focus

Inputs the spec implies but its tests do not exercise, most likely first. Each has a pinning test in the task named.

1. **A phone typed the way the mockup shows it (`+91 99200 12208`) or a lowercase PAN** — the backend regexes reject spaces and lowercase. The client strips spaces and dashes from the phone and upper-cases PAN/GSTIN before sending. (Task 5 `fieldsFor`, Task 6 view test.)
2. **A PAN image over 5 MB, or over the 1 MB default action limit** — it must show one plain sentence, not a framework error page. Client pre-check plus the 6 MB config. (Task 6.)
3. **Double-clicking Continue, Submit or Create key** — must not save twice or mint two keys. (Task 6, Task 7.)
4. **`FIX_REQUESTED` with no reviewer note** — the backend omits it today; the banner must fall back to a sentence and never print `undefined`. (Task 5 `banner`, Task 6.)
5. **A new key is revealed, then the list refreshes** — the reveal card must survive the server refresh until the user dismisses it, and a second key cannot be created while it is showing (it would be lost). (Task 7.)

---

### Task 1: Foundations (ApiError details, date format, icons)

**Files:**
- Modify: `src/lib/api/http.ts`
- Modify: `src/lib/api/http.test.ts`
- Modify: `src/lib/format.ts`
- Modify: `src/lib/format.test.ts`
- Modify: `src/ui/Icon.tsx`

**Interfaces:**
- Produces: `ApiError.details: Record<string, unknown>` (the parsed JSON error body, `{}` when unparseable); `formatDate(iso: string | null): string`; `IconName` gains `"key"` and `"shield"`.

- [ ] **Step 0: Create the implementation branch**

```bash
git switch -c feat/p3a-developer-shell
```

- [ ] **Step 1: Write the failing tests**

Append to `src/lib/api/http.test.ts`:

```ts
it("keeps the extra fields of the error body on ApiError.details", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "VERIFICATION_INCOMPLETE", message: "Complete all steps", missingSteps: ["PHONE"] }), { status: 400 })));
  const err = (await api("/api/v1/x").catch((e) => e)) as ApiError;
  expect(err.details.missingSteps).toEqual(["PHONE"]);
});

it("leaves details empty when the body is not JSON", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 500 })));
  const err = (await api("/api/v1/x").catch((e) => e)) as ApiError;
  expect(err.details).toEqual({});
});
```

Check the import line at the top of `src/lib/format.test.ts`; add `formatDate` to it, then append:

```ts
it("formats a date with day, month and year", () => {
  expect(formatDate("2026-09-30T12:00:00Z")).toMatch(/30 Sep(t)? 2026/);
  expect(formatDate(null)).toBe("–");
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/lib/api/http.test.ts src/lib/format.test.ts`
Expected: FAIL (`details` undefined, `formatDate` is not a function).

- [ ] **Step 3: Implement**

In `src/lib/api/http.ts` replace the `ApiError` class and the error branch of `api`:

```ts
export class ApiError extends Error {
  status: number;
  code: string | null;
  details: Record<string, unknown>;
  constructor(status: number, code: string | null, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}
```

```ts
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ({ code?: string; message?: string } & Record<string, unknown>) | null;
    throw new ApiError(res.status, body?.code ?? null, body?.message || `Request failed (${res.status})`, body ?? {});
  }
```

Append to `src/lib/format.ts`:

```ts
export function formatDate(iso: string | null): string {
  if (!iso) return "–";
  return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}
```

In `src/ui/Icon.tsx` extend the union and the map:

```ts
export type IconName = "home" | "store" | "runs" | "files" | "search" | "bell" | "check" | "x" | "upload" | "lock" | "key" | "shield";
```

```ts
  key: "M12 9a4 4 0 11-1 2.6L3 19.5V21h2.5v-2H8v-2h2l1-1M15.5 9.5h.01",
  shield: "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5",
```

- [ ] **Step 4: Run them to verify they pass**

Run: `npx vitest run src/lib/api/http.test.ts src/lib/format.test.ts && npx tsc --noEmit`
Expected: PASS, no type errors.

- [ ] **Step 5: Commit**

```bash
git add src/lib/api/http.ts src/lib/api/http.test.ts src/lib/format.ts src/lib/format.test.ts src/ui/Icon.tsx
git commit -m "feat(frontend): keep error body details on ApiError, add formatDate and key/shield icons"
```

---

### Task 2: API types and wrappers

**Files:**
- Modify: `src/lib/api/types.ts` (append)
- Create: `src/lib/api/verification.ts`
- Create: `src/lib/api/apikeys.ts`
- Test: `src/lib/api/verification.test.ts`, `src/lib/api/apikeys.test.ts`

**Interfaces:**
- Consumes: `api`, `json` from `./http`.
- Produces (`types.ts`): `VerificationStatus`, `VerificationStep`, `VerificationEntityType`, `DeveloperVerification`, `DeveloperApiKey`, `CreatedApiKey`.
- Produces (`verification.ts`): `getVerification(): Promise<DeveloperVerification>`, `patchVerification(step: VerificationStep, fields: Record<string, string>): Promise<DeveloperVerification>`, `uploadPanDocument(fd: FormData): Promise<DeveloperVerification>` (form field `file`), `submitVerification(): Promise<DeveloperVerification>`.
- Produces (`apikeys.ts`): `listApiKeys(): Promise<DeveloperApiKey[]>`, `createApiKey(name: string): Promise<CreatedApiKey>`, `revokeApiKey(id: string): Promise<void>`.

- [ ] **Step 1: Write the failing tests**

`src/lib/api/verification.test.ts`:

```ts
import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import { getVerification, patchVerification, submitVerification, uploadPanDocument } from "./verification";

const ok = (body: unknown) => vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }));
beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("reads the verification state", async () => {
  const f = ok({ status: "DRAFT" });
  vi.stubGlobal("fetch", f);
  expect(await getVerification()).toEqual({ status: "DRAFT" });
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification");
});

it("patches one step with its fields", async () => {
  const f = ok({});
  vi.stubGlobal("fetch", f);
  await patchVerification("TAX_IDS", { pan: "ABCDE1234F" });
  const init = f.mock.calls[0][1];
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification");
  expect(init.method).toBe("PATCH");
  expect(JSON.parse(init.body)).toEqual({ step: "TAX_IDS", fields: { pan: "ABCDE1234F" } });
});

it("uploads the PAN document as multipart without forcing a content type", async () => {
  const f = ok({});
  vi.stubGlobal("fetch", f);
  const fd = new FormData();
  fd.set("file", new Blob(["x"]), "pan.pdf");
  await uploadPanDocument(fd);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification/pan-document");
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(f.mock.calls[0][1].body).toBe(fd);
  expect(f.mock.calls[0][1].headers["Content-Type"]).toBeUndefined();
});

it("submits, and surfaces the missing steps on an incomplete submit", async () => {
  const f = ok({});
  vi.stubGlobal("fetch", f);
  await submitVerification();
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/verification/submit");
  expect(f.mock.calls[0][1].method).toBe("POST");

  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "VERIFICATION_INCOMPLETE", message: "m", missingSteps: ["PHONE"] }), { status: 400 })));
  await expect(submitVerification()).rejects.toMatchObject({ status: 400, code: "VERIFICATION_INCOMPLETE", details: { missingSteps: ["PHONE"] } });
});
```

`src/lib/api/apikeys.test.ts`:

```ts
import { vi } from "vitest";
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));
import { createApiKey, listApiKeys, revokeApiKey } from "./apikeys";

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("lists keys", async () => {
  const f = vi.fn().mockResolvedValue(new Response("[]", { status: 200 }));
  vi.stubGlobal("fetch", f);
  expect(await listApiKeys()).toEqual([]);
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/api-keys");
});

it("creates a key by name and returns the one-time value", async () => {
  const created = { id: "k1", name: "CI", prefix: "ask_live_ab", key: "ask_live_abcdef", createdAt: "2026-09-30T00:00:00Z" };
  const f = vi.fn().mockResolvedValue(new Response(JSON.stringify(created), { status: 201 }));
  vi.stubGlobal("fetch", f);
  expect(await createApiKey("CI")).toEqual(created);
  expect(f.mock.calls[0][1].method).toBe("POST");
  expect(JSON.parse(f.mock.calls[0][1].body)).toEqual({ name: "CI" });
});

it("revokes by id and treats 204 as success", async () => {
  const f = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
  vi.stubGlobal("fetch", f);
  await expect(revokeApiKey("k1")).resolves.toBeUndefined();
  expect(f.mock.calls[0][0]).toBe("http://be/api/v1/developer/api-keys/k1");
  expect(f.mock.calls[0][1].method).toBe("DELETE");
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/lib/api/verification.test.ts src/lib/api/apikeys.test.ts`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement**

Append to `src/lib/api/types.ts`:

```ts
export type VerificationStatus = "DRAFT" | "PENDING" | "VERIFIED" | "FIX_REQUESTED" | "REJECTED";
export type VerificationStep = "ABOUT" | "TAX_IDS" | "PHONE" | "AGREEMENT";
export type VerificationEntityType = "INDIVIDUAL" | "LLP" | "PRIVATE_LIMITED" | "PARTNERSHIP" | "OTHER";

// Masked values only: the API never returns the raw PAN, GSTIN or phone.
// decisionNote is stored by the backend but not yet returned (docs/frontend-gaps.md G14), so it is optional.
export interface DeveloperVerification {
  status: VerificationStatus;
  currentStep: VerificationStep;
  entityType: VerificationEntityType | null;
  legalName: string | null;
  displayName: string | null;
  country: string | null;
  city: string | null;
  panMasked: string | null;
  gstinMasked: string | null;
  panDocumentUploaded: boolean;
  phoneMasked: string | null;
  agreementVersion: string | null;
  agreementAcceptedAt: string | null;
  resubmissionCount: number;
  submittedAt: string | null;
  decisionNote?: string | null;
}

export interface DeveloperApiKey { id: string; name: string; prefix: string; createdAt: string; lastUsedAt: string | null; revokedAt: string | null }
export interface CreatedApiKey { id: string; name: string; prefix: string; key: string; createdAt: string }
```

`src/lib/api/verification.ts`:

```ts
import { api, json } from "./http";
import type { DeveloperVerification, VerificationStep } from "./types";

const base = "/api/v1/developer/verification";

export const getVerification = () => api<DeveloperVerification>(base);
export const patchVerification = (step: VerificationStep, fields: Record<string, string>) =>
  api<DeveloperVerification>(base, { method: "PATCH", body: json({ step, fields }) });
export const uploadPanDocument = (fd: FormData) => api<DeveloperVerification>(`${base}/pan-document`, { method: "POST", body: fd });
export const submitVerification = () => api<DeveloperVerification>(`${base}/submit`, { method: "POST" });
```

`src/lib/api/apikeys.ts`:

```ts
import { api, json } from "./http";
import type { CreatedApiKey, DeveloperApiKey } from "./types";

const base = "/api/v1/developer/api-keys";

export const listApiKeys = () => api<DeveloperApiKey[]>(base);
export const createApiKey = (name: string) => api<CreatedApiKey>(base, { method: "POST", body: json({ name }) });
export const revokeApiKey = (id: string) => api<void>(`${base}/${id}`, { method: "DELETE" });
```

- [ ] **Step 4: Run them to verify they pass**

Run: `npx vitest run src/lib/api && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/lib/api/types.ts src/lib/api/verification.ts src/lib/api/verification.test.ts src/lib/api/apikeys.ts src/lib/api/apikeys.test.ts
git commit -m "feat(frontend): developer verification and API key API wrappers"
```

---

### Task 3: Developer rail and role gating

**Files:**
- Create: `src/lib/gate.ts`, `src/lib/gate.test.ts`
- Modify: `src/middleware.ts`, `src/middleware.test.ts`
- Modify: `src/ui/Rail.tsx`, `src/ui/Rail.test.tsx`
- Modify: `src/app/(app)/layout.tsx`

**Interfaces:**
- Consumes: `decodeToken`, `needsRefresh` from `lib/session`; `railFor`, `isBuyer`, `UserRole` from `lib/roles`.
- Produces: `gateTarget(role: UserRole | undefined, path: string): string | null` (the redirect target, or `null` to let the request through); `developerRailItems(): RailItem[]`; `railItemsFor(role: UserRole, needsYou: number): RailItem[]`.

- [ ] **Step 1: Write the failing tests**

`src/lib/gate.test.ts`:

```ts
import { gateTarget } from "./gate";

it.each(["/home", "/store", "/store/a1/v1", "/runs", "/runs/R-1", "/files", "/connect/a/v"])("sends a developer away from buyer route %s", (path) => {
  expect(gateTarget("DEVELOPER", path)).toBe("/developer/verification");
});

it.each(["BUYER_ADMIN", "BUYER_APPROVER", "ADMIN"] as const)("sends %s away from developer routes", (role) => {
  expect(gateTarget(role, "/developer/verification")).toBe("/home");
  expect(gateTarget(role, "/developer/api-keys")).toBe("/home");
});

it("lets each role through on its own routes", () => {
  expect(gateTarget("DEVELOPER", "/developer/api-keys")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/runs")).toBeNull();
});

it("only matches whole path segments", () => {
  expect(gateTarget("DEVELOPER", "/storefront")).toBeNull();
  expect(gateTarget("BUYER_ADMIN", "/developers-guide")).toBeNull();
});

it("does nothing without a role", () => {
  expect(gateTarget(undefined, "/home")).toBeNull();
});
```

Append to `src/middleware.test.ts`:

```ts
function jwtAs(role: string, exp: number) {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u", orgId: "o", role, exp })}.sig`;
}

it("redirects a developer from a buyer route to verification", async () => {
  const res = await middleware(req("/runs", { karya_session: jwtAs("DEVELOPER", nowSec() + 900) }));
  expect(res.status).toBe(307);
  expect(res.headers.get("location")).toBe("http://app/developer/verification");
});

it("redirects a buyer from a developer route to home", async () => {
  const res = await middleware(req("/developer/api-keys", { karya_session: jwtAs("BUYER_ADMIN", nowSec() + 900) }));
  expect(res.status).toBe(307);
  expect(res.headers.get("location")).toBe("http://app/home");
});

it("lets a developer into developer routes", async () => {
  expect((await middleware(req("/developer/verification", { karya_session: jwtAs("DEVELOPER", nowSec() + 900) }))).status).toBe(200);
});

it("gates on the refreshed token's role and still sets the new cookies", async () => {
  const fresh = jwtAs("DEVELOPER", nowSec() + 900);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: fresh, refreshToken: "r2" }), { status: 200 })));
  const res = await middleware(req("/home", { karya_session: jwtAs("DEVELOPER", nowSec() - 5), karya_refresh: "r1" }));
  expect(res.status).toBe(307);
  expect(res.headers.get("location")).toBe("http://app/developer/verification");
  expect(res.cookies.get("karya_session")?.value).toBe(fresh);
  expect(res.cookies.get("karya_refresh")?.value).toBe("r2");
});
```

Replace the import line and append to `src/ui/Rail.test.tsx`:

```tsx
import { Rail, buyerRailItems, developerRailItems, railItemsFor } from "./Rail";
```

```tsx
it("gives developers verification and API keys and leaves the buyer rail alone", () => {
  expect(developerRailItems().map((i) => i.href)).toEqual(["/developer/verification", "/developer/api-keys"]);
  expect(railItemsFor("DEVELOPER", 3)).toEqual(developerRailItems());
  expect(railItemsFor("BUYER_ADMIN", 3)).toEqual(buyerRailItems(3));
  expect(railItemsFor("BUYER_APPROVER", 0)).toEqual(buyerRailItems(0));
  expect(railItemsFor("ADMIN", 0).map((i) => i.href)).toEqual(["/home"]);
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/lib/gate.test.ts src/middleware.test.ts src/ui/Rail.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/lib/gate.ts`:

```ts
import type { UserRole } from "./roles";

const BUYER_ROUTES = ["/home", "/store", "/runs", "/files", "/connect"];
const under = (path: string, prefix: string) => path === prefix || path.startsWith(prefix + "/");

// Developers live under /developer, everyone else stays out of it. Returns where to send the request, or null.
export function gateTarget(role: UserRole | undefined, path: string): string | null {
  if (!role) return null;
  if (role === "DEVELOPER" && BUYER_ROUTES.some((p) => under(path, p))) return "/developer/verification";
  if (role !== "DEVELOPER" && under(path, "/developer")) return "/home";
  return null;
}
```

In `src/middleware.ts` change the session import and add the gate import:

```ts
import { REFRESH_COOKIE, SESSION_COOKIE, cookieOptions, decodeToken, needsRefresh } from "@/lib/session";
import { gateTarget } from "@/lib/gate";
```

Replace the body from `if (!needsRefresh(token, now)) return NextResponse.next();` with these two pieces. First, before `toLogin`:

```ts
  const gateFor = (tok: string) => gateTarget(decodeToken(tok)?.role, path);
  const redirectTo = (to: string) => {
    const url = req.nextUrl.clone();
    url.pathname = to;
    url.search = "";
    return NextResponse.redirect(url);
  };

  if (!needsRefresh(token, now)) {
    const to = gateFor(token as string);
    return to ? redirectTo(to) : NextResponse.next();
  }
```

Second, at the end, replace `const res = NextResponse.next({ request: { headers } });` with:

```ts
  const to = gateFor(next.token);
  const res = to ? redirectTo(to) : NextResponse.next({ request: { headers } });
```

(the two `res.cookies.set(...)` lines that follow stay as they are).

In `src/ui/Rail.tsx` add the import and two functions:

```ts
import { railFor, type UserRole } from "@/lib/roles";
```

```ts
export function developerRailItems(): RailItem[] {
  return [
    { href: "/developer/verification", label: "Get verified", icon: "shield" },
    { href: "/developer/api-keys", label: "API keys", icon: "key" },
  ];
}

// Studio, Sandbox runs and Test files join developerRailItems in P3b/P3c; the admin rail arrives in P4.
export function railItemsFor(role: UserRole, needsYou: number): RailItem[] {
  switch (railFor(role)) {
    case "developer": return developerRailItems();
    case "buyer": return buyerRailItems(needsYou);
    default: return [{ href: "/home", label: "Workspace", icon: "home" }];
  }
}
```

In `src/app/(app)/layout.tsx` replace the `buyerRailItems` import with `railItemsFor`, and replace the two lines starting at the `// Developer and admin rails` comment:

```tsx
import { railItemsFor } from "@/ui/Rail";
```

```tsx
  const items = railItemsFor(session.role, needsYou);
```

- [ ] **Step 4: Run them to verify they pass**

Run: `npx vitest run src/lib/gate.test.ts src/middleware.test.ts src/ui/Rail.test.tsx && npx tsc --noEmit`
Expected: PASS. The existing middleware tests must still pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/lib/gate.ts src/lib/gate.test.ts src/middleware.ts src/middleware.test.ts src/ui/Rail.tsx src/ui/Rail.test.tsx "src/app/(app)/layout.tsx"
git commit -m "feat(frontend): developer rail and role gating for developer and buyer routes"
```

---

### Task 4: UI primitives (Stepper, Segmented, KeyValue)

**Files:**
- Create: `src/ui/Stepper.tsx`, `src/ui/Stepper.module.css`, `src/ui/Stepper.test.tsx`
- Create: `src/ui/Segmented.tsx`, `src/ui/Segmented.module.css`, `src/ui/Segmented.test.tsx`
- Create: `src/ui/KeyValue.tsx`, `src/ui/KeyValue.module.css`

**Interfaces:**
- Produces:
  - `Stepper({ items: StepperItem[]; onPick: (key: string) => void; note?: { title: string; hint: string } })` with `StepperItem = { key: string; name: string; hint: string; state: "todo" | "cur" | "done"; disabled?: boolean }`. Each step is a `<button>` with `data-state`, and `aria-current="step"` when `cur`.
  - `Segmented<T extends string>({ label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; disabled?: boolean })`. Buttons expose `aria-pressed`.
  - `KeyValue({ label: string; children: React.ReactNode })`.

- [ ] **Step 1: Write the failing tests**

`src/ui/Stepper.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { Stepper } from "./Stepper";

const items = [
  { key: "a", name: "About you", hint: "Asha, individual", state: "done" as const },
  { key: "b", name: "Tax IDs", hint: "PAN, GSTIN if any", state: "cur" as const },
  { key: "c", name: "Phone", hint: "Mobile number", state: "todo" as const, disabled: true },
];

it("marks done, current and todo steps and disables unreachable ones", () => {
  render(<Stepper items={items} onPick={() => {}} />);
  expect(screen.getByRole("button", { name: /About you/ })).toHaveAttribute("data-state", "done");
  expect(screen.getByRole("button", { name: /Tax IDs/ })).toHaveAttribute("aria-current", "step");
  expect(screen.getByRole("button", { name: /Phone/ })).toBeDisabled();
});

it("reports the picked step's key", async () => {
  const onPick = vi.fn();
  render(<Stepper items={items} onPick={onPick} />);
  await userEvent.click(screen.getByRole("button", { name: /About you/ }));
  expect(onPick).toHaveBeenCalledWith("a");
});

it("renders the dashed note card after the steps", () => {
  render(<Stepper items={items} onPick={() => {}} note={{ title: "Karya checks it", hint: "After you send it" }} />);
  expect(screen.getByText("Karya checks it")).toBeInTheDocument();
  expect(screen.getByText("After you send it")).toBeInTheDocument();
});
```

`src/ui/Segmented.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { Segmented } from "./Segmented";

const options = [{ value: "one", label: "An individual" }, { value: "two", label: "A company" }];

it("shows which option is pressed and reports changes", async () => {
  const onChange = vi.fn();
  render(<Segmented label="Entity type" value="one" options={options} onChange={onChange} />);
  expect(screen.getByRole("group", { name: "Entity type" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "An individual" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("button", { name: "A company" })).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(screen.getByRole("button", { name: "A company" }));
  expect(onChange).toHaveBeenCalledWith("two");
});

it("does not report changes when disabled", async () => {
  const onChange = vi.fn();
  render(<Segmented label="Entity type" value="one" options={options} onChange={onChange} disabled />);
  await userEvent.click(screen.getByRole("button", { name: "A company" }));
  expect(onChange).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/ui/Stepper.test.tsx src/ui/Segmented.test.tsx`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement**

`src/ui/Stepper.tsx`:

```tsx
import styles from "./Stepper.module.css";
import { Icon } from "./Icon";

export interface StepperItem { key: string; name: string; hint: string; state: "todo" | "cur" | "done"; disabled?: boolean }

export function Stepper({ items, onPick, note }: { items: StepperItem[]; onPick: (key: string) => void; note?: { title: string; hint: string } }) {
  return (
    <ol className={styles.list}>
      {items.map((s, i) => (
        <li key={s.key}>
          <button
            type="button"
            data-state={s.state}
            aria-current={s.state === "cur" ? "step" : undefined}
            disabled={s.disabled}
            onClick={() => onPick(s.key)}
            className={`${styles.st} ${i === 0 ? styles.first : ""} ${s.state === "done" ? styles.done : ""} ${s.state === "cur" ? styles.cur : ""}`}
          >
            <span className={styles.n}>{s.state === "done" ? <Icon name="check" /> : i + 1}</span>
            <span><b>{s.name}</b><small>{s.hint}</small></span>
          </button>
        </li>
      ))}
      {note && (
        <li>
          <div className={`${styles.st} ${styles.karya}`}>
            <span className={styles.n}><Icon name="shield" /></span>
            <span><b>{note.title}</b><small>{note.hint}</small></span>
          </div>
        </li>
      )}
    </ol>
  );
}
```

`src/ui/Stepper.module.css`:

```css
.list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.st { position: relative; display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 12px; align-items: center; padding: 12px 14px; border: 1px solid var(--l); border-radius: 14px; background: var(--s); font: inherit; color: inherit; text-align: left; cursor: pointer; width: 100%; min-height: 44px; box-shadow: 0 1px 1px rgba(13, 26, 48, 0.04), 0 10px 22px -18px rgba(43, 92, 230, 0.4); }
.st:disabled { cursor: default; opacity: 0.6; }
.st::before { content: ""; position: absolute; top: -7px; left: 26px; width: 22px; height: 7px; border: 1px solid var(--l); border-bottom: 0; border-radius: 6px 6px 0 0; background: var(--s); }
.first::before { display: none; }
.cur { border-color: var(--b); box-shadow: 0 0 0 4px var(--sky2); }
.karya { border-style: dashed; border-color: var(--b3); background: rgba(237, 243, 255, 0.6); cursor: default; box-shadow: none; }
.n { width: 26px; height: 26px; border-radius: 8px; display: grid; place-items: center; font-size: 12px; font-weight: 600; background: var(--l2); color: var(--ink3); }
.n svg { width: 14px; height: 14px; stroke-width: 2.2; }
.done .n { background: var(--okS); color: var(--ok); }
.cur .n { background: var(--b); color: #fff; }
.st b { display: block; font-size: 14px; }
.st small { display: block; font-size: 12px; color: var(--ink3); }
```

`src/ui/Segmented.tsx`:

```tsx
import styles from "./Segmented.module.css";

export function Segmented<T extends string>({ label, value, options, onChange, disabled }: { label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; disabled?: boolean }) {
  return (
    <div className={styles.seg} role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} disabled={disabled} className={o.value === value ? styles.on : undefined} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}
```

`src/ui/Segmented.module.css`:

```css
.seg { display: flex; gap: 3px; background: var(--l2); padding: 3px; border-radius: 10px; }
.seg button { flex: 1; font: inherit; font-size: 13px; min-height: 44px; padding: 0 14px; border: 0; border-radius: 8px; background: transparent; color: var(--ink2); cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.seg button:disabled { cursor: default; opacity: 0.7; }
.seg .on { background: var(--s); color: var(--ink); font-weight: 600; box-shadow: 0 1px 2px rgba(13, 26, 48, 0.1); }
```

`src/ui/KeyValue.tsx`:

```tsx
import styles from "./KeyValue.module.css";

export function KeyValue({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.kv}>
      <span>{label}</span>
      <span>{children}</span>
    </div>
  );
}
```

`src/ui/KeyValue.module.css`:

```css
.kv { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; font-size: 13px; padding: 8px 0; border-top: 1px solid var(--l2); }
.kv span:first-child { color: var(--ink2); }
.kv span:last-child { text-align: right; font-weight: 500; }
```

- [ ] **Step 4: Run them to verify they pass**

Run: `npx vitest run src/ui && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/ui/Stepper.tsx src/ui/Stepper.module.css src/ui/Stepper.test.tsx src/ui/Segmented.tsx src/ui/Segmented.module.css src/ui/Segmented.test.tsx src/ui/KeyValue.tsx src/ui/KeyValue.module.css
git commit -m "feat(frontend): stepper, segmented control and key-value row from the mockup"
```

---

### Task 5: Verification model and error mapping (pure logic)

**Files:**
- Create: `src/features/verification/errors.ts`, `src/features/verification/errors.test.ts`
- Create: `src/features/verification/model.ts`, `src/features/verification/model.test.ts`, `src/features/verification/fixtures.ts`

**Interfaces:**
- Consumes: `DeveloperVerification`, `VerificationStep`, `VerificationStatus`, `VerificationEntityType` from `@/lib/api/types`; `Tone` from `@/ui/Pill`.
- Produces (`errors.ts`): `FieldName`; `FIELD_TEXT: Record<FieldName, string>`; `Failure = { text: string; field?: FieldName; missingSteps?: VerificationStep[] }`; `StepResult = { view: DeveloperVerification } | { failure: Failure }`; `toFailure(e: { status: number; code: string | null; message: string; details?: Record<string, unknown> }): Failure`.
- Produces (`model.ts`): `AGREEMENT_VERSION = "2.1"`; `STEPS: StepMeta[]` (`{ key, name, title, sub }`); `stepIndex(key)`; `isLocked(status)`; `stepDone(v, key)`; `stepHint(v, key)`; `reachable(v, key)`; `karyaHint(status)`; `banner(v): { tone: Tone; label: string; text: string } | null`; `Draft`; `initialDraft(v)`; `fieldsFor(step, draft)`; `validate(step, draft, v, agreed)`; `missingForSubmit(v, agreed): string[]`.

- [ ] **Step 1: Write the failing tests**

`src/features/verification/errors.test.ts`:

```ts
import { toFailure } from "./errors";

const e = (status: number, code: string | null, message: string, details: Record<string, unknown> = {}) => ({ status, code, message, details });

it.each([
  ["pan must match ^[A-Z]{5}[0-9]{4}[A-Z]$", "pan"],
  ["gstin must match ^[0-9]{2}[A-Z0-9]{13}$", "gstin"],
  ["phone must be a valid Indian mobile or E.164 number", "phone"],
  ["country must be a 2-letter code", "country"],
  ["legalName must not be blank", "legalName"],
  ["displayName must be at most 100 characters", "displayName"],
  ["city must not be blank", "city"],
  ["entityType must be one of [INDIVIDUAL]", "entityType"],
  ["agreementVersion must be 2.1", "agreementVersion"],
])("puts the INVALID_FIELD '%s' under %s in plain words", (message, field) => {
  const f = toFailure(e(400, "INVALID_FIELD", message));
  expect(f.field).toBe(field);
  expect(f.text).not.toMatch(/must match|\^|\[/);
});

it("falls back to the server message when the invalid field is unknown", () => {
  expect(toFailure(e(400, "INVALID_FIELD", "request body is malformed"))).toEqual({ text: "request body is malformed" });
});

it("names the missing steps and treats the PAN image as part of Tax IDs", () => {
  const f = toFailure(e(400, "VERIFICATION_INCOMPLETE", "m", { missingSteps: ["PHONE", "PAN_DOCUMENT"] }));
  expect(f.text).toBe("Finish these first: Phone, PAN card image.");
  expect(f.missingSteps).toEqual(["PHONE", "TAX_IDS"]);
});

it("gives one plain sentence for locked, too large and wrong type", () => {
  expect(toFailure(e(409, "VERIFICATION_LOCKED", "x")).text).toMatch(/reviewing/);
  expect(toFailure(e(413, "DOCUMENT_TOO_LARGE", "x")).text).toMatch(/5 MB/);
  expect(toFailure(e(413, null, "Request failed (413)")).text).toMatch(/5 MB/);
  expect(toFailure(e(415, "UNSUPPORTED_DOCUMENT_TYPE", "x")).text).toMatch(/JPG, PNG or PDF/);
  expect(toFailure(e(415, null, "Request failed (415)")).text).toMatch(/JPG, PNG or PDF/);
});

it("passes any other error message through", () => {
  expect(toFailure(e(500, null, "Request failed (500)")).text).toBe("Request failed (500)");
});
```

`src/features/verification/model.test.ts`:

```ts
import { banner, fieldsFor, initialDraft, isLocked, karyaHint, missingForSubmit, reachable, stepDone, validate } from "./model";
import { view } from "./fixtures";

const complete = view({
  currentStep: "AGREEMENT", entityType: "INDIVIDUAL", legalName: "Asha Kulkarni", displayName: "Asha Kulkarni", country: "IN", city: "Pune",
  panMasked: "234L", panDocumentUploaded: true, phoneMasked: "2208",
});

it("normalises what is typed before it is sent", () => {
  const d = { ...initialDraft(view()), pan: " abcde1234f ", gstin: "22abcde1234f1z5", phone: "+91 99200-12208", legalName: " Asha ", displayName: "Asha", city: " Pune " };
  expect(fieldsFor("TAX_IDS", d)).toEqual({ pan: "ABCDE1234F", gstin: "22ABCDE1234F1Z5" });
  expect(fieldsFor("PHONE", d)).toEqual({ phone: "+919920012208" });
  expect(fieldsFor("ABOUT", d)).toEqual({ entityType: "INDIVIDUAL", legalName: "Asha", displayName: "Asha", country: "IN", city: "Pune" });
  expect(fieldsFor("AGREEMENT", d)).toEqual({ agreementVersion: "2.1" });
});

it("sends nothing for a tax or phone step the user left alone", () => {
  expect(fieldsFor("TAX_IDS", initialDraft(complete))).toEqual({});
  expect(fieldsFor("PHONE", initialDraft(complete))).toEqual({});
});

it("asks for what is missing, and not for what is already saved", () => {
  const blank = initialDraft(view());
  expect(Object.keys(validate("ABOUT", blank, view(), false)).sort()).toEqual(["city", "displayName", "legalName"]);
  expect(validate("TAX_IDS", blank, view(), false).pan).toBeDefined();
  expect(validate("TAX_IDS", blank, complete, false)).toEqual({});
  expect(validate("PHONE", blank, view(), false).phone).toBeDefined();
  expect(validate("PHONE", blank, complete, false)).toEqual({});
  expect(validate("AGREEMENT", blank, complete, false).agreementVersion).toBeDefined();
  expect(validate("AGREEMENT", blank, complete, true)).toEqual({});
});

it("lists what still blocks the submit, using the step names", () => {
  expect(missingForSubmit(view(), false)).toEqual(["About you", "Tax IDs", "PAN card image", "Phone", "Developer agreement"]);
  expect(missingForSubmit(complete, false)).toEqual(["Developer agreement"]);
  expect(missingForSubmit(complete, true)).toEqual([]);
});

it("derives step state from the saved view and gates the steps the user has not reached", () => {
  expect(stepDone(complete, "ABOUT")).toBe(true);
  expect(stepDone(view(), "TAX_IDS")).toBe(false);
  expect(reachable(view({ currentStep: "TAX_IDS" }), "TAX_IDS")).toBe(true);
  expect(reachable(view({ currentStep: "TAX_IDS" }), "PHONE")).toBe(false);
  expect(reachable(view({ status: "PENDING" }), "AGREEMENT")).toBe(true);
});

it("locks everything except draft and fix-requested", () => {
  expect(isLocked("DRAFT")).toBe(false);
  expect(isLocked("FIX_REQUESTED")).toBe(false);
  expect(["PENDING", "VERIFIED", "REJECTED"].every((s) => isLocked(s as never))).toBe(true);
});

it("never prints undefined when the reviewer's note is missing", () => {
  const note = banner(view({ status: "FIX_REQUESTED" }));
  expect(note?.text).toMatch(/Karya asked for a change/);
  expect(note?.text).not.toMatch(/undefined|null/);
  expect(banner(view({ status: "FIX_REQUESTED", decisionNote: "PAN image is blurry" }))?.text).toBe("PAN image is blurry");
  expect(banner(view({ status: "REJECTED" }))?.tone).toBe("bad");
  expect(banner(view({ status: "DRAFT" }))).toBeNull();
});

it("says what Karya is doing for each status", () => {
  expect(karyaHint("DRAFT")).toBe("After you send it");
  expect(karyaHint("PENDING")).toMatch(/2 working days/);
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/features/verification`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement**

`src/features/verification/errors.ts`:

```ts
import type { DeveloperVerification, VerificationStep } from "@/lib/api/types";

export type FieldName = "legalName" | "displayName" | "country" | "city" | "entityType" | "pan" | "gstin" | "phone" | "agreementVersion";

export const FIELD_TEXT: Record<FieldName, string> = {
  legalName: "Enter your legal name, as on your PAN.",
  displayName: "Enter the name buyers should see on your listings.",
  country: "Choose your country.",
  city: "Enter your city.",
  entityType: "Choose how you're registered.",
  pan: "Enter your PAN, like ABCDE1234F.",
  gstin: "A GSTIN has 15 characters, like 22ABCDE1234F1Z5.",
  phone: "Enter a 10-digit Indian mobile number, or a number that starts with +.",
  agreementVersion: "Accept the agreement to continue.",
};

export interface Failure { text: string; field?: FieldName; missingSteps?: VerificationStep[] }
export type StepResult = { view: DeveloperVerification } | { failure: Failure };

const STEP_NAME: Record<string, string> = { ABOUT: "About you", TAX_IDS: "Tax IDs", PHONE: "Phone", AGREEMENT: "Developer agreement", PAN_DOCUMENT: "PAN card image" };
const FIELD_RE = /^(legalName|displayName|country|city|entityType|pan|gstin|phone|agreementVersion)\b/;
const TOO_LARGE = "That file is over 5 MB. Choose a smaller one.";
const WRONG_TYPE = "Use a JPG, PNG or PDF.";

// The backend's INVALID_FIELD carries no field name, only a message that starts with it.
export function toFailure(e: { status: number; code: string | null; message: string; details?: Record<string, unknown> }): Failure {
  if (e.status === 413 || e.code === "DOCUMENT_TOO_LARGE") return { text: TOO_LARGE };
  if (e.status === 415 || e.code === "UNSUPPORTED_DOCUMENT_TYPE") return { text: WRONG_TYPE };
  if (e.code === "VERIFICATION_LOCKED") return { text: "Karya is reviewing this, so it can't be changed right now." };
  if (e.code === "VERIFICATION_INCOMPLETE") {
    const raw = e.details?.missingSteps;
    const missing = Array.isArray(raw) ? (raw as string[]) : [];
    const steps = Array.from(new Set(missing.map((m) => (m === "PAN_DOCUMENT" ? "TAX_IDS" : m)))) as VerificationStep[];
    return { text: `Finish these first: ${missing.map((m) => STEP_NAME[m] ?? m).join(", ")}.`, missingSteps: steps };
  }
  if (e.code === "INVALID_FIELD") {
    const field = FIELD_RE.exec(e.message)?.[1] as FieldName | undefined;
    if (field) return { text: FIELD_TEXT[field], field };
  }
  return { text: e.message };
}

export const TOO_LARGE_TEXT = TOO_LARGE;
```

`src/features/verification/model.ts`:

```ts
import type { DeveloperVerification, VerificationEntityType, VerificationStatus, VerificationStep } from "@/lib/api/types";
import type { Tone } from "@/ui/Pill";
import { FIELD_TEXT, type FieldName } from "./errors";

export const AGREEMENT_VERSION = "2.1";

export interface StepMeta { key: VerificationStep; name: string; title: string; sub: string }
export const STEPS: StepMeta[] = [
  { key: "ABOUT", name: "About you", title: "About you", sub: "This is who buyers see as the publisher of your agents." },
  { key: "TAX_IDS", name: "Tax IDs", title: "Tax IDs", sub: "Karya checks these against government records. We store them encrypted and show only the last few characters." },
  { key: "PHONE", name: "Phone", title: "Phone", sub: "A person at Karya confirms it. Buyers never see it." },
  { key: "AGREEMENT", name: "Developer agreement", title: "Developer agreement", sub: "Version 2.1. The main points in plain words:" },
];

export const stepIndex = (key: VerificationStep) => STEPS.findIndex((s) => s.key === key);
export const isLocked = (status: VerificationStatus) => status !== "DRAFT" && status !== "FIX_REQUESTED";

export function stepDone(v: DeveloperVerification, key: VerificationStep): boolean {
  switch (key) {
    case "ABOUT": return !!(v.legalName && v.displayName && v.country && v.city && v.entityType);
    case "TAX_IDS": return !!v.panMasked;
    case "PHONE": return !!v.phoneMasked;
    case "AGREEMENT": return !!v.agreementAcceptedAt;
  }
}

export function stepHint(v: DeveloperVerification, key: VerificationStep): string {
  switch (key) {
    case "ABOUT": {
      const kind = v.entityType === "INDIVIDUAL" ? "individual" : v.entityType ? "company" : null;
      return v.legalName ? [v.legalName, kind].filter(Boolean).join(", ") : "Who you are";
    }
    case "TAX_IDS": return "PAN, GSTIN if any";
    case "PHONE": return "Mobile number";
    case "AGREEMENT": return `Version ${AGREEMENT_VERSION}`;
  }
}

// A step is open once the backend has moved past it; a locked verification can be read end to end.
export const reachable = (v: DeveloperVerification, key: VerificationStep) => isLocked(v.status) || stepIndex(key) <= stepIndex(v.currentStep);

export function karyaHint(status: VerificationStatus): string {
  switch (status) {
    case "PENDING": return "Waiting, usually 2 working days";
    case "VERIFIED": return "Done";
    case "FIX_REQUESTED": return "Asked for a change";
    case "REJECTED": return "Not approved";
    default: return "After you send it";
  }
}

export function banner(v: DeveloperVerification): { tone: Tone; label: string; text: string } | null {
  switch (v.status) {
    case "PENDING": return { tone: "info", label: "In review", text: "Sent to Karya. Karya usually replies within 2 working days. Keep building meanwhile; your sandbox and test files work exactly as before." };
    case "VERIFIED": return { tone: "ok", label: "Verified", text: "You're verified. Buyers see your legal name, never your tax IDs or phone." };
    case "FIX_REQUESTED": return { tone: "warn", label: "Changes needed", text: v.decisionNote || "Karya asked for a change. Update what's needed and send it again." };
    case "REJECTED": return { tone: "bad", label: "Not approved", text: v.decisionNote || "Karya couldn't verify this account." };
    default: return null;
  }
}

// What the user has typed. The raw PAN, GSTIN and phone live here only until the step is saved.
export interface Draft {
  entityType: VerificationEntityType; legalName: string; displayName: string; country: string; city: string;
  pan: string; gstin: string; phone: string; editPan: boolean; editGstin: boolean; editPhone: boolean;
}

export const initialDraft = (v: DeveloperVerification): Draft => ({
  entityType: v.entityType ?? "INDIVIDUAL", legalName: v.legalName ?? "", displayName: v.displayName ?? "", country: v.country ?? "IN", city: v.city ?? "",
  pan: "", gstin: "", phone: "", editPan: false, editGstin: false, editPhone: false,
});

// The backend's patterns reject lowercase and spaces, so tidy what people type the way the mockup shows it.
export function fieldsFor(step: VerificationStep, d: Draft): Record<string, string> {
  switch (step) {
    case "ABOUT": return { entityType: d.entityType, legalName: d.legalName.trim(), displayName: d.displayName.trim(), country: d.country, city: d.city.trim() };
    case "TAX_IDS": {
      const out: Record<string, string> = {};
      if (d.pan.trim()) out.pan = d.pan.trim().toUpperCase();
      if (d.gstin.trim()) out.gstin = d.gstin.trim().toUpperCase();
      return out;
    }
    case "PHONE": return d.phone.trim() ? { phone: d.phone.replace(/[\s-]/g, "") } : {};
    case "AGREEMENT": return { agreementVersion: AGREEMENT_VERSION };
  }
}

export function validate(step: VerificationStep, d: Draft, v: DeveloperVerification, agreed: boolean): Partial<Record<FieldName, string>> {
  const out: Partial<Record<FieldName, string>> = {};
  const need = (f: FieldName, value: string) => { if (!value.trim()) out[f] = FIELD_TEXT[f]; };
  switch (step) {
    case "ABOUT": need("legalName", d.legalName); need("displayName", d.displayName); need("city", d.city); break;
    case "TAX_IDS": if (!d.pan.trim() && !v.panMasked) out.pan = FIELD_TEXT.pan; break;
    case "PHONE": if (!d.phone.trim() && !v.phoneMasked) out.phone = FIELD_TEXT.phone; break;
    case "AGREEMENT": if (!agreed) out.agreementVersion = FIELD_TEXT.agreementVersion; break;
  }
  return out;
}

export function missingForSubmit(v: DeveloperVerification, agreed: boolean): string[] {
  const out: string[] = [];
  if (!stepDone(v, "ABOUT")) out.push("About you");
  if (!v.panMasked) out.push("Tax IDs");
  if (!v.panDocumentUploaded) out.push("PAN card image");
  if (!v.phoneMasked) out.push("Phone");
  if (!agreed) out.push("Developer agreement");
  return out;
}
```

Also create the shared test fixture `src/features/verification/fixtures.ts` (it is imported by `model.test.ts` here and by `VerificationView.test.tsx` in Task 6; never import one test file from another, because the importer would re-run the other's tests):

```ts
import type { DeveloperVerification } from "@/lib/api/types";

export const view = (over: Partial<DeveloperVerification> = {}): DeveloperVerification => ({
  status: "DRAFT", currentStep: "ABOUT", entityType: null, legalName: null, displayName: null, country: null, city: null,
  panMasked: null, gstinMasked: null, panDocumentUploaded: false, phoneMasked: null, agreementVersion: null,
  agreementAcceptedAt: null, resubmissionCount: 0, submittedAt: null, ...over,
});
```

- [ ] **Step 4: Run them to verify they pass**

Run: `npx vitest run src/features/verification && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/features/verification
git commit -m "feat(frontend): verification model, field validation and error mapping"
```

---

### Task 6: Get verified screen

**Files:**
- Create: `src/app/(app)/developer/verification/actions.ts`, `actions.test.ts`, `page.tsx`
- Create: `src/features/verification/StepForms.tsx`, `VerificationView.tsx`, `verification.module.css`, `VerificationView.test.tsx`
- Modify: `next.config.mjs`
- Create: `src/next-config.test.ts`

**Interfaces:**
- Consumes: Task 2 wrappers; Task 4 `Stepper`, `Segmented`, `KeyValue`; Task 5 `model.ts` and `errors.ts`; existing `Field`, `Button`, `Pill`, `Dropzone`, `Icon`, `ErrorState`.
- Produces: `saveStepAction(step, fields): Promise<StepResult>`, `uploadPanAction(fd): Promise<StepResult>`, `submitAction(): Promise<StepResult>`; `<VerificationView initial={DeveloperVerification} />`.

- [ ] **Step 1: Write the failing tests**

`src/app/(app)/developer/verification/actions.test.ts`:

```ts
import { vi } from "vitest";

const patch = vi.hoisted(() => vi.fn());
const upload = vi.hoisted(() => vi.fn());
const submit = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/verification", () => ({ patchVerification: patch, uploadPanDocument: upload, submitVerification: submit }));
import { ApiError } from "@/lib/api/http";
import { saveStepAction, submitAction, uploadPanAction } from "./actions";

beforeEach(() => { patch.mockReset(); upload.mockReset(); submit.mockReset(); });

it("returns the saved view", async () => {
  patch.mockResolvedValue({ status: "DRAFT" });
  expect(await saveStepAction("PHONE", { phone: "9920012208" })).toEqual({ view: { status: "DRAFT" } });
  expect(patch).toHaveBeenCalledWith("PHONE", { phone: "9920012208" });
});

it("turns an INVALID_FIELD into a failure under that field", async () => {
  patch.mockRejectedValue(new ApiError(400, "INVALID_FIELD", "pan must match ^[A-Z]{5}[0-9]{4}[A-Z]$"));
  expect(await saveStepAction("TAX_IDS", { pan: "x" })).toEqual({ failure: { text: "Enter your PAN, like ABCDE1234F.", field: "pan" } });
});

it("turns a 413 on the upload into one sentence and an incomplete submit into the missing steps", async () => {
  upload.mockRejectedValue(new ApiError(413, null, "Request failed (413)"));
  expect(await uploadPanAction(new FormData())).toEqual({ failure: { text: "That file is over 5 MB. Choose a smaller one." } });
  submit.mockRejectedValue(new ApiError(400, "VERIFICATION_INCOMPLETE", "m", { missingSteps: ["PHONE"] }));
  expect(await submitAction()).toEqual({ failure: { text: "Finish these first: Phone.", missingSteps: ["PHONE"] } });
});

it("rethrows anything that is not an ApiError", async () => {
  patch.mockRejectedValue(new Error("boom"));
  await expect(saveStepAction("ABOUT", {})).rejects.toThrow("boom");
});
```

`src/next-config.test.ts`:

```ts
import { readFileSync } from "fs";
import path from "path";

// The PAN image can be 5 MB; Next's default server-action body limit is 1 MB.
it("raises the server action body limit above the 5 MB PAN image", () => {
  const text = readFileSync(path.resolve(__dirname, "../next.config.mjs"), "utf8");
  expect(text).toMatch(/bodySizeLimit:\s*"6mb"/);
});
```

`src/features/verification/VerificationView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const save = vi.hoisted(() => vi.fn());
const upload = vi.hoisted(() => vi.fn());
const submit = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/verification/actions", () => ({
  saveStepAction: (...a: unknown[]) => save(...a),
  uploadPanAction: (...a: unknown[]) => upload(...a),
  submitAction: (...a: unknown[]) => submit(...a),
}));
import { VerificationView } from "./VerificationView";
import { FIELD_TEXT } from "./errors";
import { view } from "./fixtures";

const complete = view({
  currentStep: "AGREEMENT", entityType: "INDIVIDUAL", legalName: "Asha Kulkarni", displayName: "Asha Kulkarni", country: "IN", city: "Pune",
  panMasked: "234L", panDocumentUploaded: true, phoneMasked: "2208",
});
const pick = (name: RegExp) => userEvent.click(screen.getByRole("button", { name }));
const cont = () => userEvent.click(screen.getByRole("button", { name: /^(continue|submit for review)$/i }));

beforeEach(() => { save.mockReset(); upload.mockReset(); submit.mockReset(); });

it("shows done, current and unreachable steps from the saved state", () => {
  render(<VerificationView initial={view({ ...complete, currentStep: "PHONE", phoneMasked: null })} />);
  expect(screen.getByRole("button", { name: /About you/ })).toHaveAttribute("data-state", "done");
  expect(screen.getByRole("button", { name: /Tax IDs/ })).toHaveAttribute("data-state", "done");
  expect(screen.getByRole("button", { name: /Phone/ })).toHaveAttribute("aria-current", "step");
  expect(screen.getByRole("button", { name: /Developer agreement/ })).toBeDisabled();
  expect(screen.getByText("Karya checks it")).toBeInTheDocument();
});

it("asks for each missing About-you field and saves nothing", async () => {
  render(<VerificationView initial={view()} />);
  await cont();
  expect(screen.getByText(FIELD_TEXT.legalName)).toBeInTheDocument();
  expect(screen.getByText(FIELD_TEXT.displayName)).toBeInTheDocument();
  expect(screen.getByText(FIELD_TEXT.city)).toBeInTheDocument();
  expect(save).not.toHaveBeenCalled();
});

it("saves About you with a company type and moves on to Tax IDs", async () => {
  save.mockResolvedValue({ view: view({ currentStep: "TAX_IDS", entityType: "LLP", legalName: "Asha LLP", displayName: "Asha", country: "IN", city: "Pune" }) });
  render(<VerificationView initial={view()} />);
  await userEvent.click(screen.getByRole("button", { name: "A company" }));
  await userEvent.selectOptions(screen.getByLabelText("Type of company"), "LLP");
  await userEvent.type(screen.getByLabelText("Legal name, as on your PAN"), "Asha LLP");
  await userEvent.type(screen.getByLabelText("Name shown on listings"), "Asha");
  await userEvent.type(screen.getByLabelText("City"), "Pune");
  await cont();
  expect(save).toHaveBeenCalledWith("ABOUT", { entityType: "LLP", legalName: "Asha LLP", displayName: "Asha", country: "IN", city: "Pune" });
  expect(await screen.findByRole("heading", { name: "Tax IDs" })).toBeInTheDocument();
});

it("shows saved IDs masked, never as an input, and sends an upper-cased replacement", async () => {
  save.mockResolvedValue({ view: complete });
  render(<VerificationView initial={complete} />);
  await pick(/Tax IDs/);
  expect(screen.getByText("•••• 234L")).toBeInTheDocument();
  expect(screen.queryByLabelText("PAN")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Replace PAN" }));
  await userEvent.type(screen.getByLabelText("PAN"), "abcde1234f");
  await cont();
  expect(save).toHaveBeenCalledWith("TAX_IDS", { pan: "ABCDE1234F" });
  await screen.findByRole("heading", { name: "Phone" });
  await pick(/Tax IDs/);
  expect(screen.queryByDisplayValue("ABCDE1234F")).not.toBeInTheDocument();
  expect(screen.getByText("•••• 234L")).toBeInTheDocument();
});

it("strips spaces from the phone number it sends", async () => {
  save.mockResolvedValue({ view: complete });
  render(<VerificationView initial={view({ ...complete, currentStep: "PHONE", phoneMasked: null })} />);
  await userEvent.type(screen.getByLabelText("Mobile number"), "+91 99200 12208");
  await cont();
  expect(save).toHaveBeenCalledWith("PHONE", { phone: "+919920012208" });
});

it("puts a server field error under the input", async () => {
  save.mockResolvedValue({ failure: { text: FIELD_TEXT.pan, field: "pan" } });
  render(<VerificationView initial={view({ currentStep: "TAX_IDS", entityType: "INDIVIDUAL", legalName: "A", displayName: "A", country: "IN", city: "P" })} />);
  await userEvent.type(screen.getByLabelText("PAN"), "BADPAN");
  await cont();
  expect(await screen.findByText(FIELD_TEXT.pan)).toBeInTheDocument();
});

it("uploads the PAN image, and refuses one over 5 MB without calling the server", async () => {
  upload.mockResolvedValue({ view: complete });
  render(<VerificationView initial={view({ ...complete, panDocumentUploaded: false, currentStep: "TAX_IDS" })} />);
  await userEvent.upload(screen.getByLabelText(/PAN card image/), new File(["x"], "pan.pdf", { type: "application/pdf" }));
  expect(upload).toHaveBeenCalledTimes(1);
  expect((upload.mock.calls[0][0] as FormData).get("file")).toBeInstanceOf(File);
  await userEvent.upload(screen.getByLabelText(/PAN card image/), new File([new ArrayBuffer(5 * 1024 * 1024 + 1)], "big.pdf", { type: "application/pdf" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("over 5 MB");
  expect(upload).toHaveBeenCalledTimes(1);
});

it("does not save twice on a double click", async () => {
  let resolve!: (v: unknown) => void;
  save.mockReturnValue(new Promise((r) => { resolve = r; }));
  render(<VerificationView initial={complete} />);
  await pick(/Phone/);
  await userEvent.dblClick(screen.getByRole("button", { name: /^continue$/i }));
  expect(save).toHaveBeenCalledTimes(1);
  resolve({ view: complete });
  expect(await screen.findByRole("heading", { name: "Developer agreement" })).toBeInTheDocument();
});

it("blocks the submit and says what is still needed", async () => {
  render(<VerificationView initial={view({ ...complete, phoneMasked: null })} />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  expect(screen.getByText(/Still needed: Phone, Developer agreement\./)).toBeInTheDocument();
});

it("saves the agreement and then submits for review", async () => {
  save.mockResolvedValue({ view: view({ ...complete, agreementAcceptedAt: "2026-09-30T00:00:00Z" }) });
  submit.mockResolvedValue({ view: view({ ...complete, status: "PENDING", agreementAcceptedAt: "2026-09-30T00:00:00Z" }) });
  render(<VerificationView initial={complete} />);
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeDisabled();
  await userEvent.click(screen.getByRole("checkbox", { name: /accept it/ }));
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(save).toHaveBeenCalledWith("AGREEMENT", { agreementVersion: "2.1" });
  expect(submit).toHaveBeenCalledTimes(1);
  expect(await screen.findByText(/Sent to Karya/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /submit for review/i })).not.toBeInTheDocument();
});

it("jumps to the first missing step when the server says the submit is incomplete", async () => {
  save.mockResolvedValue({ view: view({ ...complete, agreementAcceptedAt: "2026-09-30T00:00:00Z" }) });
  submit.mockResolvedValue({ failure: { text: "Finish these first: Phone.", missingSteps: ["PHONE"] } });
  render(<VerificationView initial={complete} />);
  await userEvent.click(screen.getByRole("checkbox", { name: /accept it/ }));
  await userEvent.click(screen.getByRole("button", { name: "Submit for review" }));
  expect(await screen.findByRole("heading", { name: "Phone" })).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("Finish these first: Phone.");
});

it.each(["PENDING", "VERIFIED", "REJECTED"] as const)("shows %s read-only", (status) => {
  render(<VerificationView initial={view({ ...complete, status, agreementAcceptedAt: "2026-09-30T00:00:00Z" })} />);
  expect(screen.getByLabelText("Legal name, as on your PAN")).toBeDisabled();
  expect(screen.queryByRole("button", { name: /continue|submit for review/i })).not.toBeInTheDocument();
  expect(screen.getByTestId("banner")).toBeInTheDocument();
});

it("shows the reviewer's note when there is one, and a plain sentence when there is not", () => {
  const { unmount } = render(<VerificationView initial={view({ ...complete, status: "FIX_REQUESTED", decisionNote: "PAN image is blurry" })} />);
  expect(screen.getByTestId("banner")).toHaveTextContent("PAN image is blurry");
  expect(screen.getByRole("button", { name: "Submit for review" })).toBeInTheDocument();
  unmount();
  render(<VerificationView initial={view({ ...complete, status: "FIX_REQUESTED" })} />);
  expect(screen.getByTestId("banner")).toHaveTextContent("Karya asked for a change");
  expect(screen.getByTestId("banner")).not.toHaveTextContent("undefined");
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run "src/app/(app)/developer" src/features/verification/VerificationView.test.tsx src/next-config.test.ts`
Expected: FAIL (modules not found; config test fails).

- [ ] **Step 3: Implement**

`next.config.mjs`:

```js
/** @type {import('next').NextConfig} */
const nextConfig = {
  // Server actions default to a 1 MB body; the PAN image can be 5 MB.
  experimental: { serverActions: { bodySizeLimit: "6mb" } },
};

export default nextConfig;
```

`src/app/(app)/developer/verification/actions.ts`:

```ts
"use server";
import { ApiError } from "@/lib/api/http";
import { patchVerification, submitVerification, uploadPanDocument } from "@/lib/api/verification";
import { toFailure, type StepResult } from "@/features/verification/errors";
import type { DeveloperVerification, VerificationStep } from "@/lib/api/types";

async function run(fn: () => Promise<DeveloperVerification>): Promise<StepResult> {
  try {
    return { view: await fn() };
  } catch (e) {
    if (e instanceof ApiError) return { failure: toFailure(e) };
    throw e;
  }
}

export async function saveStepAction(step: VerificationStep, fields: Record<string, string>): Promise<StepResult> {
  return run(() => patchVerification(step, fields));
}

export async function uploadPanAction(fd: FormData): Promise<StepResult> {
  return run(() => uploadPanDocument(fd));
}

export async function submitAction(): Promise<StepResult> {
  return run(() => submitVerification());
}
```

`src/app/(app)/developer/verification/page.tsx`:

```tsx
import { getVerification } from "@/lib/api/verification";
import { ApiError } from "@/lib/api/http";
import { VerificationView } from "@/features/verification/VerificationView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const initial = await getVerification();
    return <VerificationView initial={initial} />;
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
```

`src/features/verification/StepForms.tsx`:

```tsx
"use client";
import styles from "./verification.module.css";
import { Dropzone } from "@/ui/Dropzone";
import { Field } from "@/ui/Field";
import { Icon } from "@/ui/Icon";
import { KeyValue } from "@/ui/KeyValue";
import { Segmented } from "@/ui/Segmented";
import type { DeveloperVerification, VerificationEntityType } from "@/lib/api/types";
import type { FieldName } from "./errors";
import type { Draft } from "./model";

interface FormProps {
  view: DeveloperVerification;
  draft: Draft;
  set: (patch: Partial<Draft>) => void;
  errors: Partial<Record<FieldName, string>>;
  disabled: boolean;
}

const COMPANY_TYPES: { value: VerificationEntityType; label: string }[] = [
  { value: "PRIVATE_LIMITED", label: "Private limited company" },
  { value: "LLP", label: "LLP" },
  { value: "PARTNERSHIP", label: "Partnership" },
  { value: "OTHER", label: "Other" },
];

export function AboutForm({ draft, set, errors, disabled }: FormProps) {
  const company = draft.entityType !== "INDIVIDUAL";
  return (
    <>
      <div className={styles.fieldBox}>
        <span className={styles.lab}>You are</span>
        <Segmented
          label="Entity type"
          value={company ? "company" : "individual"}
          disabled={disabled}
          options={[{ value: "individual", label: "An individual" }, { value: "company", label: "A company" }]}
          onChange={(v) => set({ entityType: v === "individual" ? "INDIVIDUAL" : company ? draft.entityType : "PRIVATE_LIMITED" })}
        />
      </div>
      {company && (
        <div className={styles.fieldBox}>
          <label htmlFor="company-type">Type of company</label>
          <select id="company-type" className={styles.select} value={draft.entityType} disabled={disabled} onChange={(e) => set({ entityType: e.target.value as VerificationEntityType })}>
            {COMPANY_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </div>
      )}
      <Field label="Legal name, as on your PAN" value={draft.legalName} error={errors.legalName} disabled={disabled} maxLength={200} onChange={(e) => set({ legalName: e.target.value })} />
      <div className={styles.fieldBox}>
        <label htmlFor="country">Country</label>
        <select id="country" className={styles.select} value={draft.country} disabled={disabled} onChange={(e) => set({ country: e.target.value })}>
          <option value="IN">India</option>
        </select>
        {errors.country && <span className={styles.err}>{errors.country}</span>}
      </div>
      <Field label="Name shown on listings" value={draft.displayName} error={errors.displayName} disabled={disabled} maxLength={100} onChange={(e) => set({ displayName: e.target.value })} />
      <Field label="City" value={draft.city} error={errors.city} disabled={disabled} maxLength={100} onChange={(e) => set({ city: e.target.value })} />
    </>
  );
}

// Shows a saved ID as its masked last characters with a Replace button; the raw value is never available to show.
function Saved({ label, masked, editing, onEdit, disabled, children }: { label: string; masked: string | null; editing: boolean; onEdit: () => void; disabled: boolean; children: React.ReactNode }) {
  if (masked && !editing) {
    return (
      <KeyValue label={label}>
        <span className="mono">•••• {masked}</span>{" "}
        <button type="button" className={styles.link} disabled={disabled} aria-label={`Replace ${label}`} onClick={onEdit}>Replace</button>
      </KeyValue>
    );
  }
  return <>{children}</>;
}

export function TaxForm({ view, draft, set, errors, disabled, onPanFile }: FormProps & { onPanFile: (f: File) => void }) {
  return (
    <>
      <Saved label="PAN" masked={view.panMasked} editing={draft.editPan} disabled={disabled} onEdit={() => set({ editPan: true })}>
        <Field label="PAN" className="mono" value={draft.pan} error={errors.pan} disabled={disabled} maxLength={10} autoComplete="off" onChange={(e) => set({ pan: e.target.value })} />
      </Saved>
      <Saved label="GSTIN" masked={view.gstinMasked} editing={draft.editGstin} disabled={disabled} onEdit={() => set({ editGstin: true })}>
        <Field label="GSTIN, if you have one" className="mono" value={draft.gstin} error={errors.gstin} disabled={disabled} maxLength={15} autoComplete="off" placeholder="Not needed for individuals under the threshold" onChange={(e) => set({ gstin: e.target.value })} />
      </Saved>
      {view.panDocumentUploaded && <KeyValue label="PAN card image">Uploaded</KeyValue>}
      <Dropzone label={view.panDocumentUploaded ? "Replace PAN card image" : "PAN card image"} disabled={disabled} onFile={onPanFile} />
      <p className={styles.sub}>A clear photo or scan, JPG, PNG or PDF, up to 5 MB.</p>
    </>
  );
}

export function PhoneForm({ view, draft, set, errors, disabled }: FormProps) {
  return (
    <Saved label="Mobile number" masked={view.phoneMasked} editing={draft.editPhone} disabled={disabled} onEdit={() => set({ editPhone: true })}>
      <Field label="Mobile number" type="tel" autoComplete="tel" value={draft.phone} error={errors.phone} disabled={disabled} onChange={(e) => set({ phone: e.target.value })} />
    </Saved>
  );
}

const POINTS = [
  "Your agents only touch what their manifest declares, and buyers see exactly that.",
  "Karya keeps 20% of what buyers pay. AI usage through Karya is charged at cost.",
  "Buyer data is never yours to keep, copy or train on.",
  "Karya can pause a version that misbehaves, and decides buyer disputes.",
];

export function AgreementForm({ agreed, onAgreed, error, disabled }: { agreed: boolean; onAgreed: (v: boolean) => void; error?: string; disabled: boolean }) {
  return (
    <>
      <div>
        {POINTS.map((p) => (
          <div key={p} className={styles.bul}><Icon name="check" /><span>{p}</span></div>
        ))}
      </div>
      <label className={styles.chk}>
        <input type="checkbox" checked={agreed} disabled={disabled} onChange={(e) => onAgreed(e.target.checked)} />
        I've read the full Developer Distribution Agreement 2.1 and accept it
      </label>
      {error && <span className={styles.err}>{error}</span>}
    </>
  );
}
```

`src/features/verification/VerificationView.tsx`:

```tsx
"use client";
import { useRef, useState } from "react";
import styles from "./verification.module.css";
import { Button } from "@/ui/Button";
import { Pill } from "@/ui/Pill";
import { Stepper, type StepperItem } from "@/ui/Stepper";
import { saveStepAction, submitAction, uploadPanAction } from "@/app/(app)/developer/verification/actions";
import { AboutForm, AgreementForm, PhoneForm, TaxForm } from "./StepForms";
import { TOO_LARGE_TEXT, type Failure, type FieldName, type StepResult } from "./errors";
import { STEPS, banner, fieldsFor, initialDraft, isLocked, karyaHint, missingForSubmit, reachable, stepDone, stepHint, stepIndex, validate, type Draft } from "./model";
import type { DeveloperVerification, VerificationStep } from "@/lib/api/types";

const MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

export function VerificationView({ initial }: { initial: DeveloperVerification }) {
  const [view, setView] = useState(initial);
  const [stepKey, setStepKey] = useState<VerificationStep>(isLocked(initial.status) ? "ABOUT" : initial.currentStep);
  const [draft, setDraft] = useState<Draft>(() => initialDraft(initial));
  const [agreed, setAgreed] = useState(!!initial.agreementAcceptedAt);
  const [errors, setErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [alert, setAlert] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  const locked = isLocked(view.status);
  const last = stepKey === "AGREEMENT";
  const meta = STEPS[stepIndex(stepKey)];
  const missing = last ? missingForSubmit(view, agreed) : [];
  const note = banner(view);
  const disabled = locked || busy;
  const formProps = { view, draft, errors, disabled, set: (p: Partial<Draft>) => setDraft((d) => ({ ...d, ...p })) };

  // React 18 does not hold isPending across an async transition, so guard double clicks by hand.
  const run = async (fn: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try { await fn(); } finally { busyRef.current = false; setBusy(false); }
  };

  const fail = (f: Failure) => {
    if (f.field) setErrors({ [f.field]: f.text });
    else setAlert(f.text);
    if (f.missingSteps?.length) setStepKey(f.missingSteps[0]);
  };
  const apply = (res: StepResult): boolean => {
    if ("failure" in res) { fail(res.failure); return false; }
    setView(res.view);
    return true;
  };

  const onContinue = () => {
    const found = validate(stepKey, draft, view, agreed);
    setErrors(found);
    setAlert(null);
    if (Object.keys(found).length > 0) return;
    void run(async () => {
      if (!apply(await saveStepAction(stepKey, fieldsFor(stepKey, draft)))) return;
      setDraft((d) => ({ ...d, pan: "", gstin: "", phone: "", editPan: false, editGstin: false, editPhone: false }));
      if (!last) { setStepKey(STEPS[stepIndex(stepKey) + 1].key); return; }
      apply(await submitAction());
    });
  };

  const onPanFile = (file: File) => {
    setAlert(null);
    if (file.size > MAX_DOCUMENT_BYTES) { setAlert(TOO_LARGE_TEXT); return; }
    void run(async () => {
      const fd = new FormData();
      fd.set("file", file);
      apply(await uploadPanAction(fd));
    });
  };

  const items: StepperItem[] = STEPS.map((s) => ({
    key: s.key,
    name: s.name,
    hint: stepHint(view, s.key),
    state: s.key === stepKey ? "cur" : stepDone(view, s.key) ? "done" : "todo",
    disabled: !reachable(view, s.key),
  }));

  return (
    <div className={styles.wrap}>
      <section className={styles.panel} aria-label="Verification steps">
        <h1 className={styles.h}>Get verified</h1>
        <p className={styles.sub}>About 10 minutes. You can stop and come back.</p>
        <Stepper
          items={items}
          note={{ title: "Karya checks it", hint: karyaHint(view.status) }}
          onPick={(k) => { setErrors({}); setAlert(null); setStepKey(k as VerificationStep); }}
        />
      </section>
      <section className={`${styles.panel} ${styles.form}`}>
        {note && (
          <div className={styles.banner} data-testid="banner">
            <Pill tone={note.tone}>{note.label}</Pill>
            <span>{note.text}</span>
          </div>
        )}
        <div>
          <h2 className={styles.title}>{meta.title}</h2>
          <p className={styles.sub}>{meta.sub}</p>
        </div>
        {stepKey === "ABOUT" && <AboutForm {...formProps} />}
        {stepKey === "TAX_IDS" && <TaxForm {...formProps} onPanFile={onPanFile} />}
        {stepKey === "PHONE" && <PhoneForm {...formProps} />}
        {stepKey === "AGREEMENT" && <AgreementForm agreed={agreed} onAgreed={setAgreed} error={errors.agreementVersion} disabled={disabled} />}
        {alert && <p role="alert" className={styles.err}>{alert}</p>}
        {!locked && missing.length > 0 && <p className={styles.sub}>Still needed: {missing.join(", ")}.</p>}
        {!locked && (
          <div className={styles.footer}>
            <Button onClick={onContinue} loading={busy} disabled={missing.length > 0}>{last ? "Submit for review" : "Continue"}</Button>
          </div>
        )}
      </section>
    </div>
  );
}
```

`src/features/verification/verification.module.css`:

```css
.wrap { display: grid; grid-template-columns: 300px minmax(0, 608px); gap: 20px; align-items: start; }
.panel { background: var(--s); border: 1px solid var(--l); border-radius: 16px; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
.form { padding: 28px 32px; gap: 18px; }
.h { font-size: 14px; font-weight: 600; }
.sub { margin: 0; font-size: 12px; color: var(--ink3); }
.title { font-size: 24px; font-weight: 600; letter-spacing: -0.02em; margin-bottom: 4px; }
.fieldBox { display: flex; flex-direction: column; gap: 6px; }
.fieldBox label, .lab { font-size: 13px; font-weight: 600; color: var(--ink2); }
.select { font: inherit; min-height: 44px; padding: 0 12px; border: 1px solid var(--l); border-radius: 10px; background: var(--s); color: var(--ink); width: 100%; }
.err { color: var(--bad); font-size: 12px; margin: 0; }
.bul { display: grid; grid-template-columns: 18px minmax(0, 1fr); gap: 10px; font-size: 13.5px; line-height: 1.5; padding: 6px 0; }
.bul svg { width: 16px; height: 16px; color: var(--ok); margin-top: 2px; }
.chk { display: flex; align-items: flex-start; gap: 10px; font-size: 13px; line-height: 1.45; }
.chk input { width: 16px; height: 16px; accent-color: var(--b); margin: 2px 0 0; flex-shrink: 0; }
.link { font: inherit; font-size: 13px; color: var(--b); background: none; border: 0; padding: 0 4px; min-height: 44px; cursor: pointer; }
.banner { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border-radius: 12px; background: var(--sky); color: var(--ink2); font-size: 13px; line-height: 1.5; }
.footer { display: flex; justify-content: flex-end; margin-top: auto; }
@media (max-width: 899px) {
  .wrap { grid-template-columns: minmax(0, 1fr); }
  .form { padding: 20px; }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `npx vitest run "src/app/(app)/developer" src/features/verification src/next-config.test.ts && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add next.config.mjs src/next-config.test.ts "src/app/(app)/developer/verification" src/features/verification
git commit -m "feat(frontend): get verified screen with four steps, masked IDs and submit for review"
```

---

### Task 7: API keys screen

**Files:**
- Create: `src/app/(app)/developer/api-keys/actions.ts`, `page.tsx`
- Create: `src/features/apikeys/ApiKeysView.tsx`, `apikeys.module.css`, `ApiKeysView.test.tsx`

**Interfaces:**
- Consumes: Task 2 `listApiKeys`, `createApiKey`, `revokeApiKey`; Task 1 `formatDate`; existing `Button`, `Drawer`, `EmptyState`, `Field`, `Pill`, `ResponsiveTable`, `ErrorState`.
- Produces: `createKeyAction(name: string): Promise<{ key: CreatedApiKey } | { error: string }>`, `revokeKeyAction(id: string): Promise<{ error?: string }>`; `<ApiKeysView keys={DeveloperApiKey[]} />`.

- [ ] **Step 1: Write the failing test**

`src/features/apikeys/ApiKeysView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const create = vi.hoisted(() => vi.fn());
const revoke = vi.hoisted(() => vi.fn());
vi.mock("@/app/(app)/developer/api-keys/actions", () => ({ createKeyAction: (...a: unknown[]) => create(...a), revokeKeyAction: (...a: unknown[]) => revoke(...a) }));
import { ApiKeysView } from "./ApiKeysView";
import type { DeveloperApiKey } from "@/lib/api/types";

const key = (over: Partial<DeveloperApiKey> = {}): DeveloperApiKey => ({ id: "k1", name: "Laptop", prefix: "ask_live_ab12", createdAt: "2026-09-01T12:00:00Z", lastUsedAt: null, revokedAt: null, ...over });
const created = { id: "k2", name: "CI", prefix: "ask_live_cd34", key: "ask_live_cd34SECRETVALUE", createdAt: "2026-09-30T12:00:00Z" };

beforeEach(() => { create.mockReset(); revoke.mockReset(); localStorage.clear(); sessionStorage.clear(); });

it("points at karya login when there are no keys", () => {
  render(<ApiKeysView keys={[]} />);
  expect(screen.getByText(/karya login/)).toBeInTheDocument();
});

it("lists keys with prefix, last used and status, and offers revoke only on active ones", () => {
  render(<ApiKeysView keys={[key(), key({ id: "k3", name: "Old", revokedAt: "2026-09-10T00:00:00Z", lastUsedAt: "2026-09-05T00:00:00Z" })]} />);
  expect(screen.getByText("Laptop")).toBeInTheDocument();
  expect(screen.getByText("Never")).toBeInTheDocument();
  expect(screen.getByText("Active")).toBeInTheDocument();
  expect(screen.getByText("Revoked")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Revoke Laptop" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Revoke Old" })).not.toBeInTheDocument();
});

it("shows the new key once, keeps it out of storage, and clears it when dismissed", async () => {
  create.mockResolvedValue({ key: created });
  render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  expect(create).toHaveBeenCalledWith("CI");
  expect(await screen.findByText("ask_live_cd34SECRETVALUE")).toBeInTheDocument();
  expect(screen.getByText(/You won't see this again/)).toBeInTheDocument();
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
  await userEvent.click(screen.getByRole("button", { name: "Done" }));
  expect(screen.queryByText("ask_live_cd34SECRETVALUE")).not.toBeInTheDocument();
});

it("keeps the key visible when the list refreshes, and will not make a second key over it", async () => {
  create.mockResolvedValue({ key: created });
  const { rerender } = render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  await screen.findByText("ask_live_cd34SECRETVALUE");
  rerender(<ApiKeysView keys={[key({ id: "k2", name: "CI", prefix: "ask_live_cd34" })]} />);
  expect(screen.getByText("ask_live_cd34SECRETVALUE")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Create key" })).toBeDisabled();
});

it("does not create two keys on a double click", async () => {
  let resolve!: (v: unknown) => void;
  create.mockReturnValue(new Promise((r) => { resolve = r; }));
  render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.dblClick(screen.getByRole("button", { name: "Create key" }));
  expect(create).toHaveBeenCalledTimes(1);
  resolve({ key: created });
  await screen.findByText("ask_live_cd34SECRETVALUE");
});

it("does not create a key without a name", async () => {
  render(<ApiKeysView keys={[]} />);
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  expect(create).not.toHaveBeenCalled();
});

it("shows the backend's message when creating fails", async () => {
  create.mockResolvedValue({ error: "You have too many keys" });
  render(<ApiKeysView keys={[]} />);
  await userEvent.type(screen.getByLabelText("Key name"), "CI");
  await userEvent.click(screen.getByRole("button", { name: "Create key" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("You have too many keys");
});

it("copies the key to the clipboard", async () => {
  create.mockResolvedValue({ key: created });
  const user = userEvent.setup();
  render(<ApiKeysView keys={[]} />);
  await user.type(screen.getByLabelText("Key name"), "CI");
  await user.click(screen.getByRole("button", { name: "Create key" }));
  await user.click(await screen.findByRole("button", { name: "Copy" }));
  expect(await navigator.clipboard.readText()).toBe("ask_live_cd34SECRETVALUE");
  expect(screen.getByRole("button", { name: "Copied" })).toBeInTheDocument();
});

it("asks before revoking, then revokes by id", async () => {
  revoke.mockResolvedValue({});
  render(<ApiKeysView keys={[key()]} />);
  await userEvent.click(screen.getByRole("button", { name: "Revoke Laptop" }));
  const dialog = screen.getByRole("dialog");
  expect(revoke).not.toHaveBeenCalled();
  await userEvent.click(within(dialog).getByRole("button", { name: "Revoke key" }));
  expect(revoke).toHaveBeenCalledWith("k1");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

it("leaves the key alone when the revoke confirm is dismissed", async () => {
  render(<ApiKeysView keys={[key()]} />);
  await userEvent.click(screen.getByRole("button", { name: "Revoke Laptop" }));
  await userEvent.click(screen.getByRole("button", { name: "Close" }));
  expect(revoke).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run src/features/apikeys`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement**

`src/app/(app)/developer/api-keys/actions.ts`:

```ts
"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { createApiKey, revokeApiKey } from "@/lib/api/apikeys";
import type { CreatedApiKey } from "@/lib/api/types";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function createKeyAction(name: string): Promise<{ key: CreatedApiKey } | { error: string }> {
  try {
    const key = await createApiKey(name);
    revalidatePath("/developer/api-keys");
    return { key };
  } catch (e) { return fail(e); }
}

export async function revokeKeyAction(id: string): Promise<{ error?: string }> {
  try {
    await revokeApiKey(id);
    revalidatePath("/developer/api-keys");
    return {};
  } catch (e) { return fail(e); }
}
```

`src/app/(app)/developer/api-keys/page.tsx`:

```tsx
import { listApiKeys } from "@/lib/api/apikeys";
import { ApiError } from "@/lib/api/http";
import { ApiKeysView } from "@/features/apikeys/ApiKeysView";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const keys = await listApiKeys();
    return (
      <>
        <h1>API keys</h1>
        <p>A key lets the Karya CLI act as you. Treat it like a password.</p>
        <ApiKeysView keys={keys} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
```

`src/features/apikeys/ApiKeysView.tsx`:

```tsx
"use client";
import { useRef, useState } from "react";
import styles from "./apikeys.module.css";
import { Button } from "@/ui/Button";
import { Drawer } from "@/ui/Drawer";
import { EmptyState } from "@/ui/EmptyState";
import { Field } from "@/ui/Field";
import { Pill } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { createKeyAction, revokeKeyAction } from "@/app/(app)/developer/api-keys/actions";
import { formatDate } from "@/lib/format";
import type { CreatedApiKey, DeveloperApiKey } from "@/lib/api/types";

export function ApiKeysView({ keys }: { keys: DeveloperApiKey[] }) {
  const [name, setName] = useState("");
  // The full key is held here and nowhere else; it is gone once dismissed.
  const [revealed, setRevealed] = useState<CreatedApiKey | null>(null);
  const [copied, setCopied] = useState(false);
  const [confirm, setConfirm] = useState<DeveloperApiKey | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  // React 18 does not hold isPending across an async transition, so guard double clicks by hand.
  const run = async (fn: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    try { await fn(); } finally { busyRef.current = false; setBusy(false); }
  };

  const create = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || revealed) return;
    setError(null);
    void run(async () => {
      const res = await createKeyAction(name.trim());
      if ("error" in res) { setError(res.error); return; }
      setRevealed(res.key);
      setCopied(false);
      setName("");
    });
  };

  const copy = async () => {
    if (!revealed) return;
    try {
      await navigator.clipboard.writeText(revealed.key);
      setCopied(true);
    } catch {
      setError("Copy didn't work. Select the key and copy it by hand.");
    }
  };

  const revoke = () => {
    if (!confirm) return;
    setError(null);
    void run(async () => {
      const res = await revokeKeyAction(confirm.id);
      if (res.error) setError(res.error);
      else setConfirm(null);
    });
  };

  return (
    <div className={styles.wrap}>
      <form className={styles.create} onSubmit={create}>
        <Field label="Key name" value={name} maxLength={100} placeholder="For example, my laptop" disabled={!!revealed} onChange={(e) => setName(e.target.value)} />
        <Button type="submit" loading={busy} disabled={!!revealed}>Create key</Button>
      </form>
      {error && !confirm && <p role="alert" className={styles.err}>{error}</p>}
      {revealed && (
        <section className={styles.reveal} aria-label="New API key">
          <strong>Copy your key now</strong>
          <code className={`mono ${styles.key}`}>{revealed.key}</code>
          <p>You won&apos;t see this again.</p>
          <div className={styles.actions}>
            <Button variant="ghost" onClick={copy}>{copied ? "Copied" : "Copy"}</Button>
            <Button onClick={() => setRevealed(null)}>Done</Button>
          </div>
        </section>
      )}
      <ResponsiveTable
        rows={keys}
        rowKey={(k) => k.id}
        empty={<EmptyState title="No API keys yet" body="Run `karya login` in your terminal to sign the CLI in, or create a key above." />}
        columns={[
          { key: "name", header: "Name", render: (k) => <strong>{k.name}</strong> },
          { key: "prefix", header: "Key", render: (k) => <span className="mono">{k.prefix}…</span> },
          { key: "created", header: "Created", render: (k) => formatDate(k.createdAt) },
          { key: "used", header: "Last used", render: (k) => (k.lastUsedAt ? formatDate(k.lastUsedAt) : "Never") },
          { key: "status", header: "Status", render: (k) => (k.revokedAt ? <Pill tone="neutral">Revoked</Pill> : <Pill tone="ok">Active</Pill>) },
          { key: "go", header: "", render: (k) => (k.revokedAt ? null : <Button variant="ghost" aria-label={`Revoke ${k.name}`} onClick={() => { setError(null); setConfirm(k); }}>Revoke</Button>) },
        ]}
      />
      <Drawer open={!!confirm} title="Revoke this key?" onClose={() => { setConfirm(null); setError(null); }}>
        {confirm && (
          <>
            <h3>{confirm.name}</h3>
            <p>Anything using <span className="mono">{confirm.prefix}…</span> stops working straight away. This can&apos;t be undone.</p>
            {error && <p role="alert" className={styles.err}>{error}</p>}
            <Button variant="danger" loading={busy} onClick={revoke}>Revoke key</Button>
          </>
        )}
      </Drawer>
    </div>
  );
}
```

`src/features/apikeys/apikeys.module.css`:

```css
.wrap { display: flex; flex-direction: column; gap: 16px; }
.create { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.create > div { flex: 1 1 240px; max-width: 360px; }
.err { color: var(--bad); margin: 0; }
.reveal { display: flex; flex-direction: column; gap: 10px; padding: 18px 20px; border-radius: 16px; background: var(--okS); color: var(--ink); }
.reveal strong { color: var(--ok); font-size: 15px; }
.reveal p { margin: 0; color: var(--ink2); }
.key { display: block; padding: 12px 14px; border-radius: 10px; background: var(--s); border: 1px solid var(--l); word-break: break-all; font-size: 13px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `npx vitest run src/features/apikeys && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add "src/app/(app)/developer/api-keys" src/features/apikeys
git commit -m "feat(frontend): API keys screen with one-time key reveal and revoke"
```

---

### Task 8: Docs and whole-branch verification

**Files:**
- Modify: `../docs/frontend-gaps.md`

- [ ] **Step 1: Record the omissions**

In `../docs/frontend-gaps.md` add these rows after G11:

```md
| G12 | "Code we sent by SMS" on the Phone step | WS7a captures the number and a person at Karya confirms it; no SMS vendor (no paid third parties) |
| G13 | "Payout account" step | Bank fields are deferred to WS10, so the flow is four steps, not five |
| G14 | Reviewer's note on a fix-requested or rejected verification | `DeveloperVerification.decisionNote` is stored but `DeveloperVerificationView` does not return it. The UI reads an optional `decisionNote` and falls back to a plain sentence |
| G15 | "What changes when you're verified" comparison panel | Not in the P3a spec; limits shown in it (sandbox minutes, builds a day) have no backend read endpoint |
```

And under "Known visual differences" add:

```md
- Get verified has two panels (steps and form); the mockup's third "What changes when you're verified" panel is omitted (G15).
```

- [ ] **Step 2: Run the whole suite and the type check**

Run: `npx vitest run && npx tsc --noEmit`
Expected: all tests pass, no type errors. If anything fails, fix it before committing; do not skip.

- [ ] **Step 3: Commit**

```bash
git add ../docs/frontend-gaps.md
git commit -m "docs: record what the mockup's verification flow leaves out"
```

- [ ] **Step 4: Stop for review**

Do not push or open a PR without being asked. When asked, the PR body states: frontend only; the four deviations above; **not checked in a browser** until the user runs the stack; `next lint` not configured; G14 is a backend follow-up (return `decisionNote` from `DeveloperVerificationView`). No AI attribution anywhere in the PR text.
