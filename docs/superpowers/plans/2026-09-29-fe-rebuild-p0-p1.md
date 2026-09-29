# Frontend rebuild P0 and P1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `frontend/` with a new Next.js 14 app built to the Karya mockup: foundation, sign-in, workspace home (P0) and the buyer screens that already have a backend (P1): Files, Plug in data, Live run, Runs history, Store flow, phone approval.

**Architecture:** Server components fetch from the backend with an httpOnly-cookie session. Middleware refreshes an expiring access token. Mutations are server actions. The two live screens poll thin route handlers. `ui/` holds tokens and primitives with no data knowledge. `features/*` depend on `ui/` and `lib/api/` only, except that the run pages use `flow` and `gates`.

**Tech Stack:** Next.js 14.2 App Router, React 18, TypeScript, plain CSS variables and CSS Modules (no Tailwind), Vitest and Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-29-fe-rebuild-p0-p1-design.md`

**Mockup (visual reference):** `fe-mockups/latest/Karya — Agentic UI.html`. It is a bundle. To view a board, open the file in a browser. Board order: Sign in, Workspace home, Store flow, Plug in data, Live run, Approve from a phone, Runs history, Files.

## Global Constraints

- Next.js 14 App Router, React 18. No Tailwind, no component library, no TanStack Query.
- Tokens are the mockup's CSS variables: `--g #F8FAFD --dot #D6DFEC --s #FFFFFF --l #E3E9F2 --l2 #EEF2F8 --ink #0D1A30 --ink2 #46546D --ink3 #617089 --b #2B5CE6 --b2 #5B85F0 --b3 #9DB6F3 --sky #EDF3FF --sky2 #D9E5FF --ok #0B8A60 --okS #E2F4EB --wa #9E6209 --waS #FFF3DC --bad #C23B30 --badS #FCEAE8`. Fonts: Instrument Sans and Geist Mono.
- Fully responsive: rail becomes a bottom bar below 768px, tables become stacked cards below 768px, touch targets at least 44px on the phone layout.
- No fake data. If the backend has no data for a mockup element, omit it and add a row to `docs/frontend-gaps.md`.
- No backend changes.
- Only `BUYER_APPROVER` can decide a gate (`RunController.decide`, `approve`, `reject`). A `BUYER_ADMIN` never sees Approve or Reject buttons. Only `BUYER_ADMIN` can create a connection.
- The run list (`GET /runs`) allows only `BUYER_ADMIN` and `BUYER_APPROVER`.
- `BACKEND_URL` is required at request time. There is no default.
- Money is INR, formatted with `en-IN` grouping and the `₹` sign.
- Commit messages: no Claude, Anthropic or AI attribution, and no `Co-Authored-By` trailer. This overrides the harness default.
- Work on a branch `feat/fe-rebuild-p0-p1` created from `docs/fe-rebuild-p0-p1-spec` (use `superpowers:using-git-worktrees`).

## Deviations from the spec (settled while planning)

1. **Token refresh happens in `middleware.ts`, not "on a 401 retry".** Server components cannot set cookies, so a rotated refresh token could not be saved from there. Middleware refreshes when the access token has under 30 seconds left, and a 401 from the backend after that redirects to `/login`.
2. **Store route is `store/[agentId]/[versionId]`, with the list at `store`.** The catalog list already returns `versionId`, and this mirrors the connect route.
3. **Replay run is omitted (G6).** The rerun route is a sandbox route and not a buyer route.
4. **The "Reached" column is built.** `RunListItem.reachedBlock` exists.
5. **Forgot and reset password pages are included** in P0 (small), since the endpoints exist.
6. Roles in the JWT claims (`role`) choose the rail. Developer and admin users land on a holding page in P0 and P1 (their consoles come back in P3 and P4).

## Review Focus

The spec implies these inputs and no earlier task's tests would exercise them. Each has a test in the task named in brackets.

1. **Access token expired, refresh cookie valid.** The user stays signed in and the request continues. If refresh fails, the user goes to `/login?next=`. [Task 4]
2. **A `BUYER_ADMIN` opens a run with a pending gate.** They see the gate's content but no Approve or Reject buttons, with a line saying an approver must decide. [Task 10]
3. **A developer or admin opens `/runs`.** The backend answers 403. The page shows a "no access" state, not an empty list. [Task 11]
4. **A manifest with `resources: null` (untyped).** The connect page shows no sockets, does not crash, and still lets the buyer add and run. [Task 14]
5. **Upload fails (over the storage limit, duplicate, unsupported type).** The backend's message is shown next to the dropzone, the list is unchanged, and the storage bar at 100% still renders. [Task 13]

---

## File Structure

```
frontend/
  package.json  tsconfig.json  next.config.mjs  vitest.config.ts  vitest.setup.ts  .gitignore  README.md
  e2e/playwright.config.ts  e2e/seed.ts  e2e/p0-auth.spec.ts  e2e/p1-buyer.spec.ts
  middleware.ts
  src/
    app/
      layout.tsx  globals.css  error.tsx  not-found.tsx  page.tsx
      (auth)/layout.tsx  login/page.tsx  signup/page.tsx  verify/page.tsx  forgot/page.tsx  reset/page.tsx  actions.ts
      (app)/layout.tsx  home/page.tsx
      (app)/files/page.tsx  files/actions.ts
      (app)/runs/page.tsx  runs/[id]/page.tsx  runs/actions.ts
      (app)/store/page.tsx  store/[agentId]/[versionId]/page.tsx
      (app)/connect/[agentId]/[versionId]/page.tsx  connect/actions.ts
      approve/[runId]/[gateId]/page.tsx
      api/runs/route.ts  api/runs/[id]/route.ts
    ui/          tokens.css Pill Button Field Skeleton EmptyState ErrorState Rail Topbar AppShell Dropzone Drawer ResponsiveTable Icon
    features/
      flow/      blocks.ts FlowBlocks.tsx
      gates/     GateCard.tsx
      runs/      RunsTable.tsx RunTimeline.tsx timeline.ts LiveRun.tsx useRunPolling.ts stepText.ts
      files/     FilesView.tsx StorageBar.tsx
      sockets/   ConnectForm.tsx
      home/      HomeView.tsx
    lib/
      session.ts roles.ts format.ts
      api/       http.ts types.ts auth.ts me.ts runs.ts datasets.ts connections.ts catalog.ts
docs/frontend-gaps.md
```

Each `X.test.ts(x)` sits next to `X`.

---

# P0: foundation

### Task 1: Remove the old frontend and scaffold the new app

**Files:**
- Delete: everything under `frontend/` (tracked files, plus untracked `node_modules`, `.next`, `test-results`)
- Create: `frontend/package.json`, `tsconfig.json`, `next.config.mjs`, `vitest.config.ts`, `vitest.setup.ts`, `.gitignore`, `README.md`, `src/app/layout.tsx`, `src/app/page.tsx`, `src/smoke.test.ts`
- Modify: repo files that mention the old frontend (see Step 2)

**Interfaces:**
- Produces: `npm test` (vitest), `npm run dev`, `npm run build`, `npm run test:e2e`, path alias `@/*` to `src/*`.

- [ ] **Step 1: Delete the old app**

```bash
cd /Users/amittiwari/AgentStore
git rm -r -q frontend
rm -rf frontend
mkdir -p frontend/src/app
```

- [ ] **Step 2: Find and fix references to the old app**

Run: `grep -rnE "frontend/|Next\.js 14 App Router app|tailwind" --include=*.md --include=*.yml --include=*.yaml --include=*.json --include=.gitignore . --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=fe-mockups | grep -v "docs/superpowers"`
Expected: a short list (for example `docs/known-issues.md` mentioning `frontend/src/lib/types.ts`, and any `docker-compose.yml` or `.gitignore` lines). For each hit: if it names a deleted file, rewrite the sentence to point at `backend/.../manifest/ManifestParser.java` only (known-issues.md) or remove it. If it only says "frontend/" as a folder that still exists, leave it.

- [ ] **Step 3: Write `frontend/package.json`**

```json
{
  "name": "frontend",
  "version": "2.0.0",
  "private": true,
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "test": "vitest run",
    "test:e2e": "playwright test --config=e2e/playwright.config.ts",
    "seed:e2e": "tsx e2e/seed.ts"
  },
  "dependencies": {
    "next": "^14.2.35",
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@playwright/test": "^1.63.0",
    "@testing-library/jest-dom": "^7.0.1",
    "@testing-library/react": "^16.3.3",
    "@testing-library/user-event": "^14.6.7",
    "@types/node": "^22.20.3",
    "@types/pg": "^8.23.1",
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^6.1.1",
    "jsdom": "^30.1.0",
    "pg": "^8.23.0",
    "tsx": "^4.23.13",
    "typescript": "^5.9.3",
    "vitest": "^5.0.1"
  }
}
```

- [ ] **Step 4: Write the config files**

`frontend/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["dom", "dom.iterable", "esnext"],
    "allowJs": false,
    "skipLibCheck": true,
    "strict": true,
    "noEmit": true,
    "esModuleInterop": true,
    "module": "esnext",
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "jsx": "preserve",
    "incremental": true,
    "plugins": [{ "name": "next" }],
    "paths": { "@/*": ["./src/*"] }
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx", ".next/types/**/*.ts"],
  "exclude": ["node_modules"]
}
```

`frontend/next.config.mjs`:
```js
/** @type {import('next').NextConfig} */
const nextConfig = {};

export default nextConfig;
```

`frontend/vitest.config.ts`:
```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "path";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    exclude: ["**/node_modules/**", "**/.git/**", "e2e/**"],
  },
  resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
});
```

`frontend/vitest.setup.ts`:
```ts
import "@testing-library/jest-dom/vitest";
```

`frontend/.gitignore`:
```
node_modules
.next
next-env.d.ts
test-results
playwright-report
tsconfig.tsbuildinfo
.env*.local
```

- [ ] **Step 5: Minimal app and a smoke test**

`frontend/src/app/layout.tsx`:
```tsx
export const metadata = { title: "Karya" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
```

`frontend/src/app/page.tsx`:
```tsx
export default function Page() {
  return <main>Karya</main>;
}
```

`frontend/src/smoke.test.ts`:
```ts
it("runs the test setup", () => {
  expect(1 + 1).toBe(2);
});
```

`frontend/README.md`:
```markdown
# Karya frontend

Next.js 14 App Router app built to `fe-mockups/latest/Karya — Agentic UI.html`.
Design: `docs/superpowers/specs/2026-09-29-fe-rebuild-p0-p1-design.md`.

## Environment
- `BACKEND_URL` (required): base URL of the backend, e.g. `http://localhost:8080`.

## Develop
    cd frontend && npm install && BACKEND_URL=http://localhost:8080 npm run dev

## Test
    npm test                # unit and component tests
    npm run seed:e2e && npm run test:e2e   # needs backend + Postgres running
```

- [ ] **Step 6: Install and verify**

Run: `cd frontend && npm install && npm test && BACKEND_URL=http://localhost:8080 npm run build`
Expected: 1 test passes, and the Next build succeeds.

- [ ] **Step 7: Commit**

```bash
cd /Users/amittiwari/AgentStore
git add -A
git commit -m "chore(frontend): remove the old app and scaffold the mockup-based rebuild"
```

---

### Task 2: Design tokens, fonts and base primitives

**Files:**
- Create: `src/ui/tokens.css`, `src/ui/Pill.tsx`, `Pill.module.css`, `Button.tsx`, `Button.module.css`, `Field.tsx`, `Field.module.css`, `Skeleton.tsx`, `EmptyState.tsx`, `ErrorState.tsx`, `states.module.css`, `Icon.tsx`
- Modify: `src/app/layout.tsx`, create `src/app/globals.css`
- Test: `src/ui/Pill.test.tsx`, `src/ui/states.test.tsx`

**Interfaces:**
- Produces:
  - `type Tone = "neutral" | "ok" | "warn" | "bad" | "info"`
  - `Pill({ tone, children })`
  - `Button({ variant?: "primary" | "ghost" | "danger", loading?: boolean, ...ButtonHTMLAttributes })`
  - `Field({ label, error?, hint?, ...InputHTMLAttributes })`
  - `Skeleton({ lines?: number })`
  - `EmptyState({ title, body?, action? })`, `ErrorState({ message, onRetry? })`
  - `Icon({ name: IconName })` with `IconName = "home" | "store" | "runs" | "files" | "search" | "bell" | "check" | "x" | "upload" | "lock"`

- [ ] **Step 1: Write the failing tests**

`src/ui/Pill.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { Pill } from "./Pill";

it("renders its text and a tone class", () => {
  render(<Pill tone="ok">Finished</Pill>);
  const el = screen.getByText("Finished");
  expect(el).toBeInTheDocument();
  expect(el.className).toMatch(/ok/);
});
```

`src/ui/states.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EmptyState } from "./EmptyState";
import { ErrorState } from "./ErrorState";

it("shows the empty title and body", () => {
  render(<EmptyState title="No files yet" body="Upload a file to start." />);
  expect(screen.getByText("No files yet")).toBeInTheDocument();
  expect(screen.getByText("Upload a file to start.")).toBeInTheDocument();
});

it("shows the backend message and retries", async () => {
  const onRetry = vi.fn();
  render(<ErrorState message="Storage limit reached" onRetry={onRetry} />);
  expect(screen.getByRole("alert")).toHaveTextContent("Storage limit reached");
  await userEvent.click(screen.getByRole("button", { name: /try again/i }));
  expect(onRetry).toHaveBeenCalledOnce();
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx vitest run src/ui`
Expected: FAIL, modules not found.

- [ ] **Step 3: Tokens and globals**

`src/ui/tokens.css`:
```css
:root {
  --g: #f8fafd; --dot: #d6dfec; --s: #ffffff; --l: #e3e9f2; --l2: #eef2f8;
  --ink: #0d1a30; --ink2: #46546d; --ink3: #617089;
  --b: #2b5ce6; --b2: #5b85f0; --b3: #9db6f3; --sky: #edf3ff; --sky2: #d9e5ff;
  --ok: #0b8a60; --okS: #e2f4eb; --wa: #9e6209; --waS: #fff3dc; --bad: #c23b30; --badS: #fce8e8;
  --radius: 12px;
  --rail-w: 72px;
  --top-h: 64px;
  --font-sans: var(--font-instrument), ui-sans-serif, system-ui, sans-serif;
  --font-mono: var(--font-geist-mono), ui-monospace, monospace;
}
```

`src/app/globals.css`:
```css
@import "../ui/tokens.css";

* { box-sizing: border-box; }
html, body { margin: 0; }
body {
  font-family: var(--font-sans);
  color: var(--ink);
  background-color: var(--g);
  background-image: radial-gradient(circle, var(--dot) 1px, transparent 1.3px);
  background-size: 18px 18px;
  -webkit-font-smoothing: antialiased;
  font-size: 14px;
  line-height: 1.45;
}
a { color: var(--b); }
h1, h2, h3 { margin: 0; font-weight: 600; letter-spacing: -0.01em; }
:focus-visible { outline: 2px solid var(--b); outline-offset: 2px; }
.mono { font-family: var(--font-mono); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
```

`src/app/layout.tsx`:
```tsx
import "./globals.css";
import { Instrument_Sans, Geist_Mono } from "next/font/google";

const sans = Instrument_Sans({ subsets: ["latin"], variable: "--font-instrument", display: "swap" });
const mono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono", display: "swap" });

export const metadata = { title: "Karya" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${mono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
```

If `Geist_Mono` is not exported by the installed Next 14.2, run `npm install geist` and use `import { GeistMono } from "geist/font/mono"` with `className={GeistMono.variable}`, keeping the CSS variable name `--font-geist-mono`.

- [ ] **Step 4: Primitives**

`src/ui/Pill.tsx`:
```tsx
import styles from "./Pill.module.css";

export type Tone = "neutral" | "ok" | "warn" | "bad" | "info";

export function Pill({ tone = "neutral", children }: { tone?: Tone; children: React.ReactNode }) {
  return <span className={`${styles.pill} ${styles[tone]}`}>{children}</span>;
}
```

`src/ui/Pill.module.css`:
```css
.pill { display: inline-flex; align-items: center; height: 24px; padding: 0 10px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.neutral { background: var(--l2); color: var(--ink2); }
.ok { background: var(--okS); color: var(--ok); }
.warn { background: var(--waS); color: var(--wa); }
.bad { background: var(--badS); color: var(--bad); }
.info { background: var(--sky); color: var(--b); }
```

`src/ui/Button.tsx`:
```tsx
import styles from "./Button.module.css";

type Props = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "ghost" | "danger";
  loading?: boolean;
};

export function Button({ variant = "primary", loading = false, disabled, children, className, ...rest }: Props) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`${styles.btn} ${styles[variant]} ${className ?? ""}`}
    >
      {children}
    </button>
  );
}
```

`src/ui/Button.module.css`:
```css
.btn { min-height: 40px; padding: 0 16px; border-radius: 10px; border: 1px solid transparent; font: inherit; font-weight: 600; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
.btn:disabled { opacity: 0.55; cursor: not-allowed; }
.primary { background: var(--b); color: #fff; }
.primary:hover:not(:disabled) { background: #244fcb; }
.ghost { background: var(--s); color: var(--ink); border-color: var(--l); }
.ghost:hover:not(:disabled) { background: var(--sky); }
.danger { background: var(--s); color: var(--bad); border-color: var(--badS); }
@media (max-width: 767px) { .btn { min-height: 44px; } }
```

`src/ui/Field.tsx`:
```tsx
import { useId } from "react";
import styles from "./Field.module.css";

type Props = React.InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string };

export function Field({ label, error, hint, id, ...rest }: Props) {
  const auto = useId();
  const inputId = id ?? auto;
  const noteId = `${inputId}-note`;
  return (
    <div className={styles.field}>
      <label htmlFor={inputId}>{label}</label>
      <input id={inputId} aria-invalid={!!error} aria-describedby={error || hint ? noteId : undefined} {...rest} />
      {(error || hint) && (
        <span id={noteId} className={error ? styles.err : styles.hint}>
          {error ?? hint}
        </span>
      )}
    </div>
  );
}
```

`src/ui/Field.module.css`:
```css
.field { display: flex; flex-direction: column; gap: 6px; }
.field label { font-size: 13px; font-weight: 600; color: var(--ink2); }
.field input { min-height: 44px; padding: 0 12px; border: 1px solid var(--l); border-radius: 10px; font: inherit; background: var(--s); color: var(--ink); }
.field input[aria-invalid="true"] { border-color: var(--bad); }
.err { color: var(--bad); font-size: 12px; }
.hint { color: var(--ink3); font-size: 12px; }
```

`src/ui/Skeleton.tsx`:
```tsx
import styles from "./states.module.css";

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div aria-busy="true" aria-label="Loading" className={styles.skel}>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className={styles.line} style={{ width: `${90 - i * 12}%` }} />
      ))}
    </div>
  );
}
```

`src/ui/EmptyState.tsx`:
```tsx
import styles from "./states.module.css";

export function EmptyState({ title, body, action }: { title: string; body?: string; action?: React.ReactNode }) {
  return (
    <div className={styles.box}>
      <h3>{title}</h3>
      {body && <p>{body}</p>}
      {action}
    </div>
  );
}
```

`src/ui/ErrorState.tsx`:
```tsx
"use client";
import styles from "./states.module.css";
import { Button } from "./Button";

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className={`${styles.box} ${styles.error}`}>
      <p>{message}</p>
      {onRetry && (
        <Button variant="ghost" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}
```

`src/ui/states.module.css`:
```css
.box { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; padding: 24px; border: 1px dashed var(--l); border-radius: var(--radius); background: var(--s); }
.box p { margin: 0; color: var(--ink2); }
.error { border-style: solid; border-color: var(--badS); background: var(--badS); }
.error p { color: var(--bad); }
.skel { display: flex; flex-direction: column; gap: 10px; padding: 16px; }
.line { height: 14px; border-radius: 6px; background: linear-gradient(90deg, var(--l2), var(--l), var(--l2)); }
```

`src/ui/Icon.tsx`:
```tsx
export type IconName = "home" | "store" | "runs" | "files" | "search" | "bell" | "check" | "x" | "upload" | "lock";

const paths: Record<IconName, string> = {
  home: "M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10",
  store: "M4 9l1-5h14l1 5M4 9v11h16V9M4 9h16M9 20v-6h6v6",
  runs: "M5 4v16l14-8z",
  files: "M6 3h8l4 4v14H6zM14 3v4h4",
  search: "M11 4a7 7 0 100 14 7 7 0 000-14zM21 21l-5-5",
  bell: "M6 16V11a6 6 0 1112 0v5l2 2H4zM10 20a2 2 0 004 0",
  check: "M5 12l5 5 9-10",
  x: "M6 6l12 12M18 6L6 18",
  upload: "M12 16V4M7 9l5-5 5 5M4 20h16",
  lock: "M6 11h12v9H6zM8 11V8a4 4 0 118 0v3",
};

export function Icon({ name }: { name: IconName }) {
  return (
    <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d={paths[name]} />
    </svg>
  );
}
```

- [ ] **Step 5: Run and commit**

Run: `cd frontend && npx vitest run src/ui && npm run build`
Expected: PASS and a green build.

```bash
git add frontend && git commit -m "feat(frontend): design tokens, fonts and base primitives"
```

---

### Task 3: App shell (rail, topbar, responsive frame)

**Files:**
- Create: `src/ui/Rail.tsx`, `Rail.module.css`, `Topbar.tsx`, `Topbar.module.css`, `AppShell.tsx`, `AppShell.module.css`, `src/lib/roles.ts`
- Test: `src/lib/roles.test.ts`, `src/ui/Rail.test.tsx`

**Interfaces:**
- Produces:
  - `type UserRole = "BUYER_ADMIN" | "BUYER_APPROVER" | "DEVELOPER" | "ADMIN"` (in `roles.ts`)
  - `railFor(role): "buyer" | "developer" | "admin"`, `canDecideGates(role): boolean` (only `BUYER_APPROVER`), `canCreateConnections(role): boolean` (only `BUYER_ADMIN`), `isBuyer(role): boolean`
  - `type RailItem = { href: string; label: string; icon: IconName; badge?: number }`
  - `Rail({ items, activePath })`, `Topbar({ orgName, initials })`, `AppShell({ items, orgName, initials, children })`
  - `buyerRailItems(needsYou: number): RailItem[]`

- [ ] **Step 1: Failing tests**

`src/lib/roles.test.ts`:
```ts
import { canCreateConnections, canDecideGates, isBuyer, railFor } from "./roles";

it("maps roles to rails", () => {
  expect(railFor("BUYER_ADMIN")).toBe("buyer");
  expect(railFor("BUYER_APPROVER")).toBe("buyer");
  expect(railFor("DEVELOPER")).toBe("developer");
  expect(railFor("ADMIN")).toBe("admin");
});

it("only approvers decide gates and only buyer admins connect", () => {
  expect(canDecideGates("BUYER_APPROVER")).toBe(true);
  expect(canDecideGates("BUYER_ADMIN")).toBe(false);
  expect(canCreateConnections("BUYER_ADMIN")).toBe(true);
  expect(canCreateConnections("BUYER_APPROVER")).toBe(false);
  expect(isBuyer("DEVELOPER")).toBe(false);
});
```

`src/ui/Rail.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { Rail } from "./Rail";

const items = [
  { href: "/home", label: "Workspace", icon: "home" as const },
  { href: "/runs", label: "Runs", icon: "runs" as const, badge: 2 },
];

it("marks the active item and shows the badge as text", () => {
  render(<Rail items={items} activePath="/runs/R-2291" />);
  expect(screen.getByRole("link", { name: /Runs, 2 need you/i })).toHaveAttribute("aria-current", "page");
  expect(screen.getByRole("link", { name: /Workspace/ })).not.toHaveAttribute("aria-current");
});
```

- [ ] **Step 2: Verify failure, then implement**

Run: `cd frontend && npx vitest run src/lib/roles.test.ts src/ui/Rail.test.tsx` → FAIL.

`src/lib/roles.ts`:
```ts
export type UserRole = "BUYER_ADMIN" | "BUYER_APPROVER" | "DEVELOPER" | "ADMIN";

export function railFor(role: UserRole): "buyer" | "developer" | "admin" {
  if (role === "DEVELOPER") return "developer";
  if (role === "ADMIN") return "admin";
  return "buyer";
}
export const isBuyer = (role: UserRole) => role === "BUYER_ADMIN" || role === "BUYER_APPROVER";
export const canDecideGates = (role: UserRole) => role === "BUYER_APPROVER";
export const canCreateConnections = (role: UserRole) => role === "BUYER_ADMIN";
```

`src/ui/Rail.tsx`:
```tsx
import Link from "next/link";
import styles from "./Rail.module.css";
import { Icon, type IconName } from "./Icon";

export type RailItem = { href: string; label: string; icon: IconName; badge?: number };

export function buyerRailItems(needsYou: number): RailItem[] {
  return [
    { href: "/home", label: "Workspace", icon: "home" },
    { href: "/store", label: "Agent store", icon: "store" },
    { href: "/runs", label: "Runs", icon: "runs", badge: needsYou > 0 ? needsYou : undefined },
    { href: "/files", label: "Files", icon: "files" },
  ];
}

export function Rail({ items, activePath }: { items: RailItem[]; activePath: string }) {
  return (
    <nav className={styles.rail} aria-label="Main">
      <div className={styles.logo} aria-hidden="true"><i /><i /><i /><i /></div>
      {items.map((item) => {
        const active = activePath === item.href || activePath.startsWith(item.href + "/");
        const label = item.badge ? `${item.label}, ${item.badge} need you` : item.label;
        return (
          <Link key={item.href} href={item.href} aria-label={label} aria-current={active ? "page" : undefined} className={`${styles.nav} ${active ? styles.on : ""}`}>
            <Icon name={item.icon} />
            {item.badge ? <span className={styles.badge}>{item.badge}</span> : null}
          </Link>
        );
      })}
    </nav>
  );
}
```

`src/ui/Rail.module.css`:
```css
.rail { position: fixed; left: 0; top: 0; bottom: 0; width: var(--rail-w); background: rgba(255,255,255,0.88); border-right: 1px solid var(--l); display: flex; flex-direction: column; align-items: center; padding: 18px 0; gap: 6px; z-index: 5; }
.logo { display: grid; grid-template-columns: repeat(2, 9px); gap: 3px; margin-bottom: 20px; }
.logo i { width: 9px; height: 9px; border-radius: 3px; background: var(--b); }
.logo i:nth-child(2) { background: var(--b2); }
.logo i:nth-child(3) { background: var(--b3); }
.logo i:nth-child(4) { background: var(--sky2); }
.nav { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; color: var(--ink3); text-decoration: none; position: relative; }
.nav:hover, .on { background: var(--sky); color: var(--b); }
.badge { position: absolute; top: 5px; right: 5px; min-width: 16px; height: 16px; border-radius: 999px; background: var(--b); color: #fff; font-size: 10px; font-weight: 600; display: grid; place-items: center; padding: 0 4px; }
@media (max-width: 767px) {
  .rail { top: auto; right: 0; width: auto; height: 60px; flex-direction: row; justify-content: space-around; padding: 0 8px; border-right: 0; border-top: 1px solid var(--l); }
  .logo { display: none; }
  .nav { width: 56px; height: 48px; }
}
```

`src/ui/Topbar.tsx`:
```tsx
import styles from "./Topbar.module.css";
import { Icon } from "./Icon";

export function Topbar({ orgName, initials }: { orgName: string; initials: string }) {
  return (
    <header className={styles.top}>
      <span className={styles.org}>{orgName}</span>
      <div className={styles.search} role="search">
        <Icon name="search" />
        <input aria-label="Search" placeholder="Search is coming soon" disabled />
      </div>
      <span className={styles.avatar} aria-label="Your account">{initials}</span>
    </header>
  );
}
```

`src/ui/Topbar.module.css`:
```css
.top { position: sticky; top: 0; z-index: 4; height: var(--top-h); display: flex; align-items: center; gap: 16px; padding: 0 24px; background: rgba(248,250,253,0.9); border-bottom: 1px solid var(--l); backdrop-filter: blur(6px); }
.org { font-weight: 600; }
.search { flex: 1; max-width: 520px; display: flex; align-items: center; gap: 8px; height: 40px; padding: 0 12px; border: 1px solid var(--l); border-radius: 10px; background: var(--s); color: var(--ink3); }
.search input { flex: 1; border: 0; outline: 0; font: inherit; background: transparent; }
.avatar { margin-left: auto; width: 34px; height: 34px; border-radius: 50%; background: var(--sky2); color: var(--b); display: grid; place-items: center; font-weight: 600; font-size: 12px; }
@media (max-width: 767px) { .top { padding: 0 16px; } .search { display: none; } }
```

`src/ui/AppShell.tsx`:
```tsx
"use client";
import { usePathname } from "next/navigation";
import styles from "./AppShell.module.css";
import { Rail, type RailItem } from "./Rail";
import { Topbar } from "./Topbar";

export function AppShell({ items, orgName, initials, children }: { items: RailItem[]; orgName: string; initials: string; children: React.ReactNode }) {
  const path = usePathname() ?? "";
  return (
    <div className={styles.frame}>
      <Rail items={items} activePath={path} />
      <div className={styles.main}>
        <Topbar orgName={orgName} initials={initials} />
        <main className={styles.content}>{children}</main>
      </div>
    </div>
  );
}
```

`src/ui/AppShell.module.css`:
```css
.main { margin-left: var(--rail-w); min-height: 100vh; }
.content { padding: 28px 32px 48px; max-width: 1200px; }
@media (max-width: 767px) {
  .main { margin-left: 0; padding-bottom: 72px; }
  .content { padding: 20px 16px 32px; }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/lib/roles.test.ts src/ui/Rail.test.tsx` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): responsive app shell with role-aware rail"
```

---

### Task 4: Session, backend client and middleware refresh

**Files:**
- Create: `src/lib/session.ts`, `src/lib/api/http.ts`, `src/lib/api/types.ts` (auth and me types only for now), `middleware.ts`
- Test: `src/lib/session.test.ts`, `src/lib/api/http.test.ts`, `middleware.test.ts`

**Interfaces:**
- Produces:
  - `SESSION_COOKIE = "karya_session"`, `REFRESH_COOKIE = "karya_refresh"`
  - `interface Claims { userId: string; orgId: string; role: UserRole; exp: number }`
  - `decodeToken(token: string): Claims | null` (null on malformed; keeps `exp`)
  - `needsRefresh(token: string | undefined, nowSec: number): boolean` (true when missing, undecodable, or `exp - nowSec < 30`)
  - `getSession(): Promise<Claims | null>` (returns null when the token is missing or fully expired)
  - `cookieOptions(maxAgeSec?: number)`, `writeSession(store, { token, refreshToken? })`, `clearSession(store)`
  - `class ApiError extends Error { status: number; code: string | null }`
  - `apiFetch(path, init?): Promise<Response>` adds the bearer token and, for string bodies, JSON content type. Never throws on HTTP errors.
  - `api<T>(path, init?): Promise<T>` throws `ApiError` on non-2xx. On 401 it calls `redirect("/login")`. Returns `undefined` on 204.
  - `AuthResponse = { token: string; refreshToken: string | null }`
- Consumes: `UserRole` from `@/lib/roles`.

- [ ] **Step 1: Failing tests**

`src/lib/session.test.ts`:
```ts
import { decodeToken, needsRefresh } from "./session";

function jwt(payload: object) {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64(payload)}.sig`;
}

it("decodes claims including exp", () => {
  const t = jwt({ sub: "u1", orgId: "o1", role: "BUYER_ADMIN", exp: 2000 });
  expect(decodeToken(t)).toEqual({ userId: "u1", orgId: "o1", role: "BUYER_ADMIN", exp: 2000 });
});

it("returns null for garbage", () => {
  expect(decodeToken("nope")).toBeNull();
  expect(decodeToken(jwt({ sub: "u1" }))).toBeNull();
});

it("asks for a refresh when the token is missing, bad, or about to expire", () => {
  const soon = jwt({ sub: "u", orgId: "o", role: "BUYER_ADMIN", exp: 1010 });
  const later = jwt({ sub: "u", orgId: "o", role: "BUYER_ADMIN", exp: 1100 });
  expect(needsRefresh(undefined, 1000)).toBe(true);
  expect(needsRefresh("bad", 1000)).toBe(true);
  expect(needsRefresh(soon, 1000)).toBe(true);
  expect(needsRefresh(later, 1000)).toBe(false);
});
```

`src/lib/api/http.test.ts`:
```ts
import { vi } from "vitest";

const store = new Map<string, string>();
vi.mock("next/headers", () => ({ cookies: () => ({ get: (k: string) => (store.has(k) ? { value: store.get(k) } : undefined) }) }));
const redirect = vi.fn(() => { throw new Error("NEXT_REDIRECT"); });
vi.mock("next/navigation", () => ({ redirect }));

import { api, ApiError } from "./http";

beforeEach(() => {
  store.clear();
  process.env.BACKEND_URL = "http://be";
  redirect.mockClear();
});

it("sends the bearer token and parses JSON", async () => {
  store.set("karya_session", "tok");
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: 1 }), { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  await expect(api("/api/v1/x")).resolves.toEqual({ ok: 1 });
  expect(fetchMock.mock.calls[0][0]).toBe("http://be/api/v1/x");
  expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer tok");
});

it("throws ApiError with the backend message and code", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "DATASET_CHECK_FAILED", message: "Columns do not match" }), { status: 422 })));
  await expect(api("/api/v1/x")).rejects.toMatchObject({ status: 422, code: "DATASET_CHECK_FAILED", message: "Columns do not match" });
});

it("falls back to a generic message when the body is empty", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 403 })));
  const err = await api("/api/v1/x").catch((e) => e);
  expect(err).toBeInstanceOf(ApiError);
  expect(err.message).toBe("Request failed (403)");
});

it("redirects to login on 401", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
  await expect(api("/api/v1/x")).rejects.toThrow("NEXT_REDIRECT");
  expect(redirect).toHaveBeenCalledWith("/login");
});

it("does not force a JSON content type on FormData", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200 }));
  vi.stubGlobal("fetch", fetchMock);
  const fd = new FormData();
  fd.set("file", new Blob(["a"]), "a.csv");
  await api("/api/v1/datasets", { method: "POST", body: fd });
  expect(fetchMock.mock.calls[0][1].headers["Content-Type"]).toBeUndefined();
});
```

`middleware.test.ts` (Review Focus 1):
```ts
import { NextRequest } from "next/server";
import { vi } from "vitest";
import { middleware } from "./middleware";

function jwt(exp: number) {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u", orgId: "o", role: "BUYER_ADMIN", exp })}.sig`;
}
const nowSec = () => Math.floor(Date.now() / 1000);
const req = (path: string, cookies: Record<string, string> = {}) => {
  const r = new NextRequest(`http://app${path}`);
  Object.entries(cookies).forEach(([k, v]) => r.cookies.set(k, v));
  return r;
};

beforeEach(() => { process.env.BACKEND_URL = "http://be"; });

it("lets public routes through without a session", async () => {
  const res = await middleware(req("/login"));
  expect(res.status).toBe(200);
});

it("redirects to login with next when there is no session", async () => {
  const res = await middleware(req("/runs"));
  expect(res.status).toBe(307);
  expect(res.headers.get("location")).toBe("http://app/login?next=%2Fruns");
});

it("refreshes an expired access token using the refresh cookie", async () => {
  const fresh = jwt(nowSec() + 900);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: fresh, refreshToken: "r2" }), { status: 200 })));
  const res = await middleware(req("/runs", { karya_session: jwt(nowSec() - 5), karya_refresh: "r1" }));
  expect(res.status).toBe(200);
  expect(res.cookies.get("karya_session")?.value).toBe(fresh);
  expect(res.cookies.get("karya_refresh")?.value).toBe("r2");
});

it("sends the user to login and clears cookies when refresh fails", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
  const res = await middleware(req("/runs", { karya_session: jwt(nowSec() - 5), karya_refresh: "bad" }));
  expect(res.status).toBe(307);
  expect(res.cookies.get("karya_session")?.value).toBe("");
});
```

- [ ] **Step 2: Verify failure**

Run: `cd frontend && npx vitest run src/lib middleware.test.ts` → FAIL.

- [ ] **Step 3: Implement**

`src/lib/session.ts`:
```ts
import { cookies } from "next/headers";
import type { UserRole } from "./roles";

export const SESSION_COOKIE = "karya_session";
export const REFRESH_COOKIE = "karya_refresh";

export interface Claims {
  userId: string;
  orgId: string;
  role: UserRole;
  exp: number;
}

export function decodeToken(token: string): Claims | null {
  try {
    const seg = token.split(".")[1];
    const json = JSON.parse(atob(seg.replace(/-/g, "+").replace(/_/g, "/"))) as {
      sub?: string; orgId?: string; role?: UserRole; exp?: number;
    };
    if (!json.sub || !json.orgId || !json.role) return null;
    return { userId: json.sub, orgId: json.orgId, role: json.role, exp: json.exp ?? 0 };
  } catch {
    return null;
  }
}

export function needsRefresh(token: string | undefined, nowSec: number): boolean {
  if (!token) return true;
  const claims = decodeToken(token);
  if (!claims) return true;
  return claims.exp - nowSec < 30;
}

export async function getSession(): Promise<Claims | null> {
  const token = cookies().get(SESSION_COOKIE)?.value;
  if (!token) return null;
  const claims = decodeToken(token);
  if (!claims || claims.exp <= Date.now() / 1000) return null;
  return claims;
}

export function cookieOptions(maxAgeSec?: number) {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
    ...(maxAgeSec ? { maxAge: maxAgeSec } : {}),
  };
}

type CookieWriter = { set: (name: string, value: string, opts?: object) => unknown };

export function writeSession(store: CookieWriter, s: { token: string; refreshToken?: string | null }) {
  store.set(SESSION_COOKIE, s.token, cookieOptions());
  if (s.refreshToken) store.set(REFRESH_COOKIE, s.refreshToken, cookieOptions(60 * 60 * 24 * 30));
}

export function clearSession(store: CookieWriter) {
  store.set(SESSION_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
  store.set(REFRESH_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
}
```

`src/lib/api/http.ts`:
```ts
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE } from "../session";

export class ApiError extends Error {
  status: number;
  code: string | null;
  constructor(status: number, code: string | null, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = cookies().get(SESSION_COOKIE)?.value;
  const headers: Record<string, string> = { ...((init.headers as Record<string, string>) ?? {}) };
  if (typeof init.body === "string") headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(`${process.env.BACKEND_URL}${path}`, { ...init, headers, cache: "no-store" });
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await apiFetch(path, init);
  if (res.status === 401) redirect("/login");
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { code?: string; message?: string } | null;
    throw new ApiError(res.status, body?.code ?? null, body?.message || `Request failed (${res.status})`);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const json = (body: unknown) => JSON.stringify(body);
```

`src/lib/api/types.ts` (auth and me for now; later tasks append):
```ts
import type { UserRole } from "../roles";

export type OrgType = "BUYER" | "DEVELOPER";
export type SignupSide = "BUYER" | "DEVELOPER";

export interface AuthResponse { token: string; refreshToken: string | null }
export interface Membership { orgId: string; orgName: string; orgType: OrgType; role: UserRole; title: string | null }
export interface MeResponse {
  userId: string; email: string; fullName: string; emailVerified: boolean; activeOrgId: string; memberships: Membership[];
}
```

`middleware.ts`:
```ts
import { NextRequest, NextResponse } from "next/server";
import { REFRESH_COOKIE, SESSION_COOKIE, cookieOptions, needsRefresh } from "@/lib/session";

const PUBLIC = ["/login", "/signup", "/verify", "/forgot", "/reset"];

export async function middleware(req: NextRequest) {
  const path = req.nextUrl.pathname;
  if (PUBLIC.some((p) => path === p || path.startsWith(p + "/"))) return NextResponse.next();

  const token = req.cookies.get(SESSION_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;
  const now = Math.floor(Date.now() / 1000);

  if (!needsRefresh(token, now)) return NextResponse.next();

  const toLogin = () => {
    const url = req.nextUrl.clone();
    url.pathname = "/login";
    url.search = `?next=${encodeURIComponent(path + req.nextUrl.search)}`;
    const res = NextResponse.redirect(url);
    res.cookies.set(SESSION_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
    res.cookies.set(REFRESH_COOKIE, "", { ...cookieOptions(), maxAge: 0 });
    return res;
  };

  if (!refresh) return toLogin();

  const r = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: refresh }),
  }).catch(() => null);
  if (!r || !r.ok) return toLogin();

  const next = (await r.json()) as { token: string; refreshToken?: string | null };
  // Make the new token visible to this request's server components, and persist it for the browser.
  const headers = new Headers(req.headers);
  const jar = new Map(req.cookies.getAll().map((c) => [c.name, c.value]));
  jar.set(SESSION_COOKIE, next.token);
  if (next.refreshToken) jar.set(REFRESH_COOKIE, next.refreshToken);
  headers.set("cookie", [...jar].map(([k, v]) => `${k}=${v}`).join("; "));
  const res = NextResponse.next({ request: { headers } });
  res.cookies.set(SESSION_COOKIE, next.token, cookieOptions());
  if (next.refreshToken) res.cookies.set(REFRESH_COOKIE, next.refreshToken, cookieOptions(60 * 60 * 24 * 30));
  return res;
}

export const config = { matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"] };
```

- [ ] **Step 4: Run and commit**

Run: `cd frontend && npx vitest run src/lib middleware.test.ts` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): cookie session, backend client and middleware token refresh"
```

---

### Task 5: Auth pages and actions

**Files:**
- Create: `src/lib/api/auth.ts`, `src/app/(auth)/layout.tsx`, `login/page.tsx`, `signup/page.tsx`, `verify/page.tsx`, `forgot/page.tsx`, `reset/page.tsx`, `src/app/(auth)/actions.ts`, `src/app/(auth)/auth.module.css`, `src/app/(auth)/LoginForm.tsx`, `SignupForm.tsx`
- Modify: `src/app/page.tsx` (redirect)
- Test: `src/app/(auth)/actions.test.ts`, `src/app/(auth)/LoginForm.test.tsx`

**Interfaces:**
- Produces:
  - `authApi.login(body: { email; password; remember: boolean }): Promise<Response>`, `signup(body: { fullName; orgName; email; password; side })`, `verifyEmail(token)`, `resendVerification()`, `forgot(email)`, `reset(token, password)`, `logout(refreshToken)` (all use bare `fetch` without a bearer, except `resendVerification` which uses `apiFetch`)
  - Server actions, each returning `{ error?: string }` or redirecting: `loginAction(prev, formData)`, `signupAction(prev, formData)`, `forgotAction(prev, formData)`, `resetAction(prev, formData)`, `logoutAction()`
  - `safeNext(next: string | null): string` returns `next` only when it starts with `/` and not `//`, else `/home`
- Consumes: `writeSession`, `clearSession`, `REFRESH_COOKIE` from `@/lib/session`.

- [ ] **Step 1: Failing tests**

`src/app/(auth)/actions.test.ts`:
```ts
import { vi } from "vitest";

const set = vi.fn();
vi.mock("next/headers", () => ({ cookies: () => ({ set, get: () => undefined }) }));
const redirect = vi.fn((url: string) => { throw new Error(`NEXT_REDIRECT:${url}`); });
vi.mock("next/navigation", () => ({ redirect }));

import { loginAction, safeNext } from "./actions";

function form(values: Record<string, string>) {
  const fd = new FormData();
  Object.entries(values).forEach(([k, v]) => fd.set(k, v));
  return fd;
}

beforeEach(() => { set.mockClear(); redirect.mockClear(); process.env.BACKEND_URL = "http://be"; });

it("accepts only same-site next paths", () => {
  expect(safeNext("/runs")).toBe("/runs");
  expect(safeNext("//evil.com")).toBe("/home");
  expect(safeNext("https://evil.com")).toBe("/home");
  expect(safeNext(null)).toBe("/home");
});

it("stores the session and redirects to next on success", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ token: "T", refreshToken: "R" }), { status: 200 })));
  await expect(loginAction({}, form({ email: "a@b.co", password: "pw", remember: "on", next: "/runs" }))).rejects.toThrow("NEXT_REDIRECT:/runs");
  expect(set).toHaveBeenCalledWith("karya_session", "T", expect.any(Object));
  expect(set).toHaveBeenCalledWith("karya_refresh", "R", expect.any(Object));
});

it("returns a friendly error on 401", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
  await expect(loginAction({}, form({ email: "a@b.co", password: "bad" }))).resolves.toEqual({ error: "That email and password don't match." });
});

it("shows the backend message for a sign-in problem", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: "NO_WORKSPACE", message: "You have no workspace" }), { status: 422 })));
  await expect(loginAction({}, form({ email: "a@b.co", password: "pw" }))).resolves.toEqual({ error: "You have no workspace" });
});
```

`src/app/(auth)/LoginForm.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { LoginForm } from "./LoginForm";
import { vi } from "vitest";

vi.mock("./actions", () => ({ loginAction: vi.fn() }));

it("renders labelled fields and a remember checkbox", () => {
  render(<LoginForm next="/runs" />);
  expect(screen.getByLabelText("Email")).toBeInTheDocument();
  expect(screen.getByLabelText("Password")).toBeInTheDocument();
  expect(screen.getByLabelText(/keep me signed in/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /sign in/i })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /create an account/i })).toHaveAttribute("href", "/signup");
});
```

- [ ] **Step 2: Verify failure** then implement.

`src/lib/api/auth.ts`:
```ts
import { apiFetch } from "./http";

const post = (path: string, body: unknown) =>
  fetch(`${process.env.BACKEND_URL}/api/v1/auth${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });

export const authApi = {
  login: (b: { email: string; password: string; remember: boolean }) => post("/login", b),
  signup: (b: { fullName: string; orgName: string; email: string; password: string; side: "BUYER" | "DEVELOPER" }) => post("/signup", b),
  verifyEmail: (token: string) => post("/email/verify", { token }),
  resendVerification: () => apiFetch("/api/v1/auth/email/resend", { method: "POST" }),
  forgot: (email: string) => post("/password/forgot", { email }),
  reset: (token: string, password: string) => post("/password/reset", { token, password }),
  logout: (refreshToken: string) => post("/logout", { refreshToken }),
};
```

`src/app/(auth)/actions.ts`:
```ts
"use server";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { authApi } from "@/lib/api/auth";
import type { AuthResponse } from "@/lib/api/types";
import { REFRESH_COOKIE, clearSession, writeSession } from "@/lib/session";

export type FormState = { error?: string; ok?: string };

export function safeNext(next: string | null | undefined): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/home";
}

async function messageOf(res: Response, fallback: string): Promise<string> {
  const body = (await res.json().catch(() => null)) as { message?: string } | null;
  return body?.message || fallback;
}

export async function loginAction(_prev: FormState, fd: FormData): Promise<FormState> {
  const res = await authApi.login({
    email: String(fd.get("email") ?? ""),
    password: String(fd.get("password") ?? ""),
    remember: fd.get("remember") === "on",
  });
  if (res.status === 401) return { error: "That email and password don't match." };
  if (!res.ok) return { error: await messageOf(res, "We couldn't sign you in. Try again.") };
  writeSession(cookies(), (await res.json()) as AuthResponse);
  redirect(safeNext(String(fd.get("next") ?? "")));
}

export async function signupAction(_prev: FormState, fd: FormData): Promise<FormState> {
  const res = await authApi.signup({
    fullName: String(fd.get("fullName") ?? ""),
    orgName: String(fd.get("orgName") ?? ""),
    email: String(fd.get("email") ?? ""),
    password: String(fd.get("password") ?? ""),
    side: fd.get("side") === "DEVELOPER" ? "DEVELOPER" : "BUYER",
  });
  if (res.status === 409) return { error: "An account with that email already exists. Sign in instead." };
  if (!res.ok) return { error: await messageOf(res, "We couldn't create your account. Check the details and try again.") };
  writeSession(cookies(), (await res.json()) as AuthResponse);
  redirect("/home");
}

export async function forgotAction(_prev: FormState, fd: FormData): Promise<FormState> {
  await authApi.forgot(String(fd.get("email") ?? ""));
  return { ok: "If that email has an account, a reset link is on its way." };
}

export async function resetAction(_prev: FormState, fd: FormData): Promise<FormState> {
  const res = await authApi.reset(String(fd.get("token") ?? ""), String(fd.get("password") ?? ""));
  if (!res.ok) return { error: await messageOf(res, "That reset link is invalid or has expired.") };
  redirect("/login");
}

export async function logoutAction() {
  const jar = cookies();
  const refresh = jar.get(REFRESH_COOKIE)?.value;
  if (refresh) await authApi.logout(refresh).catch(() => null);
  clearSession(jar);
  redirect("/login");
}
```

`src/app/(auth)/LoginForm.tsx`:
```tsx
"use client";
import Link from "next/link";
import { useFormState, useFormStatus } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { loginAction } from "./actions";
import styles from "./auth.module.css";

function Submit({ children }: { children: React.ReactNode }) {
  const { pending } = useFormStatus();
  return <Button type="submit" loading={pending}>{children}</Button>;
}

export function LoginForm({ next }: { next: string }) {
  const [state, action] = useFormState(loginAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Sign in</h1>
      <input type="hidden" name="next" value={next} />
      <Field label="Email" name="email" type="email" autoComplete="email" required />
      <Field label="Password" name="password" type="password" autoComplete="current-password" required />
      <label className={styles.check}><input type="checkbox" name="remember" /> Keep me signed in on this device</label>
      {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
      <Submit>Sign in</Submit>
      <p className={styles.alt}><Link href="/forgot">Forgot your password?</Link></p>
      <p className={styles.alt}>New to Karya? <Link href="/signup">Create an account</Link></p>
    </form>
  );
}
```

`src/app/(auth)/SignupForm.tsx`:
```tsx
"use client";
import Link from "next/link";
import { useFormState, useFormStatus } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { signupAction } from "./actions";
import styles from "./auth.module.css";

function Submit() {
  const { pending } = useFormStatus();
  return <Button type="submit" loading={pending}>Create account</Button>;
}

export function SignupForm() {
  const [state, action] = useFormState(signupAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Create your account</h1>
      <fieldset className={styles.side}>
        <legend>I want to</legend>
        <label><input type="radio" name="side" value="BUYER" defaultChecked /> Run agents for my team</label>
        <label><input type="radio" name="side" value="DEVELOPER" /> Build agents</label>
      </fieldset>
      <Field label="Your name" name="fullName" autoComplete="name" required />
      <Field label="Company" name="orgName" autoComplete="organization" required />
      <Field label="Email" name="email" type="email" autoComplete="email" required />
      <Field label="Password" name="password" type="password" autoComplete="new-password" minLength={8} required />
      {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
      <Submit />
      <p className={styles.alt}>Have an account? <Link href="/login">Sign in</Link></p>
    </form>
  );
}
```

`src/app/(auth)/layout.tsx`:
```tsx
import styles from "./auth.module.css";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <div className={styles.brand}>Karya</div>
        {children}
      </div>
    </main>
  );
}
```

`src/app/(auth)/auth.module.css`:
```css
.page { min-height: 100vh; display: grid; place-items: center; padding: 24px 16px; }
.card { width: 100%; max-width: 420px; background: var(--s); border: 1px solid var(--l); border-radius: 16px; padding: 32px; box-shadow: 0 8px 30px rgba(13,26,48,0.06); }
.brand { font-weight: 700; color: var(--b); margin-bottom: 20px; }
.form { display: flex; flex-direction: column; gap: 16px; }
.form h1 { font-size: 22px; }
.check { display: flex; gap: 8px; align-items: center; font-size: 13px; color: var(--ink2); min-height: 44px; }
.err { color: var(--bad); margin: 0; }
.alt { margin: 0; font-size: 13px; color: var(--ink2); }
.side { border: 1px solid var(--l); border-radius: 10px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
.side legend { font-size: 13px; font-weight: 600; color: var(--ink2); padding: 0 4px; }
@media (max-width: 480px) { .card { padding: 24px 20px; } }
```

Pages:

`src/app/(auth)/login/page.tsx`:
```tsx
import { LoginForm } from "../LoginForm";
import { safeNext } from "../actions";

export default function Page({ searchParams }: { searchParams: { next?: string } }) {
  return <LoginForm next={safeNext(searchParams.next)} />;
}
```

`src/app/(auth)/signup/page.tsx`:
```tsx
import { SignupForm } from "../SignupForm";

export default function Page() {
  return <SignupForm />;
}
```

`src/app/(auth)/verify/page.tsx`:
```tsx
import Link from "next/link";
import { authApi } from "@/lib/api/auth";

export default async function Page({ searchParams }: { searchParams: { token?: string } }) {
  const ok = searchParams.token ? (await authApi.verifyEmail(searchParams.token)).ok : false;
  return (
    <div>
      <h1>{ok ? "Email confirmed" : "That link didn't work"}</h1>
      <p>{ok ? "Thanks. You can carry on." : "The link is invalid or has expired. Sign in and ask for a new one."}</p>
      <p><Link href="/home">Go to your workspace</Link></p>
    </div>
  );
}
```

`src/app/(auth)/forgot/page.tsx`:
```tsx
"use client";
import Link from "next/link";
import { useFormState } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { forgotAction } from "../actions";
import styles from "../auth.module.css";

export default function Page() {
  const [state, action] = useFormState(forgotAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Reset your password</h1>
      <Field label="Email" name="email" type="email" required />
      {state.ok && <p role="status">{state.ok}</p>}
      <Button type="submit">Send reset link</Button>
      <p className={styles.alt}><Link href="/login">Back to sign in</Link></p>
    </form>
  );
}
```

`src/app/(auth)/reset/page.tsx`:
```tsx
"use client";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { useFormState } from "react-dom";
import { Button } from "@/ui/Button";
import { Field } from "@/ui/Field";
import { resetAction } from "../actions";
import styles from "../auth.module.css";

function Form() {
  const token = useSearchParams().get("token") ?? "";
  const [state, action] = useFormState(resetAction, {});
  return (
    <form action={action} className={styles.form}>
      <h1>Choose a new password</h1>
      <input type="hidden" name="token" value={token} />
      <Field label="New password" name="password" type="password" minLength={8} autoComplete="new-password" required />
      {state.error && <p role="alert" className={styles.err}>{state.error}</p>}
      <Button type="submit">Save password</Button>
    </form>
  );
}

export default function Page() {
  return <Suspense><Form /></Suspense>;
}
```

`src/app/page.tsx`:
```tsx
import { redirect } from "next/navigation";

export default function Page() {
  redirect("/home");
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run "src/app/(auth)" && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): sign in, sign up, verify and password reset"
```

---

### Task 6: Authenticated layout and workspace home

**Files:**
- Create: `src/lib/format.ts`, `src/lib/api/me.ts`, `src/lib/api/runs.ts` (list only for now), `src/lib/api/connections.ts` (`listMyConnections` only), `src/app/(app)/layout.tsx`, `src/app/(app)/home/page.tsx`, `src/features/home/HomeView.tsx`, `HomeView.module.css`
- Modify: `src/lib/api/types.ts` (append run list and connection types)
- Test: `src/lib/format.test.ts`, `src/features/home/HomeView.test.tsx`

**Interfaces:**
- Produces:
  - `formatMoney(n: number, currency = "INR"): string`, e.g. `formatMoney(38412.6)` → `"₹38,412.60"`; whole numbers drop decimals: `formatMoney(500)` → `"₹500"`
  - `formatBytes(n: number): string`, e.g. `1153434` → `"1.1 MB"`
  - `humanize(resource: string): string`, `bank_statement` → `"Bank statement"`
  - `formatTime(iso: string | null): string` (`"10:05"` in the user's locale, `"–"` for null)
  - types: `RunStatus`, `RunGroup`, `TerminalReason`, `RunListItem`, `RunListResponse`, `ConnectionSummary`
  - `getMe()`, `listRuns(query?: RunQuery): Promise<RunListResponse>`, `listMyConnections(): Promise<ConnectionSummary[]>`
  - `HomeView({ fullName, role, needsYou: RunListItem[], connections: ConnectionSummary[] })`
- Consumes: `api` from `http.ts`, `AppShell`, `buyerRailItems`, `railFor`, `isBuyer`.

- [ ] **Step 1: Failing tests**

`src/lib/format.test.ts`:
```ts
import { formatBytes, formatMoney, humanize } from "./format";

it("formats rupees with Indian grouping", () => {
  expect(formatMoney(500)).toBe("₹500");
  expect(formatMoney(3841260)).toBe("₹38,41,260");
  expect(formatMoney(148.5)).toBe("₹148.50");
});
it("formats bytes", () => {
  expect(formatBytes(0)).toBe("0 B");
  expect(formatBytes(1153434)).toBe("1.1 MB");
  expect(formatBytes(5 * 1024 ** 3)).toBe("5 GB");
});
it("humanizes resource names", () => {
  expect(humanize("bank_statement")).toBe("Bank statement");
  expect(humanize("open-invoices")).toBe("Open invoices");
});
```

`src/features/home/HomeView.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { HomeView } from "./HomeView";

const waiting = [{ id: "r1", displayId: "R-2291", agent: "Bank Reconciliation", version: "1.3.0", status: "AWAITING_APPROVAL" as const, terminalReason: null, reachedBlock: "gate:post", startedAt: "2026-09-29T04:35:00Z", completedAt: null, charged: 0, held: 500, decidedBy: null }];

it("greets by first name and lists runs that need approval", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_APPROVER" needsYou={waiting} connections={[]} />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Priya");
  expect(screen.getByRole("link", { name: /R-2291/ })).toHaveAttribute("href", "/runs/R-2291");
});

it("shows an empty state that points to the store when nothing is connected", () => {
  render(<HomeView fullName="Priya Shah" role="BUYER_ADMIN" needsYou={[]} connections={[]} />);
  expect(screen.getByText(/nothing is waiting for you/i)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /browse the agent store/i })).toHaveAttribute("href", "/store");
});

it("shows a holding message for developers and admins", () => {
  render(<HomeView fullName="Dev Person" role="DEVELOPER" needsYou={[]} connections={[]} />);
  expect(screen.getByText(/developer console is being rebuilt/i)).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: /agent store/i })).toBeNull();
});
```

- [ ] **Step 2: Verify failure, then implement**

`src/lib/format.ts`:
```ts
export function formatMoney(n: number, currency = "INR"): string {
  const symbol = currency === "INR" ? "₹" : `${currency} `;
  const whole = Number.isInteger(n);
  return symbol + n.toLocaleString("en-IN", { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 });
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  const text = Number.isInteger(v) ? String(v) : v.toFixed(1);
  return `${text} ${units[i]}`;
}

export function humanize(resource: string): string {
  const s = resource.replace(/[_-]+/g, " ").trim();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function formatTime(iso: string | null): string {
  if (!iso) return "–";
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
}
```

Append to `src/lib/api/types.ts`:
```ts
export type RunStatus =
  | "PENDING" | "PENDING_BUILD" | "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "EXPIRED";
export type RunGroup = "needs_you" | "running" | "finished" | "stopped";
export type TerminalReason =
  | "FINISHED" | "GATE_REJECTED" | "GATE_EXPIRED" | "COST_CAP_REACHED" | "TIMED_OUT" | "SCOPE_VIOLATIONS"
  | "AGENT_FAILED" | "CANCELLED" | "SANDBOX_LOST";

export interface RunListItem {
  id: string; displayId: string; agent: string; version: string; status: RunStatus;
  terminalReason: TerminalReason | null; reachedBlock: string | null;
  startedAt: string | null; completedAt: string | null; charged: number; held: number;
  decidedBy: { name: string } | null;
}
export interface RunListResponse {
  items: RunListItem[]; counts: Record<RunGroup, number>; page: number; size: number; total: number;
}
export interface RunQuery { group?: RunGroup; agentId?: string; q?: string; from?: string; to?: string; page?: number; size?: number }

export interface ConnectionSummary {
  id: string; agentId: string; agentName: string; grantedScopes: string[]; spendCap: number; status: "ACTIVE" | "REVOKED";
}
```

`src/lib/api/me.ts`:
```ts
import { api } from "./http";
import type { MeResponse } from "./types";

export const getMe = () => api<MeResponse>("/api/v1/me");
```

`src/lib/api/runs.ts`:
```ts
import { api } from "./http";
import type { RunListResponse, RunQuery } from "./types";

export function runQueryString(q: RunQuery = {}): string {
  const p = new URLSearchParams();
  Object.entries(q).forEach(([k, v]) => { if (v !== undefined && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const listRuns = (q: RunQuery = {}) => api<RunListResponse>(`/api/v1/runs${runQueryString(q)}`);
```

`src/lib/api/connections.ts`:
```ts
import { api } from "./http";
import type { ConnectionSummary } from "./types";

export const listMyConnections = () => api<ConnectionSummary[]>("/api/v1/connections/mine");
```

`src/app/(app)/layout.tsx`:
```tsx
import { redirect } from "next/navigation";
import { AppShell } from "@/ui/AppShell";
import { buyerRailItems } from "@/ui/Rail";
import { getMe } from "@/lib/api/me";
import { listRuns } from "@/lib/api/runs";
import { getSession } from "@/lib/session";
import { isBuyer } from "@/lib/roles";

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const session = await getSession();
  if (!session) redirect("/login");
  const me = await getMe();
  const active = me.memberships.find((m) => m.orgId === me.activeOrgId);
  const initials = me.fullName.split(/\s+/).map((w) => w[0]).slice(0, 2).join("").toUpperCase();
  let needsYou = 0;
  if (isBuyer(session.role)) {
    needsYou = await listRuns({ group: "needs_you", size: 1 }).then((r) => r.counts.needs_you).catch(() => 0);
  }
  // Developer and admin rails arrive in P3 and P4; until then they get the workspace item only.
  const items = isBuyer(session.role) ? buyerRailItems(needsYou) : [{ href: "/home", label: "Workspace", icon: "home" as const }];
  return (
    <AppShell items={items} orgName={active?.orgName ?? "Karya"} initials={initials}>
      {children}
    </AppShell>
  );
}
```

`src/app/(app)/home/page.tsx`:
```tsx
import { getMe } from "@/lib/api/me";
import { listRuns } from "@/lib/api/runs";
import { listMyConnections } from "@/lib/api/connections";
import { getSession } from "@/lib/session";
import { isBuyer } from "@/lib/roles";
import { HomeView } from "@/features/home/HomeView";

export default async function Page() {
  const session = (await getSession())!;
  const me = await getMe();
  if (!isBuyer(session.role)) {
    return <HomeView fullName={me.fullName} role={session.role} needsYou={[]} connections={[]} />;
  }
  const [runs, connections] = await Promise.all([listRuns({ group: "needs_you", size: 10 }), listMyConnections()]);
  return <HomeView fullName={me.fullName} role={session.role} needsYou={runs.items} connections={connections} />;
}
```

`src/features/home/HomeView.tsx`:
```tsx
import Link from "next/link";
import styles from "./HomeView.module.css";
import { EmptyState } from "@/ui/EmptyState";
import { Pill } from "@/ui/Pill";
import { formatMoney } from "@/lib/format";
import { isBuyer, type UserRole } from "@/lib/roles";
import type { ConnectionSummary, RunListItem } from "@/lib/api/types";

type Props = { fullName: string; role: UserRole; needsYou: RunListItem[]; connections: ConnectionSummary[] };

export function HomeView({ fullName, role, needsYou, connections }: Props) {
  const first = fullName.split(/\s+/)[0];
  if (!isBuyer(role)) {
    return (
      <section>
        <h1>Welcome, {first}</h1>
        <EmptyState
          title="Your console is coming back"
          body="The developer console is being rebuilt. Until then, use the Karya CLI and your API keys to publish and manage agents."
        />
      </section>
    );
  }
  return (
    <div className={styles.wrap}>
      <h1>Good day, {first}</h1>
      <section aria-labelledby="needs">
        <h2 id="needs">Needs you</h2>
        {needsYou.length === 0 ? (
          <EmptyState
            title="Nothing is waiting for you"
            body="When an agent asks for your approval, it shows up here."
            action={<Link href="/store">Browse the agent store</Link>}
          />
        ) : (
          <ul className={styles.list}>
            {needsYou.map((r) => (
              <li key={r.id}>
                <Link href={`/runs/${r.displayId}`}>{r.displayId} · {r.agent}</Link>
                <Pill tone="warn">Needs you</Pill>
                <span className="mono">{formatMoney(r.held)} held</span>
              </li>
            ))}
          </ul>
        )}
      </section>
      {connections.length > 0 && (
        <section aria-labelledby="agents">
          <h2 id="agents">Your agents</h2>
          <ul className={styles.list}>
            {connections.map((c) => (
              <li key={c.id}>
                <span>{c.agentName}</span>
                <Pill tone={c.status === "ACTIVE" ? "ok" : "neutral"}>{c.status === "ACTIVE" ? "Active" : "Revoked"}</Pill>
                <span className="mono">Up to {formatMoney(c.spendCap)} a run</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
```

`src/features/home/HomeView.module.css`:
```css
.wrap { display: flex; flex-direction: column; gap: 28px; }
.wrap h2 { font-size: 15px; margin-bottom: 10px; color: var(--ink2); }
.list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.list li { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); padding: 12px 16px; }
.list li > :first-child { flex: 1; min-width: 160px; font-weight: 600; }
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/lib/format.test.ts src/features/home && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): authenticated shell and workspace home"
```

---

### Task 7: P0 end-to-end test and gap list

**Files:**
- Create: `frontend/e2e/playwright.config.ts`, `frontend/e2e/p0-auth.spec.ts`, `docs/frontend-gaps.md`

- [ ] **Step 1: Playwright config**

`frontend/e2e/playwright.config.ts`:
```ts
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: /.*\.spec\.ts/,
  use: { baseURL: "http://localhost:3000" },
  webServer: {
    command: "npm run dev",
    cwd: "..",
    port: 3000,
    reuseExistingServer: true,
    env: { BACKEND_URL: process.env.BACKEND_URL ?? "http://localhost:8080" },
  },
});
```

- [ ] **Step 2: The P0 test**

`frontend/e2e/p0-auth.spec.ts`:
```ts
import { expect, test } from "@playwright/test";

test("a new buyer can sign up, land on home and sign in again", async ({ page }) => {
  const email = `p0-${Date.now()}@example.com`;
  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Priya Shah");
  await page.getByLabel("Company").fill("Meridian Traders");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/home$/);
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Priya");

  await page.context().clearCookies();
  await page.goto("/runs");
  await expect(page).toHaveURL(/\/login\?next=%2Fruns/);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/runs$/);
});
```

(`/runs` exists after Task 11. Until then this last step is expected to reach a 404. Run the full test at the end of Task 11, and run the first half now by commenting the last four lines in a scratch copy, not in the committed file.)

- [ ] **Step 3: Gap list**

`docs/frontend-gaps.md`: copy the table from spec section 10 (G1 to G9) and add one line: `| G10 | Sign-in with Google | Backend supports it (POST /auth/google) but it needs a Google client id, which is not configured |`. Add the heading `# Frontend gaps` and a first paragraph: "Mockup elements omitted because the backend has no data or route for them. Update this file whenever a phase omits something."

- [ ] **Step 4: Run what can run and commit**

Run (backend and Postgres up): `cd frontend && npm run test:e2e -- p0-auth` (expected: passes through sign-up and home; final redirect step verified after Task 11).
```bash
git add frontend docs/frontend-gaps.md && git commit -m "test(frontend): P0 sign-up e2e and the gap list"
```

---

# P1: buyer screens

### Task 8: API types and clients for runs, datasets, connections, catalog

**Files:**
- Modify: `src/lib/api/types.ts`, `runs.ts`, `connections.ts`
- Create: `src/lib/api/datasets.ts`, `src/lib/api/catalog.ts`, `src/lib/api/fixtures/*.json`
- Test: `src/lib/api/clients.test.ts`

**Interfaces:**
- Produces (append to `types.ts`):
```ts
export type BlockState = "DONE" | "WAITING" | "FAILED" | "SKIPPED" | "IDLE";
export interface FlowBlock { block: string; state: BlockState; metrics: Record<string, unknown> | null; gateId: string | null; flag: "UNDECLARED" | null }
export interface FlowView { blocks: FlowBlock[]; reachedBlock: string | null; spend: { charged: number; cap: number }; deadlineAt: string | null; blockedAttempts: number }
export type RunStepKind = "READ" | "WRITE" | "GATE" | "COST" | "COMPLETE" | "FAILED" | "TIMEOUT" | "COST_CAP_REACHED" | "DATA_READ" | "DATA_WRITE" | "WRITE_STAGED" | "GATE_REQUESTED" | "GATE_DECIDED" | "LLM_CALL" | "USAGE" | "SCOPE_VIOLATION" | "AGENT_LOG" | "CANCELLED" | "NETWORK_BLOCKED";
export interface RunStep { kind: RunStepKind; source?: "PLATFORM" | "AGENT"; detail: string; createdAt: string }
export interface RunDetail { id: string; displayId: string; status: RunStatus; terminalReason: TerminalReason | null; costCap: number; totalCharged: number; steps: RunStep[] }
export type GateStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
export interface GateDetail { gateId: string; action: string; summary: string; payload: unknown; status: GateStatus; expiresAt: string; estimatedUnits: number | null; costIfApproved: number | null; preview: { columns?: string[]; rows: Record<string, unknown>[]; totalRows: number; total?: number } | null; decidedVia: "WEB" | "EMAIL_LINK" | "SLACK_LINK" | null; decidedByUserId: string | null; decidedAt: string | null; note: string | null }
export interface RunSnapshot { detail: RunDetail; flow: FlowView; gates: GateDetail[] }
export type DataFormat = string;
export interface DatasetUsage { connectionId: string; resource: string; agent: string; version: string; access: string }
export interface Dataset { id: string; displayName: string; sourceLabel: string | null; format: DataFormat; rowCount: number | null; sizeBytes: number; columns: string[]; sha256: string; uploadedBy: string; createdAt: string; piiScan: unknown; usage?: DatasetUsage[] }
export interface Storage { usedBytes: number; limitBytes: number }
export interface CheckResult { ok: boolean; rowCount: number; columnsMatch: boolean; missingColumns: string[]; rowErrorCount: number; rowErrors: { row: number; column: string | null; message: string }[] }
export interface Binding { resource: string; datasetId: string; displayName: string; format: DataFormat; rowCount: number | null; boundAt: string }
export interface CatalogListing { agentId: string; agentName: string; category: string; versionId: string; versionNumber: string }
export interface Scope { type: "READ" | "WRITE"; resource: string; description: string; requiresApproval: boolean }
export interface ResourceDecl { name: string; format: DataFormat; schema: { required: string[]; properties: Record<string, string> } | null }
export interface CostEnvelope { currency: string; maxPerRun: number; pricingUnit: "METERED" | "OUTCOME" | "SUBSCRIPTION"; unitPrice: number; unitLabel: string }
export interface ApprovalGateDecl { trigger: string; description: string; timeoutMinutes: number; preview: { columns?: string[]; totalField?: string } | null }
export interface Manifest { name: string; version: string; scopes: Scope[]; networkAllowlist: string[]; costEnvelope: CostEnvelope; approvalGates: ApprovalGateDecl[]; dataClasses: string[]; resources: ResourceDecl[] | null; llm: unknown | null }
export interface VersionDetail { id: string; agentId: string; agentName: string; versionNumber: string; status: string; manifest: Manifest }
export interface RunResponse { id: string; displayId: string; status: RunStatus; terminalReason: TerminalReason | null }
```
- Produces (functions, all use `api` from `http.ts`):
  - `runs.ts`: `getRunDetail(ref)`, `getRunFlow(ref)`, `getRunGates(ref)`, `getRunSnapshot(ref): Promise<RunSnapshot>` (three calls in parallel), `decideGate(runRef, gateId, decision: "APPROVE" | "REJECT", note?: string): Promise<RunResponse>`, `triggerRun(connectionId): Promise<RunResponse>`
  - `datasets.ts`: `listDatasets(): Promise<Dataset[]>` (calls `?include=usage`), `uploadDataset(fd: FormData): Promise<Dataset>`, `deleteDataset(id)`, `checkDataset(id, agentVersionId): Promise<CheckResult>`, `getStorage(orgId): Promise<Storage>`
  - `connections.ts` adds: `createConnection({ agentId, grantedScopes, spendCap }): Promise<{ id: string; ... }>`, `bindResource(connectionId, resource, datasetId): Promise<Binding>`, `unbindResource(connectionId, resource)`, `listBindings(connectionId): Promise<Binding[]>`
  - `catalog.ts`: `listCatalog(): Promise<CatalogListing[]>`, `getVersion(agentId, versionId): Promise<VersionDetail>`
- Before writing the types, open `backend/src/main/java/com/agentstore/platform/execution/GateDecisionRequest.java` and `GateDecision.java` and confirm the decision enum values are exactly `APPROVE` and `REJECT`. If they differ, use the real values in `decideGate`.

- [ ] **Step 1: Write failing tests using fixtures**

Create `src/lib/api/fixtures/run-list.json`, `flow.json`, `datasets.json` by copying the exact JSON shapes described by the DTOs (one representative item each; for `flow.json` include a DONE read block, a WAITING gate block with `gateId`, an IDLE charge block and one `UNDECLARED` block).

`src/lib/api/clients.test.ts`:
```ts
import { vi } from "vitest";
import runList from "./fixtures/run-list.json";
import flow from "./fixtures/flow.json";
import datasets from "./fixtures/datasets.json";

const calls: { url: string; init?: RequestInit }[] = [];
vi.mock("next/headers", () => ({ cookies: () => ({ get: () => ({ value: "tok" }) }) }));
vi.mock("next/navigation", () => ({ redirect: vi.fn() }));

import { runQueryString, getRunSnapshot, decideGate } from "./runs";
import { listDatasets } from "./datasets";

function respond(map: Record<string, unknown>) {
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    const key = Object.keys(map).find((k) => url.includes(k))!;
    return new Response(JSON.stringify(map[key]), { status: 200 });
  }));
}

beforeEach(() => { calls.length = 0; process.env.BACKEND_URL = "http://be"; });

it("builds a run query string and skips empty values", () => {
  expect(runQueryString({ group: "needs_you", q: "", page: 0 })).toBe("?group=needs_you&page=0");
  expect(runQueryString({})).toBe("");
});

it("assembles a run snapshot from detail, flow and gates", async () => {
  respond({ "/flow": flow, "/gates": [], "/runs/R-1": { id: "x", displayId: "R-1", status: "RUNNING", terminalReason: null, costCap: 500, totalCharged: 0, steps: [] } });
  const snap = await getRunSnapshot("R-1");
  expect(snap.detail.displayId).toBe("R-1");
  expect(snap.flow.blocks.length).toBeGreaterThan(0);
  expect(snap.gates).toEqual([]);
});

it("posts a gate decision with the note", async () => {
  respond({ "/decision": { id: "x", displayId: "R-1", status: "RUNNING", terminalReason: null } });
  await decideGate("R-1", "g1", "APPROVE", "looks right");
  expect(calls[0].url).toContain("/api/v1/runs/R-1/gates/g1/decision");
  expect(JSON.parse(String(calls[0].init?.body))).toEqual({ decision: "APPROVE", note: "looks right" });
});

it("requests datasets with usage", async () => {
  respond({ "/datasets": datasets });
  const list = await listDatasets();
  expect(calls[0].url).toContain("/api/v1/datasets?include=usage");
  expect(list[0].displayName).toBeTruthy();
});
```

- [ ] **Step 2: Verify failure, implement**

`src/lib/api/runs.ts` (replace):
```ts
import { api, json } from "./http";
import type { FlowView, GateDetail, RunDetail, RunListResponse, RunQuery, RunResponse, RunSnapshot } from "./types";

export function runQueryString(q: RunQuery = {}): string {
  const p = new URLSearchParams();
  Object.entries(q).forEach(([k, v]) => { if (v !== undefined && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}

const enc = encodeURIComponent;

export const listRuns = (q: RunQuery = {}) => api<RunListResponse>(`/api/v1/runs${runQueryString(q)}`);
export const getRunDetail = (ref: string) => api<RunDetail>(`/api/v1/runs/${enc(ref)}`);
export const getRunFlow = (ref: string) => api<FlowView>(`/api/v1/runs/${enc(ref)}/flow`);
export const getRunGates = (ref: string) => api<GateDetail[]>(`/api/v1/runs/${enc(ref)}/gates`);

export async function getRunSnapshot(ref: string): Promise<RunSnapshot> {
  const [detail, flow, gates] = await Promise.all([getRunDetail(ref), getRunFlow(ref), getRunGates(ref)]);
  return { detail, flow, gates };
}

export const decideGate = (runRef: string, gateId: string, decision: "APPROVE" | "REJECT", note?: string) =>
  api<RunResponse>(`/api/v1/runs/${enc(runRef)}/gates/${gateId}/decision`, { method: "POST", body: json({ decision, note: note ?? null }) });

export const triggerRun = (connectionId: string) =>
  api<RunResponse>("/api/v1/runs", { method: "POST", body: json({ connectionId }) });
```

`src/lib/api/datasets.ts`:
```ts
import { api, json } from "./http";
import type { CheckResult, Dataset, Storage } from "./types";

export const listDatasets = () => api<Dataset[]>("/api/v1/datasets?include=usage");
export const uploadDataset = (fd: FormData) => api<Dataset>("/api/v1/datasets", { method: "POST", body: fd });
export const deleteDataset = (id: string) => api<void>(`/api/v1/datasets/${id}`, { method: "DELETE" });
export const checkDataset = (id: string, agentVersionId: string) =>
  api<CheckResult>(`/api/v1/datasets/${id}/check`, { method: "POST", body: json({ agentVersionId }) });
export const getStorage = (orgId: string) => api<Storage>(`/api/v1/orgs/${orgId}/storage`);
```

`src/lib/api/connections.ts` (replace):
```ts
import { api, json } from "./http";
import type { Binding, ConnectionSummary } from "./types";

export const listMyConnections = () => api<ConnectionSummary[]>("/api/v1/connections/mine");
export const createConnection = (b: { agentId: string; grantedScopes: string[]; spendCap: number }) =>
  api<{ id: string; agentId: string; grantedScopes: string[]; spendCap: number; status: string }>("/api/v1/connections", { method: "POST", body: json(b) });
export const bindResource = (connectionId: string, resource: string, datasetId: string) =>
  api<Binding>(`/api/v1/connections/${connectionId}/bindings/${encodeURIComponent(resource)}`, { method: "PUT", body: json({ datasetId }) });
export const unbindResource = (connectionId: string, resource: string) =>
  api<void>(`/api/v1/connections/${connectionId}/bindings/${encodeURIComponent(resource)}`, { method: "DELETE" });
export const listBindings = (connectionId: string) => api<Binding[]>(`/api/v1/connections/${connectionId}/bindings`);
```

`src/lib/api/catalog.ts`:
```ts
import { api } from "./http";
import type { CatalogListing, VersionDetail } from "./types";

export const listCatalog = () => api<CatalogListing[]>("/api/v1/catalog");
export const getVersion = (agentId: string, versionId: string) => api<VersionDetail>(`/api/v1/agents/${agentId}/versions/${versionId}`);
```

Also append the types listed under Interfaces to `types.ts`, and remove the `runQueryString`/`listRuns`/`listMyConnections` duplicates from Task 6 (they are replaced by the files above).

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/lib && npx tsc --noEmit` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): typed API clients for runs, datasets, connections and catalog"
```

---

### Task 9: Flow blocks (shared by Store and Live run)

**Files:**
- Create: `src/features/flow/blocks.ts`, `blocks.test.ts`, `FlowBlocks.tsx`, `FlowBlocks.module.css`, `FlowBlocks.test.tsx`

**Interfaces:**
- Produces:
  - `type BlockKind = "Reads" | "Drafts" | "Writes" | "Asks you" | "Thinks" | "Charges"`
  - `interface BlockView { id: string; kind: BlockKind; title: string; body: string; state: BlockState; metric: string | null; undeclared: boolean }`
  - `blockKind(id: string): BlockKind` (`read:` Reads, `draft:` Drafts, `write:` Writes, `gate:` Asks you, `llm` Thinks, `charge` Charges)
  - `blocksFromManifest(m: Manifest): { id: string; state: "IDLE" }[]` in the backend's order: scopes in order (`read:R`, `draft:R` when a WRITE scope `requiresApproval`, else `write:R`), then `gate:<trigger>` per gate, `llm` when `m.llm` is non-null, then `charge`
  - `toBlockViews(blocks: { id: string; state: BlockState; metrics?: Record<string, unknown> | null; flag?: string | null }[], m: Manifest | null): BlockView[]`
  - `FlowBlocks({ blocks: BlockView[], selectedId?: string, onSelect?: (id: string) => void })`
- Consumes: `Manifest`, `BlockState` from `@/lib/api/types`; `humanize`, `formatMoney` from `@/lib/format`.

- [ ] **Step 1: Failing tests**

`src/features/flow/blocks.test.ts`:
```ts
import { blockKind, blocksFromManifest, toBlockViews } from "./blocks";
import type { Manifest } from "@/lib/api/types";

const manifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Your statement lines, CSV", requiresApproval: false },
    { type: "WRITE", resource: "ledger", description: "Post to your ledger", requiresApproval: true },
  ],
  networkAllowlist: [], dataClasses: ["FINANCIAL"], resources: null, llm: {},
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  approvalGates: [{ trigger: "post_entries", description: "Before anything is posted", timeoutMinutes: 30, preview: null }],
};

it("derives block ids in the backend's order", () => {
  expect(blocksFromManifest(manifest).map((b) => b.id)).toEqual(["read:bank_statement", "draft:ledger", "gate:post_entries", "llm", "charge"]);
});

it("maps ids to kinds", () => {
  expect(blockKind("read:x")).toBe("Reads");
  expect(blockKind("write:x")).toBe("Writes");
  expect(blockKind("gate:x")).toBe("Asks you");
  expect(blockKind("llm")).toBe("Thinks");
  expect(blockKind("charge")).toBe("Charges");
});

it("builds titles, bodies and metrics", () => {
  const views = toBlockViews(
    [{ id: "read:bank_statement", state: "DONE", metrics: { count: 412 } }, { id: "charge", state: "IDLE" }],
    manifest,
  );
  expect(views[0]).toMatchObject({ kind: "Reads", title: "Bank statement", body: "Your statement lines, CSV", metric: "412 rows" });
  expect(views[1]).toMatchObject({ kind: "Charges", body: "₹2 per match. Never more than ₹500 a run." });
});

it("flags a block the manifest did not declare", () => {
  const [v] = toBlockViews([{ id: "write:secret", state: "FAILED", flag: "UNDECLARED" }], manifest);
  expect(v.undeclared).toBe(true);
  expect(v.body).toBe("This agent did not say it would do this.");
});
```

`src/features/flow/FlowBlocks.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FlowBlocks } from "./FlowBlocks";
import type { BlockView } from "./blocks";

const base = { title: "T", body: "B", metric: null, undeclared: false } as const;
const views: BlockView[] = [
  { id: "a", kind: "Reads", state: "DONE", ...base },
  { id: "b", kind: "Asks you", state: "WAITING", ...base },
  { id: "c", kind: "Writes", state: "FAILED", ...base },
  { id: "d", kind: "Charges", state: "SKIPPED", ...base },
  { id: "e", kind: "Thinks", state: "IDLE", ...base },
];

it("states each block's status in words, not only colour", () => {
  render(<FlowBlocks blocks={views} />);
  ["Done", "Waiting for you", "Failed", "Skipped", "Not started"].forEach((t) => expect(screen.getByText(t)).toBeInTheDocument());
});

it("selects a block when clicked", async () => {
  const onSelect = vi.fn();
  render(<FlowBlocks blocks={views} onSelect={onSelect} />);
  await userEvent.click(screen.getAllByRole("button")[1]);
  expect(onSelect).toHaveBeenCalledWith("b");
});

it("marks undeclared blocks", () => {
  render(<FlowBlocks blocks={[{ ...views[0], undeclared: true }]} />);
  expect(screen.getByText(/not declared/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Verify failure, implement**

`src/features/flow/blocks.ts`:
```ts
import { formatMoney, humanize } from "@/lib/format";
import type { BlockState, Manifest } from "@/lib/api/types";

export type BlockKind = "Reads" | "Drafts" | "Writes" | "Asks you" | "Thinks" | "Charges";

export interface BlockView {
  id: string; kind: BlockKind; title: string; body: string; state: BlockState; metric: string | null; undeclared: boolean;
}

export function blockKind(id: string): BlockKind {
  if (id.startsWith("read:")) return "Reads";
  if (id.startsWith("draft:")) return "Drafts";
  if (id.startsWith("write:")) return "Writes";
  if (id.startsWith("gate:")) return "Asks you";
  if (id === "llm") return "Thinks";
  return "Charges";
}

export function blocksFromManifest(m: Manifest): { id: string; state: "IDLE" }[] {
  const ids: string[] = [];
  for (const s of m.scopes) {
    ids.push(s.type === "READ" ? `read:${s.resource}` : `${s.requiresApproval ? "draft" : "write"}:${s.resource}`);
  }
  m.approvalGates.forEach((g) => ids.push(`gate:${g.trigger}`));
  if (m.llm) ids.push("llm");
  ids.push("charge");
  return ids.map((id) => ({ id, state: "IDLE" as const }));
}

type In = { id: string; state: BlockState; metrics?: Record<string, unknown> | null; flag?: string | null };

function metricText(kind: BlockKind, metrics: Record<string, unknown> | null | undefined): string | null {
  if (!metrics) return null;
  if (kind === "Thinks" && typeof metrics.calls === "number") return `${metrics.calls} calls, ${metrics.tokens ?? 0} tokens`;
  if (typeof metrics.count === "number") return kind === "Reads" || kind === "Drafts" || kind === "Writes" ? `${metrics.count} rows` : `${metrics.count}`;
  return null;
}

export function toBlockViews(blocks: In[], m: Manifest | null): BlockView[] {
  return blocks.map((b) => {
    const kind = blockKind(b.id);
    const name = b.id.includes(":") ? b.id.slice(b.id.indexOf(":") + 1) : "";
    const undeclared = b.flag === "UNDECLARED";
    let title = kind === "Thinks" ? "Match with AI" : kind === "Charges" ? "Charges" : humanize(name);
    let body = "";
    if (undeclared) body = "This agent did not say it would do this.";
    else if (kind === "Reads" || kind === "Drafts" || kind === "Writes") body = m?.scopes.find((s) => s.resource === name)?.description ?? "";
    else if (kind === "Asks you") { title = "Your approval"; body = m?.approvalGates.find((g) => g.trigger === name)?.description ?? "Before anything is written"; }
    else if (kind === "Thinks") body = "Uses an AI model through Karya.";
    else if (m) {
      const c = m.costEnvelope;
      body = `${formatMoney(c.unitPrice, c.currency)} per ${humanize(c.unitLabel).toLowerCase()}. Never more than ${formatMoney(c.maxPerRun, c.currency)} a run.`;
    }
    return { id: b.id, kind, title, body, state: b.state, metric: metricText(kind, b.metrics), undeclared };
  });
}
```

`src/features/flow/FlowBlocks.tsx`:
```tsx
import styles from "./FlowBlocks.module.css";
import type { BlockView } from "./blocks";
import type { BlockState } from "@/lib/api/types";

const STATE_TEXT: Record<BlockState, string> = {
  DONE: "Done", WAITING: "Waiting for you", FAILED: "Failed", SKIPPED: "Skipped", IDLE: "Not started",
};

export function FlowBlocks({ blocks, selectedId, onSelect }: { blocks: BlockView[]; selectedId?: string; onSelect?: (id: string) => void }) {
  return (
    <ol className={styles.flow}>
      {blocks.map((b) => (
        <li key={b.id}>
          <button
            type="button"
            className={`${styles.block} ${styles[b.state]} ${selectedId === b.id ? styles.sel : ""}`}
            aria-pressed={selectedId === b.id}
            onClick={() => onSelect?.(b.id)}
          >
            <span className={styles.kind}>{b.kind}</span>
            <strong>{b.title}</strong>
            <span className={styles.body}>{b.body}</span>
            {b.metric && <span className={`${styles.metric} mono`}>{b.metric}</span>}
            {b.undeclared && <span className={styles.flag}>Not declared by the agent</span>}
            <span className={styles.state}>{STATE_TEXT[b.state]}</span>
          </button>
        </li>
      ))}
    </ol>
  );
}
```

`src/features/flow/FlowBlocks.module.css`:
```css
.flow { list-style: none; margin: 0; padding: 0; display: flex; gap: 12px; overflow-x: auto; align-items: stretch; }
.flow li { flex: 0 0 200px; display: flex; }
.block { width: 100%; text-align: left; display: flex; flex-direction: column; gap: 6px; padding: 14px; background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); font: inherit; color: inherit; cursor: pointer; min-height: 132px; }
.kind { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; color: var(--ink3); }
.body { font-size: 12px; color: var(--ink2); }
.metric { font-size: 12px; color: var(--ink); }
.flag { font-size: 12px; font-weight: 600; color: var(--bad); }
.state { margin-top: auto; font-size: 12px; font-weight: 600; }
.sel { border-color: var(--b); box-shadow: 0 0 0 2px var(--sky2); }
.DONE .state { color: var(--ok); }
.DONE { border-color: var(--okS); }
.WAITING { background: var(--waS); border-color: var(--wa); }
.WAITING .state { color: var(--wa); }
.FAILED { background: var(--badS); border-color: var(--bad); }
.FAILED .state { color: var(--bad); }
.SKIPPED, .IDLE { background: var(--l2); }
.SKIPPED .state, .IDLE .state { color: var(--ink3); }
@media (max-width: 767px) {
  .flow { flex-direction: column; overflow: visible; }
  .flow li { flex: 1 1 auto; }
  .block { min-height: 0; }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/flow` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): flow blocks shared by the store and live run screens"
```

---

### Task 10: Gate card

**Files:**
- Create: `src/features/gates/GateCard.tsx`, `GateCard.module.css`, `GateCard.test.tsx`, `src/app/(app)/runs/actions.ts`

**Interfaces:**
- Produces:
  - `GateCard({ runRef, gate, canDecide, onDecided? })`, a client component
  - `decideGateAction(runRef: string, gateId: string, decision: "APPROVE" | "REJECT", note?: string): Promise<{ error?: string }>` (server action in `runs/actions.ts`, wraps `decideGate`, catches `ApiError` and returns its message)
  - `gateIsExpired(gate: GateDetail, nowMs: number): boolean` (status `PENDING` and `expiresAt` in the past, or status `EXPIRED`)
- Consumes: `GateDetail` from `@/lib/api/types`, `formatMoney`, `Button`.

- [ ] **Step 1: Failing tests** (Review Focus 2)

`src/features/gates/GateCard.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const decide = vi.fn().mockResolvedValue({});
vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: (...a: unknown[]) => decide(...a) }));

import { GateCard, gateIsExpired } from "./GateCard";
import type { GateDetail } from "@/lib/api/types";

const gate: GateDetail = {
  gateId: "g1", action: "post_entries", summary: "Post 142 journal entries for August?", payload: {},
  status: "PENDING", expiresAt: "2999-01-01T00:00:00Z", estimatedUnits: 142, costIfApproved: 284,
  preview: { columns: ["party", "amount"], rows: [{ party: "NEFT ACME 0814", amount: 48200 }], totalRows: 142, total: 3841260 },
  decidedVia: null, decidedByUserId: null, decidedAt: null, note: null,
};

beforeEach(() => decide.mockClear());

it("shows the summary, preview rows and cost", () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  expect(screen.getByText("Post 142 journal entries for August?")).toBeInTheDocument();
  expect(screen.getByText("NEFT ACME 0814")).toBeInTheDocument();
  expect(screen.getByText(/₹284/)).toBeInTheDocument();
  expect(screen.getByText(/142 rows/)).toBeInTheDocument();
});

it("approves through the server action", async () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  await userEvent.click(screen.getByRole("button", { name: "Approve" }));
  expect(decide).toHaveBeenCalledWith("R-1", "g1", "APPROVE", undefined);
});

it("does not show Approve or Reject to someone who cannot decide", () => {
  render(<GateCard runRef="R-1" gate={gate} canDecide={false} />);
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  expect(screen.queryByRole("button", { name: "Reject" })).toBeNull();
  expect(screen.getByText(/an approver has to decide/i)).toBeInTheDocument();
});

it("disables the buttons once the gate has expired", () => {
  render(<GateCard runRef="R-1" gate={{ ...gate, expiresAt: "2000-01-01T00:00:00Z" }} canDecide />);
  expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  expect(screen.getByText(/this request has expired/i)).toBeInTheDocument();
});

it("shows the outcome of a decided gate and no buttons", () => {
  render(<GateCard runRef="R-1" gate={{ ...gate, status: "APPROVED", decidedAt: "2026-09-29T10:00:00Z" }} canDecide />);
  expect(screen.getByText(/you approved this/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});

it("shows the backend's error when the decision fails", async () => {
  decide.mockResolvedValueOnce({ error: "Gate already decided" });
  render(<GateCard runRef="R-1" gate={gate} canDecide />);
  await userEvent.click(screen.getByRole("button", { name: "Reject" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Gate already decided");
});

it("expiry helper", () => {
  expect(gateIsExpired({ ...gate, expiresAt: "2000-01-01T00:00:00Z" }, Date.now())).toBe(true);
  expect(gateIsExpired(gate, Date.now())).toBe(false);
  expect(gateIsExpired({ ...gate, status: "EXPIRED" }, Date.now())).toBe(true);
});
```

- [ ] **Step 2: Verify failure, implement**

`src/app/(app)/runs/actions.ts`:
```ts
"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { decideGate } from "@/lib/api/runs";

export async function decideGateAction(runRef: string, gateId: string, decision: "APPROVE" | "REJECT", note?: string): Promise<{ error?: string }> {
  try {
    await decideGate(runRef, gateId, decision, note);
    revalidatePath(`/runs/${runRef}`);
    return {};
  } catch (e) {
    if (e instanceof ApiError) return { error: e.message };
    throw e;
  }
}
```

`src/features/gates/GateCard.tsx`:
```tsx
"use client";
import { useState, useTransition } from "react";
import styles from "./GateCard.module.css";
import { Button } from "@/ui/Button";
import { decideGateAction } from "@/app/(app)/runs/actions";
import { formatMoney } from "@/lib/format";
import type { GateDetail } from "@/lib/api/types";

export function gateIsExpired(g: GateDetail, nowMs: number): boolean {
  return g.status === "EXPIRED" || (g.status === "PENDING" && new Date(g.expiresAt).getTime() <= nowMs);
}

export function GateCard({ runRef, gate, canDecide }: { runRef: string; gate: GateDetail; canDecide: boolean }) {
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const expired = gateIsExpired(gate, Date.now());
  const decided = gate.status === "APPROVED" || gate.status === "REJECTED";

  const decide = (d: "APPROVE" | "REJECT") =>
    start(async () => {
      setError(null);
      const res = await decideGateAction(runRef, gate.gateId, d, undefined);
      if (res.error) setError(res.error);
    });

  const cols = gate.preview?.columns ?? (gate.preview?.rows[0] ? Object.keys(gate.preview.rows[0]) : []);

  return (
    <section className={styles.card} aria-labelledby={`g-${gate.gateId}`}>
      <h2 id={`g-${gate.gateId}`}>{gate.summary}</h2>
      {gate.costIfApproved != null && <p>Approving costs {formatMoney(gate.costIfApproved)}.</p>}
      {gate.preview && gate.preview.rows.length > 0 && (
        <div className={styles.scroll}>
          <table>
            <thead><tr>{cols.map((c) => <th key={c}>{c}</th>)}</tr></thead>
            <tbody>
              {gate.preview.rows.map((r, i) => (
                <tr key={i}>{cols.map((c) => <td key={c} className="mono">{String(r[c] ?? "")}</td>)}</tr>
              ))}
            </tbody>
          </table>
          <p className={styles.more}>
            Showing {gate.preview.rows.length} of {gate.preview.totalRows} rows
            {gate.preview.total != null ? `, total ${formatMoney(gate.preview.total)}` : ""}.
          </p>
        </div>
      )}
      {decided && <p role="status">{gate.status === "APPROVED" ? "You approved this." : "You rejected this."}</p>}
      {!decided && expired && <p role="status">This request has expired.</p>}
      {!decided && !canDecide && <p role="status">An approver has to decide this. You can see what it will do but not answer it.</p>}
      {!decided && canDecide && (
        <div className={styles.actions}>
          <Button variant="danger" disabled={expired} loading={pending} onClick={() => decide("REJECT")}>Reject</Button>
          <Button disabled={expired} loading={pending} onClick={() => decide("APPROVE")}>Approve</Button>
        </div>
      )}
      {error && <p role="alert" className={styles.err}>{error}</p>}
    </section>
  );
}
```

`src/features/gates/GateCard.module.css`:
```css
.card { background: var(--s); border: 1px solid var(--wa); border-radius: var(--radius); padding: 20px; display: flex; flex-direction: column; gap: 12px; }
.card p { margin: 0; color: var(--ink2); }
.scroll { overflow-x: auto; }
.scroll table { width: 100%; border-collapse: collapse; font-size: 13px; }
.scroll th, .scroll td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--l2); }
.more { font-size: 12px; }
.actions { display: flex; gap: 10px; justify-content: flex-end; }
.err { color: var(--bad) !important; }
@media (max-width: 767px) { .actions { flex-direction: column-reverse; } .actions button { width: 100%; } }
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/gates` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): gate card with role-aware decision buttons"
```

---

### Task 11: Runs history (table, filters, timeline, polling list)

**Files:**
- Create: `src/features/runs/stepText.ts` (used in Task 12, define statuses now), `src/features/runs/status.ts`, `timeline.ts`, `RunTimeline.tsx`, `RunsTable.tsx`, `RunsView.tsx`, `runs.module.css`, `useRunPolling.ts`, `src/app/(app)/runs/page.tsx`, `src/app/api/runs/route.ts`, `src/ui/ResponsiveTable.tsx`, `ResponsiveTable.module.css`
- Test: `timeline.test.ts`, `status.test.ts`, `RunsTable.test.tsx`, `useRunPolling.test.tsx`, `RunsView.test.tsx`

**Interfaces:**
- Produces:
  - `status.ts`: `statusPill(status: RunStatus): { tone: Tone; text: string }`, `isTerminal(status: RunStatus): boolean`, `terminalText(reason: TerminalReason | null): string | null`. Texts: PENDING "Starting", PENDING_BUILD "Preparing", RUNNING "Running", AWAITING_APPROVAL "Needs you", COMPLETED "Finished", FAILED "Failed", CANCELLED "Cancelled", TIMED_OUT "Timed out", EXPIRED "Expired".
  - `timeline.ts`: `layoutBars(items: RunListItem[], nowMs: number): { lanes: { agent: string; bars: { id: string; displayId: string; leftPct: number; widthPct: number }[] }[]; startHour: number; endHour: number }`. Only runs with `startedAt` on the same local day as `nowMs`. Window is the floor of the earliest start hour to the ceiling of the latest end (or now), at least 3 hours. A run without `completedAt` ends at `nowMs`. Minimum bar width 1.5%.
  - `ResponsiveTable<T>({ columns: { key: string; header: string; render: (row: T) => React.ReactNode }[], rows: T[], rowKey: (row: T) => string, empty: React.ReactNode })`. It renders `<td data-label>` so the CSS can stack it on phones.
  - `useRunPolling<T>(url: string, opts: { intervalMs: number; initial: T; isDone?: (data: T) => boolean }): { data: T; error: string | null; forbidden: boolean }`. Polls with `fetch(url, { cache: "no-store" })`. Stops when `isDone(data)` is true. Pauses while `document.hidden`. Sets `forbidden` on 403 and stops. On 401 does `window.location.assign("/login")`.
  - `GET /api/runs?group=&q=&page=` route handler proxying `listRuns`, returning `{ status, body }` faithfully (403 stays 403).
  - `RunsView({ initial: RunListResponse, role })` (client)
- Consumes: `apiFetch` from `http.ts`, `runQueryString`.

- [ ] **Step 1: Failing tests**

`src/features/runs/timeline.test.ts`:
```ts
import { layoutBars } from "./timeline";
import type { RunListItem } from "@/lib/api/types";

const run = (id: string, agent: string, start: string, end: string | null): RunListItem => ({
  id, displayId: id, agent, version: "1", status: "COMPLETED", terminalReason: null, reachedBlock: null,
  startedAt: start, completedAt: end, charged: 0, held: 0, decidedBy: null,
});

const now = new Date("2026-09-29T10:30:00").getTime();

it("puts each agent in its own lane and scales bars to the window", () => {
  const out = layoutBars(
    [run("R1", "A", "2026-09-29T08:00:00", "2026-09-29T09:00:00"), run("R2", "B", "2026-09-29T09:00:00", null)],
    now,
  );
  expect(out.startHour).toBe(8);
  expect(out.endHour).toBe(11);
  expect(out.lanes.map((l) => l.agent)).toEqual(["A", "B"]);
  expect(out.lanes[0].bars[0].leftPct).toBeCloseTo(0);
  expect(out.lanes[0].bars[0].widthPct).toBeCloseTo(33.33, 1);
  expect(out.lanes[1].bars[0].leftPct).toBeCloseTo(33.33, 1);
});

it("ignores runs from other days and runs with no start", () => {
  const out = layoutBars([run("R1", "A", "2026-09-28T08:00:00", "2026-09-28T09:00:00"), { ...run("R2", "A", "x", null), startedAt: null }], now);
  expect(out.lanes).toEqual([]);
});

it("keeps a very short run visible", () => {
  const out = layoutBars([run("R1", "A", "2026-09-29T08:00:00", "2026-09-29T08:00:05")], now);
  expect(out.lanes[0].bars[0].widthPct).toBeGreaterThanOrEqual(1.5);
});
```

`src/features/runs/status.test.ts`:
```ts
import { isTerminal, statusPill, terminalText } from "./status";

it("uses plain words", () => {
  expect(statusPill("AWAITING_APPROVAL")).toEqual({ tone: "warn", text: "Needs you" });
  expect(statusPill("COMPLETED")).toEqual({ tone: "ok", text: "Finished" });
  expect(statusPill("FAILED").tone).toBe("bad");
});
it("knows terminal states", () => {
  ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"].forEach((s) => expect(isTerminal(s as never)).toBe(true));
  ["PENDING", "PENDING_BUILD", "RUNNING", "AWAITING_APPROVAL"].forEach((s) => expect(isTerminal(s as never)).toBe(false));
});
it("explains why a run stopped", () => {
  expect(terminalText("GATE_REJECTED")).toMatch(/rejected/i);
  expect(terminalText("COST_CAP_REACHED")).toMatch(/limit/i);
  expect(terminalText(null)).toBeNull();
});
```

`src/features/runs/useRunPolling.test.tsx`:
```tsx
import { act, renderHook } from "@testing-library/react";
import { vi } from "vitest";
import { useRunPolling } from "./useRunPolling";

const ok = (b: unknown) => new Response(JSON.stringify(b), { status: 200 });

beforeEach(() => vi.useFakeTimers());
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

it("polls on the interval and stops when done", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(ok({ n: 1 }))
    .mockResolvedValueOnce(ok({ n: 2, done: true }))
    .mockResolvedValue(ok({ n: 3 }));
  vi.stubGlobal("fetch", fetchMock);
  const { result } = renderHook(() => useRunPolling<{ n: number; done?: boolean }>("/api/x", { intervalMs: 1000, initial: { n: 0 }, isDone: (d) => !!d.done }));
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(result.current.data.n).toBe(1);
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(result.current.data.n).toBe(2);
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

it("stops and flags forbidden on 403", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response("", { status: 403 }));
  vi.stubGlobal("fetch", fetchMock);
  const { result } = renderHook(() => useRunPolling("/api/x", { intervalMs: 1000, initial: {} }));
  await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
  expect(result.current.forbidden).toBe(true);
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it("does not poll while the tab is hidden", async () => {
  const fetchMock = vi.fn().mockResolvedValue(ok({}));
  vi.stubGlobal("fetch", fetchMock);
  Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
  renderHook(() => useRunPolling("/api/x", { intervalMs: 1000, initial: {} }));
  await act(async () => { await vi.advanceTimersByTimeAsync(3000); });
  expect(fetchMock).not.toHaveBeenCalled();
  Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
});
```

`src/features/runs/RunsView.test.tsx` (Review Focus 3):
```tsx
import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("./useRunPolling", () => ({ useRunPolling: vi.fn() }));
import { useRunPolling } from "./useRunPolling";
import { RunsView } from "./RunsView";

const counts = { needs_you: 1, running: 0, finished: 2, stopped: 0 };
const item = { id: "i", displayId: "R-2291", agent: "Bank Reconciliation", version: "1.3.0", status: "AWAITING_APPROVAL" as const, terminalReason: null, reachedBlock: "gate:post", startedAt: null, completedAt: null, charged: 0, held: 500, decidedBy: null };
const initial = { items: [item], counts, page: 0, size: 25, total: 1 };

it("shows filter chips with counts and rows with their reached block", () => {
  vi.mocked(useRunPolling).mockReturnValue({ data: initial, error: null, forbidden: false });
  render(<RunsView initial={initial} />);
  expect(screen.getByRole("button", { name: /Needs you 1/ })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "R-2291" })).toHaveAttribute("href", "/runs/R-2291");
  expect(screen.getByText("Your approval")).toBeInTheDocument();
});

it("shows an empty state when no run matches", () => {
  const empty = { ...initial, items: [], total: 0 };
  vi.mocked(useRunPolling).mockReturnValue({ data: empty, error: null, forbidden: false });
  render(<RunsView initial={empty} />);
  expect(screen.getByText(/no runs match/i)).toBeInTheDocument();
});

it("shows a no-access state, not an empty list, when the backend says 403", () => {
  vi.mocked(useRunPolling).mockReturnValue({ data: initial, error: null, forbidden: true });
  render(<RunsView initial={initial} />);
  expect(screen.getByText(/don't have access to runs/i)).toBeInTheDocument();
  expect(screen.queryByText(/no runs match/i)).toBeNull();
});
```

- [ ] **Step 2: Verify failure, implement**

`src/features/runs/status.ts`:
```ts
import type { Tone } from "@/ui/Pill";
import type { RunStatus, TerminalReason } from "@/lib/api/types";

const MAP: Record<RunStatus, { tone: Tone; text: string }> = {
  PENDING: { tone: "info", text: "Starting" },
  PENDING_BUILD: { tone: "info", text: "Preparing" },
  RUNNING: { tone: "info", text: "Running" },
  AWAITING_APPROVAL: { tone: "warn", text: "Needs you" },
  COMPLETED: { tone: "ok", text: "Finished" },
  FAILED: { tone: "bad", text: "Failed" },
  CANCELLED: { tone: "neutral", text: "Cancelled" },
  TIMED_OUT: { tone: "bad", text: "Timed out" },
  EXPIRED: { tone: "neutral", text: "Expired" },
};

export const statusPill = (s: RunStatus) => MAP[s];
export const isTerminal = (s: RunStatus) => ["COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT", "EXPIRED"].includes(s);

const REASON: Record<TerminalReason, string> = {
  FINISHED: "The run finished.",
  GATE_REJECTED: "You rejected the approval, so nothing was written and you were not charged for the write.",
  GATE_EXPIRED: "Nobody answered the approval in time, so the run stopped.",
  COST_CAP_REACHED: "The run stopped at your spend limit.",
  TIMED_OUT: "The run took too long and was stopped.",
  SCOPE_VIOLATIONS: "The agent tried to go beyond what it declared and was stopped.",
  AGENT_FAILED: "The agent reported a failure.",
  CANCELLED: "The run was cancelled.",
  SANDBOX_LOST: "Karya lost the sandbox this run was in.",
};
export const terminalText = (r: TerminalReason | null) => (r ? REASON[r] : null);
```

`src/features/runs/timeline.ts`:
```ts
import type { RunListItem } from "@/lib/api/types";

export interface Bar { id: string; displayId: string; leftPct: number; widthPct: number }
export interface Lane { agent: string; bars: Bar[] }

const sameDay = (a: number, b: number) => new Date(a).toDateString() === new Date(b).toDateString();
const hourOf = (ms: number) => new Date(ms).getHours() + new Date(ms).getMinutes() / 60;

export function layoutBars(items: RunListItem[], nowMs: number): { lanes: Lane[]; startHour: number; endHour: number } {
  const today = items.filter((r) => r.startedAt && sameDay(new Date(r.startedAt).getTime(), nowMs));
  if (today.length === 0) return { lanes: [], startHour: 0, endHour: 0 };
  const spans = today.map((r) => {
    const s = new Date(r.startedAt!).getTime();
    const e = r.completedAt ? new Date(r.completedAt).getTime() : nowMs;
    return { r, s, e: Math.max(e, s) };
  });
  const startHour = Math.floor(Math.min(...spans.map((x) => hourOf(x.s))));
  let endHour = Math.ceil(Math.max(...spans.map((x) => hourOf(x.e))));
  if (endHour - startHour < 3) endHour = startHour + 3;
  const total = endHour - startHour;
  const lanes = new Map<string, Bar[]>();
  for (const { r, s, e } of spans) {
    const left = ((hourOf(s) - startHour) / total) * 100;
    const width = Math.max(((hourOf(e) - hourOf(s)) / total) * 100, 1.5);
    const bars = lanes.get(r.agent) ?? [];
    bars.push({ id: r.id, displayId: r.displayId, leftPct: left, widthPct: width });
    lanes.set(r.agent, bars);
  }
  return { lanes: [...lanes].map(([agent, bars]) => ({ agent, bars })), startHour, endHour };
}
```

`src/features/runs/useRunPolling.ts`:
```ts
"use client";
import { useEffect, useRef, useState } from "react";

export function useRunPolling<T>(url: string, opts: { intervalMs: number; initial: T; isDone?: (d: T) => boolean }) {
  const [data, setData] = useState<T>(opts.initial);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const stopped = useRef(false);
  const isDone = useRef(opts.isDone);
  isDone.current = opts.isDone;

  useEffect(() => {
    stopped.current = false;
    const tick = async () => {
      if (stopped.current || document.hidden) return;
      try {
        const res = await fetch(url, { cache: "no-store" });
        if (res.status === 401) { window.location.assign("/login"); stopped.current = true; return; }
        if (res.status === 403) { setForbidden(true); stopped.current = true; return; }
        if (!res.ok) { setError(`Couldn't refresh (${res.status})`); return; }
        const next = (await res.json()) as T;
        setError(null);
        setData(next);
        if (isDone.current?.(next)) stopped.current = true;
      } catch {
        setError("Couldn't refresh. Retrying.");
      }
    };
    const id = setInterval(tick, opts.intervalMs);
    return () => { stopped.current = true; clearInterval(id); };
  }, [url, opts.intervalMs]);

  return { data, error, forbidden };
}
```

`src/ui/ResponsiveTable.tsx`:
```tsx
import styles from "./ResponsiveTable.module.css";

export interface Column<T> { key: string; header: string; render: (row: T) => React.ReactNode }

export function ResponsiveTable<T>({ columns, rows, rowKey, empty }: { columns: Column<T>[]; rows: T[]; rowKey: (r: T) => string; empty: React.ReactNode }) {
  if (rows.length === 0) return <>{empty}</>;
  return (
    <table className={styles.table}>
      <thead>
        <tr>{columns.map((c) => <th key={c.key} scope="col">{c.header}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={rowKey(r)}>
            {columns.map((c) => <td key={c.key} data-label={c.header}>{c.render(r)}</td>)}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

`src/ui/ResponsiveTable.module.css`:
```css
.table { width: 100%; border-collapse: collapse; background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); overflow: hidden; }
.table th { text-align: left; font-size: 12px; color: var(--ink3); font-weight: 600; padding: 10px 14px; background: var(--l2); }
.table td { padding: 12px 14px; border-top: 1px solid var(--l2); vertical-align: middle; }
@media (max-width: 767px) {
  .table thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
  .table, .table tbody, .table tr, .table td { display: block; }
  .table tr { padding: 10px 14px; border-top: 1px solid var(--l); }
  .table td { border: 0; padding: 4px 0; display: flex; justify-content: space-between; gap: 12px; }
  .table td::before { content: attr(data-label); color: var(--ink3); font-size: 12px; }
}
```

`src/features/runs/RunTimeline.tsx`:
```tsx
import Link from "next/link";
import styles from "./runs.module.css";
import { layoutBars } from "./timeline";
import type { RunListItem } from "@/lib/api/types";

export function RunTimeline({ items, nowMs }: { items: RunListItem[]; nowMs: number }) {
  const { lanes, startHour, endHour } = layoutBars(items, nowMs);
  if (lanes.length === 0) return null;
  const hours = Array.from({ length: endHour - startHour + 1 }, (_, i) => startHour + i);
  return (
    <section aria-label="Today" className={styles.timeline}>
      <h2>Today</h2>
      <p className={styles.sub}>Each bar is one run, from start to finish.</p>
      {lanes.map((l) => (
        <div key={l.agent} className={styles.lane}>
          <span className={styles.laneName}>{l.agent}</span>
          <div className={styles.track}>
            {l.bars.map((b) => (
              <Link key={b.id} href={`/runs/${b.displayId}`} aria-label={`Run ${b.displayId}`} className={styles.bar} style={{ left: `${b.leftPct}%`, width: `${b.widthPct}%` }} />
            ))}
          </div>
        </div>
      ))}
      <div className={styles.axis} aria-hidden="true">{hours.map((h) => <span key={h} className="mono">{String(h).padStart(2, "0")}:00</span>)}</div>
    </section>
  );
}
```

`src/features/runs/RunsTable.tsx`:
```tsx
import Link from "next/link";
import { Pill } from "@/ui/Pill";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { EmptyState } from "@/ui/EmptyState";
import { statusPill } from "./status";
import { toBlockViews } from "@/features/flow/blocks";
import { formatMoney, formatTime } from "@/lib/format";
import type { RunListItem } from "@/lib/api/types";

function reached(id: string | null): string {
  if (!id) return "–";
  const [v] = toBlockViews([{ id, state: "DONE" }], null);
  return v.kind === "Asks you" ? v.title : `${v.kind} ${v.title}`.trim();
}

export function RunsTable({ rows }: { rows: RunListItem[] }) {
  return (
    <ResponsiveTable
      rows={rows}
      rowKey={(r) => r.id}
      empty={<EmptyState title="No runs match this filter." body="Try another filter, or start a run from the agent store." />}
      columns={[
        { key: "id", header: "Run", render: (r) => <Link href={`/runs/${r.displayId}`}>{r.displayId}</Link> },
        { key: "agent", header: "Agent", render: (r) => `${r.agent} ${r.version}` },
        { key: "reached", header: "Reached", render: (r) => reached(r.reachedBlock) },
        { key: "status", header: "Status", render: (r) => { const s = statusPill(r.status); return <Pill tone={s.tone}>{s.text}</Pill>; } },
        { key: "started", header: "Started", render: (r) => <span className="mono">{formatTime(r.startedAt)}</span> },
        { key: "cost", header: "Cost", render: (r) => <span className="mono">{formatMoney(r.charged)}</span> },
      ]}
    />
  );
}
```

`src/features/runs/RunsView.tsx`:
```tsx
"use client";
import { useState } from "react";
import styles from "./runs.module.css";
import { RunsTable } from "./RunsTable";
import { RunTimeline } from "./RunTimeline";
import { useRunPolling } from "./useRunPolling";
import { EmptyState } from "@/ui/EmptyState";
import type { RunGroup, RunListResponse } from "@/lib/api/types";

const CHIPS: { key: RunGroup | "all"; label: string }[] = [
  { key: "all", label: "All" }, { key: "needs_you", label: "Needs you" }, { key: "running", label: "Running" },
  { key: "finished", label: "Finished" }, { key: "stopped", label: "Stopped" },
];

export function RunsView({ initial }: { initial: RunListResponse }) {
  const [group, setGroup] = useState<RunGroup | "all">("all");
  const url = `/api/runs${group === "all" ? "" : `?group=${group}`}`;
  const { data, error, forbidden } = useRunPolling<RunListResponse>(url, { intervalMs: 3000, initial });

  if (forbidden) {
    return <EmptyState title="You don't have access to runs" body="Runs are visible to the buyers who own the workspace. Ask a workspace admin if you need access." />;
  }
  const total = data.counts.needs_you + data.counts.running + data.counts.finished + data.counts.stopped;
  return (
    <div className={styles.wrap}>
      <div className={styles.chips} role="group" aria-label="Filter runs">
        {CHIPS.map((c) => (
          <button key={c.key} type="button" aria-pressed={group === c.key} className={styles.chip} onClick={() => setGroup(c.key)}>
            {c.label} {c.key === "all" ? total : data.counts[c.key]}
          </button>
        ))}
      </div>
      {error && <p role="status" className={styles.note}>{error}</p>}
      <RunTimeline items={data.items} nowMs={Date.now()} />
      <RunsTable rows={data.items} />
    </div>
  );
}
```

`src/features/runs/runs.module.css`:
```css
.wrap { display: flex; flex-direction: column; gap: 20px; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { min-height: 36px; padding: 0 14px; border-radius: 999px; border: 1px solid var(--l); background: var(--s); font: inherit; cursor: pointer; }
.chip[aria-pressed="true"] { background: var(--sky); color: var(--b); border-color: var(--b3); font-weight: 600; }
.note { color: var(--ink3); margin: 0; }
.timeline { background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); padding: 16px; }
.sub { margin: 4px 0 12px; color: var(--ink3); font-size: 13px; }
.lane { display: grid; grid-template-columns: 160px 1fr; align-items: center; gap: 12px; margin-bottom: 8px; }
.laneName { font-size: 13px; color: var(--ink2); }
.track { position: relative; height: 22px; background: var(--l2); border-radius: 6px; }
.bar { position: absolute; top: 3px; bottom: 3px; background: var(--b); border-radius: 4px; min-width: 6px; }
.axis { display: flex; justify-content: space-between; margin-left: 172px; font-size: 11px; color: var(--ink3); }
@media (max-width: 767px) { .lane { grid-template-columns: 1fr; gap: 4px; } .axis { margin-left: 0; } }
```

`src/app/api/runs/route.ts`:
```ts
import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api/http";

export async function GET(req: NextRequest) {
  const res = await apiFetch(`/api/v1/runs${req.nextUrl.search}`);
  const body = await res.text();
  return new NextResponse(body || null, { status: res.status, headers: { "Content-Type": "application/json" } });
}
```

`src/app/(app)/runs/page.tsx`:
```tsx
import { ApiError } from "@/lib/api/http";
import { listRuns } from "@/lib/api/runs";
import { RunsView } from "@/features/runs/RunsView";
import { EmptyState } from "@/ui/EmptyState";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  let initial;
  try {
    initial = await listRuns({});
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      return <EmptyState title="You don't have access to runs" body="Runs are visible to the buyers who own the workspace." />;
    }
    return <ErrorState message={e instanceof Error ? e.message : "Couldn't load runs."} />;
  }
  return (
    <>
      <h1>Runs</h1>
      <p>Every run your agents made, with the block each one reached.</p>
      <RunsView initial={initial} />
    </>
  );
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/runs && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): runs history with timeline, filters and polling"
```

---

### Task 12: Live run page

**Files:**
- Create: `src/features/runs/stepText.ts`, `stepText.test.ts`, `LiveRun.tsx`, `LiveRun.module.css`, `LiveRun.test.tsx`, `src/app/api/runs/[id]/route.ts`, `src/app/(app)/runs/[id]/page.tsx`

**Interfaces:**
- Produces:
  - `stepText(step: RunStep): string`, a plain-words line. Map: `GATE_REQUESTED` "Asked for your approval", `GATE_DECIDED` "Approval answered", `COMPLETE` "Run finished", `FAILED` "The agent reported a failure", `TIMEOUT` "Run timed out", `COST_CAP_REACHED` "Reached the spend limit", `SCOPE_VIOLATION` "Tried something it did not declare", `NETWORK_BLOCKED` "A website request was blocked", `LLM_CALL` "Asked the AI model", `DATA_READ` and `READ` "Read your data", `DATA_WRITE`, `WRITE` and `WRITE_STAGED` "Prepared a write", `COST` and `USAGE` "Charge recorded", `CANCELLED` "Run cancelled", `AGENT_LOG` "Agent note", `GATE` "Approval step". Unknown kinds fall back to the kind in lower case with underscores replaced by spaces.
  - `LiveRun({ runRef, initial: RunSnapshot, manifest: Manifest | null, canDecide: boolean })` (client)
  - `GET /api/runs/[id]`: returns `RunSnapshot` (`getRunSnapshot`), preserving error statuses.
- Consumes: `useRunPolling`, `FlowBlocks`, `toBlockViews`, `GateCard`, `statusPill`, `isTerminal`, `terminalText`, `formatMoney`.

- [ ] **Step 1: Failing tests**

`src/features/runs/stepText.test.ts`:
```ts
import { stepText } from "./stepText";

it("uses plain words", () => {
  expect(stepText({ kind: "GATE_REQUESTED", detail: "{}", createdAt: "" })).toBe("Asked for your approval");
  expect(stepText({ kind: "NETWORK_BLOCKED", detail: "{}", createdAt: "" })).toBe("A website request was blocked");
});
it("falls back for an unknown kind", () => {
  expect(stepText({ kind: "SOMETHING_NEW" as never, detail: "", createdAt: "" })).toBe("something new");
});
```

`src/features/runs/LiveRun.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("./useRunPolling", () => ({ useRunPolling: vi.fn() }));
vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: vi.fn() }));
import { useRunPolling } from "./useRunPolling";
import { LiveRun } from "./LiveRun";
import type { RunSnapshot } from "@/lib/api/types";

const snap = (over: Partial<RunSnapshot["detail"]> = {}, gates: RunSnapshot["gates"] = []): RunSnapshot => ({
  detail: { id: "i", displayId: "R-2291", status: "AWAITING_APPROVAL", terminalReason: null, costCap: 500, totalCharged: 96, steps: [{ kind: "GATE_REQUESTED", detail: "{}", createdAt: "2026-09-29T10:05:00Z" }], ...over },
  flow: { blocks: [{ block: "gate:post", state: "WAITING", metrics: null, gateId: "g1", flag: null }, { block: "charge", state: "IDLE", metrics: null, gateId: null, flag: null }], reachedBlock: "gate:post", spend: { charged: 96, cap: 500 }, deadlineAt: "2999-01-01T00:00:00Z", blockedAttempts: 3 },
  gates,
});
const gate = { gateId: "g1", action: "post", summary: "Post 142 entries?", payload: {}, status: "PENDING" as const, expiresAt: "2999-01-01T00:00:00Z", estimatedUnits: null, costIfApproved: null, preview: null, decidedVia: null, decidedByUserId: null, decidedAt: null, note: null };

it("shows status, spend, blocked attempts, timeline and the pending gate", () => {
  const s = snap({}, [gate]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("R-2291");
  expect(screen.getAllByText("Needs you").length).toBeGreaterThan(0);
  expect(screen.getByText(/₹96 of ₹500/)).toBeInTheDocument();
  expect(screen.getByText(/blocked attempts/i).parentElement).toHaveTextContent("3");
  expect(screen.getByText("Asked for your approval")).toBeInTheDocument();
  expect(screen.getByText("Post 142 entries?")).toBeInTheDocument();
});

it("explains a terminal run and shows no gate buttons", () => {
  const s = snap({ status: "FAILED", terminalReason: "GATE_REJECTED" }, [{ ...gate, status: "REJECTED" }]);
  vi.mocked(useRunPolling).mockReturnValue({ data: s, error: null, forbidden: false });
  render(<LiveRun runRef="R-2291" initial={s} manifest={null} canDecide />);
  expect(screen.getByText(/you rejected the approval/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
});
```

- [ ] **Step 2: Verify failure, implement**

`src/features/runs/stepText.ts`:
```ts
import type { RunStep } from "@/lib/api/types";

const TEXT: Record<string, string> = {
  GATE_REQUESTED: "Asked for your approval", GATE_DECIDED: "Approval answered", GATE: "Approval step",
  COMPLETE: "Run finished", FAILED: "The agent reported a failure", TIMEOUT: "Run timed out",
  COST_CAP_REACHED: "Reached the spend limit", SCOPE_VIOLATION: "Tried something it did not declare",
  NETWORK_BLOCKED: "A website request was blocked", LLM_CALL: "Asked the AI model",
  DATA_READ: "Read your data", READ: "Read your data",
  DATA_WRITE: "Prepared a write", WRITE: "Prepared a write", WRITE_STAGED: "Prepared a write",
  COST: "Charge recorded", USAGE: "Charge recorded", CANCELLED: "Run cancelled", AGENT_LOG: "Agent note",
};

export const stepText = (s: RunStep): string => TEXT[s.kind] ?? s.kind.toLowerCase().replace(/_/g, " ");
```

`src/features/runs/LiveRun.tsx`:
```tsx
"use client";
import styles from "./LiveRun.module.css";
import { useRunPolling } from "./useRunPolling";
import { isTerminal, statusPill, terminalText } from "./status";
import { stepText } from "./stepText";
import { FlowBlocks } from "@/features/flow/FlowBlocks";
import { toBlockViews } from "@/features/flow/blocks";
import { GateCard } from "@/features/gates/GateCard";
import { Pill } from "@/ui/Pill";
import { ErrorState } from "@/ui/ErrorState";
import { formatMoney, formatTime } from "@/lib/format";
import type { Manifest, RunSnapshot } from "@/lib/api/types";

export function LiveRun({ runRef, initial, manifest, canDecide }: { runRef: string; initial: RunSnapshot; manifest: Manifest | null; canDecide: boolean }) {
  const { data, error, forbidden } = useRunPolling<RunSnapshot>(`/api/runs/${encodeURIComponent(runRef)}`, {
    intervalMs: 3000,
    initial,
    isDone: (s) => isTerminal(s.detail.status),
  });
  if (forbidden) return <ErrorState message="You don't have access to this run." />;

  const { detail, flow, gates } = data;
  const pill = statusPill(detail.status);
  const why = terminalText(detail.terminalReason);
  const pending = gates.filter((g) => g.status === "PENDING");
  const views = toBlockViews(flow.blocks.map((b) => ({ id: b.block, state: b.state, metrics: b.metrics, flag: b.flag })), manifest);

  return (
    <div className={styles.wrap}>
      <header className={styles.head}>
        <h1>Run <span className="mono">{detail.displayId}</span></h1>
        <Pill tone={pill.tone}>{pill.text}</Pill>
      </header>
      {error && <p role="status" className={styles.note}>{error}</p>}
      {why && isTerminal(detail.status) && <p className={styles.why}>{why}</p>}
      <FlowBlocks blocks={views} />
      {pending.map((g) => <GateCard key={g.gateId} runRef={runRef} gate={g} canDecide={canDecide} />)}
      {gates.filter((g) => g.status !== "PENDING").map((g) => <GateCard key={g.gateId} runRef={runRef} gate={g} canDecide={canDecide} />)}
      <div className={styles.cols}>
        <section aria-labelledby="tl">
          <h2 id="tl">Timeline</h2>
          <p className={styles.sub}>Recorded by Karya as it happens, not reported by the agent.</p>
          <ol className={styles.steps}>
            {detail.steps.map((s, i) => (
              <li key={i}><span className="mono">{formatTime(s.createdAt)}</span> {stepText(s)}</li>
            ))}
          </ol>
        </section>
        <aside aria-label="Run facts" className={styles.facts}>
          <dl>
            <div><dt>Spend</dt><dd>{formatMoney(flow.spend.charged)} of {formatMoney(flow.spend.cap)}</dd></div>
            <div><dt>Blocked attempts</dt><dd>{flow.blockedAttempts}</dd></div>
            {flow.deadlineAt && !isTerminal(detail.status) && <div><dt>Sandbox closes</dt><dd className="mono">{formatTime(flow.deadlineAt)}</dd></div>}
          </dl>
        </aside>
      </div>
    </div>
  );
}
```

`src/features/runs/LiveRun.module.css`:
```css
.wrap { display: flex; flex-direction: column; gap: 20px; }
.head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.note { color: var(--ink3); margin: 0; }
.why { margin: 0; padding: 12px 16px; background: var(--l2); border-radius: var(--radius); color: var(--ink2); }
.cols { display: grid; grid-template-columns: 1fr 280px; gap: 20px; align-items: start; }
.sub { margin: 4px 0 12px; color: var(--ink3); font-size: 13px; }
.steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.steps li { display: flex; gap: 12px; padding: 8px 12px; background: var(--s); border: 1px solid var(--l); border-radius: 10px; }
.facts { background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); padding: 16px; }
.facts dl { margin: 0; display: flex; flex-direction: column; gap: 12px; }
.facts dt { font-size: 12px; color: var(--ink3); }
.facts dd { margin: 2px 0 0; font-weight: 600; }
@media (max-width: 900px) { .cols { grid-template-columns: 1fr; } }
```

`src/app/api/runs/[id]/route.ts`:
```ts
import { NextResponse } from "next/server";
import { ApiError } from "@/lib/api/http";
import { getRunSnapshot } from "@/lib/api/runs";

export async function GET(_req: Request, { params }: { params: { id: string } }) {
  try {
    return NextResponse.json(await getRunSnapshot(params.id));
  } catch (e) {
    // api() redirects on 401 by throwing NEXT_REDIRECT, which must propagate.
    if (e instanceof ApiError) return NextResponse.json({ message: e.message }, { status: e.status });
    if (e instanceof Error && e.message === "NEXT_REDIRECT") return new NextResponse(null, { status: 401 });
    throw e;
  }
}
```

`src/app/(app)/runs/[id]/page.tsx`:
```tsx
import { ApiError } from "@/lib/api/http";
import { getRunSnapshot } from "@/lib/api/runs";
import { getVersion } from "@/lib/api/catalog";
import { getSession } from "@/lib/session";
import { canDecideGates } from "@/lib/roles";
import { LiveRun } from "@/features/runs/LiveRun";
import { ErrorState } from "@/ui/ErrorState";
import type { Manifest } from "@/lib/api/types";

export default async function Page({ params }: { params: { id: string } }) {
  const session = (await getSession())!;
  let snapshot;
  try {
    snapshot = await getRunSnapshot(params.id);
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that run." : e.message} />;
    throw e;
  }
  // The flow already names the blocks; the manifest only adds titles and descriptions, so a failure here is not fatal.
  const manifest: Manifest | null = null;
  return <LiveRun runRef={params.id} initial={snapshot} manifest={manifest} canDecide={canDecideGates(session.role)} />;
}
```

Note: the run detail responses carry no `agentId` or `versionId`, so `manifest` is `null` and the blocks show the ids humanized, with no descriptions. Record this in `docs/frontend-gaps.md` as `G11 | Block descriptions on the live run | Run detail has no agentId/versionId to fetch the manifest`. Do not add a backend field.

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/runs && npm run build` → PASS.
```bash
git add frontend docs/frontend-gaps.md && git commit -m "feat(frontend): live run page with flow, gate, timeline and spend"
```

---

### Task 13: Files page

**Files:**
- Create: `src/ui/Dropzone.tsx`, `Dropzone.module.css`, `Drawer.tsx`, `Drawer.module.css`, `src/features/files/StorageBar.tsx`, `FilesView.tsx`, `files.module.css`, `src/app/(app)/files/page.tsx`, `src/app/(app)/files/actions.ts`
- Test: `StorageBar.test.tsx`, `FilesView.test.tsx`, `Dropzone.test.tsx`

**Interfaces:**
- Produces:
  - `Dropzone({ onFile, disabled?, label })`, a keyboard-accessible control wrapping a hidden `<input type="file">`. Calls `onFile(file)` on choose or drop.
  - `Drawer({ open, title, onClose, children })`: role `dialog`, Escape closes, focus moves to the close button on open.
  - `StorageBar({ usedBytes, limitBytes })`: text `"1.1 MB of 5 GB used"`, a `<progress>` element, and at 100% or more the text `"Storage is full. Delete a file to upload more."`
  - `FilesView({ datasets, storage })` (client)
  - server actions: `uploadFileAction(fd: FormData): Promise<{ error?: string }>`, `deleteFileAction(datasetId: string, usage: { connectionId: string; resource: string }[]): Promise<{ error?: string }>` (unbinds each usage first, then deletes)
- Consumes: `uploadDataset`, `deleteDataset`, `unbindResource`, `listDatasets`, `getStorage`, `getSession` (for `orgId`).

- [ ] **Step 1: Failing tests** (Review Focus 5)

`src/features/files/StorageBar.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { StorageBar } from "./StorageBar";

it("shows used of limit", () => {
  render(<StorageBar usedBytes={1153434} limitBytes={5 * 1024 ** 3} />);
  expect(screen.getByText("1.1 MB of 5 GB used")).toBeInTheDocument();
});
it("says so when storage is full and does not overflow", () => {
  render(<StorageBar usedBytes={6 * 1024 ** 3} limitBytes={5 * 1024 ** 3} />);
  expect(screen.getByText(/storage is full/i)).toBeInTheDocument();
  expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");
});
```

`src/features/files/FilesView.test.tsx`:
```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const upload = vi.fn();
const del = vi.fn();
vi.mock("@/app/(app)/files/actions", () => ({ uploadFileAction: (...a: unknown[]) => upload(...a), deleteFileAction: (...a: unknown[]) => del(...a) }));
import { FilesView } from "./FilesView";
import type { Dataset } from "@/lib/api/types";

const ds = (over: Partial<Dataset> = {}): Dataset => ({
  id: "d1", displayName: "Aug 2026 statement.csv", sourceLabel: "HDFC current account", format: "CSV", rowCount: 412, sizeBytes: 5000,
  columns: ["date", "narration", "amount"], sha256: "x", uploadedBy: "Priya", createdAt: "2026-09-01T00:00:00Z", piiScan: null,
  usage: [{ connectionId: "c1", resource: "bank_statement", agent: "Bank Reconciliation", version: "1.3.0", access: "READ" }], ...over,
});
const storage = { usedBytes: 5000, limitBytes: 5 * 1024 ** 3 };

beforeEach(() => { upload.mockReset(); del.mockReset(); });

it("lists files with rows and what each is plugged into", () => {
  render(<FilesView datasets={[ds(), ds({ id: "d2", displayName: "Vendor master.xlsx", usage: [], rowCount: null })]} storage={storage} />);
  expect(screen.getByText("Aug 2026 statement.csv")).toBeInTheDocument();
  expect(screen.getByText("412")).toBeInTheDocument();
  expect(screen.getByText("Bank Reconciliation")).toBeInTheDocument();
  expect(screen.getByText("Not plugged in")).toBeInTheDocument();
});

it("shows the empty state when there are no files", () => {
  render(<FilesView datasets={[]} storage={storage} />);
  expect(screen.getByText(/no files yet/i)).toBeInTheDocument();
});

it("shows the backend's message when an upload fails and leaves the list alone", async () => {
  upload.mockResolvedValue({ error: "This file would go over your storage limit" });
  render(<FilesView datasets={[ds()]} storage={{ usedBytes: 5 * 1024 ** 3, limitBytes: 5 * 1024 ** 3 }} />);
  const input = screen.getByLabelText(/upload file/i) as HTMLInputElement;
  await userEvent.upload(input, new File(["a,b\n1,2"], "big.csv", { type: "text/csv" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("This file would go over your storage limit");
  expect(screen.getAllByRole("row").length).toBe(2);
});

it("opens the drawer and unplugs then deletes", async () => {
  del.mockResolvedValue({});
  render(<FilesView datasets={[ds()]} storage={storage} />);
  await userEvent.click(screen.getByRole("button", { name: /where .*Aug 2026 statement/i }));
  const dialog = screen.getByRole("dialog");
  expect(within(dialog).getByText(/Bank Reconciliation/)).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", { name: /unplug and delete/i }));
  expect(del).toHaveBeenCalledWith("d1", [{ connectionId: "c1", resource: "bank_statement" }]);
});
```

`src/ui/Dropzone.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Dropzone } from "./Dropzone";

it("passes the chosen file to onFile", async () => {
  const onFile = vi.fn();
  render(<Dropzone label="Upload file" onFile={onFile} />);
  const file = new File(["x"], "a.csv", { type: "text/csv" });
  await userEvent.upload(screen.getByLabelText("Upload file"), file);
  expect(onFile).toHaveBeenCalledWith(file);
});
```

- [ ] **Step 2: Verify failure, implement**

`src/ui/Dropzone.tsx`:
```tsx
"use client";
import { useId, useState } from "react";
import styles from "./Dropzone.module.css";
import { Icon } from "./Icon";

export function Dropzone({ onFile, label, disabled }: { onFile: (f: File) => void; label: string; disabled?: boolean }) {
  const id = useId();
  const [over, setOver] = useState(false);
  return (
    <div
      className={`${styles.zone} ${over ? styles.over : ""}`}
      onDragOver={(e) => { e.preventDefault(); setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); const f = e.dataTransfer.files?.[0]; if (f && !disabled) onFile(f); }}
    >
      <Icon name="upload" />
      <label htmlFor={id}>{label}</label>
      <span className={styles.hint}>or drop a file here</span>
      <input id={id} type="file" className={styles.input} disabled={disabled} onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ""; }} />
    </div>
  );
}
```

`src/ui/Dropzone.module.css`:
```css
.zone { position: relative; display: flex; align-items: center; gap: 10px; padding: 16px; border: 1.5px dashed var(--b3); border-radius: var(--radius); background: var(--sky); color: var(--b); flex-wrap: wrap; }
.zone label { font-weight: 600; cursor: pointer; }
.hint { color: var(--ink3); font-size: 13px; }
.over { background: var(--sky2); }
.input { position: absolute; inset: 0; opacity: 0; cursor: pointer; width: 100%; height: 100%; }
.input:focus-visible + * { outline: 2px solid var(--b); }
.zone:focus-within { outline: 2px solid var(--b); outline-offset: 2px; }
```

`src/ui/Drawer.tsx`:
```tsx
"use client";
import { useEffect, useRef } from "react";
import styles from "./Drawer.module.css";
import { Icon } from "./Icon";

export function Drawer({ open, title, onClose, children }: { open: boolean; title: string; onClose: () => void; children: React.ReactNode }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className={styles.scrim} onClick={onClose}>
      <aside role="dialog" aria-modal="true" aria-label={title} className={styles.panel} onClick={(e) => e.stopPropagation()}>
        <header>
          <h2>{title}</h2>
          <button ref={closeRef} type="button" onClick={onClose} aria-label="Close"><Icon name="x" /></button>
        </header>
        {children}
      </aside>
    </div>
  );
}
```

`src/ui/Drawer.module.css`:
```css
.scrim { position: fixed; inset: 0; background: rgba(13,26,48,0.35); z-index: 20; display: flex; justify-content: flex-end; }
.panel { width: 420px; max-width: 100%; height: 100%; background: var(--s); padding: 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
.panel header { display: flex; align-items: center; justify-content: space-between; }
.panel header button { min-width: 44px; min-height: 44px; border: 0; background: transparent; cursor: pointer; }
@media (max-width: 767px) { .scrim { align-items: flex-end; } .panel { width: 100%; height: auto; max-height: 85vh; border-radius: 16px 16px 0 0; } }
```

`src/features/files/StorageBar.tsx`:
```tsx
import styles from "./files.module.css";
import { formatBytes } from "@/lib/format";

export function StorageBar({ usedBytes, limitBytes }: { usedBytes: number; limitBytes: number }) {
  const pct = limitBytes > 0 ? Math.min(100, Math.round((usedBytes / limitBytes) * 100)) : 0;
  const full = limitBytes > 0 && usedBytes >= limitBytes;
  return (
    <div className={styles.storage}>
      <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label="Storage used" className={styles.bar}>
        <span style={{ width: `${pct}%` }} />
      </div>
      <span>{formatBytes(usedBytes)} of {formatBytes(limitBytes)} used</span>
      {full && <strong role="status">Storage is full. Delete a file to upload more.</strong>}
    </div>
  );
}
```

`src/features/files/FilesView.tsx`:
```tsx
"use client";
import { useState, useTransition } from "react";
import styles from "./files.module.css";
import { StorageBar } from "./StorageBar";
import { Button } from "@/ui/Button";
import { Drawer } from "@/ui/Drawer";
import { Dropzone } from "@/ui/Dropzone";
import { EmptyState } from "@/ui/EmptyState";
import { ResponsiveTable } from "@/ui/ResponsiveTable";
import { deleteFileAction, uploadFileAction } from "@/app/(app)/files/actions";
import { formatTime } from "@/lib/format";
import type { Dataset, Storage } from "@/lib/api/types";

export function FilesView({ datasets, storage }: { datasets: Dataset[]; storage: Storage }) {
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Dataset | null>(null);
  const [pending, start] = useTransition();

  const upload = (file: File) =>
    start(async () => {
      setError(null);
      const fd = new FormData();
      fd.set("file", file);
      const res = await uploadFileAction(fd);
      if (res.error) setError(res.error);
    });

  const remove = (d: Dataset) =>
    start(async () => {
      setError(null);
      const res = await deleteFileAction(d.id, (d.usage ?? []).map((u) => ({ connectionId: u.connectionId, resource: u.resource })));
      if (res.error) setError(res.error);
      else setOpen(null);
    });

  return (
    <div className={styles.wrap}>
      <StorageBar usedBytes={storage.usedBytes} limitBytes={storage.limitBytes} />
      <Dropzone label="Upload file" onFile={upload} disabled={pending} />
      {error && <p role="alert" className={styles.err}>{error}</p>}
      <ResponsiveTable
        rows={datasets}
        rowKey={(d) => d.id}
        empty={<EmptyState title="No files yet" body="An agent only sees the files you plug into it. Upload one to start." />}
        columns={[
          { key: "file", header: "File", render: (d) => <><strong>{d.displayName}</strong>{d.sourceLabel && <div className={styles.sub}>{d.sourceLabel}</div>}</> },
          { key: "rows", header: "Rows", render: (d) => <span className="mono">{d.rowCount ?? "–"}</span> },
          { key: "use", header: "Plugged into", render: (d) => (d.usage?.length ? d.usage.map((u) => <div key={`${u.connectionId}${u.resource}`}>{u.agent}</div>) : "Not plugged in") },
          { key: "added", header: "Added", render: (d) => <span className="mono">{formatTime(d.createdAt)}</span> },
          { key: "go", header: "", render: (d) => <Button variant="ghost" onClick={() => setOpen(d)} aria-label={`Where ${d.displayName} goes`}>Where it goes</Button> },
        ]}
      />
      <Drawer open={!!open} title="Where this file goes" onClose={() => setOpen(null)}>
        {open && (
          <>
            <h3>{open.displayName}</h3>
            <p className="mono">{open.columns.join(", ")}</p>
            {open.usage?.length ? (
              <ul>{open.usage.map((u) => <li key={`${u.connectionId}${u.resource}`}>{u.agent} {u.version} reads it as {u.resource}</li>)}</ul>
            ) : <p>Not plugged into any agent.</p>}
            <Button variant="danger" loading={pending} onClick={() => remove(open)}>Unplug and delete</Button>
          </>
        )}
      </Drawer>
    </div>
  );
}
```

`src/features/files/files.module.css`:
```css
.wrap { display: flex; flex-direction: column; gap: 16px; }
.storage { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; color: var(--ink2); font-size: 13px; }
.bar { width: 200px; height: 8px; background: var(--l); border-radius: 999px; overflow: hidden; }
.bar span { display: block; height: 100%; background: var(--b); }
.sub { color: var(--ink3); font-size: 12px; }
.err { color: var(--bad); margin: 0; }
```

`src/app/(app)/files/actions.ts`:
```ts
"use server";
import { revalidatePath } from "next/cache";
import { ApiError } from "@/lib/api/http";
import { unbindResource } from "@/lib/api/connections";
import { deleteDataset, uploadDataset } from "@/lib/api/datasets";

const fail = (e: unknown): { error: string } => {
  if (e instanceof ApiError) return { error: e.message };
  throw e;
};

export async function uploadFileAction(fd: FormData): Promise<{ error?: string }> {
  try {
    await uploadDataset(fd);
    revalidatePath("/files");
    return {};
  } catch (e) { return fail(e); }
}

export async function deleteFileAction(datasetId: string, usage: { connectionId: string; resource: string }[]): Promise<{ error?: string }> {
  try {
    for (const u of usage) await unbindResource(u.connectionId, u.resource);
    await deleteDataset(datasetId);
    revalidatePath("/files");
    return {};
  } catch (e) { return fail(e); }
}
```

`src/app/(app)/files/page.tsx`:
```tsx
import { listDatasets, getStorage } from "@/lib/api/datasets";
import { getSession } from "@/lib/session";
import { FilesView } from "@/features/files/FilesView";
import { ErrorState } from "@/ui/ErrorState";
import { ApiError } from "@/lib/api/http";

export default async function Page() {
  const session = (await getSession())!;
  try {
    const [datasets, storage] = await Promise.all([listDatasets(), getStorage(session.orgId)]);
    return (
      <>
        <h1>Files</h1>
        <p>An agent only sees the files you plug into it.</p>
        <FilesView datasets={datasets} storage={storage} />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/files src/ui/Dropzone.test.tsx && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): files page with upload, storage and where-it-goes drawer"
```

---

### Task 14: Plug in data (connect)

**Files:**
- Create: `src/features/sockets/sockets.ts`, `sockets.test.ts`, `ConnectForm.tsx`, `ConnectForm.test.tsx`, `sockets.module.css`, `src/app/(app)/connect/[agentId]/[versionId]/page.tsx`, `src/app/(app)/connect/actions.ts`

**Interfaces:**
- Produces:
  - `sockets.ts`: `socketsOf(m: Manifest): { resource: string; label: string; description: string; requiredColumns: string[] }[]`. One socket per READ scope. Its `requiredColumns` come from `m.resources?.find(r => r.name === scope.resource)?.schema?.required ?? []`. When `m.resources` is null, the sockets still list from scopes but `requiredColumns` is empty. `grantedScopesOf(m): string[]` (all scope resources). `defaultCap(m): number` (`m.costEnvelope.maxPerRun`).
  - server actions (`connect/actions.ts`):
    - `uploadForSocketAction(fd): Promise<{ dataset?: Dataset; error?: string }>`
    - `checkSocketAction(datasetId, versionId): Promise<{ result?: CheckResult; error?: string }>`
    - `createConnectionAction(agentId, grantedScopes, spendCap): Promise<{ connectionId?: string; error?: string }>`
    - `bindSocketAction(connectionId, resource, datasetId): Promise<{ error?: string }>`
    - `startRunAction(connectionId): Promise<{ displayId?: string; error?: string }>`
  - `ConnectForm({ agentId, versionId, agentName, manifest, datasets, canConnect })` (client). It orchestrates create, bind each, run; it remembers `connectionId` after the first step so a retry never creates a second connection.
- Consumes: `Dropzone`, `Button`, `Field`, `Pill`, `formatMoney`, `humanize`.

- [ ] **Step 1: Failing tests** (Review Focus 4)

`src/features/sockets/sockets.test.ts`:
```ts
import { defaultCap, grantedScopesOf, socketsOf } from "./sockets";
import type { Manifest } from "@/lib/api/types";

const base: Manifest = {
  name: "A", version: "1", networkAllowlist: [], dataClasses: [], llm: null, approvalGates: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  scopes: [
    { type: "READ", resource: "bank_statement", description: "Statement lines", requiresApproval: false },
    { type: "WRITE", resource: "ledger", description: "Post", requiresApproval: true },
  ],
  resources: [{ name: "bank_statement", format: "CSV", schema: { required: ["date", "amount"], properties: {} } }],
};

it("makes one socket per READ scope with its required columns", () => {
  expect(socketsOf(base)).toEqual([{ resource: "bank_statement", label: "Bank statement", description: "Statement lines", requiredColumns: ["date", "amount"] }]);
});
it("still lists sockets when resources are untyped (null)", () => {
  expect(socketsOf({ ...base, resources: null })[0].requiredColumns).toEqual([]);
});
it("grants every declared scope and defaults the cap to the declared maximum", () => {
  expect(grantedScopesOf(base)).toEqual(["bank_statement", "ledger"]);
  expect(defaultCap(base)).toBe(500);
});
```

`src/features/sockets/ConnectForm.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

const a = { upload: vi.fn(), check: vi.fn(), create: vi.fn(), bind: vi.fn(), run: vi.fn() };
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/app/(app)/connect/actions", () => ({
  uploadForSocketAction: (...x: unknown[]) => a.upload(...x),
  checkSocketAction: (...x: unknown[]) => a.check(...x),
  createConnectionAction: (...x: unknown[]) => a.create(...x),
  bindSocketAction: (...x: unknown[]) => a.bind(...x),
  startRunAction: (...x: unknown[]) => a.run(...x),
}));
import { ConnectForm } from "./ConnectForm";
import type { Dataset, Manifest } from "@/lib/api/types";

const manifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0", networkAllowlist: [], dataClasses: [], llm: null, approvalGates: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
  scopes: [{ type: "READ", resource: "bank_statement", description: "Statement lines", requiresApproval: false }],
  resources: [{ name: "bank_statement", format: "CSV", schema: { required: ["date"], properties: {} } }],
};
const ds: Dataset = { id: "d1", displayName: "Aug.csv", sourceLabel: null, format: "CSV", rowCount: 412, sizeBytes: 1, columns: ["date"], sha256: "x", uploadedBy: "P", createdAt: "2026-09-01T00:00:00Z", piiScan: null, usage: [] };

beforeEach(() => { Object.values(a).forEach((m) => m.mockReset()); });

it("blocks 'Add and run now' until every socket has a checked file", async () => {
  a.check.mockResolvedValue({ result: { ok: true, rowCount: 412, columnsMatch: true, missingColumns: [], rowErrorCount: 0, rowErrors: [] } });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="Bank Reconciliation" manifest={manifest} datasets={[ds]} canConnect />);
  expect(screen.getByRole("button", { name: /add and run now/i })).toBeDisabled();
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  expect(await screen.findByText(/412 rows, columns match/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /add and run now/i })).toBeEnabled();
});

it("shows what is missing when the columns do not match", async () => {
  a.check.mockResolvedValue({ result: { ok: false, rowCount: 10, columnsMatch: false, missingColumns: ["amount"], rowErrorCount: 0, rowErrors: [] } });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect />);
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  expect(await screen.findByText(/missing: amount/i)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /add and run now/i })).toBeDisabled();
});

it("creates the connection once, binds, runs, and reuses the connection on retry", async () => {
  a.check.mockResolvedValue({ result: { ok: true, rowCount: 1, columnsMatch: true, missingColumns: [], rowErrorCount: 0, rowErrors: [] } });
  a.create.mockResolvedValue({ connectionId: "c1" });
  a.bind.mockResolvedValue({});
  a.run.mockResolvedValueOnce({ error: "Insufficient funds" }).mockResolvedValueOnce({ displayId: "R-1" });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect />);
  await userEvent.selectOptions(screen.getByLabelText(/bank statement/i), "d1");
  await screen.findByText(/columns match/i);
  await userEvent.click(screen.getByRole("button", { name: /add and run now/i }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Insufficient funds");
  await userEvent.click(screen.getByRole("button", { name: /add and run now/i }));
  expect(a.create).toHaveBeenCalledTimes(1);
  expect(a.bind).toHaveBeenCalledWith("c1", "bank_statement", "d1");
  expect(a.run).toHaveBeenCalledTimes(2);
});

it("works for an agent whose manifest declares no resources", async () => {
  a.create.mockResolvedValue({ connectionId: "c1" });
  a.run.mockResolvedValue({ displayId: "R-1" });
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={{ ...manifest, resources: null, scopes: [{ type: "WRITE", resource: "ledger", description: "x", requiresApproval: true }] }} datasets={[]} canConnect />);
  expect(screen.queryByRole("combobox")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: /add and run now/i }));
  expect(a.bind).not.toHaveBeenCalled();
  expect(a.run).toHaveBeenCalledWith("c1");
});

it("tells someone who is not a buyer admin why they cannot connect", () => {
  render(<ConnectForm agentId="a1" versionId="v1" agentName="A" manifest={manifest} datasets={[ds]} canConnect={false} />);
  expect(screen.getByText(/only a workspace admin can add an agent/i)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /add and run now/i })).toBeNull();
});
```

- [ ] **Step 2: Verify failure, implement**

`src/features/sockets/sockets.ts`:
```ts
import { humanize } from "@/lib/format";
import type { Manifest } from "@/lib/api/types";

export interface Socket { resource: string; label: string; description: string; requiredColumns: string[] }

export function socketsOf(m: Manifest): Socket[] {
  return m.scopes
    .filter((s) => s.type === "READ")
    .map((s) => ({
      resource: s.resource,
      label: humanize(s.resource),
      description: s.description,
      requiredColumns: m.resources?.find((r) => r.name === s.resource)?.schema?.required ?? [],
    }));
}

export const grantedScopesOf = (m: Manifest): string[] => m.scopes.map((s) => s.resource);
export const defaultCap = (m: Manifest): number => m.costEnvelope.maxPerRun;
```

`src/app/(app)/connect/actions.ts`:
```ts
"use server";
import { ApiError } from "@/lib/api/http";
import { bindResource, createConnection } from "@/lib/api/connections";
import { checkDataset, uploadDataset } from "@/lib/api/datasets";
import { triggerRun } from "@/lib/api/runs";
import type { CheckResult, Dataset } from "@/lib/api/types";

async function guard<T extends object>(fn: () => Promise<T>): Promise<T | { error: string }> {
  try { return await fn(); } catch (e) {
    if (e instanceof ApiError) return { error: e.message };
    throw e;
  }
}

export const uploadForSocketAction = (fd: FormData) => guard<{ dataset: Dataset }>(async () => ({ dataset: await uploadDataset(fd) }));
export const checkSocketAction = (datasetId: string, versionId: string) => guard<{ result: CheckResult }>(async () => ({ result: await checkDataset(datasetId, versionId) }));
export const createConnectionAction = (agentId: string, grantedScopes: string[], spendCap: number) =>
  guard<{ connectionId: string }>(async () => ({ connectionId: (await createConnection({ agentId, grantedScopes, spendCap })).id }));
export const bindSocketAction = (connectionId: string, resource: string, datasetId: string) =>
  guard<{ ok: true }>(async () => { await bindResource(connectionId, resource, datasetId); return { ok: true as const }; });
export const startRunAction = (connectionId: string) => guard<{ displayId: string }>(async () => ({ displayId: (await triggerRun(connectionId)).displayId }));
```

`src/features/sockets/ConnectForm.tsx`:
```tsx
"use client";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import styles from "./sockets.module.css";
import { defaultCap, grantedScopesOf, socketsOf } from "./sockets";
import { Button } from "@/ui/Button";
import { Dropzone } from "@/ui/Dropzone";
import { Pill } from "@/ui/Pill";
import {
  bindSocketAction, checkSocketAction, createConnectionAction, startRunAction, uploadForSocketAction,
} from "@/app/(app)/connect/actions";
import { formatMoney } from "@/lib/format";
import type { CheckResult, Dataset, Manifest } from "@/lib/api/types";

type Props = { agentId: string; versionId: string; agentName: string; manifest: Manifest; datasets: Dataset[]; canConnect: boolean };
type Picked = { datasetId: string; check: CheckResult | null };

export function ConnectForm({ agentId, versionId, agentName, manifest, datasets: initial, canConnect }: Props) {
  const router = useRouter();
  const sockets = socketsOf(manifest);
  const [datasets, setDatasets] = useState(initial);
  const [picked, setPicked] = useState<Record<string, Picked>>({});
  const [cap, setCap] = useState(defaultCap(manifest));
  const [connectionId, setConnectionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const max = defaultCap(manifest);

  if (!canConnect) {
    return <p role="status">Only a workspace admin can add an agent. Ask your admin to add {agentName}.</p>;
  }

  const choose = (resource: string, datasetId: string) =>
    start(async () => {
      setError(null);
      if (!datasetId) { setPicked((p) => ({ ...p, [resource]: undefined as never })); return; }
      setPicked((p) => ({ ...p, [resource]: { datasetId, check: null } }));
      const res = await checkSocketAction(datasetId, versionId);
      if ("error" in res) { setError(res.error); return; }
      setPicked((p) => ({ ...p, [resource]: { datasetId, check: res.result } }));
    });

  const upload = (resource: string, file: File) =>
    start(async () => {
      setError(null);
      const fd = new FormData();
      fd.set("file", file);
      const res = await uploadForSocketAction(fd);
      if ("error" in res) { setError(res.error); return; }
      setDatasets((d) => [res.dataset, ...d]);
      choose(resource, res.dataset.id);
    });

  const ready = sockets.every((s) => picked[s.resource]?.check?.ok) && cap > 0 && cap <= max;

  const submit = () =>
    start(async () => {
      setError(null);
      let cid = connectionId;
      if (!cid) {
        const created = await createConnectionAction(agentId, grantedScopesOf(manifest), cap);
        if ("error" in created) { setError(created.error); return; }
        cid = created.connectionId;
        setConnectionId(cid);
      }
      for (const s of sockets) {
        const bound = await bindSocketAction(cid, s.resource, picked[s.resource].datasetId);
        if ("error" in bound) { setError(bound.error); return; }
      }
      const ran = await startRunAction(cid);
      if ("error" in ran) { setError(ran.error); return; }
      router.push(`/runs/${ran.displayId}`);
    });

  return (
    <div className={styles.wrap}>
      {sockets.map((s) => {
        const p = picked[s.resource];
        return (
          <section key={s.resource} className={styles.socket} aria-label={s.label}>
            <div>
              <span className={styles.kind}>Reads</span>
              <strong>{s.label}</strong>
              <p className={styles.sub}>{s.description}{s.requiredColumns.length ? `. Needs: ${s.requiredColumns.join(", ")}` : ""}</p>
            </div>
            <label className={styles.pick}>
              <span className="sr-only">{s.label}</span>
              <select value={p?.datasetId ?? ""} onChange={(e) => choose(s.resource, e.target.value)} disabled={pending}>
                <option value="">Choose a file</option>
                {datasets.map((d) => <option key={d.id} value={d.id}>{d.displayName}</option>)}
              </select>
            </label>
            <Dropzone label="Upload a file" onFile={(f) => upload(s.resource, f)} disabled={pending} />
            {p?.check && (
              <p role="status">
                {p.check.ok
                  ? <Pill tone="ok">{`${p.check.rowCount} rows, columns match`}</Pill>
                  : <Pill tone="bad">{p.check.missingColumns.length ? `Missing: ${p.check.missingColumns.join(", ")}` : `${p.check.rowErrorCount} rows have problems`}</Pill>}
              </p>
            )}
          </section>
        );
      })}
      <section className={styles.limits}>
        <h2>Limits</h2>
        <label>
          Most one run can cost
          <input type="number" min={1} max={max} value={cap} onChange={(e) => setCap(Number(e.target.value))} />
        </label>
        <p className={styles.sub}>{formatMoney(max, manifest.costEnvelope.currency)} is the most this agent ever charges. A run stops when it reaches your limit.</p>
      </section>
      {error && <p role="alert" className={styles.err}>{error}</p>}
      {!ready && sockets.length > 0 && <p className={styles.sub}>Plug in a file for each block above to continue.</p>}
      <Button onClick={submit} loading={pending} disabled={!ready}>Add and run now</Button>
    </div>
  );
}
```

`src/features/sockets/sockets.module.css`:
```css
.wrap { display: flex; flex-direction: column; gap: 16px; max-width: 720px; }
.socket { display: flex; flex-direction: column; gap: 10px; background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); padding: 16px; }
.kind { display: block; font-size: 11px; font-weight: 600; text-transform: uppercase; color: var(--ink3); }
.sub { margin: 2px 0 0; color: var(--ink3); font-size: 13px; }
.pick select { width: 100%; min-height: 44px; border: 1px solid var(--l); border-radius: 10px; padding: 0 10px; font: inherit; background: var(--s); }
.limits { background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); padding: 16px; display: flex; flex-direction: column; gap: 8px; }
.limits input { min-height: 44px; width: 160px; border: 1px solid var(--l); border-radius: 10px; padding: 0 10px; font: inherit; }
.err { color: var(--bad); margin: 0; }
```

`src/app/(app)/connect/[agentId]/[versionId]/page.tsx`:
```tsx
import { ApiError } from "@/lib/api/http";
import { getVersion } from "@/lib/api/catalog";
import { listDatasets } from "@/lib/api/datasets";
import { getSession } from "@/lib/session";
import { canCreateConnections } from "@/lib/roles";
import { ConnectForm } from "@/features/sockets/ConnectForm";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const session = (await getSession())!;
  try {
    const [version, datasets] = await Promise.all([getVersion(params.agentId, params.versionId), listDatasets()]);
    return (
      <>
        <h1>Plug your files into {version.agentName}</h1>
        <p>Each socket takes one file. Karya checks the columns before the agent ever sees it.</p>
        <ConnectForm
          agentId={params.agentId}
          versionId={params.versionId}
          agentName={version.agentName}
          manifest={version.manifest}
          datasets={datasets}
          canConnect={canCreateConnections(session.role)}
        />
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that agent version." : e.message} />;
    throw e;
  }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/sockets && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): plug in data sockets with column check and add-and-run"
```

---

### Task 15: Store list and the agent as a flow

**Files:**
- Create: `src/app/(app)/store/page.tsx`, `src/app/(app)/store/[agentId]/[versionId]/page.tsx`, `src/features/flow/StoreFlow.tsx`, `StoreFlow.module.css`, `StoreFlow.test.tsx`

**Interfaces:**
- Produces: `StoreFlow({ agentId, versionId, detail: VersionDetail, canConnect: boolean })` (client): the block strip with a selected-block panel, a "What you agree to" box, and an "Add to workspace" link to `/connect/{agentId}/{versionId}` (shown for `BUYER_ADMIN`; others see a line telling them to ask a workspace admin).
- "What you agree to" rows: Price (`{unitPrice} per {unitLabel}`), Most a run can cost, Outside websites (`None` when `networkAllowlist` is empty, else the list), Data it handles (`dataClasses` humanized, joined by ", "; `None` when empty), and the agent's version.
- Consumes: `FlowBlocks`, `blocksFromManifest`, `toBlockViews`.

- [ ] **Step 1: Failing test**

`src/features/flow/StoreFlow.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StoreFlow } from "./StoreFlow";
import type { VersionDetail } from "@/lib/api/types";

const detail: VersionDetail = {
  id: "v1", agentId: "a1", agentName: "Bank Reconciliation", versionNumber: "1.3.0", status: "LIVE",
  manifest: {
    name: "Bank Reconciliation", version: "1.3.0", networkAllowlist: [], dataClasses: ["FINANCIAL", "BASIC_PERSONAL"], llm: {}, resources: null,
    costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "METERED", unitPrice: 2, unitLabel: "match" },
    scopes: [{ type: "READ", resource: "bank_statement", description: "Your statement lines, CSV", requiresApproval: false }],
    approvalGates: [{ trigger: "post", description: "Before anything is posted", timeoutMinutes: 30, preview: null }],
  },
};

it("shows the declared blocks and what the buyer agrees to", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Bank Reconciliation");
  expect(screen.getByText("Your statement lines, CSV")).toBeInTheDocument();
  expect(screen.getByText("₹2 per match")).toBeInTheDocument();
  expect(screen.getByText("₹500")).toBeInTheDocument();
  expect(screen.getByText("None")).toBeInTheDocument();
  expect(screen.getByText("Financial, Basic personal")).toBeInTheDocument();
});

it("links to the connect flow for a workspace admin", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  expect(screen.getByRole("link", { name: "Add to workspace" })).toHaveAttribute("href", "/connect/a1/v1");
});

it("does not offer add to workspace to someone who cannot", () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect={false} />);
  expect(screen.queryByRole("link", { name: "Add to workspace" })).toBeNull();
  expect(screen.getByText(/ask a workspace admin/i)).toBeInTheDocument();
});

it("shows the selected block in the side panel", async () => {
  render(<StoreFlow agentId="a1" versionId="v1" detail={detail} canConnect />);
  await userEvent.click(screen.getByRole("button", { name: /Your approval/ }));
  expect(screen.getByRole("region", { name: "Selected block" })).toHaveTextContent("Before anything is posted");
});
```

- [ ] **Step 2: Verify failure, implement**

`src/features/flow/StoreFlow.tsx`:
```tsx
"use client";
import Link from "next/link";
import { useState } from "react";
import styles from "./StoreFlow.module.css";
import { FlowBlocks } from "./FlowBlocks";
import { blocksFromManifest, toBlockViews } from "./blocks";
import { formatMoney, humanize } from "@/lib/format";
import type { VersionDetail } from "@/lib/api/types";

export function StoreFlow({ agentId, versionId, detail, canConnect }: { agentId: string; versionId: string; detail: VersionDetail; canConnect: boolean }) {
  const m = detail.manifest;
  const views = toBlockViews(blocksFromManifest(m), m);
  const [sel, setSel] = useState(views[0]?.id);
  const cur = views.find((v) => v.id === sel);
  const c = m.costEnvelope;
  return (
    <div className={styles.wrap}>
      <header>
        <h1>{detail.agentName}</h1>
        <p className={styles.sub}>Version {detail.versionNumber}</p>
      </header>
      <FlowBlocks blocks={views} selectedId={sel} onSelect={setSel} />
      <div className={styles.cols}>
        <section aria-label="Selected block" className={styles.panel}>
          {cur && (<><span className={styles.kind}>{cur.kind}</span><h2>{cur.title}</h2><p>{cur.body}</p></>)}
        </section>
        <section aria-label="What you agree to" className={styles.panel}>
          <h2>What you agree to</h2>
          <dl>
            <div><dt>Price</dt><dd>{`${formatMoney(c.unitPrice, c.currency)} per ${c.unitLabel.replace(/_/g, " ")}`}</dd></div>
            <div><dt>Most a run can cost</dt><dd>{formatMoney(c.maxPerRun, c.currency)}</dd></div>
            <div><dt>Outside websites</dt><dd>{m.networkAllowlist.length ? m.networkAllowlist.join(", ") : "None"}</dd></div>
            <div><dt>Data it handles</dt><dd>{m.dataClasses.length ? m.dataClasses.map((d) => humanize(d)).join(", ") : "None"}</dd></div>
          </dl>
          {canConnect
            ? <Link href={`/connect/${agentId}/${versionId}`} className={styles.cta}>Add to workspace</Link>
            : <p className={styles.sub}>Ask a workspace admin to add this agent.</p>}
        </section>
      </div>
    </div>
  );
}
```

`src/features/flow/StoreFlow.module.css`:
```css
.wrap { display: flex; flex-direction: column; gap: 20px; }
.sub { color: var(--ink3); margin: 4px 0 0; }
.cols { display: grid; grid-template-columns: 1fr 340px; gap: 20px; align-items: start; }
.panel { background: var(--s); border: 1px solid var(--l); border-radius: var(--radius); padding: 18px; display: flex; flex-direction: column; gap: 10px; }
.panel p { margin: 0; color: var(--ink2); }
.kind { font-size: 11px; font-weight: 600; text-transform: uppercase; color: var(--ink3); }
.panel dl { margin: 0; display: flex; flex-direction: column; gap: 10px; }
.panel dt { font-size: 12px; color: var(--ink3); }
.panel dd { margin: 2px 0 0; font-weight: 600; }
.cta { display: inline-flex; align-items: center; justify-content: center; min-height: 44px; padding: 0 16px; border-radius: 10px; background: var(--b); color: #fff; font-weight: 600; text-decoration: none; }
@media (max-width: 900px) { .cols { grid-template-columns: 1fr; } }
```

Also make `humanize("FINANCIAL")` produce `"Financial"`: update `humanize` in `src/lib/format.ts` to lower-case the rest of the string (`s.charAt(0).toUpperCase() + s.slice(1).toLowerCase()`) and add the test `expect(humanize("BASIC_PERSONAL")).toBe("Basic personal")` to `format.test.ts`. Re-run the format tests.

`src/app/(app)/store/page.tsx`:
```tsx
import Link from "next/link";
import { listCatalog } from "@/lib/api/catalog";
import { ApiError } from "@/lib/api/http";
import { EmptyState } from "@/ui/EmptyState";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page() {
  try {
    const listings = await listCatalog();
    return (
      <>
        <h1>Agent store</h1>
        <p>Every agent shows what it will read, do and charge before you add it.</p>
        {listings.length === 0 ? (
          <EmptyState title="No agents are published yet" body="Check back soon." />
        ) : (
          <ul style={{ listStyle: "none", padding: 0, display: "grid", gap: 12, gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))" }}>
            {listings.map((l) => (
              <li key={l.versionId} style={{ background: "var(--s)", border: "1px solid var(--l)", borderRadius: 12, padding: 16 }}>
                <Link href={`/store/${l.agentId}/${l.versionId}`}><strong>{l.agentName}</strong></Link>
                <div style={{ color: "var(--ink3)", fontSize: 13 }}>{l.category} · v{l.versionNumber}</div>
              </li>
            ))}
          </ul>
        )}
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.message} />;
    throw e;
  }
}
```

`src/app/(app)/store/[agentId]/[versionId]/page.tsx`:
```tsx
import { ApiError } from "@/lib/api/http";
import { getVersion } from "@/lib/api/catalog";
import { getSession } from "@/lib/session";
import { canCreateConnections } from "@/lib/roles";
import { StoreFlow } from "@/features/flow/StoreFlow";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { agentId: string; versionId: string } }) {
  const session = (await getSession())!;
  try {
    const detail = await getVersion(params.agentId, params.versionId);
    return <StoreFlow agentId={params.agentId} versionId={params.versionId} detail={detail} canConnect={canCreateConnections(session.role)} />;
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that agent version." : e.message} />;
    throw e;
  }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/features/flow src/lib/format.test.ts && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): agent store list and the agent as a flow"
```

---

### Task 16: Phone approval route

**Files:**
- Create: `src/app/approve/[runId]/[gateId]/page.tsx`, `src/app/approve/layout.tsx`, `src/app/approve/approve.module.css`, `src/app/approve/ApprovePage.test.tsx`

**Interfaces:**
- Produces: a route that renders `GateCard` for one gate of one run, with no rail. If the gate id is not in the run's gates, it shows "We can't find that request." After a decision it links back to `/runs/{runId}`.
- Auth: the path is not in the middleware's public list, so a signed-out user is redirected to `/login?next=/approve/...` and returns here after signing in (`safeNext` accepts the path).

- [ ] **Step 1: Failing test**

`src/app/approve/ApprovePage.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { vi } from "vitest";

vi.mock("@/app/(app)/runs/actions", () => ({ decideGateAction: vi.fn() }));
vi.mock("@/lib/api/runs", () => ({ getRunGates: vi.fn() }));
vi.mock("@/lib/session", () => ({ getSession: vi.fn().mockResolvedValue({ userId: "u", orgId: "o", role: "BUYER_APPROVER", exp: 9e9 }) }));
import { getRunGates } from "@/lib/api/runs";
import Page from "./[runId]/[gateId]/page";

const gate = { gateId: "g1", action: "post", summary: "Post 142 entries?", payload: {}, status: "PENDING", expiresAt: "2999-01-01T00:00:00Z", estimatedUnits: null, costIfApproved: null, preview: null, decidedVia: null, decidedByUserId: null, decidedAt: null, note: null };

it("renders the requested gate for an approver", async () => {
  vi.mocked(getRunGates).mockResolvedValue([gate] as never);
  render(await Page({ params: { runId: "R-1", gateId: "g1" } }));
  expect(screen.getByText("Post 142 entries?")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
});

it("says so when the gate is not part of the run", async () => {
  vi.mocked(getRunGates).mockResolvedValue([gate] as never);
  render(await Page({ params: { runId: "R-1", gateId: "nope" } }));
  expect(screen.getByText(/can't find that request/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Verify failure, implement**

`src/app/approve/layout.tsx`:
```tsx
import styles from "./approve.module.css";

export default function ApproveLayout({ children }: { children: React.ReactNode }) {
  return (
    <main className={styles.page}>
      <div className={styles.brand}>Karya</div>
      {children}
    </main>
  );
}
```

`src/app/approve/approve.module.css`:
```css
.page { max-width: 560px; margin: 0 auto; padding: 20px 16px 40px; display: flex; flex-direction: column; gap: 16px; }
.brand { font-weight: 700; color: var(--b); }
```

`src/app/approve/[runId]/[gateId]/page.tsx`:
```tsx
import Link from "next/link";
import { ApiError } from "@/lib/api/http";
import { getRunGates } from "@/lib/api/runs";
import { getSession } from "@/lib/session";
import { canDecideGates } from "@/lib/roles";
import { GateCard } from "@/features/gates/GateCard";
import { ErrorState } from "@/ui/ErrorState";

export default async function Page({ params }: { params: { runId: string; gateId: string } }) {
  const session = await getSession();
  try {
    const gates = await getRunGates(params.runId);
    const gate = gates.find((g) => g.gateId === params.gateId);
    if (!gate) return <ErrorState message="We can't find that request." />;
    return (
      <>
        <h1>Approval needed</h1>
        <GateCard runRef={params.runId} gate={gate} canDecide={!!session && canDecideGates(session.role)} />
        <p><Link href={`/runs/${params.runId}`}>See the whole run</Link></p>
      </>
    );
  } catch (e) {
    if (e instanceof ApiError) return <ErrorState message={e.status === 404 ? "We can't find that run." : e.message} />;
    throw e;
  }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd frontend && npx vitest run src/app/approve && npm run build` → PASS.
```bash
git add frontend && git commit -m "feat(frontend): mobile-first approval route"
```

---

### Task 17: P1 end-to-end test, responsive check and wrap-up

**Files:**
- Create: `frontend/e2e/seed.ts`, `frontend/e2e/p1-buyer.spec.ts`, `frontend/e2e/responsive.spec.ts`
- Modify: `docs/frontend-gaps.md`, `frontend/README.md`, `docs/known-issues.md` (only if a fact there changed)

**Interfaces:**
- Produces: `npm run seed:e2e` creates (against the local backend and Postgres) a developer, a buyer admin `e2e-buyer@example.com`, a buyer approver `e2e-approver@example.com` in the same org (both password `e2e-password`), a LIVE agent "E2E Bank Reconciliation" with a READ resource `bank_statement` (CSV, required columns `date`, `narration`, `amount`) and a WRITE scope with an approval gate `post_entries`, and a funded wallet.
- The e2e cannot run a real sandbox, so the test seeds a run that is already `AWAITING_APPROVAL` with a pending gate, by inserting rows directly.

- [ ] **Step 1: Seed script**

Start from the old seed's approach (it is in git history: `git show HEAD~N:frontend/e2e/seed.ts`, or reuse the version shown in this plan's spec review): sign up the three users through `POST /api/v1/auth/signup`, create the agent and a version via `POST /api/v1/agents` and `.../versions`, move the approver into the buyer org and fund the wallet with SQL (as the old seed did, using `pg`).

New in this seed compared to the old one:
1. The manifest is the parsed-wire form used by `POST .../versions`: `scopes` has `{type:"READ",resource:"bank_statement",description:"Bank statement lines"}` and `{type:"WRITE",resource:"ledger",description:"Post entries",requiresApproval:true}`; add `"resources":[{"name":"bank_statement","format":"CSV","schema":{"required":["date","narration","amount"],"properties":{}}}]`, `"approvalGates":[{"trigger":"post_entries","description":"Before anything is posted"}]`, `costEnvelope.maxPerRun` 500.
2. Make the version LIVE by SQL: `update agent_version set status='LIVE', approved_at=now(), image_ref='e2e-template' where id=$1`.
3. Create a connection (SQL or `POST /api/v1/connections` with the buyer admin's token), then insert a `run` in status `AWAITING_APPROVAL` with `display_id 'R-E2E1'`, a `gate_request` in status `PENDING` (`action 'post_entries'`, `summary 'Post 142 journal entries for August?'`, `expires_at now() + interval '1 day'`) and `run_step` rows of kind `GATE_REQUESTED` with `block 'gate:post_entries'`.
4. Before writing the inserts, read the current column lists: `grep -rn "create table run\b\|create table gate_request\|create table run_step" backend/src/main/resources/db/migration` and use the real column names and NOT NULL columns. Print the seeded ids as JSON on the last line so the spec can read them.

- [ ] **Step 2: The P1 spec**

`frontend/e2e/p1-buyer.spec.ts`:
```ts
import { expect, test } from "@playwright/test";
import { writeFileSync } from "fs";
import { join } from "path";

const password = "e2e-password";

async function signIn(page: import("@playwright/test").Page, email: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/home$/);
}

test("buyer admin uploads a file, sees the store flow and the plug-in screen", async ({ page }, info) => {
  await signIn(page, "e2e-buyer@example.com");
  const csv = join(info.outputDir, "statement.csv");
  writeFileSync(csv, "date,narration,amount\n2026-08-01,NEFT ACME,48200\n");

  await page.goto("/files");
  await page.getByLabel("Upload file").setInputFiles(csv);
  await expect(page.getByText("statement.csv")).toBeVisible();

  await page.goto("/store");
  await page.getByRole("link", { name: /E2E Bank Reconciliation/ }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("E2E Bank Reconciliation");
  await page.getByRole("link", { name: "Add to workspace" }).click();

  await page.getByLabel(/bank statement/i).selectOption({ label: "statement.csv" });
  await expect(page.getByText(/1 rows, columns match/i)).toBeVisible();
  await expect(page.getByRole("button", { name: /add and run now/i })).toBeEnabled();
});

test("buyer admin sees a waiting run but cannot decide it", async ({ page }) => {
  await signIn(page, "e2e-buyer@example.com");
  await page.goto("/runs");
  await expect(page.getByRole("link", { name: "R-E2E1" })).toBeVisible();
  await page.getByRole("link", { name: "R-E2E1" }).click();
  await expect(page.getByText("Post 142 journal entries for August?")).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
});

test("approver approves the gate from the phone route and the run moves on", async ({ page }) => {
  await signIn(page, "e2e-approver@example.com");
  await page.goto("/runs/R-E2E1");
  await page.getByRole("button", { name: "Approve" }).click();
  await expect(page.getByText("You approved this.")).toBeVisible();
  await page.goto("/runs");
  await expect(page.getByRole("link", { name: "R-E2E1" })).toBeVisible();
});
```

(The original spec asks for "run" in this flow. A real sandbox run is out of reach for e2e, so the run is seeded. The "Add and run now" click is covered by `ConnectForm.test.tsx`. Record this in the commit body.)

`frontend/e2e/responsive.spec.ts`:
```ts
import { expect, test } from "@playwright/test";

const widths = [390, 768, 1440];
const pages = ["/home", "/files", "/runs", "/store"];

for (const w of widths) {
  test(`no horizontal page scroll at ${w}px`, async ({ page }) => {
    await page.setViewportSize({ width: w, height: 900 });
    await page.goto("/login");
    await page.getByLabel("Email").fill("e2e-buyer@example.com");
    await page.getByLabel("Password").fill("e2e-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    for (const p of pages) {
      await page.goto(p);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${p} overflows at ${w}px`).toBeLessThanOrEqual(0);
      await page.screenshot({ path: `test-results/${w}${p.replace("/", "-")}.png`, fullPage: true });
    }
  });
}
```

- [ ] **Step 3: Run everything**

Run (backend, Postgres and object store up; see memory `agentstore-dev-environment` for the Colima and Testcontainers settings):
```bash
cd frontend && npm test && npx tsc --noEmit && npm run build
npm run seed:e2e      # once, on a fresh database
npm run test:e2e
```
Expected: all unit tests pass, type check and build pass, and every e2e spec passes (including the P0 spec's `/runs` step from Task 7).

- [ ] **Step 4: Review screenshots against the mockup**

Open `test-results/*.png` for the four P1 screens at 390, 768 and 1440 px next to the mockup boards (Files, Plug in data, Live run, Runs history, Approve from a phone). List each visible deviation and fix any that are layout bugs. Record the rest under "Known visual differences" at the bottom of `docs/frontend-gaps.md`.

- [ ] **Step 5: Finalize docs and commit**

Update `docs/frontend-gaps.md` so its table matches what was omitted (G1 to G11). Update `frontend/README.md` with the e2e seed notes (fresh database, the two logins). Then:

```bash
git add frontend docs && git commit -m "test(frontend): P1 e2e, responsive checks and final gap list"
```

---

## Self-review

**Spec coverage**
- Section 1, decisions (delete old, fresh Next.js, responsive, omit and log gaps): Tasks 1, 2, 3, 7, 12, 17.
- Section 2, repo changes and `docs/frontend-gaps.md`: Tasks 1 and 7.
- Section 3, structure: File Structure and Tasks 2 to 16.
- Section 4, design system: Tasks 2, 3, 11 (ResponsiveTable), 13 (Dropzone, Drawer).
- Section 5, session and data: Task 4 (with deviation 1), Task 8, Task 11 (polling).
- Section 6 screens: sign in (5), home (6), Files (13), Plug in data (14), Live run (12), Runs (11), Store flow (15), phone approval (16).
- Section 7, states: every page has an empty and an error state (Tasks 6, 11 to 16).
- Section 8, testing: unit tests per task, e2e in Tasks 7 and 17, responsive in 17.
- Section 10, gaps G1 to G9 plus G10, G11: Tasks 7 and 12.
- Section 11, risks: the catalog shape was confirmed (`VersionDetail.manifest`); the 403 case is covered in Task 11.

**Placeholder scan:** the only "look up" instructions are in Task 8 (confirm the two decision enum values) and Task 17 (read the migration column names for the seed inserts). Both name the exact file and command.

**Type consistency:** `RunListItem`, `RunSnapshot`, `GateDetail`, `Manifest`, `VersionDetail`, `Dataset`, `CheckResult` are defined once in `types.ts` (Tasks 6 and 8) and used with the same names in Tasks 9 to 16. `BlockView`, `toBlockViews`, `blocksFromManifest` (Task 9) match their use in Tasks 11, 12 and 15. `canDecideGates` and `canCreateConnections` (Task 3) match Tasks 10, 12, 14, 15 and 16. `useRunPolling` (Task 11) is used with the same signature in Task 12.

**Review Focus:** items 1 to 5 each have a named test (Tasks 4, 10, 11, 13, 14).
