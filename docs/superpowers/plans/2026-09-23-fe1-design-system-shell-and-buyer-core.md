# FE-1 — Design System, App Shell and Buyer Core Implementation Plan


**Goal:** Move the web app onto the new Karya visual language from `fe-mockups/latest` and build the buyer screens whose backend is planned. That covers:
- the new design tokens, fonts and UI primitives;
- the app shell (icon rail, top bar, workspace switcher, ⌘K search, notification bell);
- sign in and create account, plus the invite, email verification and password reset pages;
- workspace home, runs history, the run console with approvals and disputes;
- one-tap approval on a phone;
- the notification centre with its settings, team settings, billing, and "Plug in data and set limits" (limits, approvers and channels).

**Architecture:**
- **Stack and layering.** The app stays Next.js 14 App Router. Server components fetch from the Spring backend through `lib/backend.ts`, with the JWT read from an httpOnly cookie. Client components render and call server actions, and polling goes through thin `/api/*` proxy routes. Every screen follows the existing split: `page.tsx` (server, fetches) → `XxxView.tsx` (client, renders, tested with React Testing Library) → `actions.ts` (server actions that return `ActionResult`).
- **Design system.** Tokens are CSS variables mapped into Tailwind. The primitives are small, dependency-free React components in `src/components/ui/`.
- **Legacy screens.** Old token names stay as aliases of the new palette, so developer and admin screens that FE-3 and FE-4 have not rebuilt yet keep rendering.
- **Sessions.** When a person asks to stay signed in, a refresh-token cookie is stored. The middleware refreshes the access token on page navigations only (never on prefetches or API polls), so concurrent refreshes, which WS13 treats as token theft, stay rare. This is not sufficient on its own: two tabs of the same session can still both hit a navigation at once and race to call `/auth/refresh` with the same token, and WS13's rotation treats the loser's reuse of the now-rotated token as theft, signing both tabs out. The proxy must therefore serialize refreshes per session — one in-flight refresh at a time, with any concurrent caller awaiting its result instead of issuing its own `/auth/refresh` call.

**Tech Stack:** Next.js 14.2 (App Router, server actions), React 18, TypeScript 5, Tailwind CSS 3.4, TanStack Query 5, `next/font/google` (Instrument Sans), `geist` (Geist Mono), Vitest 5 + React Testing Library + jsdom, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §10, rows 1, 2, 4 (limits and approvers only), 5 (without the flow canvas), 6, 7, 9 (without top-up and invoices) and 10. The backend contracts come from the plans named under Prerequisites.

**Mockups:** open `fe-mockups/latest/Karya — Agentic UI.html` in Chrome. Each board is titled with the screen name used in this plan. Copy, spacing and colour come from there. The mockups' example data (Meridian Traders, R-2291) is illustrative; real screens show real data.

**Prerequisites (backend merged first):**
- `2026-09-23-ws0-execution-defect-fixes.md`, including its frontend Task 8. It adds the `TIMED_OUT` and `EXPIRED` run statuses, the `TIMEOUT` and `COST_CAP_REACHED` step kinds, and `isTerminalRunStatus`.
- `2026-09-23-ws13-identity-workspaces-teams.md`, including its Task 7, which already reshaped the signup call and the seed.
- `2026-09-23-ws15-workspace-spending-approvers.md`
- `2026-09-23-ws14-notifications-and-approval-links.md`
- `2026-09-23-ws16-disputes.md`

**Series:** FE-1 (this plan) → FE-2 `2026-09-23-fe2-buyer-agents-files-and-live-flow.md` (needs WS1, WS2, WS8, WS9, WS11) → FE-3 `2026-09-23-fe3-developer-studio-and-money.md` (needs WS5, WS6, WS7, WS10) → FE-4 `2026-09-23-fe4-karya-admin-consoles.md` (needs WS7, WS8, WS11).

## Global Constraints

- Palette, exactly as in the mockups:

  | Token | Value | Token | Value |
  |---|---|---|---|
  | `--g` | `#F8FAFD` | `--b` | `#2B5CE6` |
  | `--dot` | `#D6DFEC` | `--b-hover` | `#2450CC` |
  | `--s` | `#FFFFFF` | `--b2` | `#5B85F0` |
  | `--l` | `#E3E9F2` | `--b3` | `#9DB6F3` |
  | `--l2` | `#EEF2F8` | `--sky` | `#EDF3FF` |
  | `--ink` | `#0D1A30` | `--sky2` | `#D9E5FF` |
  | `--ink2` | `#46546D` | `--ok` / `--okS` | `#0B8A60` / `#E2F4EB` |
  | `--ink3` | `#617089` | `--wa` / `--waS` / `--wa-line` | `#9E6209` / `#FFF3DC` / `#E7B25B` |
  | | | `--bad` / `--badS` / `--bad-line` | `#C23B30` / `#FCEAE8` / `#EAB0A9` |

  The new design is light only. The old dark-mode block is removed (dark mode is a later design pass).
- Fonts: Instrument Sans (UI) via `next/font/google`, and Geist Mono (numbers, ids, code) via the `geist` package, because Next 14.2's Google font list has no Geist Mono. Money, ids and times use `font-mono tabular-nums`.
- Money: `inr()` from `lib/format.ts`. Whole rupees show no paise (`₹12,480`), fractional amounts show two decimals (`₹284.50`), with Indian digit grouping (`₹38,41,260`). All times display in Asia/Kolkata.
- Copy: plain sentences in the product's voice, taken from the mockups where one exists. Don't use "error occurred" style text. Every backend problem `code` a screen can hit is mapped to a sentence in that screen's `actions.ts`.
- Accessibility:
  - Every icon-only control has an `aria-label`.
  - Toggles expose `aria-pressed` or `aria-checked`, and expandable rows expose `aria-expanded`.
  - Focus rings are `outline-2 outline-blue`.
  - Nothing relies on colour alone: every pill has text.
- Server actions never throw for expected backend refusals. They return `{ ok: false, error }` built by `actionError`. They throw only for programming errors, and they `redirect` only outside `try`.
- Client code never calls the backend directly. It goes through server actions or `/api/*` proxy routes, and polling uses `clientFetch`, which reloads the page on 401 so the middleware can refresh the session.
- No new runtime dependencies besides `geist`. No component library.
- Test commands: `npx tsc --noEmit` and `npm test` (Vitest). There is no lint script in this repo.
- **Not in FE-1** (and where it lands):
  - The store's agent flow, track record, versions, files, data sockets on Connect, the live-run flow canvas and runs-history timeline bars → FE-2.
  - Add money, tax invoices, and everything for developers → FE-3.
  - The Karya admin consoles → FE-4.
  - FE-1 keeps the existing catalog, agent detail, developer and admin pages working inside the new shell.

## Review Focus

1. **An access token that expires while someone is on a page.** The next navigation must refresh it silently if they chose "Keep me signed in". A background poll that hits 401 reloads the page instead of showing an error. [Task 2]
2. **A `next` parameter pointing off-site** (`/login?next=//evil.example`) must be ignored after sign-in, landing on the role's home. [Task 4]
3. **An approver tapping Approve on a phone after someone else decided** must see "This request has closed", not an error page, and must not see buttons that do nothing. [Task 8]
4. **A person who belongs to two workspaces** must see their current workspace name in the top bar, and switching must land them on that workspace's home with the right navigation. [Task 3]
5. **"Add and run now" refused for money reasons** (wallet, monthly limits) must leave the buyer on the setup screen with a sentence saying why and what to do, and must not show a half-created agent. [Task 13]

---

## File Structure

```
frontend/
  package.json                                  (modify: + geist)
  tailwind.config.ts                            (modify: new tokens, fonts, shadows)
  src/app/globals.css                           (modify: palette, legacy aliases, dot grid)
  src/app/layout.tsx                            (modify: fonts)
  src/app/error.tsx                             (modify: new styles)
  src/middleware.ts                             (modify: session refresh)
  src/lib/
    format.ts            (modify: inr, times, greeting, initials)
    backendError.ts      (create)      backend.ts (modify: put/patch/delete)
    problems.ts          (create: problemCode, actionError, ActionResult)
    types.ts             (modify: WS13–WS16 contracts)
    session.ts           (modify: refresh cookie, tokenExpiry, roleHome → /home)
    sessionCookies.ts    (create)      sessionRefresh.ts (create)
    routing.ts           (modify: public paths, safeNextPath)
    authActions.ts       (modify: logout revokes refresh token)
    clientFetch.ts       (create)
    roles.ts             (create)      runStatus.ts (create)   runSteps.ts (create)
    homeCopy.ts          (create)      notificationCopy.ts (create)  connectCopy.ts (create)
    statement.ts         (modify: groupStatement)
  src/components/ui/     Icon, Logo, Button, Pill, Panel, Kv, ProgressBar, Segmented, MiniFlow, Avatar  (create)
  src/components/shell/  nav.ts, RailNav, TopBar, WorkspaceSwitcher, CommandBar, NotificationBell,
                         UserMenu, AppShell, shellActions.ts                                        (create)
  src/app/api/proxy.ts, api/search/route.ts, api/notifications/unread-count/route.ts                (create)
  src/app/(auth)/        layout.tsx, AuthScreen.tsx, GoogleButton.tsx, actions.ts, login/page.tsx,
                         signup/page.tsx, forgot-password/, reset-password/[token]/,
                         verify-email/[token]/, invite/[token]/                                    (create/modify)
  src/app/(buyer)/       layout.tsx, home/, runs/, runs/[runId]/, billing/, connect/[agentId]/[versionId]/,
                         store/[agentId]/                                                          (create/modify)
  src/app/(account)/     layout.tsx, notifications/, settings/team/, settings/notifications/        (create)
  src/app/approve/[token]/                                                                          (create)
  src/app/(developer)/layout.tsx, src/app/(admin)/layout.tsx                                        (modify)
  e2e/core-loop.spec.ts                                                                             (modify)
  Deleted: components/ApprovalsBadge.tsx, components/RunTraceStep.tsx(+test),
           (buyer)/runs/RunsList.tsx(+test), (buyer)/runs/StartRun.tsx(+test),
           (buyer)/connect/.../ConnectWizard.tsx(+test), (auth)/login/actions.ts, (auth)/signup/actions.ts
```

All commands run from `frontend/`.

---

### Task 1: Design tokens, fonts, formatting and UI primitives

**Files:**
- Modify: `package.json`, `tailwind.config.ts`, `src/app/globals.css`, `src/app/layout.tsx`, `src/lib/format.ts`
- Create: `src/components/ui/Icon.tsx`, `Logo.tsx`, `Button.tsx`, `Pill.tsx`, `Panel.tsx`, `Kv.tsx`, `ProgressBar.tsx`, `Segmented.tsx`, `MiniFlow.tsx`, `Avatar.tsx`
- Test: create `src/lib/format.test.ts`, `src/components/ui/ui.test.tsx`

**Interfaces:**
- Produces:
  - Tailwind colours:
    - surfaces and lines: `canvas`, `dot`, `surface`, `line`, `line2`;
    - text: `ink`, `ink2`, `ink3`;
    - blues: `blue` (plus `blue-hover`, `blue-2`, `blue-3`) and `sky` (plus `sky-2`);
    - status: `ok` (plus `ok-soft`), `wa` (plus `wa-soft`, `wa-line`), `bad` (plus `bad-soft`, `bad-line`).
  - Tailwind shadows `shadow-block`, `shadow-wait`. Font families `sans`, `mono`. Utility class `.dot-grid`.
  - `format.ts`:
    - money and ids: `inr(amount: number): string`, `shortId(id: string): string`;
    - times: `minutesUntil(iso, now?)`, `timeAgo(iso, now?)`, `clockTime(iso)`, `dayLabel(iso, now?)`;
    - words: `greeting(now?)`, `firstName(name)`, `initials(name)`, `countWord(n)`;
    - `formatInr` stays for legacy screens.
  - UI:
    - `Icon({ name: IconName, className?, label? })`, `Logo({ size? })`
    - `Button({ variant?: "primary"|"secondary"|"danger", size?: "sm"|"md"|"lg", ...buttonProps })`, `ButtonLink({ href, variant?, size?, children, className? })`, `buttonClass(variant?, size?)`
    - `Pill({ tone?: Tone, children, dot? })` with `type Tone = "b"|"ok"|"wa"|"bad"|"n"`
    - `Panel({ className?, children, as? })`, `Kv({ label, value, mono? })`
    - `ProgressBar({ value, max, tone?, label })`
    - `Segmented<T extends string>({ options: {value: T, label: string}[], value: T, onChange, label })`
    - `MiniFlow({ steps: FlowStepState[], label })` with `type FlowStepState = "done"|"run"|"wait"|"bad"|"idle"`
    - `Avatar({ name })`

- [ ] **Step 1: Write the failing tests**

`src/lib/format.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { clockTime, countWord, dayLabel, firstName, greeting, initials, inr, minutesUntil, shortId, timeAgo } from "./format";

describe("inr", () => {
  it("shows whole rupees without paise and uses Indian grouping", () => {
    expect(inr(12480)).toBe("₹12,480");
    expect(inr(3841260)).toBe("₹38,41,260");
    expect(inr(0)).toBe("₹0");
  });
  it("shows two decimals for fractional amounts", () => {
    expect(inr(284.5)).toBe("₹284.50");
    expect(inr(21.4)).toBe("₹21.40");
  });
});

describe("times", () => {
  const now = new Date("2026-09-23T04:14:00Z"); // 09:44 IST
  it("formats clock time in India", () => {
    expect(clockTime("2026-09-23T04:11:02Z")).toBe("09:41");
  });
  it("says Today or a short date", () => {
    expect(dayLabel("2026-09-23T02:00:00Z", now)).toBe("Today");
    expect(dayLabel("2026-09-21T02:00:00Z", now)).toBe("21 Sep");
  });
  it("describes how long ago", () => {
    expect(timeAgo("2026-09-23T04:13:40Z", now)).toBe("just now");
    expect(timeAgo("2026-09-23T04:11:00Z", now)).toBe("3 minutes ago");
    expect(timeAgo("2026-09-23T03:13:00Z", now)).toBe("1 hour ago");
    expect(timeAgo("2026-09-20T03:13:00Z", now)).toBe("20 Sep");
  });
  it("counts whole minutes left, never negative", () => {
    expect(minutesUntil("2026-09-23T04:40:30Z", now)).toBe(27);
    expect(minutesUntil("2026-09-23T04:00:00Z", now)).toBe(0);
  });
  it("greets by the time of day in India", () => {
    expect(greeting(new Date("2026-09-23T03:00:00Z"))).toBe("Good morning");
    expect(greeting(new Date("2026-09-23T09:00:00Z"))).toBe("Good afternoon");
    expect(greeting(new Date("2026-09-23T14:00:00Z"))).toBe("Good evening");
  });
});

describe("names and ids", () => {
  it("takes first names and initials", () => {
    expect(firstName("Priya Shah")).toBe("Priya");
    expect(firstName(null)).toBe("");
    expect(initials("Priya Shah")).toBe("PS");
    expect(initials("asha")).toBe("A");
    expect(initials("")).toBe("?");
  });
  it("shortens ids and spells small counts", () => {
    expect(shortId("0f8a2c4e-1111-2222-3333-444455556666")).toBe("0f8a2c4e");
    expect(countWord(1)).toBe("One");
    expect(countWord(2)).toBe("Two");
    expect(countWord(12)).toBe("12");
  });
});
```

`src/components/ui/ui.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Avatar } from "./Avatar";
import { Button, ButtonLink } from "./Button";
import { MiniFlow } from "./MiniFlow";
import { Pill } from "./Pill";
import { ProgressBar } from "./ProgressBar";
import { Segmented } from "./Segmented";

describe("ui primitives", () => {
  it("renders buttons as buttons by default and links when asked", () => {
    render(
      <>
        <Button>Approve</Button>
        <ButtonLink href="/runs">Open runs</ButtonLink>
      </>,
    );
    expect(screen.getByRole("button", { name: "Approve" })).toHaveAttribute("type", "button");
    expect(screen.getByRole("link", { name: "Open runs" })).toHaveAttribute("href", "/runs");
  });

  it("keeps a submit type when given one", () => {
    render(<Button type="submit">Save</Button>);
    expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("type", "submit");
  });

  it("shows pill text so status never relies on colour alone", () => {
    render(<Pill tone="wa">Needs you</Pill>);
    expect(screen.getByText("Needs you")).toBeInTheDocument();
  });

  it("clamps the progress bar and exposes its value", () => {
    render(<ProgressBar value={620} max={500} label="Spend" />);
    const bar = screen.getByRole("progressbar", { name: "Spend" });
    expect(bar).toHaveAttribute("aria-valuenow", "620");
    expect(bar.querySelector("i")).toHaveStyle({ width: "100%" });
  });

  it("marks the chosen segment pressed and reports changes", async () => {
    const onChange = vi.fn();
    render(
      <Segmented
        label="Most one run can cost"
        options={[{ value: "250", label: "₹250" }, { value: "500", label: "₹500" }]}
        value="500"
        onChange={onChange}
      />,
    );
    expect(screen.getByRole("button", { name: "₹500" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "₹250" }));
    expect(onChange).toHaveBeenCalledWith("250");
  });

  it("describes a mini flow for screen readers", () => {
    render(<MiniFlow steps={["done", "done", "run", "idle"]} label="Step 3 of 4" />);
    expect(screen.getByLabelText("Step 3 of 4")).toBeInTheDocument();
  });

  it("shows initials in avatars", () => {
    render(<Avatar name="Priya Shah" />);
    expect(screen.getByText("PS")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/format.test.ts src/components/ui/ui.test.tsx`
Expected: FAIL (`inr is not exported`, `Cannot find module './Avatar'`).

- [ ] **Step 3: Tokens, fonts and Tailwind**

Run: `npm install geist@^1.3.1`

Replace `src/app/globals.css`:

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

:root {
  --g: #F8FAFD; --dot: #D6DFEC; --s: #FFFFFF; --l: #E3E9F2; --l2: #EEF2F8;
  --ink: #0D1A30; --ink2: #46546D; --ink3: #617089;
  --b: #2B5CE6; --b-hover: #2450CC; --b2: #5B85F0; --b3: #9DB6F3; --sky: #EDF3FF; --sky2: #D9E5FF;
  --ok: #0B8A60; --okS: #E2F4EB;
  --wa: #9E6209; --waS: #FFF3DC; --wa-line: #E7B25B;
  --bad: #C23B30; --badS: #FCEAE8; --bad-line: #EAB0A9;

  /* Legacy names, kept so screens not yet rebuilt (developer, admin, catalog) still render. */
  --paper: var(--g); --paper-2: var(--l2); --ink-2: var(--ink2); --muted: var(--ink3); --line: var(--l);
  --green: var(--b); --green-soft: var(--sky); --amber: var(--wa); --amber-soft: var(--waS);
  --red: var(--bad); --red-soft: var(--badS); --doc: var(--s);
}

body {
  background: var(--g);
  color: var(--ink);
  -webkit-font-smoothing: antialiased;
}

.dot-grid {
  background-image: radial-gradient(circle, var(--dot) 1px, transparent 1.3px);
  background-size: 18px 18px;
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition: none !important; }
}
```

Replace `tailwind.config.ts`:

```ts
import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        canvas: "var(--g)",
        dot: "var(--dot)",
        surface: "var(--s)",
        line: "var(--l)",
        line2: "var(--l2)",
        ink: "var(--ink)",
        ink2: "var(--ink2)",
        ink3: "var(--ink3)",
        blue: { DEFAULT: "var(--b)", hover: "var(--b-hover)", 2: "var(--b2)", 3: "var(--b3)" },
        sky: { DEFAULT: "var(--sky)", 2: "var(--sky2)" },
        ok: { DEFAULT: "var(--ok)", soft: "var(--okS)" },
        wa: { DEFAULT: "var(--wa)", soft: "var(--waS)", line: "var(--wa-line)" },
        bad: { DEFAULT: "var(--bad)", soft: "var(--badS)", line: "var(--bad-line)" },
        // legacy names (aliases of the new palette)
        paper: "var(--paper)",
        paper2: "var(--paper-2)",
        muted: "var(--muted)",
        green: "var(--green)",
        greenSoft: "var(--green-soft)",
        amber: "var(--amber)",
        amberSoft: "var(--amber-soft)",
        red: "var(--red)",
        redSoft: "var(--red-soft)",
        doc: "var(--doc)",
      },
      fontFamily: {
        sans: ["var(--font-instrument)", "ui-sans-serif", "system-ui", "sans-serif"],
        serif: ["var(--font-instrument)", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["var(--font-geist-mono)", "ui-monospace", "SFMono-Regular", "monospace"],
      },
      boxShadow: {
        block: "0 1px 1px rgba(13,26,48,.04), 0 12px 26px -18px rgba(43,92,230,.4)",
        wait: "0 0 0 4px var(--waS), 0 14px 30px -22px rgba(158,98,9,.5)",
      },
    },
  },
};

export default config;
```

Replace `src/app/layout.tsx`:

```tsx
import type { Metadata } from "next";
import { Instrument_Sans } from "next/font/google";
import { GeistMono } from "geist/font/mono";
import { Providers } from "./providers";
import "./globals.css";

const instrument = Instrument_Sans({ subsets: ["latin", "latin-ext"], variable: "--font-instrument", display: "swap" });

export const metadata: Metadata = {
  title: "Karya",
  description: "Agents that do the finance work. You approve what matters.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${instrument.variable} ${GeistMono.variable}`}>
      <body className="font-sans text-[14px] text-ink bg-canvas">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
```

`GeistMono.variable` defines `--font-geist-mono`.

- [ ] **Step 4: Formatting helpers**

Replace `src/lib/format.ts`:

```ts
const IST = "Asia/Kolkata";
const WORDS = ["No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten"];

/** Legacy formatter kept for screens not yet rebuilt. New code uses inr(). */
export function formatInr(amount: number): string {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(amount);
}

export function inr(amount: number): string {
  const whole = Number.isInteger(amount);
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: whole ? 0 : 2,
    maximumFractionDigits: whole ? 0 : 2,
  }).format(amount);
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}

export function minutesUntil(iso: string, now: Date = new Date()): number {
  return Math.max(0, Math.floor((new Date(iso).getTime() - now.getTime()) / 60000));
}

export function clockTime(iso: string): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: IST })
    .format(new Date(iso));
}

export function dayLabel(iso: string, now: Date = new Date()): string {
  const fmt = (d: Date) => new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: IST }).format(d);
  const label = fmt(new Date(iso));
  return label === fmt(now) ? "Today" : label;
}

export function timeAgo(iso: string, now: Date = new Date()): string {
  const minutes = Math.floor((now.getTime() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  return dayLabel(iso, now);
}

export function greeting(now: Date = new Date()): string {
  const hour = Number(new Intl.DateTimeFormat("en-GB", { hour: "numeric", hour12: false, timeZone: IST }).format(now));
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

export function firstName(name: string | null | undefined): string {
  return name?.trim().split(/\s+/)[0] ?? "";
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export function countWord(n: number): string {
  return n >= 0 && n < WORDS.length ? WORDS[n] : String(n);
}
```

`minutesUntil` rounds down, so "closes in 27 minutes" is never optimistic.

- [ ] **Step 5: UI primitives**

`src/components/ui/Icon.tsx`:

```tsx
const PATHS = {
  home: "M4 11l8-7 8 7v9H4zM10 20v-5h4v5",
  store: "M4 8h16l-1.2 12H5.2zM8.5 8a3.5 3.5 0 017 0",
  runs: "M4 6h16M4 12h11M4 18h7",
  files: "M6 3h8l4 4v14H6zM14 3v4h4",
  billing: "M3 6h18v13H3zM3 10h18",
  bell: "M6 16v-5a6 6 0 1112 0v5l2 2H4zM10 21h4",
  search: "M11 18a7 7 0 100-14 7 7 0 000 14zM20 20l-4-4",
  plus: "M12 5v14M5 12h14",
  check: "M5 12.5l4.5 4.5L19 7.5",
  cross: "M6 6l12 12M18 6L6 18",
  gate: "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6l8-3z",
  layers: "M12 3l9 5-9 5-9-5 9-5zM3 13l9 5 9-5",
  clock: "M12 21a9 9 0 100-18 9 9 0 000 18zM12 7v5l3 2",
  stop: "M6 6h12v12H6z",
  read: "M3 4h18v16H3zM3 10h18M9 4v16",
  write: "M6 3h8l4 4v14H6z",
  rupee: "M7 5h10M7 9h10M13 5c3 0 3 8-2 8H7l7 7",
  team: "M9 11a3 3 0 100-6 3 3 0 000 6zM3 20c0-3 3-5 6-5s6 2 6 5M16 5a3 3 0 010 6M21 20c0-2.4-1.6-4.2-4-4.8",
  settings: "M12 15a3 3 0 100-6 3 3 0 000 6zM12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1",
  logout: "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10",
  chevronDown: "M6 9l6 6 6-6",
  arrowRight: "M5 12h14M13 6l6 6-6 6",
  code: "M8 8l-4 4 4 4M16 8l4 4-4 4",
  slack: "M9 3v8M15 13v8M3 15h8M13 9h8",
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({ name, className = "w-[18px] h-[18px]", label }: { name: IconName; className?: string; label?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={`${className} shrink-0 fill-none stroke-current`}
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
```

`src/components/ui/Logo.tsx`:

```tsx
export function Logo({ size = 9 }: { size?: number }) {
  const colors = ["bg-blue", "bg-blue-2", "bg-blue-3", "bg-sky-2"];
  return (
    <span aria-hidden className="grid grid-cols-2 gap-[3px]">
      {colors.map((c) => (
        <i key={c} className={`${c} rounded-[3px]`} style={{ width: size, height: size }} />
      ))}
    </span>
  );
}
```

`src/components/ui/Button.tsx`:

```tsx
import Link from "next/link";
import type { ButtonHTMLAttributes, ReactNode } from "react";

type Variant = "primary" | "secondary" | "danger";
type Size = "sm" | "md" | "lg";

const BASE =
  "inline-flex items-center justify-center gap-2 font-semibold whitespace-nowrap border transition-colors " +
  "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue " +
  "disabled:bg-line2 disabled:border-line disabled:text-ink3 disabled:cursor-default";
const VARIANTS: Record<Variant, string> = {
  primary: "bg-blue border-blue text-white hover:bg-blue-hover",
  secondary: "bg-surface border-line text-ink hover:border-blue-3",
  danger: "bg-surface border-bad-line text-bad hover:bg-bad-soft",
};
const SIZES: Record<Size, string> = {
  sm: "h-8 px-3 text-[12.5px] rounded-lg",
  md: "h-[38px] px-4 text-[13px] rounded-[10px]",
  lg: "h-[54px] px-5 text-base rounded-[14px] w-full",
};

export function buttonClass(variant: Variant = "primary", size: Size = "md"): string {
  return `${BASE} ${VARIANTS[variant]} ${SIZES[size]}`;
}

export function Button({
  variant,
  size,
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: Size }) {
  return <button type="button" {...props} className={`${buttonClass(variant, size)} ${className}`} />;
}

export function ButtonLink({
  href,
  variant,
  size,
  className = "",
  children,
}: {
  href: string;
  variant?: Variant;
  size?: Size;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Link href={href} className={`${buttonClass(variant, size)} ${className}`}>
      {children}
    </Link>
  );
}
```

`src/components/ui/Pill.tsx`:

```tsx
import type { ReactNode } from "react";

export type Tone = "b" | "ok" | "wa" | "bad" | "n";

const TONES: Record<Tone, string> = {
  b: "bg-sky text-blue",
  ok: "bg-ok-soft text-ok",
  wa: "bg-wa-soft text-wa",
  bad: "bg-bad-soft text-bad",
  n: "bg-line2 text-ink2",
};

export function Pill({ tone = "n", dot = true, children }: { tone?: Tone; dot?: boolean; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1.5 h-6 px-2.5 rounded-full text-xs font-medium whitespace-nowrap ${TONES[tone]}`}>
      {dot && <i aria-hidden className="w-1.5 h-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}
```

`src/components/ui/Panel.tsx`:

```tsx
import type { ReactNode } from "react";

export function Panel({
  className = "",
  children,
  as: Tag = "section",
  "aria-label": ariaLabel,
}: {
  className?: string;
  children: ReactNode;
  as?: "section" | "div" | "aside";
  "aria-label"?: string;
}) {
  return (
    <Tag aria-label={ariaLabel} className={`bg-surface border border-line rounded-2xl ${className}`}>
      {children}
    </Tag>
  );
}

export function PanelTitle({ children, hint }: { children: ReactNode; hint?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h2 className="text-sm font-semibold">{children}</h2>
      {hint && <span className="text-xs text-ink3">{hint}</span>}
    </div>
  );
}
```

`src/components/ui/Kv.tsx`:

```tsx
import type { ReactNode } from "react";

export function Kv({ label, value, mono = true }: { label: ReactNode; value: ReactNode; mono?: boolean }) {
  return (
    <div className="flex justify-between items-center gap-3 text-[13px] py-1.5">
      <span className="text-ink2">{label}</span>
      <span className={`${mono ? "font-mono" : ""} tabular-nums font-medium text-right`}>{value}</span>
    </div>
  );
}
```

`src/components/ui/ProgressBar.tsx`:

```tsx
const FILL = { b: "bg-blue", wa: "bg-wa-line", bad: "bg-bad", ok: "bg-ok" } as const;

export function ProgressBar({
  value,
  max,
  label,
  tone = "b",
}: {
  value: number;
  max: number;
  label: string;
  tone?: keyof typeof FILL;
}) {
  const pct = max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0;
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuenow={value}
      aria-valuemin={0}
      aria-valuemax={max}
      className="h-1.5 rounded-full bg-line2 overflow-hidden"
    >
      <i className={`block h-full rounded-full ${FILL[tone]} transition-[width] duration-500`} style={{ width: `${pct}%` }} />
    </div>
  );
}
```

`src/components/ui/Segmented.tsx`:

```tsx
export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
  label: string;
}) {
  return (
    <div role="group" aria-label={label} className="flex gap-[3px] bg-line2 p-[3px] rounded-[10px]">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className={`flex-1 h-[34px] px-3.5 rounded-lg text-[13px] ${
            o.value === value ? "bg-surface text-ink font-semibold shadow-sm" : "text-ink2"
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
```

`src/components/ui/MiniFlow.tsx`:

```tsx
export type FlowStepState = "done" | "run" | "wait" | "bad" | "idle";

const STEP: Record<FlowStepState, string> = {
  done: "bg-blue-3 border-blue-3",
  run: "bg-blue border-blue",
  wait: "bg-[#F3C77A] border-wa-line",
  bad: "bg-bad-soft border-bad-line",
  idle: "bg-[#EAF0FA] border-[#D5E0F1]",
};

/** The small row of blocks the mockups use to show how far a run got. */
export function MiniFlow({ steps, label }: { steps: FlowStepState[]; label: string }) {
  return (
    <div role="img" aria-label={label} className="flex items-center">
      {steps.map((s, i) => (
        <span key={i} className="flex items-center">
          {i > 0 && <b aria-hidden className="w-2 h-[1.5px] bg-[#CBD7EB]" />}
          <i aria-hidden className={`w-[22px] h-3.5 rounded border ${STEP[s]}`} />
        </span>
      ))}
    </div>
  );
}
```

`src/components/ui/Avatar.tsx`:

```tsx
import { initials } from "@/lib/format";

export function Avatar({ name, size = 32 }: { name: string; size?: number }) {
  return (
    <span
      className="rounded-full bg-sky-2 text-blue grid place-items-center text-xs font-semibold shrink-0"
      style={{ width: size, height: size }}
    >
      {initials(name)}
    </span>
  );
}
```

- [ ] **Step 6: Run the tests**

Run: `npm test -- src/lib/format.test.ts src/components/ui/ui.test.tsx && npx tsc --noEmit`
Expected: PASS and no type errors.

- [ ] **Step 7: Run the whole suite**

Run: `npm test`
Expected: all PASS. The legacy screens' tests still pass, because the legacy token names still exist.

- [ ] **Step 8: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/tailwind.config.ts frontend/src/app/globals.css \
  frontend/src/app/layout.tsx frontend/src/lib/format.ts frontend/src/lib/format.test.ts frontend/src/components/ui
git commit -m "feat(frontend): Karya design tokens, fonts, formatting helpers and UI primitives"
```

---

### Task 2: Backend calls, problem codes, contracts and staying signed in

**Files:**
- Create: `src/lib/backendError.ts`, `src/lib/problems.ts`, `src/lib/sessionCookies.ts`, `src/lib/sessionRefresh.ts`, `src/lib/clientFetch.ts`, `src/lib/roles.ts`
- Modify: `src/lib/backend.ts`, `src/lib/types.ts`, `src/lib/session.ts`, `src/lib/routing.ts`, `src/middleware.ts`, `src/lib/authActions.ts`
- Test: create `src/lib/problems.test.ts`, `src/lib/sessionRefresh.test.ts`, `src/lib/roles.test.ts`; rewrite `src/lib/routing.test.ts`; update `src/lib/session.test.ts`

**Interfaces:**
- Consumes: `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout` (WS13).
- Assumption: `refreshSession`/the middleware's refresh path is called at most once at a time per session — concurrent callers (e.g. two tabs navigating together) must be serialized (a shared in-flight promise, or equivalent) in front of it, not each call `/auth/refresh` independently, because WS13 revokes the whole refresh-token family on reuse of an already-rotated token.
- Produces:
  - `backend.get/post/put/patch/delete<T>(path, body?)`. `BackendError` moves to `lib/backendError.ts` and is re-exported from `lib/backend.ts`.
  - `problemCode(e): string | undefined`, `problemMessage(e, fallback): string`.
  - `type ActionResult<T = object> = ({ ok: true } & T) | ActionFailure` with `ActionFailure = { ok: false; error: string; code?: string }`.
  - `actionError(e, messages: Record<string, string>, fallback): ActionFailure` looks up `messages` by problem `code` first, then by HTTP status as a string, and rethrows anything that isn't a `BackendError`.
  - `SESSION_COOKIE`, `REFRESH_COOKIE`, `tokenExpiry(token): number | null`, `roleHome(role)` (buyer roles → `/home`).
  - `storeSession(auth: AuthResponse)`, `clearSession()` (server actions only).
  - `planSession({ accessToken?, refreshToken?, nowSeconds }): "continue" | "refresh" | "signin"`, `refreshSession(backendUrl, refreshToken): Promise<AuthResponse | null>`.
  - `isPublicPath(pathname)`, `safeNextPath(next): string | null`, `resolveRedirect(pathname, claims)`.
  - `clientFetch<T>(url): Promise<T>` (reloads on 401).
  - `roleLabel(role)`, `assignableRoles(orgType)`, `canManageMembers(role)`, `isBuyerRole(role)`.
  - New types in `types.ts`, listed in Step 3.

- [ ] **Step 1: Write the failing tests**

`src/lib/problems.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { BackendError } from "./backendError";
import { actionError, problemCode, problemMessage } from "./problems";

describe("problems", () => {
  const problem = new BackendError(409, { status: 409, code: "ORG_MONTHLY_LIMIT", detail: "Over the limit of ₹50000" });

  it("reads the stable code", () => {
    expect(problemCode(problem)).toBe("ORG_MONTHLY_LIMIT");
    expect(problemCode(new BackendError(500, null))).toBeUndefined();
  });

  it("prefers a mapped sentence, then status, then the backend detail, then the fallback", () => {
    expect(actionError(problem, { ORG_MONTHLY_LIMIT: "Mapped by code" }, "Fallback").error).toBe("Mapped by code");
    expect(actionError(problem, { "409": "Mapped by status" }, "Fallback").error).toBe("Mapped by status");
    expect(actionError(problem, {}, "Fallback").error).toBe("Over the limit of ₹50000");
    expect(actionError(new BackendError(500, null), {}, "Fallback").error).toBe("Fallback");
  });

  it("keeps the code on the failure", () => {
    expect(actionError(problem, {}, "x")).toEqual({ ok: false, error: "Over the limit of ₹50000", code: "ORG_MONTHLY_LIMIT" });
  });

  it("rethrows anything that is not a backend refusal", () => {
    expect(() => actionError(new TypeError("bug"), {}, "x")).toThrow("bug");
  });

  it("reads Spring's default message field too", () => {
    expect(problemMessage(new BackendError(400, { message: "Scope not declared" }), "x")).toBe("Scope not declared");
    expect(problemMessage(new BackendError(400, { message: "" }), "x")).toBe("x");
  });
});
```

`src/lib/sessionRefresh.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from "vitest";
import { planSession, refreshSession } from "./sessionRefresh";

function jwt(payload: Record<string, unknown>): string {
  const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "HS256" })}.${b64(payload)}.sig`;
}

const now = Math.floor(Date.now() / 1000);
const claims = { sub: "u1", orgId: "o1", role: "BUYER_ADMIN" };

describe("planSession", () => {
  it("continues with a token that has more than two minutes left", () => {
    expect(planSession({ accessToken: jwt({ ...claims, exp: now + 3600 }), refreshToken: "r", nowSeconds: now })).toBe("continue");
  });
  it("refreshes an expired or nearly expired token when a refresh token exists", () => {
    expect(planSession({ accessToken: jwt({ ...claims, exp: now - 10 }), refreshToken: "r", nowSeconds: now })).toBe("refresh");
    expect(planSession({ accessToken: jwt({ ...claims, exp: now + 60 }), refreshToken: "r", nowSeconds: now })).toBe("refresh");
    expect(planSession({ refreshToken: "r", nowSeconds: now })).toBe("refresh");
  });
  it("keeps using a nearly expired token when there is nothing to refresh with", () => {
    expect(planSession({ accessToken: jwt({ ...claims, exp: now + 60 }), nowSeconds: now })).toBe("continue");
  });
  it("asks to sign in when the token is gone or expired and there is no refresh token", () => {
    expect(planSession({ nowSeconds: now })).toBe("signin");
    expect(planSession({ accessToken: jwt({ ...claims, exp: now - 10 }), nowSeconds: now })).toBe("signin");
  });
});

describe("refreshSession", () => {
  afterEach(() => vi.restoreAllMocks());

  it("returns the new tokens on success", async () => {
    vi.spyOn(global, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ token: "a2", refreshToken: "r2" }), { status: 200 }),
    );
    await expect(refreshSession("http://backend", "r1")).resolves.toEqual({ token: "a2", refreshToken: "r2" });
    expect(fetch).toHaveBeenCalledWith("http://backend/api/v1/auth/refresh", expect.objectContaining({ method: "POST" }));
  });

  it("returns null when the backend refuses or is unreachable", async () => {
    vi.spyOn(global, "fetch").mockResolvedValueOnce(new Response("{}", { status: 401 }));
    await expect(refreshSession("http://backend", "r1")).resolves.toBeNull();
    vi.spyOn(global, "fetch").mockRejectedValueOnce(new Error("down"));
    await expect(refreshSession("http://backend", "r1")).resolves.toBeNull();
  });
});
```

Replace `src/lib/routing.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { resolveRedirect, safeNextPath } from "./routing";
import type { SessionClaims } from "./session";

const buyer: SessionClaims = { userId: "u1", orgId: "o1", role: "BUYER_ADMIN" };
const approver: SessionClaims = { userId: "u1", orgId: "o1", role: "BUYER_APPROVER" };
const developer: SessionClaims = { userId: "u1", orgId: "o1", role: "DEVELOPER" };

describe("resolveRedirect", () => {
  it("lets anyone open public pages", () => {
    for (const p of ["/login", "/signup", "/forgot-password", "/reset-password/t", "/verify-email/t", "/invite/t", "/approve/t"]) {
      expect(resolveRedirect(p, null)).toBeNull();
    }
  });
  it("sends signed-out people to sign in and remembers where they were going", () => {
    expect(resolveRedirect("/runs/r1", null)).toBe("/login?next=%2Fruns%2Fr1");
  });
  it("sends signed-in people away from the sign-in page", () => {
    expect(resolveRedirect("/login", buyer)).toBe("/home");
  });
  it("keeps each role inside its own area", () => {
    expect(resolveRedirect("/home", developer)).toBe("/dashboard");
    expect(resolveRedirect("/billing", approver)).toBe("/home");
    expect(resolveRedirect("/dashboard", buyer)).toBe("/home");
    expect(resolveRedirect("/runs/r1", approver)).toBeNull();
  });
  it("lets every role reach notifications and settings", () => {
    expect(resolveRedirect("/notifications", developer)).toBeNull();
    expect(resolveRedirect("/settings/team", approver)).toBeNull();
  });
});

describe("safeNextPath", () => {
  it("accepts same-site paths only", () => {
    expect(safeNextPath("/runs/r1")).toBe("/runs/r1");
    expect(safeNextPath("//evil.example")).toBeNull();
    expect(safeNextPath("/\\evil.example")).toBeNull();
    expect(safeNextPath("https://evil.example")).toBeNull();
    expect(safeNextPath(undefined)).toBeNull();
  });
});
```

`src/lib/roles.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { assignableRoles, canManageMembers, isBuyerRole, roleLabel } from "./roles";

describe("roles", () => {
  it("labels roles in plain words", () => {
    expect(roleLabel("BUYER_ADMIN")).toBe("Admin");
    expect(roleLabel("BUYER_APPROVER")).toBe("Approver");
    expect(roleLabel("DEVELOPER")).toBe("Developer");
    expect(roleLabel("ADMIN")).toBe("Karya admin");
  });
  it("offers only the roles an org can hold", () => {
    expect(assignableRoles("BUYER")).toEqual(["BUYER_ADMIN", "BUYER_APPROVER"]);
    expect(assignableRoles("DEVELOPER")).toEqual(["DEVELOPER"]);
  });
  it("knows who manages members and who is a buyer", () => {
    expect(canManageMembers("BUYER_ADMIN")).toBe(true);
    expect(canManageMembers("BUYER_APPROVER")).toBe(false);
    expect(isBuyerRole("BUYER_APPROVER")).toBe(true);
    expect(isBuyerRole("DEVELOPER")).toBe(false);
  });
});
```

In `src/lib/session.test.ts`, change the `roleHome` expectations for `BUYER_ADMIN` and `BUYER_APPROVER` from `"/catalog"` to `"/home"`.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib`
Expected: FAIL (`Cannot find module './backendError'`, and the routing and session expectations fail).

- [ ] **Step 3: Backend client, problems, contracts**

`src/lib/backendError.ts`:

```ts
export class BackendError extends Error {
  status: number;
  body: unknown;

  constructor(status: number, body: unknown) {
    super(`Backend request failed with status ${status}`);
    this.status = status;
    this.body = body;
  }
}
```

Replace `src/lib/backend.ts`:

```ts
import { cookies } from "next/headers";
import { BackendError } from "./backendError";
import { SESSION_COOKIE } from "./session";

export { BackendError };

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  const res = await fetch(`${process.env.BACKEND_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {}),
    },
    cache: "no-store",
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new BackendError(res.status, body);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const withBody = (method: string) => <T,>(path: string, body?: unknown) =>
  request<T>(path, { method, body: body !== undefined ? JSON.stringify(body) : undefined });

export const backend = {
  get: <T,>(path: string) => request<T>(path),
  post: withBody("POST"),
  put: withBody("PUT"),
  patch: withBody("PATCH"),
  delete: <T,>(path: string) => request<T>(path, { method: "DELETE" }),
};
```

`src/lib/problems.ts`:

```ts
import { BackendError } from "./backendError";

export type ActionFailure = { ok: false; error: string; code?: string };
export type ActionResult<T = object> = ({ ok: true } & T) | ActionFailure;

function body(e: unknown): Record<string, unknown> | null {
  return e instanceof BackendError && e.body && typeof e.body === "object" ? (e.body as Record<string, unknown>) : null;
}

export function problemCode(e: unknown): string | undefined {
  const b = body(e);
  return b && typeof b.code === "string" ? b.code : undefined;
}

export function problemMessage(e: unknown, fallback: string): string {
  const b = body(e);
  for (const key of ["detail", "message"]) {
    const v = b?.[key];
    if (typeof v === "string" && v.trim()) return v;
  }
  return fallback;
}

/** Turns an expected backend refusal into a sentence for the screen. Programming errors are rethrown. */
export function actionError(e: unknown, messages: Record<string, string>, fallback: string): ActionFailure {
  if (!(e instanceof BackendError)) throw e;
  const code = problemCode(e);
  const error = (code && messages[code]) || messages[String(e.status)] || problemMessage(e, fallback);
  return code ? { ok: false, error, code } : { ok: false, error };
}
```

Append to `src/lib/types.ts`, and replace its `AuthResponse`, `ConnectionResponse` and `WalletResponse` with the versions below:

```ts
export interface AuthResponse {
  token: string;
  refreshToken?: string | null;
}

export type NotifyChannel = "WEB" | "EMAIL" | "SLACK";

export interface ConnectionResponse {
  id: string;
  agentId: string;
  grantedScopes: string[];
  spendCap: number;
  status: ConnectionStatus;
  monthlyLimit?: number | null;
  notifyChannels?: NotifyChannel[];
  approverUserIds?: string[];
  firstRunId?: string | null;
}

export interface WalletResponse {
  balance: number;
  cap: number | null;
  reserved: number;
}

// WS13 identity
export interface Membership {
  orgId: string;
  orgName: string;
  orgType: OrgType;
  role: UserRole;
  title: string | null;
}
export interface MeResponse {
  userId: string;
  email: string;
  fullName: string | null;
  emailVerified: boolean;
  activeOrgId: string;
  memberships: Membership[];
}
export interface Member {
  userId: string;
  fullName: string | null;
  email: string;
  role: UserRole;
  title: string | null;
}
export interface InvitationView {
  id: string;
  email: string;
  role: UserRole;
  title: string | null;
  expiresAt: string;
}
export interface InvitationPreview {
  orgName: string;
  orgType: OrgType;
  email: string;
  role: UserRole;
  invitedBy: string | null;
}

// WS15 workspace
export interface WorkspaceHome {
  needsYou: { runId: string; agentName: string; connectionId: string; waitingSince: string | null; expiresAt: string | null; summary: string | null; canDecide: boolean }[];
  workingNow: { runId: string; agentName: string; status: RunStatus; startedAt: string | null; charged: number; spendCap: number }[];
  agents: {
    connectionId: string; agentId: string; agentName: string; publisher: string; version: string;
    reads: number; writes: number; writesNeedApproval: boolean; runsThisMonth: number; spendThisMonth: number;
  }[];
  spend: { monthToDate: number; monthlyLimit: number | null };
  wallet: { balance: number; held: number };
  exposure: { resourcesRead: number; writeScopes: number; outsideHosts: string[] };
  recent: ActivityItem[];
}
export interface ActivityItem {
  kind: string;
  actorName: string | null;
  payload: Record<string, unknown>;
  at: string;
}
export interface SearchResults {
  agents: { agentId: string; name: string; category: string }[];
  runs: { runId: string; agentName: string; status: RunStatus; startedAt: string | null }[];
}

// WS14 notifications
export type NotificationCategory =
  | "APPROVAL_NEEDED" | "MORE_ACCESS_REQUESTED" | "RUN_STOPPED_EARLY" | "RUN_FINISHED" | "SPEND_LIMITS" | "WEEKLY_SUMMARY";
export interface NotificationItem {
  id: string;
  category: NotificationCategory;
  subjectType: string | null;
  subjectId: string | null;
  payload: Record<string, unknown>;
  read: boolean;
  createdAt: string;
}
export interface NotificationPage {
  unreadCount: number;
  items: NotificationItem[];
}
export interface PreferenceRow {
  category: NotificationCategory;
  web: boolean;
  email: boolean;
  slack: boolean;
  webLocked: boolean;
}
export interface QuietHoursView {
  enabled: boolean;
  start: string;
  end: string;
  timezone: string;
}
export interface ApprovalCard {
  runId: string;
  agentName: string;
  summary: string | null;
  state: "OPEN" | "APPROVED" | "REJECTED" | "CLOSED";
  expiresAt: string;
  charged: number;
  spendCap: number;
  requiresLogin: boolean;
}

// WS16 disputes
export type DisputeStatus = "OPEN" | "AWAITING_DECISION" | "REFUNDED" | "NOT_REFUNDED" | "WITHDRAWN";
export type DisputeReason = "WRONG_OUTPUT" | "OVERCHARGED" | "DID_NOT_RUN" | "OTHER";
export interface DisputeView {
  id: string;
  runId: string;
  agentId: string;
  agentName: string | null;
  buyerOrgName: string | null;
  status: DisputeStatus;
  reason: DisputeReason;
  details: string;
  amountDisputed: number;
  amountHeld: number;
  amountRefunded: number | null;
  publicSummary: string | null;
  respondBy: string;
  decideBy: string;
  createdAt: string;
  decidedAt: string | null;
  messages: { side: string; authorName: string | null; body: string; at: string }[];
}
```

- [ ] **Step 4: Session, routing and roles**

Replace `src/lib/session.ts`:

```ts
import { cookies } from "next/headers";
import type { UserRole } from "./types";

export const SESSION_COOKIE = "karya_session";
export const REFRESH_COOKIE = "karya_refresh";
export const REFRESH_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

export interface SessionClaims {
  userId: string;
  orgId: string;
  role: UserRole;
}

function payload(token: string): { sub?: string; orgId?: string; role?: UserRole; exp?: number } | null {
  try {
    const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(base64));
  } catch {
    return null;
  }
}

export function tokenExpiry(token: string): number | null {
  const p = payload(token);
  return typeof p?.exp === "number" ? p.exp : null;
}

export function decodeSessionToken(token: string): SessionClaims | null {
  const json = payload(token);
  if (!json?.sub || !json.orgId || !json.role) return null;
  if (json.exp && Date.now() / 1000 > json.exp) return null;
  return { userId: json.sub, orgId: json.orgId, role: json.role };
}

export async function getSession(): Promise<SessionClaims | null> {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  return token ? decodeSessionToken(token) : null;
}

export function roleHome(role: UserRole): string {
  if (role === "DEVELOPER") return "/dashboard";
  if (role === "ADMIN") return "/review";
  return "/home";
}

export const cookieBase = {
  httpOnly: true,
  sameSite: "lax" as const,
  path: "/",
  secure: process.env.NODE_ENV === "production",
};
```

`src/lib/sessionCookies.ts`:

```ts
import { cookies } from "next/headers";
import { cookieBase, REFRESH_COOKIE, REFRESH_MAX_AGE_SECONDS, SESSION_COOKIE } from "./session";
import type { AuthResponse } from "./types";

/** Server actions only. The access token lives for the browser session; the refresh token for 30 days. */
export async function storeSession(auth: AuthResponse): Promise<void> {
  const jar = await cookies();
  jar.set(SESSION_COOKIE, auth.token, cookieBase);
  if (auth.refreshToken) {
    jar.set(REFRESH_COOKIE, auth.refreshToken, { ...cookieBase, maxAge: REFRESH_MAX_AGE_SECONDS });
  }
}

export async function clearSession(): Promise<void> {
  const jar = await cookies();
  jar.delete(SESSION_COOKIE);
  jar.delete(REFRESH_COOKIE);
}
```

`src/lib/sessionRefresh.ts`:

```ts
import { decodeSessionToken, tokenExpiry } from "./session";
import type { AuthResponse } from "./types";

export const REFRESH_AHEAD_SECONDS = 120;

export type SessionPlan = "continue" | "refresh" | "signin";

export function planSession(input: { accessToken?: string; refreshToken?: string; nowSeconds: number }): SessionPlan {
  const claims = input.accessToken ? decodeSessionToken(input.accessToken) : null;
  const exp = input.accessToken ? tokenExpiry(input.accessToken) : null;
  const fresh = claims !== null && (exp === null || exp - input.nowSeconds > REFRESH_AHEAD_SECONDS);
  if (fresh) return "continue";
  if (input.refreshToken) return "refresh";
  return claims ? "continue" : "signin";
}

export async function refreshSession(backendUrl: string, refreshToken: string): Promise<AuthResponse | null> {
  try {
    const res = await fetch(`${backendUrl}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    });
    if (!res.ok) return null;
    return (await res.json()) as AuthResponse;
  } catch {
    return null;
  }
}
```

`sessionRefresh.ts` imports `session.ts`, and through it `next/headers`. The existing middleware already does the same (for `decodeSessionToken`), and the import is safe as long as `cookies()` isn't called.

Replace `src/lib/routing.ts`:

```ts
import type { SessionClaims } from "./session";
import { roleHome } from "./session";
import type { UserRole } from "./types";

const PUBLIC_PREFIXES = ["/login", "/signup", "/forgot-password", "/reset-password", "/verify-email", "/invite", "/approve"];
const BUYER: UserRole[] = ["BUYER_ADMIN", "BUYER_APPROVER"];

const ROLE_PREFIXES: [string, UserRole[]][] = [
  ["/home", BUYER],
  ["/store", BUYER],
  ["/catalog", BUYER],
  ["/agents", BUYER],
  ["/connect", BUYER],
  ["/runs", BUYER],
  ["/billing", ["BUYER_ADMIN"]],
  ["/dashboard", ["DEVELOPER"]],
  ["/review", ["ADMIN"]],
];

export function isPublicPath(pathname: string): boolean {
  return PUBLIC_PREFIXES.some((p) => pathname === p || pathname.startsWith(p + "/"));
}

export function safeNextPath(next: string | null | undefined): string | null {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return null;
  return next;
}

export function resolveRedirect(pathname: string, claims: SessionClaims | null): string | null {
  if (claims && (pathname === "/login" || pathname === "/signup")) return roleHome(claims.role);
  if (isPublicPath(pathname)) return null;
  if (!claims) return `/login?next=${encodeURIComponent(pathname)}`;
  const match = ROLE_PREFIXES.find(([prefix]) => pathname === prefix || pathname.startsWith(prefix + "/"));
  if (match && !match[1].includes(claims.role)) return roleHome(claims.role);
  return null;
}
```

`src/lib/roles.ts`:

```ts
import type { OrgType, UserRole } from "./types";

const LABELS: Record<UserRole, string> = {
  BUYER_ADMIN: "Admin",
  BUYER_APPROVER: "Approver",
  DEVELOPER: "Developer",
  ADMIN: "Karya admin",
};

export function roleLabel(role: UserRole): string {
  return LABELS[role];
}

export function assignableRoles(orgType: OrgType): UserRole[] {
  return orgType === "BUYER" ? ["BUYER_ADMIN", "BUYER_APPROVER"] : ["DEVELOPER"];
}

export function canManageMembers(role: UserRole): boolean {
  return role === "BUYER_ADMIN" || role === "DEVELOPER";
}

export function isBuyerRole(role: UserRole): boolean {
  return role === "BUYER_ADMIN" || role === "BUYER_APPROVER";
}
```

- [ ] **Step 5: Middleware, client fetch, logout**

Replace `src/middleware.ts`:

```ts
import { NextRequest, NextResponse } from "next/server";
import { cookieBase, decodeSessionToken, REFRESH_COOKIE, REFRESH_MAX_AGE_SECONDS, SESSION_COOKIE } from "@/lib/session";
import { isPublicPath, resolveRedirect } from "@/lib/routing";
import { planSession, refreshSession } from "@/lib/sessionRefresh";
import type { AuthResponse } from "@/lib/types";

function isPrefetch(request: NextRequest): boolean {
  return request.headers.has("next-router-prefetch") || (request.headers.get("purpose") ?? "").includes("prefetch");
}

export async function middleware(request: NextRequest) {
  const path = request.nextUrl.pathname;
  const access = request.cookies.get(SESSION_COOKIE)?.value;
  const refresh = request.cookies.get(REFRESH_COOKIE)?.value;

  let token = access;
  let refreshed: AuthResponse | null = null;
  let refreshFailed = false;
  const plan = planSession({ accessToken: access, refreshToken: refresh, nowSeconds: Date.now() / 1000 });
  // Refresh on real navigations only. Parallel refreshes with one token look like theft to the backend.
  if (plan === "refresh" && !isPrefetch(request) && refresh) {
    refreshed = await refreshSession(process.env.BACKEND_URL ?? "", refresh);
    if (refreshed) {
      token = refreshed.token;
      request.cookies.set(SESSION_COOKIE, refreshed.token);   // visible to server components in this request
    } else {
      refreshFailed = true;
    }
  }

  const claims = token ? decodeSessionToken(token) : null;
  const redirectTo = isPublicPath(path) && !claims ? null : resolveRedirect(path, claims);
  const response = redirectTo
    ? NextResponse.redirect(new URL(redirectTo, request.url))
    : NextResponse.next({ request: { headers: request.headers } });

  if (refreshed) {
    response.cookies.set(SESSION_COOKIE, refreshed.token, cookieBase);
    if (refreshed.refreshToken) {
      response.cookies.set(REFRESH_COOKIE, refreshed.refreshToken, { ...cookieBase, maxAge: REFRESH_MAX_AGE_SECONDS });
    }
  } else if (refreshFailed) {
    response.cookies.delete(REFRESH_COOKIE);
    if (!claims) response.cookies.delete(SESSION_COOKIE);
  }
  return response;
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
```

`src/lib/clientFetch.ts`:

```ts
/**
 * Browser-side JSON fetch for polling our own /api proxy routes. A 401 means the access token expired
 * mid-page: reloading lets the middleware refresh the session (or send the person to sign in).
 */
export async function clientFetch<T>(url: string): Promise<T> {
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (res.status === 401) {
    window.location.reload();
    throw new Error("Session expired");
  }
  if (!res.ok) throw new Error(`Request failed: ${res.status}`);
  return (await res.json()) as T;
}
```

Replace `src/lib/authActions.ts`:

```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { REFRESH_COOKIE } from "./session";
import { clearSession } from "./sessionCookies";

export async function logoutAction() {
  const refreshToken = (await cookies()).get(REFRESH_COOKIE)?.value;
  if (refreshToken) {
    await fetch(`${process.env.BACKEND_URL}/api/v1/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    }).catch(() => undefined);
  }
  await clearSession();
  redirect("/login");
}
```

Change `useRunPolling.ts` to use `clientFetch` (import `{ clientFetch } from "@/lib/clientFetch"`), replacing its inline `fetch`:

```ts
    queryFn: () => clientFetch<RunDetailResponse>(`/api/runs/${runId}`),
```

- [ ] **Step 6: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS. Any legacy test that imported `BackendError` from `@/lib/backend` still works through the re-export.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): problem-code aware backend calls, new contracts, and refresh-token sessions"
```

---

### Task 3: The app shell

**Files:**
- Create: `src/components/shell/nav.ts`, `RailNav.tsx`, `TopBar.tsx`, `WorkspaceSwitcher.tsx`, `CommandBar.tsx`, `NotificationBell.tsx`, `UserMenu.tsx`, `AppShell.tsx`, `shellActions.ts`
- Create: `src/app/api/proxy.ts`, `src/app/api/search/route.ts`, `src/app/api/notifications/unread-count/route.ts`, `src/app/(account)/layout.tsx`
- Modify: `src/app/(buyer)/layout.tsx`, `src/app/(developer)/layout.tsx`, `src/app/(admin)/layout.tsx`, `src/app/error.tsx`
- Test: create `src/components/shell/nav.test.ts`, `RailNav.test.tsx`, `CommandBar.test.tsx`, `WorkspaceSwitcher.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/me`, `POST /api/v1/auth/switch-org`, `POST /api/v1/me/orgs`, `GET /api/v1/search`, `GET /api/v1/notifications/unread-count`, `GET /api/v1/runs/mine?status=AWAITING_APPROVAL` (via the existing `/api/runs/mine` proxy); `storeSession`, `clientFetch`, `roleHome` (Task 2); UI primitives (Task 1).
- Produces:
  - `interface NavItem { href: string; label: string; icon: IconName; badge?: "approvals" }`, `navFor(role): NavItem[]`, `activeHref(items, pathname): string | null`, `workspaceKind(orgType, role): string`.
  - `AppShell({ children })`: a server component used by every signed-in layout.
  - `switchOrgAction(orgId)`, `addOrgAction(side, orgName): ActionResult`.
  - `proxyGet(path)` for `/api/*` routes.
  - FE-2, FE-3 and FE-4 extend `navFor` with their screens.

- [ ] **Step 1: Write the failing tests**

`src/components/shell/nav.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { activeHref, navFor, workspaceKind } from "./nav";

describe("navigation", () => {
  it("gives buyer admins home, store, runs and billing", () => {
    expect(navFor("BUYER_ADMIN").map((i) => i.href)).toEqual(["/home", "/catalog", "/runs", "/billing"]);
  });
  it("hides billing from approvers", () => {
    expect(navFor("BUYER_APPROVER").map((i) => i.href)).toEqual(["/home", "/catalog", "/runs"]);
  });
  it("counts approvals only on the runs item", () => {
    expect(navFor("BUYER_ADMIN").filter((i) => i.badge === "approvals").map((i) => i.href)).toEqual(["/runs"]);
  });
  it("marks the longest matching item active", () => {
    const items = navFor("DEVELOPER");
    expect(activeHref(items, "/dashboard/new")).toBe("/dashboard/new");
    expect(activeHref(items, "/dashboard/agents/a1")).toBe("/dashboard");
    expect(activeHref(items, "/settings/team")).toBeNull();
  });
  it("describes the workspace", () => {
    expect(workspaceKind("BUYER", "BUYER_ADMIN")).toBe("Finance workspace");
    expect(workspaceKind("DEVELOPER", "DEVELOPER")).toBe("Developer workspace");
    expect(workspaceKind("DEVELOPER", "ADMIN")).toBe("Karya trust and review");
  });
});
```

`src/components/shell/RailNav.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RailNav } from "./RailNav";
import { navFor } from "./nav";

vi.mock("next/navigation", () => ({ usePathname: () => "/runs/r1" }));

function renderRail() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RailNav items={navFor("BUYER_ADMIN")} />
    </QueryClientProvider>,
  );
}

describe("RailNav", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ id: "r1" }, { id: "r2" }] });
  });

  it("labels every icon and marks the current section", () => {
    renderRail();
    expect(screen.getByRole("link", { name: "Workspace home" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /^Runs/ })).toHaveAttribute("aria-current", "page");
  });

  it("shows how many runs need approval", async () => {
    renderRail();
    expect(await screen.findByRole("link", { name: "Runs, 2 need approval" })).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });
});
```

`src/components/shell/CommandBar.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CommandBar } from "./CommandBar";

describe("CommandBar", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        agents: [{ agentId: "a1", name: "Bank Reconciliation", category: "reconciliation" }],
        runs: [{ runId: "0f8a2c4e-aaaa", agentName: "Bank Reconciliation", status: "COMPLETED", startedAt: "2026-09-23T04:11:02Z" }],
      }),
    });
  });

  it("opens with Cmd+K and finds agents and runs", async () => {
    render(<CommandBar />);
    await userEvent.keyboard("{Meta>}k{/Meta}");
    await userEvent.type(screen.getByRole("searchbox", { name: "Search Karya" }), "recon");

    expect(await screen.findByRole("link", { name: /Bank Reconciliation.*reconciliation/ })).toHaveAttribute("href", "/store/a1");
    expect(screen.getByRole("link", { name: /0f8a2c4e/ })).toHaveAttribute("href", "/runs/0f8a2c4e-aaaa");
    expect(fetch).toHaveBeenCalledWith("/api/search?q=recon", expect.anything());
  });

  it("does not search for a single character", async () => {
    render(<CommandBar />);
    await userEvent.click(screen.getByRole("button", { name: /Find an agent or open a run/ }));
    await userEvent.type(screen.getByRole("searchbox", { name: "Search Karya" }), "r");
    await new Promise((r) => setTimeout(r, 300));
    expect(fetch).not.toHaveBeenCalled();
  });

  it("closes with Escape", async () => {
    render(<CommandBar />);
    await userEvent.keyboard("{Control>}k{/Control}");
    expect(screen.getByRole("dialog", { name: "Search" })).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Search" })).not.toBeInTheDocument();
  });
});
```

`src/components/shell/WorkspaceSwitcher.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";
import type { Membership } from "@/lib/types";

vi.mock("./shellActions", () => ({
  switchOrgAction: vi.fn().mockResolvedValue(undefined),
  addOrgAction: vi.fn().mockResolvedValue({ ok: true }),
}));

const memberships: Membership[] = [
  { orgId: "o1", orgName: "Meridian Traders", orgType: "BUYER", role: "BUYER_ADMIN", title: "Finance lead" },
  { orgId: "o2", orgName: "Priya's studio", orgType: "DEVELOPER", role: "DEVELOPER", title: null },
];

describe("WorkspaceSwitcher", () => {
  it("shows the active workspace and switches to another", async () => {
    const { switchOrgAction } = await import("./shellActions");
    render(<WorkspaceSwitcher memberships={memberships} activeOrgId="o1" />);

    const trigger = screen.getByRole("button", { name: /Meridian Traders/ });
    expect(trigger).toHaveTextContent("Finance workspace");
    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole("menuitem", { name: /Priya's studio/ }));

    expect(switchOrgAction).toHaveBeenCalledWith("o2");
  });

  it("offers to create a workspace of the other kind", async () => {
    const { addOrgAction } = await import("./shellActions");
    render(<WorkspaceSwitcher memberships={[memberships[0]]} activeOrgId="o1" />);

    await userEvent.click(screen.getByRole("button", { name: /Meridian Traders/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Add a developer workspace" }));
    await userEvent.type(screen.getByLabelText("Name"), "Priya's studio");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(addOrgAction).toHaveBeenCalledWith("DEVELOPER", "Priya's studio");
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/components/shell`
Expected: FAIL (modules not found).

- [ ] **Step 3: Navigation model**

`src/components/shell/nav.ts`:

```ts
import type { IconName } from "@/components/ui/Icon";
import type { OrgType, UserRole } from "@/lib/types";

export interface NavItem {
  href: string;
  label: string;
  icon: IconName;
  badge?: "approvals";
}

const HOME: NavItem = { href: "/home", label: "Workspace home", icon: "home" };
const STORE: NavItem = { href: "/catalog", label: "Agent store", icon: "store" };
const RUNS: NavItem = { href: "/runs", label: "Runs", icon: "runs", badge: "approvals" };
const BILLING: NavItem = { href: "/billing", label: "Billing", icon: "billing" };

export function navFor(role: UserRole): NavItem[] {
  switch (role) {
    case "BUYER_ADMIN":
      return [HOME, STORE, RUNS, BILLING];
    case "BUYER_APPROVER":
      return [HOME, STORE, RUNS];
    case "DEVELOPER":
      return [
        { href: "/dashboard", label: "Your agents", icon: "code" },
        { href: "/dashboard/new", label: "New submission", icon: "plus" },
      ];
    case "ADMIN":
      return [{ href: "/review", label: "Review queue", icon: "gate" }];
  }
}

/** The item whose href is the longest prefix of the path, so /dashboard/new doesn't also light up /dashboard. */
export function activeHref(items: NavItem[], pathname: string): string | null {
  const matches = items.filter((i) => pathname === i.href || pathname.startsWith(i.href + "/"));
  return matches.sort((a, b) => b.href.length - a.href.length)[0]?.href ?? null;
}

export function workspaceKind(orgType: OrgType, role: UserRole): string {
  if (role === "ADMIN") return "Karya trust and review";
  return orgType === "BUYER" ? "Finance workspace" : "Developer workspace";
}
```

- [ ] **Step 4: Shell components**

`src/components/shell/RailNav.tsx`:

```tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Icon } from "@/components/ui/Icon";
import { Logo } from "@/components/ui/Logo";
import { clientFetch } from "@/lib/clientFetch";
import type { RunSummaryResponse } from "@/lib/types";
import { activeHref, type NavItem } from "./nav";

export function RailNav({ items }: { items: NavItem[] }) {
  const pathname = usePathname();
  const active = activeHref(items, pathname);
  const wantsApprovals = items.some((i) => i.badge === "approvals");
  const { data } = useQuery({
    queryKey: ["runs", "awaiting-approval"],
    enabled: wantsApprovals,
    refetchInterval: 15000,
    queryFn: () => clientFetch<RunSummaryResponse[]>("/api/runs/mine?status=AWAITING_APPROVAL"),
  });
  const approvals = data?.length ?? 0;

  return (
    <nav aria-label="Main" className="fixed inset-y-0 left-0 w-[72px] z-30 flex flex-col items-center gap-1.5 py-[18px] bg-white/90 border-r border-line">
      <Link href="/" aria-label="Karya" className="mb-5">
        <Logo />
      </Link>
      {items.map((item) => {
        const count = item.badge === "approvals" ? approvals : 0;
        const isActive = item.href === active;
        return (
          <Link
            key={item.href}
            href={item.href}
            title={item.label}
            aria-label={count > 0 ? `${item.label}, ${count} need${count === 1 ? "s" : ""} approval` : item.label}
            aria-current={isActive ? "page" : undefined}
            className={`relative w-11 h-11 rounded-xl grid place-items-center ${
              isActive ? "bg-sky text-blue" : "text-ink3 hover:bg-sky hover:text-blue"
            }`}
          >
            <Icon name={item.icon} />
            {count > 0 && (
              <span aria-hidden className="absolute top-[5px] right-[5px] min-w-4 h-4 px-1 rounded-full bg-blue text-white text-[10px] font-semibold grid place-items-center">
                {count}
              </span>
            )}
          </Link>
        );
      })}
    </nav>
  );
}
```

`src/components/shell/CommandBar.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Icon } from "@/components/ui/Icon";
import { Pill } from "@/components/ui/Pill";
import { clientFetch } from "@/lib/clientFetch";
import { dayLabel, shortId } from "@/lib/format";
import { runStatusPill } from "@/lib/runStatus";
import type { SearchResults } from "@/lib/types";

export function CommandBar() {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [results, setResults] = useState<SearchResults | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen(true);
      } else if (e.key === "Escape") {
        setOpen(false);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  useEffect(() => {
    const query = q.trim();
    if (query.length < 2) {
      setResults(null);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        setResults(await clientFetch<SearchResults>(`/api/search?q=${encodeURIComponent(query)}`));
      } catch {
        setResults({ agents: [], runs: [] });
      }
    }, 200);
    return () => clearTimeout(timer);
  }, [q]);

  const empty = results && results.agents.length === 0 && results.runs.length === 0;

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="flex-1 max-w-[520px] mx-auto h-[38px] flex items-center gap-2.5 px-3 rounded-[10px] border border-line bg-surface text-ink3 text-[13px]"
      >
        <Icon name="search" className="w-4 h-4" />
        Find an agent or open a run
        <kbd className="ml-auto font-mono text-[11px] border border-line rounded-md px-1.5 bg-canvas">⌘K</kbd>
      </button>
      {open && (
        <div className="fixed inset-0 z-50 bg-ink/20 flex items-start justify-center pt-24" onClick={() => setOpen(false)}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Search"
            className="w-full max-w-[560px] bg-surface border border-line rounded-2xl shadow-block overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2.5 px-4 h-12 border-b border-line2">
              <Icon name="search" className="w-4 h-4 text-ink3" />
              <input
                ref={inputRef}
                type="search"
                aria-label="Search Karya"
                placeholder="Search agents and runs"
                value={q}
                onChange={(e) => setQ(e.target.value)}
                className="flex-1 bg-transparent outline-none text-sm"
              />
            </div>
            <div className="max-h-[360px] overflow-y-auto p-2">
              {!results && <p className="px-3 py-4 text-[13px] text-ink3">Type at least two letters.</p>}
              {empty && <p className="px-3 py-4 text-[13px] text-ink3">Nothing matches “{q.trim()}”.</p>}
              {results && results.agents.length > 0 && (
                <Group title="Agents">
                  {results.agents.map((a) => (
                    <Link key={a.agentId} href={`/store/${a.agentId}`} onClick={() => setOpen(false)} className="flex justify-between px-3 py-2 rounded-lg hover:bg-sky">
                      <span className="font-medium">{a.name}</span>
                      <span className="text-xs text-ink3">{a.category}</span>
                    </Link>
                  ))}
                </Group>
              )}
              {results && results.runs.length > 0 && (
                <Group title="Your runs">
                  {results.runs.map((r) => {
                    const pill = runStatusPill(r.status);
                    return (
                      <Link key={r.runId} href={`/runs/${r.runId}`} onClick={() => setOpen(false)} className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-sky">
                        <span className="font-mono text-xs text-ink3">{shortId(r.runId)}</span>
                        <span className="flex-1">{r.agentName}</span>
                        {r.startedAt && <span className="text-xs text-ink3">{dayLabel(r.startedAt)}</span>}
                        <Pill tone={pill.tone}>{pill.label}</Pill>
                      </Link>
                    );
                  })}
                </Group>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-2">
      <div className="px-3 pt-2 pb-1 text-[11px] uppercase tracking-wider text-ink3">{title}</div>
      {children}
    </div>
  );
}
```

The command bar needs `runStatusPill`. Create `src/lib/runStatus.ts` now; Task 6 adds its tests and filter helpers:

```ts
import type { Tone } from "@/components/ui/Pill";
import type { RunStatus } from "./types";

export type RunGroup = "needs" | "running" | "done" | "stopped";

export function runGroup(status: RunStatus): RunGroup {
  switch (status) {
    case "AWAITING_APPROVAL":
      return "needs";
    case "PENDING":
    case "RUNNING":
      return "running";
    case "COMPLETED":
      return "done";
    default:
      return "stopped";
  }
}

export function runStatusPill(status: RunStatus): { tone: Tone; label: string } {
  switch (status) {
    case "PENDING":
      return { tone: "b", label: "Starting" };
    case "RUNNING":
      return { tone: "b", label: "Running" };
    case "AWAITING_APPROVAL":
      return { tone: "wa", label: "Needs you" };
    case "COMPLETED":
      return { tone: "ok", label: "Completed" };
    case "FAILED":
      return { tone: "bad", label: "Stopped" };
    case "CANCELLED":
      return { tone: "n", label: "Rejected" };
    case "TIMED_OUT":
      return { tone: "bad", label: "Ran out of time" };
    case "EXPIRED":
      return { tone: "n", label: "No answer in time" };
  }
}
```

`src/components/shell/NotificationBell.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Icon } from "@/components/ui/Icon";
import { clientFetch } from "@/lib/clientFetch";

export function NotificationBell() {
  const { data } = useQuery({
    queryKey: ["notifications", "unread-count"],
    refetchInterval: 30000,
    queryFn: () => clientFetch<{ count: number }>("/api/notifications/unread-count"),
  });
  const count = data?.count ?? 0;
  return (
    <Link
      href="/notifications"
      aria-label={count > 0 ? `Notifications, ${count} unread` : "Notifications"}
      className="relative w-11 h-11 rounded-xl grid place-items-center text-ink3 hover:bg-sky hover:text-blue"
    >
      <Icon name="bell" />
      {count > 0 && (
        <span aria-hidden className="absolute top-[5px] right-[5px] min-w-4 h-4 px-1 rounded-full bg-blue text-white text-[10px] font-semibold grid place-items-center">
          {count > 99 ? "99+" : count}
        </span>
      )}
    </Link>
  );
}
```

`src/components/shell/UserMenu.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Icon } from "@/components/ui/Icon";
import { logoutAction } from "@/lib/authActions";

export function UserMenu({ name, email }: { name: string; email: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <button type="button" aria-haspopup="menu" aria-expanded={open} aria-label={`Account, ${name}`} onClick={() => setOpen((o) => !o)}>
        <Avatar name={name} />
      </button>
      {open && (
        <div role="menu" className="absolute right-0 top-11 w-64 bg-surface border border-line rounded-xl shadow-block p-2 z-40">
          <div className="px-3 py-2 border-b border-line2 mb-1">
            <div className="text-sm font-semibold">{name}</div>
            <div className="text-xs text-ink3 truncate">{email}</div>
          </div>
          <Link role="menuitem" href="/settings/team" className="flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-sky text-[13px]">
            <Icon name="team" className="w-4 h-4" /> Team
          </Link>
          <Link role="menuitem" href="/notifications#where" className="flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-sky text-[13px]">
            <Icon name="bell" className="w-4 h-4" /> Where we reach you
          </Link>
          <form action={logoutAction}>
            <button role="menuitem" type="submit" className="w-full flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-sky text-[13px]">
              <Icon name="logout" className="w-4 h-4" /> Sign out
            </button>
          </form>
        </div>
      )}
    </div>
  );
}
```

`src/components/shell/shellActions.ts`:

```ts
"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import { decodeSessionToken, roleHome } from "@/lib/session";
import { storeSession } from "@/lib/sessionCookies";
import type { AuthResponse } from "@/lib/types";

export async function switchOrgAction(orgId: string) {
  const auth = await backend.post<AuthResponse>("/api/v1/auth/switch-org", { orgId });
  await storeSession(auth);
  const claims = decodeSessionToken(auth.token);
  redirect(claims ? roleHome(claims.role) : "/");
}

export async function addOrgAction(side: "BUYER" | "DEVELOPER", orgName: string): Promise<ActionResult> {
  let auth: AuthResponse;
  try {
    auth = await backend.post<AuthResponse>("/api/v1/me/orgs", { side, orgName });
  } catch (e) {
    return actionError(e, { "400": "Give the workspace a name." }, "Could not create the workspace.");
  }
  await storeSession(auth);
  const claims = decodeSessionToken(auth.token);
  redirect(claims ? roleHome(claims.role) : "/");
}
```

`src/components/shell/WorkspaceSwitcher.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import type { Membership } from "@/lib/types";
import { workspaceKind } from "./nav";
import { addOrgAction, switchOrgAction } from "./shellActions";

export function WorkspaceSwitcher({ memberships, activeOrgId }: { memberships: Membership[]; activeOrgId: string }) {
  const [open, setOpen] = useState(false);
  const [adding, setAdding] = useState<"BUYER" | "DEVELOPER" | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const active = memberships.find((m) => m.orgId === activeOrgId);
  const hasBuyer = memberships.some((m) => m.orgType === "BUYER");
  const hasDeveloper = memberships.some((m) => m.orgType === "DEVELOPER");

  async function create() {
    if (!adding) return;
    const result = await addOrgAction(adding, name.trim());
    if (result && !result.ok) setError(result.error);
  }

  return (
    <div className="relative min-w-[200px]">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-2 text-left"
      >
        <span className="flex flex-col leading-tight">
          <span className="text-sm font-semibold">{active?.orgName ?? "Workspace"}</span>
          <span className="text-xs text-ink3">{active ? workspaceKind(active.orgType, active.role) : ""}</span>
        </span>
        <Icon name="chevronDown" className="w-4 h-4 text-ink3" />
      </button>
      {open && (
        <div role="menu" className="absolute left-0 top-12 w-72 bg-surface border border-line rounded-xl shadow-block p-2 z-40">
          {memberships.map((m) => (
            <button
              key={m.orgId}
              role="menuitem"
              type="button"
              onClick={() => (m.orgId === activeOrgId ? setOpen(false) : switchOrgAction(m.orgId))}
              className={`w-full text-left px-3 py-2 rounded-lg hover:bg-sky ${m.orgId === activeOrgId ? "bg-sky" : ""}`}
            >
              <span className="block text-[13px] font-medium">{m.orgName}</span>
              <span className="block text-xs text-ink3">{workspaceKind(m.orgType, m.role)}</span>
            </button>
          ))}
          <div className="border-t border-line2 mt-1 pt-1">
            {!hasDeveloper && (
              <button role="menuitem" type="button" onClick={() => setAdding("DEVELOPER")} className="w-full text-left px-3 py-2 rounded-lg hover:bg-sky text-[13px] text-blue">
                Add a developer workspace
              </button>
            )}
            {!hasBuyer && (
              <button role="menuitem" type="button" onClick={() => setAdding("BUYER")} className="w-full text-left px-3 py-2 rounded-lg hover:bg-sky text-[13px] text-blue">
                Add a finance workspace
              </button>
            )}
          </div>
          {adding && (
            <form
              className="p-3 flex flex-col gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                void create();
              }}
            >
              <label className="text-xs text-ink2" htmlFor="new-workspace-name">Name</label>
              <input
                id="new-workspace-name"
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={adding === "BUYER" ? "Company" : "Your name or studio"}
                className="h-9 px-3 rounded-lg border border-line text-[13px]"
              />
              {error && <p role="alert" className="text-xs text-bad">{error}</p>}
              <Button type="submit" size="sm">Create</Button>
            </form>
          )}
        </div>
      )}
    </div>
  );
}
```

`src/components/shell/TopBar.tsx`:

```tsx
import type { Membership } from "@/lib/types";
import { CommandBar } from "./CommandBar";
import { NotificationBell } from "./NotificationBell";
import { UserMenu } from "./UserMenu";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";

export function TopBar({
  memberships,
  activeOrgId,
  userName,
  userEmail,
}: {
  memberships: Membership[];
  activeOrgId: string;
  userName: string;
  userEmail: string;
}) {
  return (
    <header className="fixed top-0 right-0 left-[72px] h-16 z-20 flex items-center gap-4 px-6 bg-white/80 backdrop-blur border-b border-line">
      <WorkspaceSwitcher memberships={memberships} activeOrgId={activeOrgId} />
      <CommandBar />
      <NotificationBell />
      <UserMenu name={userName} email={userEmail} />
    </header>
  );
}
```

`src/components/shell/AppShell.tsx`:

```tsx
import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { MeResponse } from "@/lib/types";
import { navFor } from "./nav";
import { RailNav } from "./RailNav";
import { TopBar } from "./TopBar";

export async function AppShell({ children }: { children: React.ReactNode }) {
  const session = await getSession();
  if (!session) redirect("/login");
  const me = await backend.get<MeResponse>("/api/v1/me");
  return (
    <div className="min-h-screen bg-canvas dot-grid">
      <RailNav items={navFor(session.role)} />
      <TopBar
        memberships={me.memberships}
        activeOrgId={session.orgId}
        userName={me.fullName ?? me.email}
        userEmail={me.email}
      />
      <main className="pl-[72px] pt-16">
        <div className="px-10 py-8 max-w-[1320px]">{children}</div>
      </main>
    </div>
  );
}
```

- [ ] **Step 5: Proxy routes and layouts**

`src/app/api/proxy.ts`:

```ts
import { NextResponse } from "next/server";
import { backend } from "@/lib/backend";
import { BackendError } from "@/lib/backendError";

export async function proxyGet(path: string): Promise<NextResponse> {
  try {
    return NextResponse.json(await backend.get<unknown>(path));
  } catch (e) {
    if (e instanceof BackendError) return NextResponse.json(e.body, { status: e.status });
    throw e;
  }
}
```

`src/app/api/search/route.ts`:

```ts
import { NextRequest } from "next/server";
import { proxyGet } from "../proxy";

export async function GET(request: NextRequest) {
  const q = request.nextUrl.searchParams.get("q") ?? "";
  return proxyGet(`/api/v1/search?q=${encodeURIComponent(q)}`);
}
```

`src/app/api/notifications/unread-count/route.ts`:

```ts
import { proxyGet } from "../../proxy";

export async function GET() {
  return proxyGet("/api/v1/notifications/unread-count");
}
```

Replace `src/app/(buyer)/layout.tsx`, `src/app/(developer)/layout.tsx` and `src/app/(admin)/layout.tsx` with the same content:

```tsx
import { AppShell } from "@/components/shell/AppShell";

export default function Layout({ children }: { children: React.ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
```

Create `src/app/(account)/layout.tsx` with that same content. It hosts `/notifications` and `/settings/*` for every role.

In `src/app/error.tsx`, replace the two action elements with the new primitives:

```tsx
import { Button, ButtonLink } from "@/components/ui/Button";
// …
        <div className="flex justify-center gap-3">
          <Button variant="secondary" onClick={() => reset()}>Try again</Button>
          <ButtonLink href="/login">Sign in again</ButtonLink>
        </div>
```

and change the heading class to `text-xl font-semibold mb-2`.

- [ ] **Step 6: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): app shell with icon rail, workspace switcher, Cmd+K search and notification bell"
```

---

### Task 4: Sign in, create account, and the emailed-link pages

**Files:**
- Create: `src/app/(auth)/layout.tsx`, `src/app/(auth)/AuthScreen.tsx`, `src/app/(auth)/GoogleButton.tsx`, `src/app/(auth)/actions.ts`
- Modify: `src/app/(auth)/login/page.tsx`, `src/app/(auth)/signup/page.tsx`
- Delete: `src/app/(auth)/login/actions.ts`, `src/app/(auth)/signup/actions.ts`
- Create: `src/app/(auth)/forgot-password/page.tsx`, `ForgotForm.tsx`; `src/app/(auth)/reset-password/[token]/page.tsx`, `ResetForm.tsx`; `src/app/(auth)/verify-email/[token]/page.tsx`, `VerifyEmail.tsx`; `src/app/(auth)/invite/[token]/page.tsx`, `AcceptInvite.tsx`
- Test: create `src/app/(auth)/AuthScreen.test.tsx`, `src/app/(auth)/reset-password/[token]/ResetForm.test.tsx`, `src/app/(auth)/invite/[token]/AcceptInvite.test.tsx`

**Interfaces:**
- Consumes: WS13 endpoints:
  - `POST /api/v1/auth/login {email, password, remember}`
  - `POST /api/v1/auth/signup {fullName, orgName, email, password, side}`
  - `POST /api/v1/auth/google {idToken, side?, orgName?, remember}`
  - `POST /api/v1/auth/password/forgot {email}`
  - `POST /api/v1/auth/password/reset {token, password}`
  - `POST /api/v1/auth/email/verify {token}`
  - `GET /api/v1/invitations/{token}`
  - `POST /api/v1/invitations/{token}/accept {fullName?, password?}`
- Also consumes `storeSession`, `safeNextPath`, `roleHome`, `roleLabel` (Task 2) and the UI primitives.
- Produces:
  - `loginAction(formData): Promise<FormState>`, `signupAction(formData): Promise<FormState>` with `FormState = { error: string | null }`. Both redirect on success. They take `FormData` directly, rather than working through `useFormState`: the React used by Vitest (18.3 stable) has no `useFormState`, so screens built on it can't be tested.
  - `googleSignInAction(input: {idToken, side?, orgName?, remember, next?}): Promise<{ status: "needsDetails" } | { status: "error"; message: string }>`. On success it redirects instead of returning.
  - `forgotPasswordAction(email): Promise<{ sent: true }>`, `resetPasswordAction(token, password): ActionResult`, `verifyEmailAction(token): ActionResult`, `acceptInviteAction(token, fullName?, password?): ActionResult` (redirects on success).
  - `AuthScreen({ initialMode, next?, googleClientId? })`.

- [ ] **Step 1: Write the failing tests**

`src/app/(auth)/AuthScreen.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthScreen } from "./AuthScreen";

vi.mock("./actions", () => ({
  loginAction: vi.fn().mockResolvedValue({ error: "That email and password don't match." }),
  signupAction: vi.fn().mockResolvedValue({ error: "An account with this email already exists. Sign in instead." }),
  googleSignInAction: vi.fn().mockResolvedValue({ status: "needsDetails" }),
}));

describe("AuthScreen", () => {
  it("starts on sign in with keep-me-signed-in and forgot-password", () => {
    render(<AuthScreen initialMode="signin" />);
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(screen.getByLabelText("Keep me signed in on this device")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Forgot password?" })).toHaveAttribute("href", "/forgot-password");
  });

  it("changes the form's words by the side you pick", async () => {
    render(<AuthScreen initialMode="signup" />);
    expect(screen.getByLabelText("Company")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create workspace" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: /I build agents/ }));
    expect(screen.getByLabelText("Your name or studio")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create developer account" })).toBeInTheDocument();
    expect(screen.getByText(/Build and test in the sandbox for free/)).toBeInTheDocument();
  });

  it("shows the backend's reason when signup is refused", async () => {
    render(<AuthScreen initialMode="signup" />);
    await userEvent.type(screen.getByLabelText("Full name"), "Priya Shah");
    await userEvent.type(screen.getByLabelText("Company"), "Meridian Traders");
    await userEvent.type(screen.getByLabelText("Work email"), "priya@meridian.example");
    await userEvent.type(screen.getByLabelText("Password"), "s3cret-password");
    await userEvent.click(screen.getByRole("button", { name: "Create workspace" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("already exists");
  });

  it("sends remember-me with the sign-in and shows a refusal", async () => {
    const { loginAction } = await import("./actions");
    render(<AuthScreen initialMode="signin" next="/runs/r1" />);
    await userEvent.type(screen.getByLabelText("Work email"), "priya@meridian.example");
    await userEvent.type(screen.getByLabelText("Password"), "wrong-password");
    await userEvent.click(screen.getByLabelText("Keep me signed in on this device"));
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));

    const sent = vi.mocked(loginAction).mock.calls[0][0] as FormData;
    expect(sent.get("remember")).toBe("on");
    expect(sent.get("next")).toBe("/runs/r1");
    expect(await screen.findByRole("alert")).toHaveTextContent("don't match");
  });

  it("switches between the two tabs", async () => {
    render(<AuthScreen initialMode="signin" />);
    await userEvent.click(screen.getByRole("tab", { name: "Create account" }));
    expect(screen.getByRole("heading", { name: "Create your account" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Create account" })).toHaveAttribute("aria-selected", "true");
  });

  it("hides Google when no client id is configured", () => {
    render(<AuthScreen initialMode="signin" />);
    expect(screen.queryByText("or with email")).not.toBeInTheDocument();
  });
});
```

`src/app/(auth)/reset-password/[token]/ResetForm.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ResetForm } from "./ResetForm";

vi.mock("../../actions", () => ({
  resetPasswordAction: vi.fn().mockResolvedValue({ ok: false, error: "This link has expired or was already used." }),
}));

describe("ResetForm", () => {
  it("refuses mismatched passwords before calling the backend", async () => {
    const { resetPasswordAction } = await import("../../actions");
    render(<ResetForm token="t1" />);
    await userEvent.type(screen.getByLabelText("New password"), "new-s3cret-pw");
    await userEvent.type(screen.getByLabelText("Type it again"), "different-pw");
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));

    expect(screen.getByRole("alert")).toHaveTextContent("The two passwords don't match.");
    expect(resetPasswordAction).not.toHaveBeenCalled();
  });

  it("explains an expired link and offers a new one", async () => {
    render(<ResetForm token="t1" />);
    await userEvent.type(screen.getByLabelText("New password"), "new-s3cret-pw");
    await userEvent.type(screen.getByLabelText("Type it again"), "new-s3cret-pw");
    await userEvent.click(screen.getByRole("button", { name: "Set new password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("expired");
    expect(screen.getByRole("link", { name: "Send a new link" })).toHaveAttribute("href", "/forgot-password");
  });
});
```

`src/app/(auth)/invite/[token]/AcceptInvite.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AcceptInvite } from "./AcceptInvite";
import type { InvitationPreview } from "@/lib/types";

vi.mock("../../actions", () => ({
  acceptInviteAction: vi.fn().mockResolvedValue({ ok: true }),
}));
vi.mock("@/lib/authActions", () => ({ logoutAction: vi.fn() }));

const preview: InvitationPreview = {
  orgName: "Meridian Traders",
  orgType: "BUYER",
  email: "arjun@meridian.example",
  role: "BUYER_APPROVER",
  invitedBy: "Priya Shah",
};

describe("AcceptInvite", () => {
  it("lets a new person create an account and join", async () => {
    const { acceptInviteAction } = await import("../../actions");
    render(<AcceptInvite token="t1" preview={preview} signedInEmail={null} />);

    expect(screen.getByText(/Priya Shah invited you to Meridian Traders as an Approver/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Full name"), "Arjun Mehta");
    await userEvent.type(screen.getByLabelText("Password"), "s3cret-password");
    await userEvent.click(screen.getByRole("button", { name: "Create account and join" }));

    expect(acceptInviteAction).toHaveBeenCalledWith("t1", "Arjun Mehta", "s3cret-password");
  });

  it("lets the invited person join with one click when signed in", async () => {
    const { acceptInviteAction } = await import("../../actions");
    render(<AcceptInvite token="t1" preview={preview} signedInEmail="arjun@meridian.example" />);
    await userEvent.click(screen.getByRole("button", { name: "Join Meridian Traders" }));
    expect(acceptInviteAction).toHaveBeenCalledWith("t1");
  });

  it("warns when signed in as someone else", () => {
    render(<AcceptInvite token="t1" preview={preview} signedInEmail="priya@meridian.example" />);
    expect(screen.getByText(/You're signed in as priya@meridian.example/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Join Meridian Traders" })).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- "src/app/(auth)"`
Expected: FAIL (modules not found).

- [ ] **Step 3: Server actions**

`src/app/(auth)/actions.ts`:

```ts
"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import { safeNextPath } from "@/lib/routing";
import { decodeSessionToken, roleHome } from "@/lib/session";
import { storeSession } from "@/lib/sessionCookies";
import type { AuthResponse } from "@/lib/types";

export interface FormState {
  error: string | null;
}

async function post(path: string, body: unknown): Promise<Response> {
  return fetch(`${process.env.BACKEND_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
}

async function finish(auth: AuthResponse, next: string | null, fallback?: string): Promise<never> {
  const claims = decodeSessionToken(auth.token);
  if (!claims) redirect("/login");
  await storeSession(auth);
  redirect(next ?? fallback ?? roleHome(claims.role));
}

export async function loginAction(formData: FormData): Promise<FormState> {
  const res = await post("/api/v1/auth/login", {
    email: String(formData.get("email") ?? ""),
    password: String(formData.get("password") ?? ""),
    remember: formData.get("remember") === "on",
  });
  if (res.status === 401) return { error: "That email and password don't match." };
  if (res.status === 403) return { error: "You're not part of any workspace yet. Ask your admin for an invitation." };
  if (!res.ok) return { error: "We couldn't sign you in. Try again in a moment." };
  return finish((await res.json()) as AuthResponse, safeNextPath(String(formData.get("next") ?? "")));
}

export async function signupAction(formData: FormData): Promise<FormState> {
  const side = formData.get("side") === "DEVELOPER" ? "DEVELOPER" : "BUYER";
  const res = await post("/api/v1/auth/signup", {
    fullName: String(formData.get("fullName") ?? ""),
    orgName: String(formData.get("orgName") ?? ""),
    email: String(formData.get("email") ?? ""),
    password: String(formData.get("password") ?? ""),
    side,
  });
  if (res.status === 409) return { error: "An account with this email already exists. Sign in instead." };
  if (res.status === 400) return { error: "Check your details. Every field is needed." };
  if (!res.ok) return { error: "We couldn't create your account. Try again in a moment." };
  // Buyers set up their team next; developers go straight to building.
  return finish((await res.json()) as AuthResponse, null, side === "BUYER" ? "/settings/team?welcome=1" : undefined);
}

export async function googleSignInAction(input: {
  idToken: string;
  side?: "BUYER" | "DEVELOPER";
  orgName?: string;
  remember: boolean;
  next?: string;
}): Promise<{ status: "needsDetails" } | { status: "error"; message: string }> {
  const res = await post("/api/v1/auth/google", {
    idToken: input.idToken,
    side: input.side,
    orgName: input.orgName,
    remember: input.remember,
  });
  if (res.status === 422) return { status: "needsDetails" };
  if (res.status === 409) {
    return {
      status: "error",
      message: "An account with this email already exists. Sign in with your password once and confirm your email, then Google will work.",
    };
  }
  if (res.status === 503) return { status: "error", message: "Google sign-in isn't set up here yet." };
  if (!res.ok) return { status: "error", message: "Google sign-in didn't work. Try again." };
  return finish((await res.json()) as AuthResponse, safeNextPath(input.next));
}

export async function forgotPasswordAction(email: string): Promise<{ sent: true }> {
  await post("/api/v1/auth/password/forgot", { email }).catch(() => undefined);
  return { sent: true };   // always the same answer, whether or not the account exists
}

export async function resetPasswordAction(token: string, password: string): Promise<ActionResult> {
  try {
    await backend.post("/api/v1/auth/password/reset", { token, password });
    return { ok: true };
  } catch (e) {
    return actionError(e, { "400": "This link has expired or was already used." }, "We couldn't change your password.");
  }
}

export async function verifyEmailAction(token: string): Promise<ActionResult> {
  try {
    await backend.post("/api/v1/auth/email/verify", { token });
    return { ok: true };
  } catch (e) {
    return actionError(e, { "400": "This link has expired or was already used." }, "We couldn't confirm your email.");
  }
}

export async function acceptInviteAction(token: string, fullName?: string, password?: string): Promise<ActionResult> {
  let auth: AuthResponse;
  try {
    auth = await backend.post<AuthResponse>(`/api/v1/invitations/${token}/accept`,
      fullName !== undefined ? { fullName, password } : {});
  } catch (e) {
    return actionError(e, {
      "409": "You already have a Karya account. Sign in, then open this invitation again.",
      "403": "This invitation was sent to a different email address.",
      "410": "This invitation has expired or was already used. Ask for a new one.",
      "400": "Enter your full name and a password.",
    }, "We couldn't accept the invitation.");
  }
  return finish(auth, null);
}
```

`finish` redirects, which throws `NEXT_REDIRECT`. It is only ever called outside `try` blocks, so that redirect is never caught.

- [ ] **Step 4: The sign-in screen**

`src/app/(auth)/layout.tsx`:

```tsx
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <div className="min-h-screen bg-canvas dot-grid">{children}</div>;
}
```

`src/app/(auth)/GoogleButton.tsx`:

```tsx
"use client";

import Script from "next/script";
import { useEffect, useRef, useState } from "react";

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize(options: { client_id: string; callback: (response: { credential: string }) => void }): void;
          renderButton(element: HTMLElement, options: Record<string, unknown>): void;
        };
      };
    };
  }
}

export function GoogleButton({ clientId, onCredential }: { clientId: string; onCredential: (idToken: string) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!loaded || !window.google || !ref.current) return;
    window.google.accounts.id.initialize({ client_id: clientId, callback: (r) => onCredential(r.credential) });
    window.google.accounts.id.renderButton(ref.current, { theme: "outline", size: "large", width: 360, text: "continue_with" });
  }, [loaded, clientId, onCredential]);

  return (
    <>
      <Script src="https://accounts.google.com/gsi/client" strategy="afterInteractive" onLoad={() => setLoaded(true)} />
      <div ref={ref} className="min-h-[44px]" />
    </>
  );
}
```

`src/app/(auth)/AuthScreen.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Logo } from "@/components/ui/Logo";
import { GoogleButton } from "./GoogleButton";
import { googleSignInAction, loginAction, signupAction, type FormState } from "./actions";


type Mode = "signin" | "signup";
type Side = "BUYER" | "DEVELOPER";

const STEPS = [
  ["Reads your statement", "412 lines, read only"],
  ["Matches to invoices", "142 matches found"],
  ["Drafts entries", "Held, not posted"],
  ["Asks you", "Approve or reject"],
  ["Posts to your ledger", "Only what you approved"],
];

function Submit({ pending, children }: { pending: boolean; children: React.ReactNode }) {
  return (
    <Button type="submit" disabled={pending} className="w-full h-11">
      {pending ? "One moment…" : children}
    </Button>
  );
}

/** Runs a server action with the form's data; the action redirects on success, so only failures come back. */
function useSubmit(action: (formData: FormData) => Promise<FormState>) {
  const [state, setState] = useState<FormState>({ error: null });
  const [pending, setPending] = useState(false);
  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setPending(true);
    const result = await action(new FormData(e.currentTarget));
    setPending(false);
    if (result) setState(result);
  }
  return { state, pending, onSubmit };
}

function Field({ label, name, type = "text", autoComplete, minLength, placeholder }: {
  label: string; name: string; type?: string; autoComplete?: string; minLength?: number; placeholder?: string;
}) {
  return (
    <label className="flex flex-col gap-1.5 text-[13px]">
      <span className="text-ink2">{label}</span>
      <input
        name={name}
        type={type}
        required
        minLength={minLength}
        autoComplete={autoComplete}
        placeholder={placeholder}
        className="h-10 px-3 rounded-[10px] border border-line bg-surface focus:outline focus:outline-2 focus:outline-blue"
      />
    </label>
  );
}

export function AuthScreen({ initialMode, next, googleClientId }: { initialMode: Mode; next?: string; googleClientId?: string }) {
  const [mode, setMode] = useState<Mode>(initialMode);
  const [side, setSide] = useState<Side>("BUYER");
  const login = useSubmit(loginAction);
  const signup = useSubmit(signupAction);
  const [googleToken, setGoogleToken] = useState<string | null>(null);
  const [googleError, setGoogleError] = useState<string | null>(null);
  const buyer = side === "BUYER";

  const onCredential = useCallback(async (idToken: string) => {
    setGoogleError(null);
    const result = await googleSignInAction({ idToken, remember: true, next });
    if (result?.status === "needsDetails") {
      setGoogleToken(idToken);
      setMode("signup");
    } else if (result?.status === "error") {
      setGoogleError(result.message);
    }
  }, [next]);

  async function finishGoogle(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!googleToken) return;
    const result = await googleSignInAction({
      idToken: googleToken,
      side,
      orgName: String(new FormData(e.currentTarget).get("orgName") ?? ""),
      remember: true,
      next,
    });
    if (result?.status === "error") setGoogleError(result.message);
  }

  return (
    <div className="min-h-screen grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_520px]">
      <section className="hidden lg:flex flex-col justify-between p-14">
        <div className="flex items-center gap-2 font-semibold"><Logo /> Karya</div>
        <div className="max-w-[520px]">
          <h1 className="text-[40px] leading-[1.1] font-semibold tracking-tight">Agents that do the finance work. You approve what matters.</h1>
          <p className="mt-4 text-ink2">Every agent is checked by Karya before you see it, works only on the files you plug in, and stops to ask before it writes anything.</p>
          <ol className="mt-8 flex flex-col gap-2">
            {STEPS.map(([title, meta], i) => (
              <li key={title} className={`flex items-center gap-3 bg-surface border rounded-xl px-4 py-3 ${i === 3 ? "border-wa-line shadow-wait" : "border-line"}`}>
                <span className="font-medium text-sm">{title}</span>
                <span className="ml-auto text-xs text-ink3">{meta}</span>
              </li>
            ))}
          </ol>
        </div>
        <p className="text-xs text-ink3">Made in India for Indian finance teams. Your data stays in India.</p>
      </section>

      <section className="flex items-center justify-center p-6">
        <div className="w-full max-w-[420px] bg-surface border border-line rounded-2xl p-8 shadow-block">
          <div role="tablist" aria-label="Sign in or create account" className="flex gap-1 bg-line2 p-[3px] rounded-[10px] mb-6">
            {(["signin", "signup"] as Mode[]).map((m) => (
              <button
                key={m}
                role="tab"
                type="button"
                aria-selected={mode === m}
                onClick={() => setMode(m)}
                className={`flex-1 h-[34px] rounded-lg text-[13px] ${mode === m ? "bg-surface font-semibold shadow-sm" : "text-ink2"}`}
              >
                {m === "signin" ? "Sign in" : "Create account"}
              </button>
            ))}
          </div>

          {googleClientId && !googleToken && (
            <div className="flex flex-col items-center gap-3 mb-4">
              <GoogleButton clientId={googleClientId} onCredential={onCredential} />
              <span className="text-xs text-ink3">or with email</span>
            </div>
          )}
          {googleError && <p role="alert" className="mb-4 text-[13px] text-bad">{googleError}</p>}

          {mode === "signin" ? (
            <form onSubmit={login.onSubmit} className="flex flex-col gap-4">
              <div>
                <h2 className="text-xl font-semibold">Welcome back</h2>
                <p className="text-[13px] text-ink2">Sign in to your workspace.</p>
              </div>
              <input type="hidden" name="next" value={next ?? ""} />
              <Field label="Work email" name="email" type="email" autoComplete="email" />
              <Field label="Password" name="password" type="password" autoComplete="current-password" />
              <div className="flex items-center justify-between text-[13px]">
                <label className="flex items-center gap-2">
                  <input type="checkbox" name="remember" className="accent-blue w-4 h-4" />
                  Keep me signed in on this device
                </label>
                <Link href="/forgot-password" className="text-blue font-medium">Forgot password?</Link>
              </div>
              {login.state.error && <p role="alert" className="text-[13px] text-bad">{login.state.error}</p>}
              <Submit pending={login.pending}>Sign in</Submit>
              <p className="text-xs text-ink3">Your admin turned on single sign-on? Use Continue with Google or ask them for the link.</p>
            </form>
          ) : (
            <form onSubmit={googleToken ? finishGoogle : signup.onSubmit} className="flex flex-col gap-4">
              <div>
                <h2 className="text-xl font-semibold">Create your account</h2>
                <p className="text-[13px] text-ink2">Pick what you&apos;re here to do. You can add the other later.</p>
              </div>
              <div role="radiogroup" aria-label="What you're here to do" className="grid grid-cols-2 gap-2">
                {([
                  ["BUYER", "I run finance", "Use agents on my company's books"],
                  ["DEVELOPER", "I build agents", "Publish agents and get paid"],
                ] as [Side, string, string][]).map(([value, title, hint]) => (
                  <label key={value} className={`cursor-pointer rounded-xl border p-3 ${side === value ? "border-blue shadow-[0_0_0_3px_var(--sky2)]" : "border-line"}`}>
                    <input type="radio" name="side" value={value} checked={side === value} onChange={() => setSide(value)} className="sr-only" aria-label={`${title}. ${hint}`} />
                    <span className="block text-[13px] font-semibold">{title}</span>
                    <span className="block text-xs text-ink3">{hint}</span>
                  </label>
                ))}
              </div>
              {!googleToken && <Field label="Full name" name="fullName" autoComplete="name" />}
              <Field label={buyer ? "Company" : "Your name or studio"} name="orgName" placeholder={buyer ? "Meridian Traders Pvt Ltd" : "Ledgerline Labs"} />
              {!googleToken && <Field label="Work email" name="email" type="email" autoComplete="email" />}
              {!googleToken && <Field label="Password" name="password" type="password" autoComplete="new-password" minLength={8} />}
              {signup.state.error && <p role="alert" className="text-[13px] text-bad">{signup.state.error}</p>}
              <Submit pending={signup.pending}>{buyer ? "Create workspace" : "Create developer account"}</Submit>
              <p className="text-xs text-ink3">
                {buyer
                  ? "Next you add teammates and choose who approves. Adding money to the wallet comes last."
                  : "Build and test in the sandbox for free, right away. Get verified when you want to list an agent and get paid."}
              </p>
            </form>
          )}
        </div>
      </section>
    </div>
  );
}
```

Replace `src/app/(auth)/login/page.tsx`:

```tsx
import { AuthScreen } from "../AuthScreen";

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ mode?: string; next?: string }> }) {
  const sp = await searchParams;
  return (
    <AuthScreen
      initialMode={sp.mode === "signup" ? "signup" : "signin"}
      next={sp.next}
      googleClientId={process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID}
    />
  );
}
```

Replace `src/app/(auth)/signup/page.tsx`:

```tsx
import { AuthScreen } from "../AuthScreen";

export default function SignupPage() {
  return <AuthScreen initialMode="signup" googleClientId={process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID} />;
}
```

Delete `src/app/(auth)/login/actions.ts` and `src/app/(auth)/signup/actions.ts`.

- [ ] **Step 5: Forgot, reset, verify, invite**

`src/app/(auth)/forgot-password/ForgotForm.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { forgotPasswordAction } from "../actions";

export function ForgotForm() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  if (sent) {
    return (
      <p role="status" className="text-[13px] text-ink2">
        If an account exists for {email}, a reset link is on its way. It works once, for 1 hour.{" "}
        <Link href="/login" className="text-blue font-medium">Back to sign in</Link>
      </p>
    );
  }
  return (
    <form
      className="flex flex-col gap-4"
      onSubmit={async (e) => {
        e.preventDefault();
        await forgotPasswordAction(email);
        setSent(true);
      }}
    >
      <label className="flex flex-col gap-1.5 text-[13px]">
        <span className="text-ink2">Work email</span>
        <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className="h-10 px-3 rounded-[10px] border border-line" />
      </label>
      <Button type="submit">Send reset link</Button>
    </form>
  );
}
```

`src/app/(auth)/forgot-password/page.tsx`:

```tsx
import { Panel } from "@/components/ui/Panel";
import { ForgotForm } from "./ForgotForm";

export default function ForgotPasswordPage() {
  return (
    <div className="min-h-screen grid place-items-center p-6">
      <Panel className="w-full max-w-[420px] p-8 flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Reset your password</h1>
        <p className="text-[13px] text-ink2">We&apos;ll email you a link to choose a new one.</p>
        <ForgotForm />
      </Panel>
    </div>
  );
}
```

`src/app/(auth)/reset-password/[token]/ResetForm.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { resetPasswordAction } from "../../actions";

export function ResetForm({ token }: { token: string }) {
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (password !== again) {
      setError("The two passwords don't match.");
      return;
    }
    const result = await resetPasswordAction(token, password);
    if (result.ok) setDone(true);
    else setError(result.error);
  }

  if (done) {
    return (
      <p role="status" className="text-[13px]">
        Password changed. You were signed out everywhere else. <Link href="/login" className="text-blue font-medium">Sign in</Link>
      </p>
    );
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <label className="flex flex-col gap-1.5 text-[13px]">
        <span className="text-ink2">New password</span>
        <input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" className="h-10 px-3 rounded-[10px] border border-line" />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px]">
        <span className="text-ink2">Type it again</span>
        <input type="password" required minLength={8} value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" className="h-10 px-3 rounded-[10px] border border-line" />
      </label>
      {error && (
        <p role="alert" className="text-[13px] text-bad">
          {error} {error.includes("expired") && <Link href="/forgot-password" className="text-blue font-medium">Send a new link</Link>}
        </p>
      )}
      <Button type="submit">Set new password</Button>
    </form>
  );
}
```

`src/app/(auth)/reset-password/[token]/page.tsx`:

```tsx
import { Panel } from "@/components/ui/Panel";
import { ResetForm } from "./ResetForm";

export default async function ResetPasswordPage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return (
    <div className="min-h-screen grid place-items-center p-6">
      <Panel className="w-full max-w-[420px] p-8 flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Choose a new password</h1>
        <ResetForm token={token} />
      </Panel>
    </div>
  );
}
```

`src/app/(auth)/verify-email/[token]/VerifyEmail.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { verifyEmailAction } from "../../actions";

/** Confirms on a click, not on page load, so mail scanners that open links don't confirm anything. */
export function VerifyEmail({ token }: { token: string }) {
  const [state, setState] = useState<{ done: boolean; error: string | null }>({ done: false, error: null });
  if (state.done) {
    return <p role="status" className="text-[13px]">Email confirmed. <Link href="/" className="text-blue font-medium">Continue to Karya</Link></p>;
  }
  return (
    <div className="flex flex-col gap-3">
      {state.error && <p role="alert" className="text-[13px] text-bad">{state.error}</p>}
      <Button
        onClick={async () => {
          const r = await verifyEmailAction(token);
          setState(r.ok ? { done: true, error: null } : { done: false, error: r.error });
        }}
      >
        Confirm my email
      </Button>
    </div>
  );
}
```

`src/app/(auth)/verify-email/[token]/page.tsx`:

```tsx
import { Panel } from "@/components/ui/Panel";
import { VerifyEmail } from "./VerifyEmail";

export default async function VerifyEmailPage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return (
    <div className="min-h-screen grid place-items-center p-6">
      <Panel className="w-full max-w-[420px] p-8 flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Confirm your email</h1>
        <VerifyEmail token={token} />
      </Panel>
    </div>
  );
}
```

`src/app/(auth)/invite/[token]/AcceptInvite.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { logoutAction } from "@/lib/authActions";
import { roleLabel } from "@/lib/roles";
import type { InvitationPreview } from "@/lib/types";
import { acceptInviteAction } from "../../actions";

export function AcceptInvite({ token, preview, signedInEmail }: { token: string; preview: InvitationPreview; signedInEmail: string | null }) {
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const label = roleLabel(preview.role);
  const article = /^[AEIOU]/.test(label) ? "an" : "a";

  async function accept(...args: [] | [string, string]) {
    const result = await acceptInviteAction(token, ...args);
    if (result && !result.ok) setError(result.error);
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-[13px] text-ink2">
        {preview.invitedBy ?? "A teammate"} invited you to {preview.orgName} as {article} {label}.
      </p>
      {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
      {signedInEmail === preview.email ? (
        <Button onClick={() => accept()}>Join {preview.orgName}</Button>
      ) : signedInEmail ? (
        <div className="flex flex-col gap-3 text-[13px]">
          <p>You&apos;re signed in as {signedInEmail}. This invitation is for {preview.email}.</p>
          <form action={logoutAction}><Button type="submit" variant="secondary">Sign out</Button></form>
        </div>
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            void accept(fullName, password);
          }}
        >
          <label className="flex flex-col gap-1.5 text-[13px]">
            <span className="text-ink2">Full name</span>
            <input required value={fullName} onChange={(e) => setFullName(e.target.value)} autoComplete="name" className="h-10 px-3 rounded-[10px] border border-line" />
          </label>
          <label className="flex flex-col gap-1.5 text-[13px]">
            <span className="text-ink2">Password</span>
            <input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" className="h-10 px-3 rounded-[10px] border border-line" />
          </label>
          <Button type="submit">Create account and join</Button>
          <p className="text-xs text-ink3">
            Already have a Karya account? <Link href={`/login?next=/invite/${token}`} className="text-blue font-medium">Sign in</Link> first.
          </p>
        </form>
      )}
    </div>
  );
}
```

`src/app/(auth)/invite/[token]/page.tsx`:

```tsx
import Link from "next/link";
import { Panel } from "@/components/ui/Panel";
import { backend, BackendError } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { InvitationPreview, MeResponse } from "@/lib/types";
import { AcceptInvite } from "./AcceptInvite";

export default async function InvitePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  let preview: InvitationPreview | null = null;
  try {
    preview = await backend.get<InvitationPreview>(`/api/v1/invitations/${token}`);
  } catch (e) {
    if (!(e instanceof BackendError) || e.status !== 410) throw e;
  }
  const session = await getSession();
  const me = session ? await backend.get<MeResponse>("/api/v1/me").catch(() => null) : null;

  return (
    <div className="min-h-screen grid place-items-center p-6">
      <Panel className="w-full max-w-[420px] p-8 flex flex-col gap-4">
        <h1 className="text-xl font-semibold">{preview ? `Join ${preview.orgName}` : "This invitation has closed"}</h1>
        {preview ? (
          <AcceptInvite token={token} preview={preview} signedInEmail={me?.email ?? null} />
        ) : (
          <p className="text-[13px] text-ink2">
            It expired or was already used. Ask whoever invited you to send a new one. <Link href="/login" className="text-blue font-medium">Sign in</Link>
          </p>
        )}
      </Panel>
    </div>
  );
}
```

- [ ] **Step 6: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): sign in or create account by side, Google, keep me signed in, and emailed-link pages"
```

---

### Task 5: Workspace home

**Files:**
- Create: `src/lib/homeCopy.ts`, `src/app/(buyer)/home/page.tsx`, `src/app/(buyer)/home/HomeView.tsx`
- Test: create `src/lib/homeCopy.test.ts`, `src/app/(buyer)/home/HomeView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/orgs/{orgId}/home` → `WorkspaceHome` (WS15), `GET /api/v1/me` (for the first name); UI primitives; `inr`, `greeting`, `minutesUntil`, `clockTime`, `timeAgo`, `countWord`.
- Produces:
  - `homeSubtitle(home): string`, `allowedTo(agent): string`, `activitySentence(item: ActivityItem): string`.
  - `HomeView({ home, firstName, now? })`.

- [ ] **Step 1: Write the failing tests**

`src/lib/homeCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { activitySentence, allowedTo, homeSubtitle } from "./homeCopy";
import type { WorkspaceHome } from "./types";

const base: WorkspaceHome = {
  needsYou: [], workingNow: [], agents: [],
  spend: { monthToDate: 0, monthlyLimit: null }, wallet: { balance: 0, held: 0 },
  exposure: { resourcesRead: 0, writeScopes: 0, outsideHosts: [] }, recent: [],
};
const running = { runId: "r", agentName: "A", status: "RUNNING" as const, startedAt: null, charged: 0, spendCap: 300 };
const waiting = { runId: "w", agentName: "B", connectionId: "c", waitingSince: null, expiresAt: null, summary: null, canDecide: true };

describe("homeSubtitle", () => {
  it("reads like the mockup", () => {
    expect(homeSubtitle({ ...base, workingNow: [running, running], needsYou: [waiting] })).toBe("Two agents are working. One is waiting on you.");
    expect(homeSubtitle({ ...base, workingNow: [running] })).toBe("One agent is working.");
    expect(homeSubtitle({ ...base, needsYou: [waiting, waiting] })).toBe("Two runs are waiting on you.");
    expect(homeSubtitle(base)).toBe("Nothing is running right now.");
  });
  it("does not count approvals the person cannot decide", () => {
    expect(homeSubtitle({ ...base, needsYou: [{ ...waiting, canDecide: false }] })).toBe("Nothing is running right now.");
  });
});

describe("allowedTo", () => {
  const agent = { connectionId: "c", agentId: "a", agentName: "A", publisher: "P", version: "1.0.0", runsThisMonth: 0, spendThisMonth: 0 };
  it("describes reads and writes in plain words", () => {
    expect(allowedTo({ ...agent, reads: 2, writes: 1, writesNeedApproval: true })).toBe("Read 2 sources, write 1 after approval");
    expect(allowedTo({ ...agent, reads: 1, writes: 0, writesNeedApproval: false })).toBe("Read 1 source, no writes");
    expect(allowedTo({ ...agent, reads: 0, writes: 2, writesNeedApproval: false })).toBe("No reads, write 2");
  });
});

describe("activitySentence", () => {
  it("describes each kind of event", () => {
    expect(activitySentence({ kind: "RUN_FINISHED", actorName: null, at: "", payload: { agentName: "Expense Policy Checker", status: "COMPLETED", charged: "428.00" } }))
      .toBe("Expense Policy Checker finished. ₹428 charged.");
    expect(activitySentence({ kind: "GATE_DECIDED", actorName: "Arjun Mehta", at: "", payload: { agentName: "Vendor Payment Scheduler", decision: "APPROVED" } }))
      .toBe("Arjun Mehta approved a Vendor Payment Scheduler request.");
    expect(activitySentence({ kind: "RUN_FINISHED", actorName: null, at: "", payload: { agentName: "Bank Reconciliation", status: "EXPIRED" } }))
      .toBe("Bank Reconciliation stopped because nobody approved in time.");
    expect(activitySentence({ kind: "SPEND_THRESHOLD_CROSSED", actorName: null, at: "", payload: { threshold: 25, limit: "50000" } }))
      .toBe("Spend reached 25% of your ₹50,000 monthly limit.");
    expect(activitySentence({ kind: "SOMETHING_NEW", actorName: null, at: "", payload: {} })).toBe("Something new happened.");
  });
});
```

`src/app/(buyer)/home/HomeView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { HomeView } from "./HomeView";
import type { WorkspaceHome } from "@/lib/types";

const now = new Date("2026-09-23T04:14:00Z"); // 09:44 IST
const home: WorkspaceHome = {
  needsYou: [{
    runId: "0f8a2c4e-1111", agentName: "Bank Reconciliation", connectionId: "c1",
    waitingSince: "2026-09-23T04:11:00Z", expiresAt: "2026-09-23T04:41:00Z",
    summary: "Post 142 journal entries for August 2026", canDecide: true,
  }],
  workingNow: [{ runId: "r2", agentName: "GSTR-2B Reconciler", status: "RUNNING", startedAt: "2026-09-23T04:10:00Z", charged: 0, spendCap: 300 }],
  agents: [{ connectionId: "c1", agentId: "a1", agentName: "Bank Reconciliation", publisher: "Ledgerline Labs", version: "1.3.0", reads: 2, writes: 1, writesNeedApproval: true, runsThisMonth: 18, spendThisMonth: 4210 }],
  spend: { monthToDate: 12480, monthlyLimit: 50000 },
  wallet: { balance: 87520, held: 800 },
  exposure: { resourcesRead: 8, writeScopes: 2, outsideHosts: [] },
  recent: [{ kind: "RUN_FINISHED", actorName: null, at: "2026-09-23T04:32:00Z", payload: { agentName: "Expense Policy Checker", status: "COMPLETED", charged: "428.00" } }],
};

describe("HomeView", () => {
  it("greets the person and summarises the workspace", () => {
    render(<HomeView home={home} firstName="Priya" now={now} />);
    expect(screen.getByRole("heading", { name: "Good morning, Priya" })).toBeInTheDocument();
    expect(screen.getByText("One agent is working. One is waiting on you.")).toBeInTheDocument();
  });

  it("puts the waiting approval first with its time left", () => {
    render(<HomeView home={home} firstName="Priya" now={now} />);
    const card = screen.getByRole("region", { name: "Needs your approval" });
    expect(within(card).getByText("Post 142 journal entries for August 2026")).toBeInTheDocument();
    expect(within(card).getByText(/The window closes in 27 minutes/)).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: "Review" })).toHaveAttribute("href", "/runs/0f8a2c4e-1111");
  });

  it("lists agents with what they may do, runs and spend", () => {
    render(<HomeView home={home} firstName="Priya" now={now} />);
    const row = screen.getByRole("row", { name: /Bank Reconciliation/ });
    expect(within(row).getByText("Read 2 sources, write 1 after approval")).toBeInTheDocument();
    expect(within(row).getByText("₹4,210")).toBeInTheDocument();
  });

  it("shows money: spend against the limit, balance and what is held", () => {
    render(<HomeView home={home} firstName="Priya" now={now} />);
    expect(screen.getByText("₹12,480")).toBeInTheDocument();
    expect(screen.getByText("of ₹50,000 limit")).toBeInTheDocument();
    expect(screen.getByText("₹87,520")).toBeInTheDocument();
    expect(screen.getByText("₹800")).toBeInTheDocument();
    expect(screen.getByText("None")).toBeInTheDocument();
  });

  it("says who is waiting when the person cannot decide", () => {
    render(<HomeView home={{ ...home, needsYou: [{ ...home.needsYou[0], canDecide: false }] }} firstName="Priya" now={now} />);
    expect(screen.getByText("Waiting on your approvers")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/homeCopy.test.ts "src/app/(buyer)/home"`
Expected: FAIL (modules not found).

- [ ] **Step 3: Copy helpers**

`src/lib/homeCopy.ts`:

```ts
import { countWord, inr } from "./format";
import type { ActivityItem, WorkspaceHome } from "./types";

type Agent = WorkspaceHome["agents"][number];

const num = (v: unknown) => (typeof v === "number" ? v : Number(v ?? 0));
const str = (v: unknown, fallback = "") => (typeof v === "string" && v ? v : fallback);

export function homeSubtitle(home: WorkspaceHome): string {
  const working = home.workingNow.length;
  const waiting = home.needsYou.filter((n) => n.canDecide).length;
  if (working === 0 && waiting === 0) return "Nothing is running right now.";
  const parts: string[] = [];
  if (working > 0) parts.push(`${countWord(working)} ${working === 1 ? "agent is" : "agents are"} working.`);
  if (waiting > 0) {
    parts.push(working > 0
      ? `${countWord(waiting)} ${waiting === 1 ? "is" : "are"} waiting on you.`
      : `${countWord(waiting)} ${waiting === 1 ? "run is" : "runs are"} waiting on you.`);
  }
  return parts.join(" ");
}

export function allowedTo(agent: Agent): string {
  const reads = agent.reads === 0 ? "No reads" : `Read ${agent.reads} ${agent.reads === 1 ? "source" : "sources"}`;
  const writes = agent.writes === 0
    ? "no writes"
    : `write ${agent.writes}${agent.writesNeedApproval ? " after approval" : ""}`;
  return `${reads}, ${writes}`;
}

const RUN_ENDINGS: Record<string, string> = {
  FAILED: "stopped with an error",
  TIMED_OUT: "ran out of time",
  EXPIRED: "stopped because nobody approved in time",
  CANCELLED: "stopped after a rejection",
};

export function activitySentence(item: ActivityItem): string {
  const p = item.payload;
  const agent = str(p.agentName, "An agent");
  const who = item.actorName ?? "Someone";
  switch (item.kind) {
    case "GATE_OPENED":
      return p.summary ? `${agent} asks for approval: ${str(p.summary)}.` : `${agent} is waiting for approval.`;
    case "GATE_DECIDED":
      return `${who} ${p.decision === "APPROVED" ? "approved" : "rejected"} a ${agent} request.`;
    case "RUN_FINISHED":
      return p.status === "COMPLETED"
        ? `${agent} finished. ${inr(num(p.charged))} charged.`
        : `${agent} ${RUN_ENDINGS[str(p.status)] ?? "stopped"}.`;
    case "SPEND_THRESHOLD_CROSSED":
      return `Spend reached ${num(p.threshold)}% of your ${inr(num(p.limit))} monthly limit.`;
    case "CONNECTION_CREATED":
      return `${who} added ${agent}.`;
    case "DISPUTE_OPENED":
      return `${who} disputed a ${agent} run.`;
    case "DISPUTE_DECIDED":
      return p.outcome === "REFUNDED"
        ? `Karya decided a ${agent} dispute: ${inr(num(p.amountRefunded))} refunded.`
        : `Karya decided a ${agent} dispute: not refunded.`;
    default:
      return "Something new happened.";
  }
}
```

- [ ] **Step 4: View and page**

`src/app/(buyer)/home/HomeView.tsx`:

```tsx
import Link from "next/link";
import { ButtonLink } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Kv } from "@/components/ui/Kv";
import { MiniFlow } from "@/components/ui/MiniFlow";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { clockTime, greeting, inr, minutesUntil, shortId, timeAgo } from "@/lib/format";
import { activitySentence, allowedTo, homeSubtitle } from "@/lib/homeCopy";
import type { WorkspaceHome } from "@/lib/types";

export function HomeView({ home, firstName, now = new Date() }: { home: WorkspaceHome; firstName: string; now?: Date }) {
  const limit = home.spend.monthlyLimit;
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_340px] gap-7">
      <div className="flex flex-col gap-6 min-w-0">
        <div className="flex items-end justify-between gap-4">
          <div>
            <h1 className="text-[30px] font-semibold tracking-tight">{greeting(now)}{firstName ? `, ${firstName}` : ""}</h1>
            <p className="text-ink2 mt-1">{homeSubtitle(home)}</p>
          </div>
          <div className="flex gap-2.5">
            <ButtonLink href="/catalog" variant="secondary">Browse agents</ButtonLink>
            <ButtonLink href="/runs"><Icon name="plus" className="w-4 h-4" />Run an agent</ButtonLink>
          </div>
        </div>

        {home.needsYou.map((n) => (
          <Panel key={n.runId} aria-label="Needs your approval" className="p-5 flex items-center gap-5 border-[#EFD19E] shadow-wait">
            <MiniFlow steps={["done", "done", "done", "wait", "idle"]} label="Waiting at the approval step" />
            <div className="flex-1 min-w-0 flex flex-col gap-1">
              <div className="flex gap-2.5 items-center">
                {n.canDecide ? <Pill tone="wa">Needs your approval</Pill> : <Pill tone="n">Waiting on your approvers</Pill>}
                <span className="text-xs text-ink3">{n.agentName}, run <span className="font-mono">{shortId(n.runId)}</span></span>
              </div>
              <div className="text-lg font-semibold tracking-tight">{n.summary ?? `${n.agentName} wants to write data`}</div>
              <div className="text-[13px] text-ink2">
                {n.waitingSince ? `Staged ${timeAgo(n.waitingSince, now)}. ` : ""}
                Nothing reaches your ledger until someone approves.
                {n.expiresAt ? ` The window closes in ${minutesUntil(n.expiresAt, now)} minutes.` : ""}
              </div>
            </div>
            <ButtonLink href={`/runs/${n.runId}`}>Review</ButtonLink>
          </Panel>
        ))}

        {home.workingNow.length > 0 && (
          <section className="flex flex-col gap-3">
            <h2 className="text-sm font-semibold">Working now</h2>
            <div className="grid grid-cols-2 gap-4">
              {home.workingNow.map((w) => (
                <Link key={w.runId} href={`/runs/${w.runId}`}>
                  <Panel as="div" className="p-4 flex flex-col gap-3 hover:border-blue-3">
                    <div className="flex justify-between items-center">
                      <span className="font-semibold text-[15px]">{w.agentName}</span>
                      <Pill tone="b">{w.status === "PENDING" ? "Starting" : "Running"}</Pill>
                    </div>
                    <div className="flex justify-between text-xs text-ink2">
                      <span>{w.startedAt ? `Started ${clockTime(w.startedAt)}` : "Starting"}</span>
                      <span className="font-mono">{inr(w.charged)} of {inr(w.spendCap)}</span>
                    </div>
                  </Panel>
                </Link>
              ))}
            </div>
          </section>
        )}

        <Panel className="overflow-hidden">
          <div className="px-5 py-4"><PanelTitle hint="This month">Your agents</PanelTitle></div>
          {home.agents.length === 0 ? (
            <p className="px-5 pb-5 text-[13px] text-ink2">
              No agents yet. <Link href="/catalog" className="text-blue font-semibold">Browse the store</Link> to add your first.
            </p>
          ) : (
            <table className="w-full text-[13px]">
              <thead>
                <tr className="text-xs text-ink3 border-t border-line2">
                  <th className="text-left font-normal px-5 py-2">Agent</th>
                  <th className="text-left font-normal py-2">Allowed to</th>
                  <th className="text-left font-normal py-2">Runs</th>
                  <th className="text-left font-normal py-2">Spend</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {home.agents.map((a) => (
                  <tr key={a.connectionId} aria-label={a.agentName} className="border-t border-line2">
                    <td className="px-5 py-3">
                      <span className="block font-semibold">{a.agentName}</span>
                      <span className="text-xs text-ink3">{a.publisher}, v{a.version}</span>
                    </td>
                    <td className="py-3 text-ink2">{allowedTo(a)}</td>
                    <td className="py-3 font-mono">{a.runsThisMonth}</td>
                    <td className="py-3 font-mono">{inr(a.spendThisMonth)}</td>
                    <td className="py-3 pr-5 text-right"><Link href={`/store/${a.agentId}`} className="text-blue font-semibold">Open</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
      </div>

      <aside className="flex flex-col gap-4">
        <Panel className="p-5">
          <div className="text-xs text-ink3">Spend this month</div>
          <div className="flex items-baseline gap-2 mt-1 mb-3">
            <span className="font-mono text-[30px] font-medium tracking-tight">{inr(home.spend.monthToDate)}</span>
            <span className="text-xs text-ink3">{limit !== null ? `of ${inr(limit)} limit` : "no monthly limit"}</span>
          </div>
          {limit !== null && <ProgressBar value={home.spend.monthToDate} max={limit} label="Spend against the monthly limit" />}
        </Panel>
        <Panel className="p-5">
          <Kv label="Wallet balance" value={inr(home.wallet.balance)} />
          <Kv label="Held for running agents" value={inr(home.wallet.held)} />
        </Panel>
        <Panel className="p-5">
          <PanelTitle>What your agents can touch</PanelTitle>
          <div className="mt-2">
            <Kv label="Data they read" value={home.exposure.resourcesRead} />
            <Kv label="Writes they can make" value={home.exposure.writeScopes} />
            <Kv label="Outside websites contacted" value={home.exposure.outsideHosts.length ? home.exposure.outsideHosts.join(", ") : "None"} mono={false} />
          </div>
        </Panel>
        {home.recent.length > 0 && (
          <Panel className="p-5">
            <PanelTitle>Recent</PanelTitle>
            <ul className="mt-2">
              {home.recent.map((r, i) => (
                <li key={i} className="grid grid-cols-[48px_1fr] gap-2 py-2 border-t border-line2 text-[13px] first:border-t-0">
                  <span className="font-mono text-xs text-ink3 pt-0.5">{clockTime(r.at)}</span>
                  <span>{activitySentence(r)}</span>
                </li>
              ))}
            </ul>
          </Panel>
        )}
      </aside>
    </div>
  );
}
```

`src/app/(buyer)/home/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { firstName } from "@/lib/format";
import { getSession } from "@/lib/session";
import type { MeResponse, WorkspaceHome } from "@/lib/types";
import { HomeView } from "./HomeView";

export default async function HomePage() {
  const session = await getSession();
  if (!session) return null;
  const [home, me] = await Promise.all([
    backend.get<WorkspaceHome>(`/api/v1/orgs/${session.orgId}/home`),
    backend.get<MeResponse>("/api/v1/me"),
  ]);
  return <HomeView home={home} firstName={firstName(me.fullName)} />;
}
```

The store link `/store/{agentId}` resolves in Task 14. Until then it 404s, which Task 14's E2E catches.

- [ ] **Step 5: Run the tests**

Run: `npm test -- src/lib/homeCopy.test.ts "src/app/(buyer)/home" && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): workspace home with approvals waiting, running work, agents, spend and activity"
```

---

### Task 6: Runs history and "Run an agent"

**Files:**
- Modify: `src/lib/runStatus.ts`, `src/app/(buyer)/runs/page.tsx`, `src/app/(buyer)/runs/actions.ts`
- Create: `src/lib/runStartMessages.ts`, `src/app/(buyer)/runs/RunsHistory.tsx`, `src/app/(buyer)/runs/RunAnAgent.tsx`
- Delete: `src/app/(buyer)/runs/RunsList.tsx`, `RunsList.test.tsx`, `StartRun.tsx`, `StartRun.test.tsx`
- Test: create `src/lib/runStatus.test.ts`, `src/app/(buyer)/runs/RunsHistory.test.tsx`, `src/app/(buyer)/runs/RunAnAgent.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/runs/mine` → `RunSummaryResponse[]`, `GET /api/v1/connections/mine` → `ConnectionSummaryResponse[]`, `POST /api/v1/runs {connectionId}` (WS15 codes: `ORG_MONTHLY_LIMIT`, `AGENT_MONTHLY_LIMIT`, `INSUFFICIENT_FUNDS`); `runGroup`, `runStatusPill` (Task 3); `actionError`.
- Produces:
  - `RUN_FILTERS`, `countByGroup(runs): Record<"all" | RunGroup, number>`.
  - `RunsHistory({ runs })`, `RunAnAgent({ connections, error? })`.
  - `startRunAction(connectionId): Promise<ActionFailure>`, which redirects on success.
  - `RUN_START_MESSAGES` in `src/lib/runStartMessages.ts`. It lives in a plain module because a `"use server"` file may export only async functions.

- [ ] **Step 1: Write the failing tests**

`src/lib/runStatus.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { countByGroup, runGroup, runStatusPill } from "./runStatus";

describe("runStatus", () => {
  it("groups statuses for the filter chips", () => {
    expect(runGroup("AWAITING_APPROVAL")).toBe("needs");
    expect(runGroup("PENDING")).toBe("running");
    expect(runGroup("COMPLETED")).toBe("done");
    expect(runGroup("EXPIRED")).toBe("stopped");
    expect(runGroup("CANCELLED")).toBe("stopped");
  });
  it("labels statuses in plain words", () => {
    expect(runStatusPill("EXPIRED")).toEqual({ tone: "n", label: "No answer in time" });
    expect(runStatusPill("AWAITING_APPROVAL")).toEqual({ tone: "wa", label: "Needs you" });
  });
  it("counts each group", () => {
    const runs = [{ status: "RUNNING" }, { status: "COMPLETED" }, { status: "COMPLETED" }, { status: "FAILED" }] as { status: never }[];
    expect(countByGroup(runs)).toEqual({ all: 4, needs: 0, running: 1, done: 2, stopped: 1 });
  });
});
```

`src/app/(buyer)/runs/RunsHistory.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { RunsHistory } from "./RunsHistory";
import type { RunSummaryResponse } from "@/lib/types";

const runs: RunSummaryResponse[] = [
  { id: "0f8a2c4e-a", agentName: "Bank Reconciliation", status: "AWAITING_APPROVAL", totalCharged: 0, startedAt: "2026-09-23T04:11:02Z" },
  { id: "1b2c3d4e-b", agentName: "Expense Policy Checker", status: "COMPLETED", totalCharged: 428, startedAt: "2026-09-23T04:30:00Z" },
  { id: "2c3d4e5f-c", agentName: "Bank Reconciliation", status: "EXPIRED", totalCharged: 0, startedAt: "2026-09-21T04:30:00Z" },
];

describe("RunsHistory", () => {
  it("shows every run with counts on the filters", () => {
    render(<RunsHistory runs={runs} />);
    expect(screen.getByRole("button", { name: "All 3" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Needs you 1" })).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(4); // header + 3
  });

  it("filters to a group", async () => {
    render(<RunsHistory runs={runs} />);
    await userEvent.click(screen.getByRole("button", { name: "Stopped 1" }));
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(within(rows[0]).getByText("No answer in time")).toBeInTheDocument();
    expect(within(rows[0]).getByRole("link", { name: "Open" })).toHaveAttribute("href", "/runs/2c3d4e5f-c");
  });

  it("says so when a filter is empty", async () => {
    render(<RunsHistory runs={runs.slice(1)} />);
    await userEvent.click(screen.getByRole("button", { name: "Needs you 0" }));
    expect(screen.getByText("No runs match this filter.")).toBeInTheDocument();
  });
});
```

`src/app/(buyer)/runs/RunAnAgent.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { RunAnAgent } from "./RunAnAgent";
import type { ConnectionSummaryResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  startRunAction: vi.fn().mockResolvedValue({ ok: false, error: "This agent has reached its monthly limit." }),
}));

const connections: ConnectionSummaryResponse[] = [
  { id: "c1", agentId: "a1", agentName: "Bank Reconciliation", grantedScopes: ["bank_statement"], spendCap: 500, status: "ACTIVE" },
  { id: "c2", agentId: "a2", agentName: "Old Agent", grantedScopes: [], spendCap: 100, status: "REVOKED" },
];

describe("RunAnAgent", () => {
  it("lists active agents only, with their run limit", () => {
    render(<RunAnAgent connections={connections} />);
    expect(screen.getByText("Bank Reconciliation")).toBeInTheDocument();
    expect(screen.getByText("Stops at ₹500")).toBeInTheDocument();
    expect(screen.queryByText("Old Agent")).not.toBeInTheDocument();
  });

  it("explains why a run could not start", async () => {
    const { startRunAction } = await import("./actions");
    render(<RunAnAgent connections={connections} />);
    await userEvent.click(screen.getByRole("button", { name: "Run Bank Reconciliation now" }));
    expect(startRunAction).toHaveBeenCalledWith("c1");
    expect(await screen.findByRole("alert")).toHaveTextContent("monthly limit");
  });

  it("points to the store when there is nothing to run", () => {
    render(<RunAnAgent connections={[]} />);
    expect(screen.getByRole("link", { name: "Browse the store" })).toHaveAttribute("href", "/catalog");
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/runStatus.test.ts "src/app/(buyer)/runs/RunsHistory" "src/app/(buyer)/runs/RunAnAgent"`
Expected: FAIL (`countByGroup` not exported; modules not found).

- [ ] **Step 3: Filters**

Append to `src/lib/runStatus.ts`:

```ts
export const RUN_FILTERS: { key: "all" | RunGroup; label: string }[] = [
  { key: "all", label: "All" },
  { key: "needs", label: "Needs you" },
  { key: "running", label: "Running" },
  { key: "done", label: "Finished" },
  { key: "stopped", label: "Stopped" },
];

export function countByGroup(runs: { status: RunStatus }[]): Record<"all" | RunGroup, number> {
  const counts = { all: runs.length, needs: 0, running: 0, done: 0, stopped: 0 };
  for (const r of runs) counts[runGroup(r.status)]++;
  return counts;
}
```

- [ ] **Step 4: Components, action, page**

`src/app/(buyer)/runs/RunsHistory.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Panel } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { clockTime, dayLabel, inr, shortId } from "@/lib/format";
import { countByGroup, RUN_FILTERS, runGroup, runStatusPill, type RunGroup } from "@/lib/runStatus";
import type { RunSummaryResponse } from "@/lib/types";

export function RunsHistory({ runs }: { runs: RunSummaryResponse[] }) {
  const [filter, setFilter] = useState<"all" | RunGroup>("all");
  const counts = countByGroup(runs);
  const shown = filter === "all" ? runs : runs.filter((r) => runGroup(r.status) === filter);

  return (
    <Panel className="overflow-hidden">
      <div role="group" aria-label="Filter runs" className="flex gap-2 p-4">
        {RUN_FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            aria-pressed={filter === f.key}
            onClick={() => setFilter(f.key)}
            className={`h-8 px-3 rounded-full text-[13px] border ${filter === f.key ? "bg-sky border-blue-3 text-blue font-semibold" : "border-line text-ink2"}`}
          >
            {f.label} <span className="font-mono text-xs">{counts[f.key]}</span>
          </button>
        ))}
      </div>
      <table className="w-full text-[13px]">
        <thead>
          <tr className="text-xs text-ink3 border-t border-line2">
            <th className="text-left font-normal px-5 py-2">Run</th>
            <th className="text-left font-normal py-2">Agent</th>
            <th className="text-left font-normal py-2">Status</th>
            <th className="text-left font-normal py-2">Started</th>
            <th className="text-left font-normal py-2">Cost</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {shown.map((r) => {
            const pill = runStatusPill(r.status);
            return (
              <tr key={r.id} className="border-t border-line2">
                <td className="px-5 py-3 font-mono text-xs">{shortId(r.id)}</td>
                <td className="py-3 font-medium">{r.agentName}</td>
                <td className="py-3"><Pill tone={pill.tone}>{pill.label}</Pill></td>
                <td className="py-3 font-mono text-xs text-ink2">
                  {r.startedAt ? (dayLabel(r.startedAt) === "Today" ? clockTime(r.startedAt) : dayLabel(r.startedAt)) : "—"}
                </td>
                <td className="py-3 font-mono">{inr(r.totalCharged)}</td>
                <td className="py-3 pr-5 text-right"><Link href={`/runs/${r.id}`} className="text-blue font-semibold">Open</Link></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {shown.length === 0 && <p className="px-5 py-6 text-[13px] text-ink3">No runs match this filter.</p>}
    </Panel>
  );
}
```

`src/lib/runStartMessages.ts`:

```ts
/** Sentences for every refusal a run start can meet (WS15 codes and statuses). */
export const RUN_START_MESSAGES: Record<string, string> = {
  ORG_MONTHLY_LIMIT: "This run could take the workspace past its monthly limit. Raise the limit in Billing, or wait for next month.",
  AGENT_MONTHLY_LIMIT: "This agent has reached its monthly limit. Raise it in the agent's settings, or wait for next month.",
  INSUFFICIENT_FUNDS: "Your wallet doesn't hold enough for this agent's run limit. Add money in Billing.",
  "409": "This agent can't run right now: its connection or version is no longer active.",
  "502": "The sandbox couldn't start. Try again in a minute.",
};
```

Replace `src/app/(buyer)/runs/actions.ts`:

```ts
"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { actionError, type ActionFailure } from "@/lib/problems";
import { RUN_START_MESSAGES } from "@/lib/runStartMessages";
import type { RunResponse } from "@/lib/types";

export async function startRunAction(connectionId: string): Promise<ActionFailure> {
  let run: RunResponse;
  try {
    run = await backend.post<RunResponse>("/api/v1/runs", { connectionId });
  } catch (e) {
    return actionError(e, RUN_START_MESSAGES, "The run couldn't start.");
  }
  redirect(`/runs/${run.id}`);
}
```

`src/app/(buyer)/runs/RunAnAgent.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { inr } from "@/lib/format";
import type { ConnectionSummaryResponse } from "@/lib/types";
import { startRunAction } from "./actions";

export function RunAnAgent({ connections }: { connections: ConnectionSummaryResponse[] }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const active = connections.filter((c) => c.status === "ACTIVE");

  async function start(id: string) {
    setBusy(id);
    setError(null);
    const result = await startRunAction(id);
    if (result && !result.ok) setError(result.error);
    setBusy(null);
  }

  return (
    <Panel className="p-5 flex flex-col gap-3">
      <PanelTitle>Run an agent</PanelTitle>
      {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
      {active.length === 0 ? (
        <p className="text-[13px] text-ink2">No agents added yet. <Link href="/catalog" className="text-blue font-semibold">Browse the store</Link></p>
      ) : (
        <ul className="flex flex-col">
          {active.map((c) => (
            <li key={c.id} className="flex items-center gap-3 py-2 border-t border-line2 first:border-t-0">
              <span className="flex-1">
                <span className="block font-medium text-[13px]">{c.agentName}</span>
                <span className="text-xs text-ink3">Stops at {inr(c.spendCap)}</span>
              </span>
              <Button size="sm" disabled={busy !== null} onClick={() => start(c.id)} aria-label={`Run ${c.agentName} now`}>
                {busy === c.id ? "Starting…" : "Run now"}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
```

Replace `src/app/(buyer)/runs/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import type { ConnectionSummaryResponse, RunSummaryResponse } from "@/lib/types";
import { RunAnAgent } from "./RunAnAgent";
import { RunsHistory } from "./RunsHistory";

export default async function RunsPage() {
  const [runs, connections] = await Promise.all([
    backend.get<RunSummaryResponse[]>("/api/v1/runs/mine"),
    backend.get<ConnectionSummaryResponse[]>("/api/v1/connections/mine"),
  ]);
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Runs</h1>
        <p className="text-ink2">Every run your agents made, and where it got to.</p>
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-6 items-start">
        <RunsHistory runs={runs} />
        <RunAnAgent connections={connections} />
      </div>
    </div>
  );
}
```

Delete `RunsList.tsx`, `RunsList.test.tsx`, `StartRun.tsx` and `StartRun.test.tsx` in `src/app/(buyer)/runs/`.

- [ ] **Step 5: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src/app/\(buyer\)/runs frontend/src/lib
git commit -m "feat(frontend): runs history with filters, and run-now that explains spending refusals"
```

---

### Task 7: The run console, with approvals and disputes

**Files:**
- Create: `src/lib/runSteps.ts`, `src/app/(buyer)/runs/[runId]/DisputePanel.tsx`
- Modify: `src/app/(buyer)/runs/[runId]/RunConsole.tsx`, `actions.ts`, `page.tsx`
- Delete: `src/components/RunTraceStep.tsx`, `src/components/RunTraceStep.test.tsx`
- Test: create `src/lib/runSteps.test.ts`, `src/app/(buyer)/runs/[runId]/DisputePanel.test.tsx`; rewrite `RunConsole.test.tsx`

**Interfaces:**
- Consumes:
  - `GET /api/v1/runs/{id}` → `RunDetailResponse`;
  - `POST /api/v1/runs/{id}/approve|reject` (403 `NOT_AN_APPROVER`, 409 when nothing is waiting);
  - `POST /api/v1/runs/{id}/disputes {reason, details}` (WS16 codes `DISPUTE_WINDOW_CLOSED`, `ALREADY_DISPUTED`, `NOT_DISPUTABLE`);
  - `useRunPolling`, `isTerminalRunStatus` (WS0 frontend); `runStatusPill` (Task 3).
- Produces:
  - `stepSentence(step): string`, `lastGateSummary(steps): string | null`.
  - `decideAction(runId, approve): ActionResult`, `openDisputeAction(runId, reason, details): ActionResult<{ respondBy: string }>`.
  - `RunConsole({ runId, initial, role })`, `DisputePanel({ runId })`.

- [ ] **Step 1: Write the failing tests**

`src/lib/runSteps.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { lastGateSummary, stepSentence } from "./runSteps";
import type { RunStepResponse } from "./types";

const step = (kind: string, detail: Record<string, unknown> | string): RunStepResponse => ({
  kind: kind as RunStepResponse["kind"],
  detail: typeof detail === "string" ? detail : JSON.stringify(detail),
  createdAt: "2026-09-23T04:11:02Z",
});

describe("stepSentence", () => {
  it("describes each step kind", () => {
    expect(stepSentence(step("READ", { resource: "bank_statement", rows: 412 }))).toBe("Read bank_statement, 412 rows");
    expect(stepSentence(step("GATE", { summary: "Post 142 journal entries" }))).toBe("Asked for approval: Post 142 journal entries");
    expect(stepSentence(step("COST", { quantity: 142 }))).toBe("Charged for 142 units");
    expect(stepSentence(step("COMPLETE", {}))).toBe("Finished");
    expect(stepSentence(step("TIMEOUT", { summary: "Run exceeded its time limit" }))).toBe("Run exceeded its time limit");
    expect(stepSentence(step("COST_CAP_REACHED", {}))).toBe("Stopped at the spend limit");
  });
  it("survives a detail that is not JSON", () => {
    expect(stepSentence(step("WRITE", "not json"))).toBe("Wrote data");
  });
});

describe("lastGateSummary", () => {
  it("finds the latest approval request", () => {
    expect(lastGateSummary([step("GATE", { summary: "first" }), step("READ", {}), step("GATE", { summary: "second" })])).toBe("second");
    expect(lastGateSummary([step("READ", {})])).toBeNull();
  });
});
```

Replace `src/app/(buyer)/runs/[runId]/RunConsole.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RunConsole } from "./RunConsole";
import type { RunDetailResponse } from "@/lib/types";

vi.mock("./actions", () => ({
  decideAction: vi.fn().mockResolvedValue({ ok: false, error: "You're not one of the approvers for this agent." }),
  openDisputeAction: vi.fn(),
}));

const awaiting: RunDetailResponse = {
  id: "0f8a2c4e-1111",
  status: "AWAITING_APPROVAL",
  costCap: 500,
  totalCharged: 0,
  steps: [
    { kind: "READ", detail: '{"resource":"bank_statement","rows":412}', createdAt: "2026-09-23T04:11:05Z" },
    { kind: "GATE", detail: '{"summary":"Post 142 journal entries"}', createdAt: "2026-09-23T04:11:40Z" },
  ],
};

function renderConsole(run: RunDetailResponse, role: "BUYER_ADMIN" | "BUYER_APPROVER" = "BUYER_APPROVER") {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <RunConsole runId={run.id} initial={run} role={role} />
    </QueryClientProvider>,
  );
}

describe("RunConsole", () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => awaiting });
  });

  it("puts the decision first when the run is waiting", () => {
    renderConsole(awaiting);
    expect(screen.getByRole("heading", { name: "Post 142 journal entries?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
  });

  it("lets admins decide too, and explains a refusal from the backend", async () => {
    renderConsole(awaiting, "BUYER_ADMIN");
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("not one of the approvers");
  });

  it("shows the recorded timeline and the money", () => {
    renderConsole(awaiting);
    expect(screen.getByText("Read bank_statement, 412 rows")).toBeInTheDocument();
    expect(screen.getByText("₹0 of ₹500")).toBeInTheDocument();
    expect(screen.getByText("Held in your wallet")).toBeInTheDocument();
  });

  it("offers a dispute only on a finished run that charged something", () => {
    const finished: RunDetailResponse = { ...awaiting, status: "COMPLETED", totalCharged: 284 };
    const { unmount } = renderConsole(finished, "BUYER_ADMIN");
    expect(screen.getByRole("button", { name: "Dispute this charge" })).toBeInTheDocument();
    unmount();
    renderConsole({ ...finished, totalCharged: 0 }, "BUYER_ADMIN");
    expect(screen.queryByRole("button", { name: "Dispute this charge" })).not.toBeInTheDocument();
  });
});
```

`src/app/(buyer)/runs/[runId]/DisputePanel.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DisputePanel } from "./DisputePanel";

vi.mock("./actions", () => ({
  decideAction: vi.fn(),
  openDisputeAction: vi.fn()
    .mockResolvedValueOnce({ ok: true, respondBy: "2026-09-30T04:00:00Z" })
    .mockResolvedValueOnce({ ok: false, error: "Runs can be disputed within 14 days of finishing." }),
}));

describe("DisputePanel", () => {
  it("opens a dispute with a reason and details", async () => {
    const { openDisputeAction } = await import("./actions");
    render(<DisputePanel runId="r1" />);
    await userEvent.click(screen.getByRole("button", { name: "Dispute this charge" }));
    await userEvent.selectOptions(screen.getByLabelText("What went wrong"), "WRONG_OUTPUT");
    await userEvent.type(screen.getByLabelText("Tell Karya what happened"), "6 matches were against the wrong invoice");
    await userEvent.click(screen.getByRole("button", { name: "Send dispute" }));

    expect(openDisputeAction).toHaveBeenCalledWith("r1", "WRONG_OUTPUT", "6 matches were against the wrong invoice");
    expect(await screen.findByRole("status")).toHaveTextContent("Dispute sent");
  });

  it("explains why a dispute was refused", async () => {
    render(<DisputePanel runId="r1" />);
    await userEvent.click(screen.getByRole("button", { name: "Dispute this charge" }));
    await userEvent.type(screen.getByLabelText("Tell Karya what happened"), "x");
    await userEvent.click(screen.getByRole("button", { name: "Send dispute" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("14 days");
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/runSteps.test.ts "src/app/(buyer)/runs/\[runId\]"`
Expected: FAIL.

- [ ] **Step 3: Step sentences**

`src/lib/runSteps.ts`:

```ts
import type { RunStepResponse } from "./types";

function parse(detail: string): Record<string, unknown> {
  try {
    const v = JSON.parse(detail);
    return v && typeof v === "object" ? v : {};
  } catch {
    return {};
  }
}

export function stepSentence(step: RunStepResponse): string {
  const d = parse(step.detail);
  const summary = typeof d.summary === "string" && d.summary ? d.summary : null;
  const rows = typeof d.rows === "number" ? `, ${d.rows} rows` : "";
  switch (step.kind) {
    case "READ":
      return d.resource ? `Read ${String(d.resource)}${rows}` : "Read data";
    case "WRITE":
      return d.resource ? `Wrote ${String(d.resource)}${rows}` : "Wrote data";
    case "GATE":
      return summary ? `Asked for approval: ${summary}` : "Asked for approval";
    case "COST":
      return `Charged for ${String(d.quantity ?? "?")} units`;
    case "COMPLETE":
      return summary ?? "Finished";
    case "FAILED":
      return summary ? `Stopped: ${summary}` : "Stopped with an error";
    case "TIMEOUT":
      return summary ?? "Ran out of time";
    case "COST_CAP_REACHED":
      return "Stopped at the spend limit";
    default:
      return summary ?? String(step.kind);
  }
}

export function lastGateSummary(steps: RunStepResponse[]): string | null {
  for (let i = steps.length - 1; i >= 0; i--) {
    if (steps[i].kind === "GATE") {
      const s = parse(steps[i].detail).summary;
      return typeof s === "string" && s ? s : null;
    }
  }
  return null;
}
```

- [ ] **Step 4: Actions**

Replace `src/app/(buyer)/runs/[runId]/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { DisputeReason, DisputeView } from "@/lib/types";

export async function decideAction(runId: string, approve: boolean): Promise<ActionResult> {
  try {
    await backend.post(`/api/v1/runs/${runId}/${approve ? "approve" : "reject"}`, { notes: null });
  } catch (e) {
    return actionError(e, {
      NOT_AN_APPROVER: "You're not one of the approvers for this agent.",
      "409": "This request has closed. Someone may have decided already, or it ran out of time.",
    }, "We couldn't record your decision. Try again.");
  }
  revalidatePath(`/runs/${runId}`);
  return { ok: true };
}

export async function openDisputeAction(runId: string, reason: DisputeReason, details: string): Promise<ActionResult<{ respondBy: string }>> {
  try {
    const d = await backend.post<DisputeView>(`/api/v1/runs/${runId}/disputes`, { reason, details });
    return { ok: true, respondBy: d.respondBy };
  } catch (e) {
    return actionError(e, {
      DISPUTE_WINDOW_CLOSED: "Runs can be disputed within 14 days of finishing.",
      ALREADY_DISPUTED: "This run was already disputed. You'll find it in Billing.",
      NOT_DISPUTABLE: "Only finished runs that charged something can be disputed.",
    }, "We couldn't send the dispute.");
  }
}
```

- [ ] **Step 5: Components**

`src/app/(buyer)/runs/[runId]/DisputePanel.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { dayLabel } from "@/lib/format";
import type { DisputeReason } from "@/lib/types";
import { openDisputeAction } from "./actions";

const REASONS: [DisputeReason, string][] = [
  ["WRONG_OUTPUT", "The result was wrong"],
  ["OVERCHARGED", "I was charged too much"],
  ["DID_NOT_RUN", "It didn't do the work"],
  ["OTHER", "Something else"],
];

export function DisputePanel({ runId }: { runId: string }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<DisputeReason>("WRONG_OUTPUT");
  const [details, setDetails] = useState("");
  const [result, setResult] = useState<{ sent?: string; error?: string }>({});

  if (result.sent) {
    return (
      <p role="status" className="text-[13px] text-ink2">
        Dispute sent. The developer can reply until {dayLabel(result.sent)}, then Karya decides. Any refund goes back to your wallet.
      </p>
    );
  }
  if (!open) {
    return <Button variant="secondary" size="sm" onClick={() => setOpen(true)}>Dispute this charge</Button>;
  }
  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={async (e) => {
        e.preventDefault();
        const r = await openDisputeAction(runId, reason, details.trim());
        setResult(r.ok ? { sent: r.respondBy } : { error: r.error });
      }}
    >
      <label className="flex flex-col gap-1 text-[13px]">
        <span className="text-ink2">What went wrong</span>
        <select value={reason} onChange={(e) => setReason(e.target.value as DisputeReason)} className="h-9 px-2 rounded-lg border border-line bg-surface">
          {REASONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-[13px]">
        <span className="text-ink2">Tell Karya what happened</span>
        <textarea required rows={3} value={details} onChange={(e) => setDetails(e.target.value)} className="px-3 py-2 rounded-lg border border-line" />
      </label>
      {result.error && <p role="alert" className="text-[13px] text-bad">{result.error}</p>}
      <div className="flex gap-2">
        <Button type="submit" size="sm">Send dispute</Button>
        <Button variant="secondary" size="sm" onClick={() => setOpen(false)}>Cancel</Button>
      </div>
    </form>
  );
}
```

Replace `src/app/(buyer)/runs/[runId]/RunConsole.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { isTerminalRunStatus, useRunPolling } from "@/components/useRunPolling";
import { clockTime, inr, shortId } from "@/lib/format";
import { runStatusPill } from "@/lib/runStatus";
import { lastGateSummary, stepSentence } from "@/lib/runSteps";
import type { RunDetailResponse, UserRole } from "@/lib/types";
import { decideAction } from "./actions";
import { DisputePanel } from "./DisputePanel";

const DOT: Record<string, string> = { GATE: "bg-wa-line", COMPLETE: "bg-ok", FAILED: "bg-bad", TIMEOUT: "bg-bad", COST_CAP_REACHED: "bg-bad" };

export function RunConsole({ runId, initial, role }: { runId: string; initial: RunDetailResponse; role?: UserRole }) {
  const { data } = useRunPolling(runId, initial);
  const run = data ?? initial;
  const [deciding, setDeciding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pill = runStatusPill(run.status);
  const terminal = isTerminalRunStatus(run.status);
  const canDecide = role === "BUYER_ADMIN" || role === "BUYER_APPROVER";
  const summary = lastGateSummary(run.steps);

  async function decide(approve: boolean) {
    setDeciding(true);
    setError(null);
    const result = await decideAction(runId, approve);
    if (!result.ok) setError(result.error);
    setDeciding(false);
  }

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-7 items-start">
      <div className="flex flex-col gap-5 min-w-0">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-[26px] font-semibold tracking-tight">Run <span className="font-mono">{shortId(runId)}</span></h1>
            <Pill tone={pill.tone}>{pill.label}</Pill>
          </div>
          <p className="text-ink2 mt-1">Every step below is recorded by Karya, not reported by the agent.</p>
        </div>

        {run.status === "AWAITING_APPROVAL" && (
          <Panel aria-label="Your decision" className="p-5 border-[#EFD19E] shadow-wait flex flex-col gap-3">
            <h2 className="text-lg font-semibold">{summary ? `${summary}?` : "This run wants to write data. Allow it?"}</h2>
            <p className="text-[13px] text-ink2">Nothing is written until someone approves. Rejecting throws the drafts away and stops the run.</p>
            {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
            {canDecide ? (
              <div className="flex gap-2.5">
                <Button disabled={deciding} onClick={() => decide(true)}>Approve</Button>
                <Button variant="danger" disabled={deciding} onClick={() => decide(false)}>Reject</Button>
              </div>
            ) : (
              <p className="text-[13px]">Waiting on an approver.</p>
            )}
          </Panel>
        )}

        <Panel className="p-5">
          <PanelTitle hint="Recorded by Karya as it happens">Timeline</PanelTitle>
          <ol className="mt-2">
            {run.steps.map((s, i) => (
              <li key={i} className="grid grid-cols-[62px_12px_minmax(0,1fr)] gap-2.5 py-2 border-t border-line2 text-[13px] first:border-t-0">
                <span className="font-mono text-[11.5px] text-ink3 pt-0.5">{clockTime(s.createdAt)}</span>
                <i aria-hidden className={`w-2 h-2 rounded-full mt-1.5 ${DOT[s.kind] ?? "bg-blue-3"}`} />
                <span>{stepSentence(s)}</span>
              </li>
            ))}
            {run.steps.length === 0 && <li className="text-[13px] text-ink3 py-2">Starting in a sealed sandbox…</li>}
          </ol>
        </Panel>
      </div>

      <aside className="flex flex-col gap-4">
        <Panel className="p-5 flex flex-col gap-2">
          <PanelTitle>Spend</PanelTitle>
          <div className="font-mono text-sm">{inr(run.totalCharged)} of {inr(run.costCap)}</div>
          <ProgressBar value={run.totalCharged} max={run.costCap} label="Charged against this run's limit" />
          <Kv label="Held in your wallet" value={inr(terminal ? 0 : Math.max(0, run.costCap - run.totalCharged))} />
        </Panel>
        {role === "BUYER_ADMIN" && terminal && run.totalCharged > 0 && (
          <Panel className="p-5 flex flex-col gap-2">
            <PanelTitle>Something wrong?</PanelTitle>
            <DisputePanel runId={runId} />
          </Panel>
        )}
      </aside>
    </div>
  );
}
```

`page.tsx` already passes `role` and needs no change. Delete `src/components/RunTraceStep.tsx` and `src/components/RunTraceStep.test.tsx`.

- [ ] **Step 6: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): run console with the decision first, a recorded timeline, spend and disputes"
```

---

### Task 8: Approve from a phone

**Files:**
- Create: `src/app/approve/[token]/page.tsx`, `ApprovalView.tsx`, `actions.ts`
- Test: create `src/app/approve/[token]/ApprovalView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/approvals/{token}` → `ApprovalCard`, `POST /api/v1/approvals/{token}/decision {decision}` (WS14: 410 closed, 401 sign-in required, 403 `NOT_AN_APPROVER`).
- Produces: `decideApprovalAction(token, decision: "APPROVE" | "REJECT"): ActionResult<{ card: ApprovalCard }>`, `ApprovalView({ token, card, now? })`.

- [ ] **Step 1: Write the failing test**

`src/app/approve/[token]/ApprovalView.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ApprovalView } from "./ApprovalView";
import type { ApprovalCard } from "@/lib/types";

vi.mock("./actions", () => ({
  decideApprovalAction: vi.fn(),
}));

const now = new Date("2026-09-23T04:15:00Z");
const card: ApprovalCard = {
  runId: "r1", agentName: "Bank Reconciliation", summary: "Post 142 journal entries for August",
  state: "OPEN", expiresAt: "2026-09-23T04:41:00Z", charged: 0, spendCap: 500, requiresLogin: false,
};

describe("ApprovalView", () => {
  it("asks the question with big buttons and the time left", () => {
    render(<ApprovalView token="t" card={card} now={now} />);
    expect(screen.getByText("Bank Reconciliation asks")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Post 142 journal entries for August?" })).toBeInTheDocument();
    expect(screen.getByText(/stops by itself in 26 minutes/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  });

  it("shows the outcome after approving", async () => {
    const { decideApprovalAction } = await import("./actions");
    vi.mocked(decideApprovalAction).mockResolvedValueOnce({ ok: true, card: { ...card, state: "APPROVED" } });
    render(<ApprovalView token="t" card={card} now={now} />);
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(decideApprovalAction).toHaveBeenCalledWith("t", "APPROVE");
    expect(await screen.findByRole("status")).toHaveTextContent("Approved");
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("turns a late tap into a closed card, not an error page", async () => {
    const { decideApprovalAction } = await import("./actions");
    vi.mocked(decideApprovalAction).mockResolvedValueOnce({ ok: false, error: "This request has closed.", code: "410" });
    render(<ApprovalView token="t" card={card} now={now} />);
    await userEvent.click(screen.getByRole("button", { name: "Reject" }));

    expect(await screen.findByText(/This request has closed/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  it("asks to sign in when the workspace requires it", async () => {
    const { decideApprovalAction } = await import("./actions");
    vi.mocked(decideApprovalAction).mockResolvedValueOnce({ ok: false, error: "Sign in to decide.", code: "401" });
    render(<ApprovalView token="t" card={card} now={now} />);
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(await screen.findByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login?next=/approve/t");
  });

  it("renders a closed card without buttons", () => {
    render(<ApprovalView token="t" card={{ ...card, state: "CLOSED" }} now={now} />);
    expect(screen.getByText(/This request has closed/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- src/app/approve`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement**

`src/app/approve/[token]/actions.ts`:

```ts
"use server";

import { backend, BackendError } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import type { ApprovalCard } from "@/lib/types";

export async function decideApprovalAction(token: string, decision: "APPROVE" | "REJECT"): Promise<ActionResult<{ card: ApprovalCard }>> {
  try {
    const card = await backend.post<ApprovalCard>(`/api/v1/approvals/${token}/decision`, { decision });
    return { ok: true, card };
  } catch (e) {
    const failure = actionError(e, {
      "410": "This request has closed. Someone may have decided already, or it ran out of time.",
      "401": "Your workspace asks approvers to sign in before deciding.",
      NOT_AN_APPROVER: "You're no longer one of the approvers for this agent.",
    }, "We couldn't record your decision. Try again.");
    // The view needs the status to choose between "closed" and "sign in"; keep it when there's no problem code.
    return e instanceof BackendError && !failure.code ? { ...failure, code: String(e.status) } : failure;
  }
}
```

`src/app/approve/[token]/ApprovalView.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Logo } from "@/components/ui/Logo";
import { Panel } from "@/components/ui/Panel";
import { Pill } from "@/components/ui/Pill";
import { inr, minutesUntil } from "@/lib/format";
import type { ApprovalCard } from "@/lib/types";
import { decideApprovalAction } from "./actions";

const DONE: Record<Exclude<ApprovalCard["state"], "OPEN">, string> = {
  APPROVED: "Approved. The run carries on and posts only what it drafted.",
  REJECTED: "Rejected. The drafts were thrown away and nothing was charged.",
  CLOSED: "This request has closed. Someone may have decided already, or it ran out of time.",
};

export function ApprovalView({ token, card: initial, now = new Date() }: { token: string; card: ApprovalCard; now?: Date }) {
  const [card, setCard] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<{ error: string; code?: string } | null>(null);

  async function decide(decision: "APPROVE" | "REJECT") {
    setBusy(true);
    const result = await decideApprovalAction(token, decision);
    setBusy(false);
    if (result.ok) {
      setCard(result.card);
      setProblem(null);
    } else if (result.code === "410") {
      setCard({ ...card, state: "CLOSED" });
      setProblem(null);
    } else {
      setProblem({ error: result.error, code: result.code });
    }
  }

  const open = card.state === "OPEN";
  const pill = open ? { tone: "wa" as const, text: "Needs you" }
    : card.state === "APPROVED" ? { tone: "ok" as const, text: "Approved" }
    : card.state === "REJECTED" ? { tone: "bad" as const, text: "Rejected" }
    : { tone: "n" as const, text: "Closed" };

  return (
    <main className="min-h-screen bg-canvas px-[18px] pt-5 pb-7 max-w-[420px] mx-auto flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-2 font-semibold text-[15px]"><Logo size={8} /> Karya</span>
        <Pill tone={pill.tone}>{pill.text}</Pill>
      </div>
      <div>
        <div className="text-[13px] text-ink3">{card.agentName} asks</div>
        <h1 className="text-[26px] leading-tight font-semibold tracking-tight">
          {card.summary ? `${card.summary}?` : "Allow this run to write data?"}
        </h1>
      </div>
      <Panel className="px-4 py-2">
        <Kv label="Charged so far" value={inr(card.charged)} />
        <Kv label="Most this run can cost" value={inr(card.spendCap)} />
      </Panel>
      {open && (
        <p className="text-[12.5px] text-ink3 leading-snug">
          The run is paused in a sealed sandbox and stops by itself in {minutesUntil(card.expiresAt, now)} minutes if nobody answers.
        </p>
      )}
      {problem && (
        <p role="alert" className="text-[13px] text-bad">
          {problem.error}{" "}
          {problem.code === "401" && <Link href={`/login?next=/approve/${token}`} className="text-blue font-semibold">Sign in</Link>}
        </p>
      )}
      <div className="mt-auto flex flex-col gap-2.5">
        {open ? (
          <>
            <Button size="lg" disabled={busy} onClick={() => decide("APPROVE")}>Approve</Button>
            <Button size="lg" variant="danger" disabled={busy} onClick={() => decide("REJECT")}>Reject</Button>
          </>
        ) : (
          <Panel className="p-4 text-center text-sm leading-snug">
            <span role="status">{DONE[card.state]}</span>
          </Panel>
        )}
      </div>
    </main>
  );
}
```

`src/app/approve/[token]/page.tsx`:

```tsx
import { backend, BackendError } from "@/lib/backend";
import type { ApprovalCard } from "@/lib/types";
import { ApprovalView } from "./ApprovalView";

export const metadata = { title: "Approve · Karya", robots: { index: false } };

export default async function ApprovePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  let card: ApprovalCard;
  try {
    card = await backend.get<ApprovalCard>(`/api/v1/approvals/${token}`);
  } catch (e) {
    if (!(e instanceof BackendError) || e.status !== 410) throw e;
    card = { runId: "", agentName: "Karya", summary: null, state: "CLOSED", expiresAt: new Date().toISOString(), charged: 0, spendCap: 0, requiresLogin: false };
  }
  return <ApprovalView token={token} card={card} />;
}
```

Add a viewport export for phones to `src/app/layout.tsx`:

```tsx
export const viewport = { width: "device-width", initialScale: 1 };
```

- [ ] **Step 4: Run the tests**

Run: `npm test -- src/app/approve && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/approve frontend/src/app/layout.tsx
git commit -m "feat(frontend): one-tap approval page for phones, with honest closed and sign-in states"
```

---

### Task 9: Notification centre

**Files:**
- Create: `src/lib/notificationCopy.ts`, `src/app/(account)/notifications/page.tsx`, `NotificationsView.tsx`, `actions.ts`
- Test: create `src/lib/notificationCopy.test.ts`, `src/app/(account)/notifications/NotificationsView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/notifications?filter=` → `NotificationPage`, `POST /api/v1/notifications/{id}/read`, `POST /api/v1/notifications/read-all` (WS14).
- Produces:
  - `notificationSentence(item): { who: string; text: string }`, `notificationLink(item): { href: string; label: string }`, `categoryTone(category): Tone`, `NOTIFICATION_FILTERS`.
  - `markReadAndOpenAction(id, href)`, `markAllReadAction()`.
  - `NotificationsView({ page, filter, settings? })`. The `settings` slot is filled in Task 10.

- [ ] **Step 1: Write the failing tests**

`src/lib/notificationCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { notificationLink, notificationSentence } from "./notificationCopy";
import type { NotificationItem } from "./types";

const item = (category: NotificationItem["category"], payload: Record<string, unknown>, subjectId = "r1"): NotificationItem => ({
  id: "n1", category, subjectType: "RUN", subjectId, payload, read: false, createdAt: "2026-09-23T04:11:00Z",
});

describe("notificationSentence", () => {
  it("reads like the mockup", () => {
    expect(notificationSentence(item("APPROVAL_NEEDED", { agentName: "Bank Reconciliation", summary: "post 142 journal entries for August" })))
      .toEqual({ who: "Bank Reconciliation", text: "wants to post 142 journal entries for August. Nothing happens until someone approves." });
    expect(notificationSentence(item("RUN_STOPPED_EARLY", { agentName: "Vendor Payment Scheduler", status: "EXPIRED" })))
      .toEqual({ who: "Vendor Payment Scheduler", text: "stopped because nobody approved in time. Nothing was written." });
    expect(notificationSentence(item("RUN_FINISHED", { agentName: "Expense Policy Checker", charged: "428.00" })))
      .toEqual({ who: "Expense Policy Checker", text: "finished. ₹428 charged." });
    expect(notificationSentence(item("SPEND_LIMITS", { threshold: 25, limit: "50000" })))
      .toEqual({ who: "Spend", text: "reached 25% of your ₹50,000 monthly limit." });
  });
});

describe("notificationLink", () => {
  it("goes where the person needs to act", () => {
    expect(notificationLink(item("APPROVAL_NEEDED", {}))).toEqual({ href: "/runs/r1", label: "Review" });
    expect(notificationLink(item("RUN_FINISHED", {}))).toEqual({ href: "/runs/r1", label: "Open run" });
    expect(notificationLink({ ...item("SPEND_LIMITS", {}), subjectType: "ORG" })).toEqual({ href: "/billing", label: "Billing" });
  });
});
```

`src/app/(account)/notifications/NotificationsView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { NotificationsView } from "./NotificationsView";
import type { NotificationPage } from "@/lib/types";

vi.mock("./actions", () => ({
  markReadAndOpenAction: vi.fn(),
  markAllReadAction: vi.fn().mockResolvedValue(undefined),
}));

const today = new Date().toISOString();
const page: NotificationPage = {
  unreadCount: 1,
  items: [
    { id: "n1", category: "APPROVAL_NEEDED", subjectType: "RUN", subjectId: "r1", payload: { agentName: "Bank Reconciliation", summary: "post 142 journal entries" }, read: false, createdAt: today },
    { id: "n2", category: "RUN_FINISHED", subjectType: "RUN", subjectId: "r2", payload: { agentName: "Expense Policy Checker", charged: "428.00" }, read: true, createdAt: "2026-09-01T04:00:00Z" },
  ],
};

describe("NotificationsView", () => {
  it("groups by day, marks unread items and counts them", () => {
    render(<NotificationsView page={page} filter="all" />);
    expect(screen.getByText("1 unread")).toBeInTheDocument();
    const today = screen.getByRole("region", { name: "Today" });
    expect(within(today).getByText("Bank Reconciliation")).toBeInTheDocument();
    expect(within(today).getByLabelText("Unread")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Earlier" })).toBeInTheDocument();
  });

  it("links filters as pages and marks the current one", () => {
    render(<NotificationsView page={page} filter="needs_you" />);
    expect(screen.getByRole("link", { name: "Needs you" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Money" })).toHaveAttribute("href", "/notifications?filter=money");
  });

  it("marks everything read", async () => {
    const { markAllReadAction } = await import("./actions");
    render(<NotificationsView page={page} filter="all" />);
    await userEvent.click(screen.getByRole("button", { name: "Mark all as read" }));
    expect(markAllReadAction).toHaveBeenCalled();
  });

  it("marks an item read on the way to its run", async () => {
    const { markReadAndOpenAction } = await import("./actions");
    render(<NotificationsView page={page} filter="all" />);
    await userEvent.click(screen.getByRole("button", { name: "Review" }));
    expect(markReadAndOpenAction).toHaveBeenCalledWith("n1", "/runs/r1");
  });

  it("says when there is nothing", () => {
    render(<NotificationsView page={{ unreadCount: 0, items: [] }} filter="all" />);
    expect(screen.getByText("Nothing here. You're all caught up.")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/notificationCopy.test.ts "src/app/(account)/notifications"`
Expected: FAIL.

- [ ] **Step 3: Copy helpers**

`src/lib/notificationCopy.ts`:

```ts
import type { IconName } from "@/components/ui/Icon";
import type { Tone } from "@/components/ui/Pill";
import { inr } from "./format";
import type { NotificationCategory, NotificationItem } from "./types";

export const NOTIFICATION_FILTERS = [
  { key: "all", label: "All" },
  { key: "needs_you", label: "Needs you" },
  { key: "agents", label: "Agents" },
  { key: "money", label: "Money" },
] as const;

export const CATEGORY_LABELS: Record<NotificationCategory, string> = {
  APPROVAL_NEEDED: "Approval needed",
  MORE_ACCESS_REQUESTED: "Agent asks for more access",
  RUN_STOPPED_EARLY: "Run stopped early",
  RUN_FINISHED: "Run finished",
  SPEND_LIMITS: "Spend limits",
  WEEKLY_SUMMARY: "Weekly summary",
};

const num = (v: unknown) => (typeof v === "number" ? v : Number(v ?? 0));

const STOPS: Record<string, string> = {
  EXPIRED: "stopped because nobody approved in time. Nothing was written.",
  TIMED_OUT: "ran out of time and stopped.",
  FAILED: "stopped with an error.",
};

export function notificationSentence(item: NotificationItem): { who: string; text: string } {
  const p = item.payload;
  const agent = typeof p.agentName === "string" ? p.agentName : "An agent";
  switch (item.category) {
    case "APPROVAL_NEEDED":
      return { who: agent, text: `wants to ${typeof p.summary === "string" && p.summary ? p.summary.replace(/^./, (c) => c.toLowerCase()) : "write data"}. Nothing happens until someone approves.` };
    case "MORE_ACCESS_REQUESTED":
      return { who: agent, text: "asks for more access. You stay on your current version until you agree." };
    case "RUN_STOPPED_EARLY":
      return { who: agent, text: STOPS[String(p.status)] ?? "stopped early." };
    case "RUN_FINISHED":
      return { who: agent, text: `finished. ${inr(num(p.charged))} charged.` };
    case "SPEND_LIMITS":
      return { who: "Spend", text: `reached ${num(p.threshold)}% of your ${inr(num(p.limit))} monthly limit.` };
    case "WEEKLY_SUMMARY":
      return { who: "Karya", text: "Your weekly summary is ready." };
  }
}

export function notificationLink(item: NotificationItem): { href: string; label: string } {
  if (item.subjectType === "RUN" && item.subjectId) {
    return { href: `/runs/${item.subjectId}`, label: item.category === "APPROVAL_NEEDED" ? "Review" : "Open run" };
  }
  if (item.category === "SPEND_LIMITS") return { href: "/billing", label: "Billing" };
  return { href: "/home", label: "Open" };
}

export function categoryLook(category: NotificationCategory): { tone: Tone; icon: IconName } {
  switch (category) {
    case "APPROVAL_NEEDED":
      return { tone: "wa", icon: "gate" };
    case "MORE_ACCESS_REQUESTED":
      return { tone: "b", icon: "layers" };
    case "RUN_STOPPED_EARLY":
      return { tone: "bad", icon: "stop" };
    case "RUN_FINISHED":
      return { tone: "ok", icon: "check" };
    case "SPEND_LIMITS":
      return { tone: "wa", icon: "billing" };
    case "WEEKLY_SUMMARY":
      return { tone: "n", icon: "clock" };
  }
}
```

- [ ] **Step 4: Actions, view and page**

`src/app/(account)/notifications/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { safeNextPath } from "@/lib/routing";

export async function markReadAndOpenAction(id: string, href: string) {
  await backend.post(`/api/v1/notifications/${id}/read`).catch(() => undefined);
  redirect(safeNextPath(href) ?? "/notifications");
}

export async function markAllReadAction() {
  await backend.post("/api/v1/notifications/read-all");
  revalidatePath("/notifications");
}
```

`src/app/(account)/notifications/NotificationsView.tsx`:

```tsx
"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Panel } from "@/components/ui/Panel";
import { clockTime, dayLabel } from "@/lib/format";
import { categoryLook, NOTIFICATION_FILTERS, notificationLink, notificationSentence } from "@/lib/notificationCopy";
import type { NotificationPage } from "@/lib/types";
import { markAllReadAction, markReadAndOpenAction } from "./actions";

const TONE_BG = { b: "bg-sky text-blue", ok: "bg-ok-soft text-ok", wa: "bg-wa-soft text-wa", bad: "bg-bad-soft text-bad", n: "bg-line2 text-ink2" };

export function NotificationsView({ page, filter, settings }: { page: NotificationPage; filter: string; settings?: ReactNode }) {
  const groups = [
    { label: "Today", items: page.items.filter((n) => dayLabel(n.createdAt) === "Today") },
    { label: "Earlier", items: page.items.filter((n) => dayLabel(n.createdAt) !== "Today") },
  ].filter((g) => g.items.length > 0);

  return (
    <div className="grid grid-cols-[minmax(0,1fr)_400px] gap-7 items-start">
      <div className="flex flex-col gap-4 min-w-0">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight">Notifications</h1>
          <p className="text-ink2">What your agents need from you, and what they did.</p>
        </div>
        <div className="flex items-center gap-2">
          <nav aria-label="Filter notifications" className="flex gap-2">
            {NOTIFICATION_FILTERS.map((f) => (
              <Link
                key={f.key}
                href={f.key === "all" ? "/notifications" : `/notifications?filter=${f.key}`}
                aria-current={filter === f.key ? "page" : undefined}
                className={`h-8 px-3 rounded-full text-[13px] border grid place-items-center ${filter === f.key ? "bg-sky border-blue-3 text-blue font-semibold" : "border-line text-ink2"}`}
              >
                {f.label}
              </Link>
            ))}
          </nav>
          <span className="ml-auto text-xs text-ink3">{page.unreadCount ? `${page.unreadCount} unread` : "All read"}</span>
          {page.unreadCount > 0 && <Button variant="secondary" size="sm" onClick={() => markAllReadAction()}>Mark all as read</Button>}
        </div>

        {groups.length === 0 && <Panel className="p-6 text-[13px] text-ink3">Nothing here. You&apos;re all caught up.</Panel>}
        {groups.map((g) => (
          <section key={g.label} aria-label={g.label} className="flex flex-col gap-2">
            <h2 className="text-xs uppercase tracking-wider text-ink3">{g.label}</h2>
            <Panel className="overflow-hidden">
              {g.items.map((n) => {
                const look = categoryLook(n.category);
                const s = notificationSentence(n);
                const link = notificationLink(n);
                return (
                  <div key={n.id} className="flex items-start gap-3 px-5 py-4 border-t border-line2 first:border-t-0">
                    <span className={`w-8 h-8 rounded-lg grid place-items-center shrink-0 ${TONE_BG[look.tone]}`}>
                      <Icon name={look.icon} className="w-4 h-4" />
                    </span>
                    <div className="flex-1 min-w-0 text-[13px]">
                      <p className={n.read ? "text-ink2" : ""}>
                        <b className="font-semibold text-ink">{s.who}</b> {s.text}
                      </p>
                      <p className="text-xs text-ink3 mt-0.5 font-mono">{g.label === "Today" ? clockTime(n.createdAt) : dayLabel(n.createdAt)}</p>
                    </div>
                    {!n.read && <i aria-label="Unread" role="img" className="w-2 h-2 rounded-full bg-blue mt-2" />}
                    <button type="button" onClick={() => markReadAndOpenAction(n.id, link.href)} className="text-blue font-semibold text-[13px] shrink-0">
                      {link.label}
                    </button>
                  </div>
                );
              })}
            </Panel>
          </section>
        ))}
      </div>
      <div id="where">{settings}</div>
    </div>
  );
}
```

`src/app/(account)/notifications/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { NOTIFICATION_FILTERS } from "@/lib/notificationCopy";
import type { NotificationPage } from "@/lib/types";
import { NotificationsView } from "./NotificationsView";

export default async function NotificationsPage({ searchParams }: { searchParams: Promise<{ filter?: string }> }) {
  const sp = await searchParams;
  const filter = NOTIFICATION_FILTERS.some((f) => f.key === sp.filter) ? (sp.filter as string) : "all";
  const page = await backend.get<NotificationPage>(`/api/v1/notifications?filter=${filter}`);
  return <NotificationsView page={page} filter={filter} />;
}
```

- [ ] **Step 5: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): notification centre grouped by day, with filters and mark-as-read"
```

---

### Task 10: Where we reach you (preferences, quiet hours, Slack)

**Files:**
- Create: `src/app/(account)/notifications/NotificationSettings.tsx`, `settingsActions.ts`, `src/app/(account)/settings/notifications/page.tsx`
- Modify: `src/app/(account)/notifications/page.tsx`
- Test: create `src/app/(account)/notifications/NotificationSettings.test.tsx`

**Interfaces:**
- Consumes:
  - `GET/PUT /api/v1/me/notification-preferences`, `GET/PUT /api/v1/me/quiet-hours` (WS14);
  - `GET /api/v1/orgs/{orgId}/integrations/slack/install-url`, `POST /api/v1/me/slack-link` (`SLACK_NOT_INSTALLED`, `SLACK_USER_NOT_FOUND`);
  - `CATEGORY_LABELS` (Task 9).
- Produces:
  - `updatePreferenceAction(row): ActionResult`, `setQuietHoursAction(view): ActionResult`, `slackInstallUrlAction(): ActionResult<{ url: string }>`, `linkSlackAction(): ActionResult`.
  - `NotificationSettings({ rows, quiet, isAdmin, slackResult? })`.
  - `/settings/notifications?slack=` redirects to `/notifications?slack=…#where`. That is where WS14's Slack callback lands.

- [ ] **Step 1: Write the failing test**

`src/app/(account)/notifications/NotificationSettings.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { NotificationSettings } from "./NotificationSettings";
import type { PreferenceRow, QuietHoursView } from "@/lib/types";

vi.mock("./settingsActions", () => ({
  updatePreferenceAction: vi.fn().mockResolvedValue({ ok: true }),
  setQuietHoursAction: vi.fn().mockResolvedValue({ ok: true }),
  slackInstallUrlAction: vi.fn().mockResolvedValue({ ok: true, url: "https://slack.com/oauth/v2/authorize?x" }),
  linkSlackAction: vi.fn().mockResolvedValue({ ok: false, error: "Your workspace admin hasn't connected Slack yet." }),
}));

const rows: PreferenceRow[] = [
  { category: "APPROVAL_NEEDED", web: true, email: true, slack: true, webLocked: true },
  { category: "RUN_FINISHED", web: true, email: false, slack: false, webLocked: false },
];
const quiet: QuietHoursView = { enabled: false, start: "20:00:00", end: "08:00:00", timezone: "Asia/Kolkata" };

describe("NotificationSettings", () => {
  it("locks Karya on for approvals and saves other toggles", async () => {
    const { updatePreferenceAction } = await import("./settingsActions");
    render(<NotificationSettings rows={rows} quiet={quiet} isAdmin={false} />);

    const approval = screen.getByRole("row", { name: /Approval needed/ });
    expect(within(approval).getByRole("checkbox", { name: "Approval needed on Karya" })).toBeDisabled();
    expect(within(approval).getByText("Karya is always on for this")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("checkbox", { name: "Run finished by email" }));
    expect(updatePreferenceAction).toHaveBeenCalledWith({ category: "RUN_FINISHED", web: true, email: true, slack: false, webLocked: false });
  });

  it("saves quiet hours", async () => {
    const { setQuietHoursAction } = await import("./settingsActions");
    render(<NotificationSettings rows={rows} quiet={quiet} isAdmin={false} />);
    await userEvent.click(screen.getByRole("checkbox", { name: /Quiet hours/ }));
    await userEvent.click(screen.getByRole("button", { name: "Save quiet hours" }));
    expect(setQuietHoursAction).toHaveBeenCalledWith({ enabled: true, start: "20:00", end: "08:00", timezone: "Asia/Kolkata" });
  });

  it("explains why Slack could not be linked, and offers admins to connect it", async () => {
    render(<NotificationSettings rows={rows} quiet={quiet} isAdmin />);
    expect(screen.getByRole("button", { name: "Connect Slack" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Link my Slack account" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("hasn't connected Slack");
  });

  it("confirms a finished Slack install", () => {
    render(<NotificationSettings rows={rows} quiet={quiet} isAdmin slackResult="connected" />);
    expect(screen.getByRole("status")).toHaveTextContent("Slack is connected");
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(account)/notifications/NotificationSettings"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(account)/notifications/settingsActions.ts`:

```ts
"use server";

import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import { getSession } from "@/lib/session";
import type { PreferenceRow, QuietHoursView } from "@/lib/types";

export async function updatePreferenceAction(row: PreferenceRow): Promise<ActionResult> {
  try {
    await backend.put("/api/v1/me/notification-preferences", [row]);
    return { ok: true };
  } catch (e) {
    return actionError(e, {}, "We couldn't save that. Try again.");
  }
}

export async function setQuietHoursAction(view: QuietHoursView): Promise<ActionResult> {
  try {
    await backend.put("/api/v1/me/quiet-hours", view);
    return { ok: true };
  } catch (e) {
    return actionError(e, { "400": "Pick a start and an end time." }, "We couldn't save quiet hours.");
  }
}

export async function slackInstallUrlAction(): Promise<ActionResult<{ url: string }>> {
  const session = await getSession();
  if (!session) return { ok: false, error: "Sign in again." };
  try {
    const { url } = await backend.get<{ url: string }>(`/api/v1/orgs/${session.orgId}/integrations/slack/install-url`);
    return { ok: true, url };
  } catch (e) {
    return actionError(e, { "403": "Only workspace admins can connect Slack." }, "We couldn't start the Slack connection.");
  }
}

export async function linkSlackAction(): Promise<ActionResult> {
  try {
    await backend.post("/api/v1/me/slack-link");
    return { ok: true };
  } catch (e) {
    return actionError(e, {
      SLACK_NOT_INSTALLED: "Your workspace admin hasn't connected Slack yet.",
      SLACK_USER_NOT_FOUND: "No one in your Slack workspace uses your Karya email address.",
    }, "We couldn't link your Slack account.");
  }
}
```

`src/app/(account)/notifications/NotificationSettings.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { CATEGORY_LABELS } from "@/lib/notificationCopy";
import type { PreferenceRow, QuietHoursView } from "@/lib/types";
import { linkSlackAction, setQuietHoursAction, slackInstallUrlAction, updatePreferenceAction } from "./settingsActions";

type Channel = "web" | "email" | "slack";
const CHANNELS: [Channel, string, string][] = [["web", "Karya", "on Karya"], ["email", "Email", "by email"], ["slack", "Slack", "on Slack"]];
const hhmm = (t: string) => t.slice(0, 5);

export function NotificationSettings({
  rows: initialRows,
  quiet: initialQuiet,
  isAdmin,
  slackResult,
}: {
  rows: PreferenceRow[];
  quiet: QuietHoursView;
  isAdmin: boolean;
  slackResult?: string;
}) {
  const [rows, setRows] = useState(initialRows);
  const [quiet, setQuiet] = useState({ ...initialQuiet, start: hhmm(initialQuiet.start), end: hhmm(initialQuiet.end) });
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);

  async function toggle(row: PreferenceRow, channel: Channel) {
    const next = { ...row, [channel]: !row[channel] };
    setRows((rs) => rs.map((r) => (r.category === row.category ? next : r)));
    const result = await updatePreferenceAction(next);
    if (!result.ok) {
      setRows((rs) => rs.map((r) => (r.category === row.category ? row : r)));
      setMessage({ tone: "bad", text: result.error });
    }
  }

  async function saveQuiet() {
    const result = await setQuietHoursAction(quiet);
    setMessage(result.ok ? { tone: "ok", text: "Quiet hours saved." } : { tone: "bad", text: result.error });
  }

  async function connectSlack() {
    const result = await slackInstallUrlAction();
    if (result.ok) window.location.assign(result.url);
    else setMessage({ tone: "bad", text: result.error });
  }

  async function linkSlack() {
    const result = await linkSlackAction();
    setMessage(result.ok ? { tone: "ok", text: "Your Slack account is linked." } : { tone: "bad", text: result.error });
  }

  return (
    <Panel className="p-5 flex flex-col gap-4">
      <div>
        <PanelTitle>Where we reach you</PanelTitle>
        <p className="text-xs text-ink3 mt-1">Approvals always reach you somewhere. Approval links in email and Slack work with one tap on a phone.</p>
      </div>
      {slackResult === "connected" && <p role="status" className="text-[13px] text-ok">Slack is connected. Each person links their own account below.</p>}
      {slackResult === "failed" && <p role="alert" className="text-[13px] text-bad">Slack couldn&apos;t be connected. Try again.</p>}

      <table className="w-full text-[13px]">
        <thead>
          <tr className="text-xs text-ink3">
            <th className="text-left font-normal pb-2">When</th>
            {CHANNELS.map(([, label]) => <th key={label} className="font-normal pb-2 w-14">{label}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const label = CATEGORY_LABELS[row.category];
            return (
              <tr key={row.category} aria-label={label} className="border-t border-line2">
                <td className="py-2.5">
                  {label}
                  {row.webLocked && <span className="block text-xs text-ink3">Karya is always on for this</span>}
                </td>
                {CHANNELS.map(([channel, , words]) => (
                  <td key={channel} className="text-center">
                    <input
                      type="checkbox"
                      aria-label={`${label} ${words}`}
                      className="w-4 h-4 accent-blue"
                      checked={row[channel]}
                      disabled={channel === "web" && row.webLocked}
                      onChange={() => toggle(row, channel)}
                    />
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>

      <div className="flex flex-col gap-2 border-t border-line2 pt-4">
        <label className="flex items-center gap-2 text-[13px]">
          <input type="checkbox" className="w-4 h-4 accent-blue" checked={quiet.enabled} onChange={(e) => setQuiet({ ...quiet, enabled: e.target.checked })} />
          Quiet hours. Approvals still come through.
        </label>
        <div className="flex items-center gap-2 text-[13px]">
          <input type="time" aria-label="Quiet from" value={quiet.start} onChange={(e) => setQuiet({ ...quiet, start: e.target.value })} className="h-9 px-2 rounded-lg border border-line" />
          <span>to</span>
          <input type="time" aria-label="Quiet until" value={quiet.end} onChange={(e) => setQuiet({ ...quiet, end: e.target.value })} className="h-9 px-2 rounded-lg border border-line" />
          <Button size="sm" variant="secondary" onClick={saveQuiet}>Save quiet hours</Button>
        </div>
      </div>

      <div className="flex flex-wrap gap-2 border-t border-line2 pt-4">
        {isAdmin && <Button size="sm" variant="secondary" onClick={connectSlack}>Connect Slack</Button>}
        <Button size="sm" variant="secondary" onClick={linkSlack}>Link my Slack account</Button>
      </div>

      {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}
      <p className="text-xs text-ink3">If nobody approves within 30 minutes, the run stops by itself and you get a note. Nothing is ever written without a yes.</p>
    </Panel>
  );
}
```

The quiet-hours checkbox label starts with "Quiet hours", which the test's `/Quiet hours/` matcher finds.

Replace `src/app/(account)/notifications/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { NOTIFICATION_FILTERS } from "@/lib/notificationCopy";
import { getSession } from "@/lib/session";
import type { NotificationPage, PreferenceRow, QuietHoursView } from "@/lib/types";
import { NotificationSettings } from "./NotificationSettings";
import { NotificationsView } from "./NotificationsView";

export default async function NotificationsPage({ searchParams }: { searchParams: Promise<{ filter?: string; slack?: string }> }) {
  const sp = await searchParams;
  const filter = NOTIFICATION_FILTERS.some((f) => f.key === sp.filter) ? (sp.filter as string) : "all";
  const session = await getSession();
  const [page, rows, quiet] = await Promise.all([
    backend.get<NotificationPage>(`/api/v1/notifications?filter=${filter}`),
    backend.get<PreferenceRow[]>("/api/v1/me/notification-preferences"),
    backend.get<QuietHoursView>("/api/v1/me/quiet-hours"),
  ]);
  return (
    <NotificationsView
      page={page}
      filter={filter}
      settings={<NotificationSettings rows={rows} quiet={quiet} isAdmin={session?.role === "BUYER_ADMIN"} slackResult={sp.slack} />}
    />
  );
}
```

`src/app/(account)/settings/notifications/page.tsx`:

```tsx
import { redirect } from "next/navigation";

/** WS14's Slack callback lands here; the settings live on the notifications page. */
export default async function NotificationSettingsRedirect({ searchParams }: { searchParams: Promise<{ slack?: string }> }) {
  const { slack } = await searchParams;
  redirect(`/notifications${slack ? `?slack=${encodeURIComponent(slack)}` : ""}#where`);
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): where-we-reach-you settings: channels per category, quiet hours and Slack"
```

---

### Task 11: Team settings

**Files:**
- Create: `src/app/(account)/settings/team/page.tsx`, `TeamView.tsx`, `actions.ts`
- Test: create `src/app/(account)/settings/team/TeamView.test.tsx`

**Interfaces:**
- Consumes: WS13 endpoints:
  - `GET /api/v1/orgs/{orgId}/members` → `Member[]`
  - `GET /api/v1/orgs/{orgId}/invitations` → `InvitationView[]` (403 for non-managers)
  - `POST /api/v1/orgs/{orgId}/invitations {email, role, title}`
  - `PATCH /api/v1/orgs/{orgId}/members/{userId} {role?, title?}` and `DELETE` on the same path (409 last admin)
  - `DELETE /api/v1/orgs/{orgId}/invitations/{id}`
- Also consumes `roleLabel`, `assignableRoles`, `canManageMembers` (Task 2).
- Produces:
  - `inviteAction(input): ActionResult`, `updateMemberAction(userId, change): ActionResult`, `removeMemberAction(userId): ActionResult`, `revokeInviteAction(id): ActionResult`.
  - `TeamView({ orgType, myRole, myUserId, members, invitations, welcome })`.

- [ ] **Step 1: Write the failing test**

`src/app/(account)/settings/team/TeamView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TeamView } from "./TeamView";
import type { InvitationView, Member } from "@/lib/types";

vi.mock("./actions", () => ({
  inviteAction: vi.fn().mockResolvedValue({ ok: true }),
  updateMemberAction: vi.fn().mockResolvedValue({ ok: false, error: "A workspace needs at least one admin." }),
  removeMemberAction: vi.fn().mockResolvedValue({ ok: true }),
  revokeInviteAction: vi.fn().mockResolvedValue({ ok: true }),
}));

const members: Member[] = [
  { userId: "u1", fullName: "Priya Shah", email: "priya@m.example", role: "BUYER_ADMIN", title: "Finance lead" },
  { userId: "u2", fullName: "Arjun Mehta", email: "arjun@m.example", role: "BUYER_APPROVER", title: "Accounts manager" },
];
const invitations: InvitationView[] = [{ id: "i1", email: "kiran@m.example", role: "BUYER_APPROVER", title: null, expiresAt: "2026-09-30T00:00:00Z" }];

describe("TeamView", () => {
  it("lists people with their titles and roles", () => {
    render(<TeamView orgType="BUYER" myRole="BUYER_ADMIN" myUserId="u1" members={members} invitations={invitations} welcome={false} />);
    const arjun = screen.getByRole("row", { name: /Arjun Mehta/ });
    expect(within(arjun).getByText("Accounts manager")).toBeInTheDocument();
    expect(within(arjun).getByRole("combobox", { name: "Role for Arjun Mehta" })).toHaveValue("BUYER_APPROVER");
  });

  it("invites with a buyer role and a title", async () => {
    const { inviteAction } = await import("./actions");
    render(<TeamView orgType="BUYER" myRole="BUYER_ADMIN" myUserId="u1" members={members} invitations={[]} welcome={false} />);
    await userEvent.type(screen.getByLabelText("Work email"), "kiran@m.example");
    await userEvent.selectOptions(screen.getByLabelText("Role"), "BUYER_APPROVER");
    await userEvent.type(screen.getByLabelText("Title (optional)"), "Controller");
    await userEvent.click(screen.getByRole("button", { name: "Send invitation" }));

    expect(inviteAction).toHaveBeenCalledWith({ email: "kiran@m.example", role: "BUYER_APPROVER", title: "Controller" });
    expect(await screen.findByRole("status")).toHaveTextContent("Invitation sent to kiran@m.example");
  });

  it("explains a refused role change", async () => {
    render(<TeamView orgType="BUYER" myRole="BUYER_ADMIN" myUserId="u1" members={members} invitations={[]} welcome={false} />);
    await userEvent.selectOptions(screen.getByRole("combobox", { name: "Role for Priya Shah" }), "BUYER_APPROVER");
    expect(await screen.findByRole("alert")).toHaveTextContent("at least one admin");
  });

  it("asks before removing someone", async () => {
    const { removeMemberAction } = await import("./actions");
    render(<TeamView orgType="BUYER" myRole="BUYER_ADMIN" myUserId="u1" members={members} invitations={[]} welcome={false} />);
    await userEvent.click(screen.getByRole("button", { name: "Remove Arjun Mehta" }));
    await userEvent.click(screen.getByRole("button", { name: "Yes, remove" }));
    expect(removeMemberAction).toHaveBeenCalledWith("u2");
  });

  it("is read-only for approvers", () => {
    render(<TeamView orgType="BUYER" myRole="BUYER_APPROVER" myUserId="u2" members={members} invitations={[]} welcome={false} />);
    expect(screen.queryByRole("button", { name: "Send invitation" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("welcomes a new workspace", () => {
    render(<TeamView orgType="BUYER" myRole="BUYER_ADMIN" myUserId="u1" members={members.slice(0, 1)} invitations={[]} welcome />);
    expect(screen.getByText(/Next, add teammates and choose who approves/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `npm test -- "src/app/(account)/settings/team"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`src/app/(account)/settings/team/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import { getSession } from "@/lib/session";
import type { UserRole } from "@/lib/types";

const MESSAGES = {
  "409": "A workspace needs at least one admin.",
  "403": "Only workspace admins can change the team.",
  "400": "That role isn't available in this workspace.",
};

async function orgPath(rest: string): Promise<string> {
  const session = await getSession();
  if (!session) throw new Error("No session");
  return `/api/v1/orgs/${session.orgId}${rest}`;
}

async function run(call: () => Promise<unknown>, extra: Record<string, string> = {}): Promise<ActionResult> {
  try {
    await call();
  } catch (e) {
    return actionError(e, { ...MESSAGES, ...extra }, "We couldn't save that change.");
  }
  revalidatePath("/settings/team");
  return { ok: true };
}

export async function inviteAction(input: { email: string; role: UserRole; title: string }): Promise<ActionResult> {
  return run(async () => backend.post(await orgPath("/invitations"), { ...input, title: input.title || null }),
    { "409": "That person is already in this workspace." });
}

export async function updateMemberAction(userId: string, change: { role?: UserRole; title?: string }): Promise<ActionResult> {
  return run(async () => backend.patch(await orgPath(`/members/${userId}`), change));
}

export async function removeMemberAction(userId: string): Promise<ActionResult> {
  return run(async () => backend.delete(await orgPath(`/members/${userId}`)));
}

export async function revokeInviteAction(id: string): Promise<ActionResult> {
  return run(async () => backend.delete(await orgPath(`/invitations/${id}`)));
}
```

`src/app/(account)/settings/team/TeamView.tsx`:

```tsx
"use client";

import { useState } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { dayLabel } from "@/lib/format";
import { assignableRoles, canManageMembers, roleLabel } from "@/lib/roles";
import type { InvitationView, Member, OrgType, UserRole } from "@/lib/types";
import { inviteAction, removeMemberAction, revokeInviteAction, updateMemberAction } from "./actions";

export function TeamView({
  orgType, myRole, myUserId, members, invitations, welcome,
}: {
  orgType: OrgType; myRole: UserRole; myUserId: string; members: Member[]; invitations: InvitationView[]; welcome: boolean;
}) {
  const manage = canManageMembers(myRole);
  const roles = assignableRoles(orgType);
  const [message, setMessage] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [invite, setInvite] = useState({ email: "", role: roles[roles.length - 1], title: "" });

  const report = (r: { ok: boolean; error?: string }, ok: string) =>
    setMessage(r.ok ? { tone: "ok", text: ok } : { tone: "bad", text: r.error ?? "Something went wrong." });

  return (
    <div className="flex flex-col gap-6 max-w-[900px]">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Team</h1>
        <p className="text-ink2">Who is in this workspace and what they can do.</p>
      </div>
      {welcome && (
        <Panel className="p-4 border-blue-3 bg-sky text-[13px]">
          Your workspace is ready. Next, add teammates and choose who approves. Adding money to the wallet comes last.
        </Panel>
      )}
      {message && <p role={message.tone === "bad" ? "alert" : "status"} className={`text-[13px] ${message.tone === "bad" ? "text-bad" : "text-ok"}`}>{message.text}</p>}

      <Panel className="overflow-hidden">
        <div className="px-5 py-4"><PanelTitle hint={`${members.length} ${members.length === 1 ? "person" : "people"}`}>Members</PanelTitle></div>
        <table className="w-full text-[13px]">
          <tbody>
            {members.map((m) => {
              const name = m.fullName ?? m.email;
              return (
                <tr key={m.userId} aria-label={name} className="border-t border-line2">
                  <td className="px-5 py-3">
                    <span className="flex items-center gap-3">
                      <Avatar name={name} />
                      <span>
                        <span className="block font-medium">{name}{m.userId === myUserId ? " (you)" : ""}</span>
                        <span className="text-xs text-ink3">{m.email}</span>
                      </span>
                    </span>
                  </td>
                  <td className="py-3 text-ink2">{m.title ?? ""}</td>
                  <td className="py-3">
                    {manage ? (
                      <select
                        aria-label={`Role for ${name}`}
                        defaultValue={m.role}
                        onChange={async (e) => report(await updateMemberAction(m.userId, { role: e.target.value as UserRole }), "Role updated.")}
                        className="h-8 px-2 rounded-lg border border-line bg-surface"
                      >
                        {roles.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
                      </select>
                    ) : (
                      roleLabel(m.role)
                    )}
                  </td>
                  <td className="py-3 pr-5 text-right">
                    {manage && (confirming === m.userId ? (
                      <span className="inline-flex gap-2">
                        <Button size="sm" variant="danger" onClick={async () => { setConfirming(null); report(await removeMemberAction(m.userId), `${name} was removed.`); }}>Yes, remove</Button>
                        <Button size="sm" variant="secondary" onClick={() => setConfirming(null)}>Keep</Button>
                      </span>
                    ) : (
                      <Button size="sm" variant="secondary" aria-label={`Remove ${name}`} onClick={() => setConfirming(m.userId)}>Remove</Button>
                    ))}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Panel>

      {manage && (
        <Panel className="p-5 flex flex-col gap-4">
          <PanelTitle>Invite someone</PanelTitle>
          <form
            className="grid grid-cols-[minmax(0,1.4fr)_160px_minmax(0,1fr)_auto] gap-3 items-end"
            onSubmit={async (e) => {
              e.preventDefault();
              const r = await inviteAction(invite);
              report(r, `Invitation sent to ${invite.email}. It works for 7 days.`);
              if (r.ok) setInvite({ ...invite, email: "", title: "" });
            }}
          >
            <label className="flex flex-col gap-1 text-[13px]">
              <span className="text-ink2">Work email</span>
              <input type="email" required value={invite.email} onChange={(e) => setInvite({ ...invite, email: e.target.value })} className="h-9 px-3 rounded-lg border border-line" />
            </label>
            <label className="flex flex-col gap-1 text-[13px]">
              <span className="text-ink2">Role</span>
              <select value={invite.role} onChange={(e) => setInvite({ ...invite, role: e.target.value as UserRole })} className="h-9 px-2 rounded-lg border border-line bg-surface">
                {roles.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-[13px]">
              <span className="text-ink2">Title (optional)</span>
              <input value={invite.title} onChange={(e) => setInvite({ ...invite, title: e.target.value })} placeholder="Accounts manager" className="h-9 px-3 rounded-lg border border-line" />
            </label>
            <Button type="submit">Send invitation</Button>
          </form>
          {invitations.length > 0 && (
            <ul className="flex flex-col">
              {invitations.map((i) => (
                <li key={i.id} className="flex items-center gap-3 py-2 border-t border-line2 text-[13px]">
                  <span className="flex-1">{i.email} <span className="text-ink3">· {roleLabel(i.role)} · expires {dayLabel(i.expiresAt)}</span></span>
                  <Button size="sm" variant="secondary" onClick={async () => report(await revokeInviteAction(i.id), "Invitation withdrawn.")}>Withdraw</Button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      )}
    </div>
  );
}
```

`src/app/(account)/settings/team/page.tsx`:

```tsx
import { backend, BackendError } from "@/lib/backend";
import { canManageMembers } from "@/lib/roles";
import { getSession } from "@/lib/session";
import type { InvitationView, Member, MeResponse } from "@/lib/types";
import { TeamView } from "./TeamView";

export default async function TeamPage({ searchParams }: { searchParams: Promise<{ welcome?: string }> }) {
  const sp = await searchParams;
  const session = await getSession();
  if (!session) return null;
  const [me, members] = await Promise.all([
    backend.get<MeResponse>("/api/v1/me"),
    backend.get<Member[]>(`/api/v1/orgs/${session.orgId}/members`),
  ]);
  let invitations: InvitationView[] = [];
  if (canManageMembers(session.role)) {
    invitations = await backend.get<InvitationView[]>(`/api/v1/orgs/${session.orgId}/invitations`).catch((e) => {
      if (e instanceof BackendError && e.status === 403) return [];
      throw e;
    });
  }
  const active = me.memberships.find((m) => m.orgId === session.orgId);
  return (
    <TeamView
      orgType={active?.orgType ?? "BUYER"}
      myRole={session.role}
      myUserId={session.userId}
      members={members}
      invitations={invitations}
      welcome={sp.welcome === "1"}
    />
  );
}
```

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): team settings: members with titles and roles, invitations, safe removal"
```

---

### Task 12: Billing and wallet

**Files:**
- Modify: `src/lib/statement.ts`, `src/lib/statement.test.ts`, `src/app/(buyer)/billing/page.tsx`
- Create: `src/app/(buyer)/billing/BillingView.tsx`, `src/app/(buyer)/billing/actions.ts`
- Test: create `src/app/(buyer)/billing/BillingView.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/orgs/{orgId}/wallet` → `WalletResponse` (incl. `reserved`), `GET /api/v1/orgs/{orgId}/statement`, `GET /api/v1/orgs/{orgId}/home` (for `spend`), `GET /api/v1/orgs/{orgId}/disputes` → `DisputeView[]`, `PUT /api/v1/orgs/{orgId}/spend-limit {monthlyLimit}`.
- Produces:
  - `groupStatement(lines): StatementGroup[]` with `StatementGroup = { agentName: string; total: number; runs: number; lines: StatementLineResponse[] }`. `groupStatementByAgent` stays for any legacy caller.
  - `setSpendLimitAction(limit: number | null): ActionResult`.
  - `BillingView({ wallet, spend, lines, disputes })`.

- [ ] **Step 1: Write the failing tests**

Append to `src/lib/statement.test.ts`:

```ts
import { groupStatement } from "./statement";

describe("groupStatement", () => {
  it("groups by agent, nets refunds, counts runs and sorts by total", () => {
    const lines = [
      { runId: "r1", agentName: "Bank Reconciliation", amount: 284, type: "CHARGE" as const, createdAt: "2026-09-20T00:00:00Z" },
      { runId: "r1", agentName: "Bank Reconciliation", amount: 284, type: "REVERSAL" as const, createdAt: "2026-09-22T00:00:00Z" },
      { runId: "r2", agentName: "Bank Reconciliation", amount: 500, type: "CHARGE" as const, createdAt: "2026-09-21T00:00:00Z" },
      { runId: "r3", agentName: "Expense Policy Checker", amount: 96, type: "CHARGE" as const, createdAt: "2026-09-23T00:00:00Z" },
    ];
    const groups = groupStatement(lines);
    expect(groups.map((g) => [g.agentName, g.total, g.runs])).toEqual([
      ["Bank Reconciliation", 500, 2],
      ["Expense Policy Checker", 96, 1],
    ]);
    expect(groups[0].lines[0].createdAt).toBe("2026-09-22T00:00:00Z");
  });
});
```

(If `describe`/`expect` are not yet imported at the top of that file, extend its existing `vitest` import.)

`src/app/(buyer)/billing/BillingView.test.tsx`:

```tsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { BillingView } from "./BillingView";
import type { DisputeView, StatementLineResponse } from "@/lib/types";

vi.mock("./actions", () => ({ setSpendLimitAction: vi.fn().mockResolvedValue({ ok: true }) }));

const lines: StatementLineResponse[] = [
  { runId: "0f8a2c4e-1", agentName: "Bank Reconciliation", amount: 284, type: "CHARGE", createdAt: "2026-09-20T04:00:00Z" },
  { runId: "1a2b3c4d-2", agentName: "Vendor Payment Scheduler", amount: 620, type: "CHARGE", createdAt: "2026-09-23T04:00:00Z" },
];
const dispute: DisputeView = {
  id: "d1", runId: "0f8a2c4e-1", agentId: "a1", agentName: "Bank Reconciliation", buyerOrgName: "Meridian", status: "OPEN",
  reason: "WRONG_OUTPUT", details: "x", amountDisputed: 284, amountHeld: 284, amountRefunded: null, publicSummary: null,
  respondBy: "2026-09-30T00:00:00Z", decideBy: "2026-10-07T00:00:00Z", createdAt: "2026-09-23T00:00:00Z", decidedAt: null, messages: [],
};

function renderBilling() {
  return render(
    <BillingView
      wallet={{ balance: 87520, cap: null, reserved: 800 }}
      spend={{ monthToDate: 12480, monthlyLimit: 50000 }}
      lines={lines}
      disputes={[dispute]}
    />,
  );
}

describe("BillingView", () => {
  it("shows balance, what is held and what is free", () => {
    renderBilling();
    const wallet = screen.getByRole("region", { name: "Wallet" });
    expect(within(wallet).getByText("₹87,520")).toBeInTheDocument();
    expect(within(wallet).getByText("₹800")).toBeInTheDocument();
    expect(within(wallet).getByText("₹86,720")).toBeInTheDocument();
  });

  it("expands an agent's statement lines", async () => {
    renderBilling();
    const toggle = screen.getByRole("button", { name: /Vendor Payment Scheduler/ });
    expect(toggle).toHaveAttribute("aria-expanded", "true"); // largest first, open by default
    await userEvent.click(screen.getByRole("button", { name: /Bank Reconciliation/ }));
    expect(screen.getByRole("link", { name: "0f8a2c4e" })).toHaveAttribute("href", "/runs/0f8a2c4e-1");
  });

  it("changes and removes the monthly limit", async () => {
    const { setSpendLimitAction } = await import("./actions");
    renderBilling();
    await userEvent.click(screen.getByRole("button", { name: "Change limit" }));
    const input = screen.getByLabelText("Monthly limit (₹)");
    await userEvent.clear(input);
    await userEvent.type(input, "75000");
    await userEvent.click(screen.getByRole("button", { name: "Save limit" }));
    expect(setSpendLimitAction).toHaveBeenCalledWith(75000);

    await userEvent.click(screen.getByRole("button", { name: "Change limit" }));
    await userEvent.click(screen.getByRole("button", { name: "Remove limit" }));
    expect(setSpendLimitAction).toHaveBeenCalledWith(null);
  });

  it("lists open disputes with the decision date", () => {
    renderBilling();
    const disputes = screen.getByRole("region", { name: "Disputes" });
    expect(within(disputes).getByText("Waiting for the developer")).toBeInTheDocument();
    expect(within(disputes).getByText(/Karya decides by 7 Oct/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/statement.test.ts "src/app/(buyer)/billing"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Append to `src/lib/statement.ts`:

```ts
export interface StatementGroup {
  agentName: string;
  total: number;
  runs: number;
  lines: StatementLineResponse[];
}

export function groupStatement(lines: StatementLineResponse[]): StatementGroup[] {
  const groups = new Map<string, StatementGroup & { runIds: Set<string> }>();
  for (const line of lines) {
    const g = groups.get(line.agentName) ?? { agentName: line.agentName, total: 0, runs: 0, lines: [], runIds: new Set<string>() };
    g.total += line.type === "REVERSAL" ? -line.amount : line.amount;
    g.runIds.add(line.runId);
    g.lines.push(line);
    groups.set(line.agentName, g);
  }
  return [...groups.values()]
    .map(({ runIds, ...g }) => ({ ...g, runs: runIds.size, lines: [...g.lines].sort((a, b) => b.createdAt.localeCompare(a.createdAt)) }))
    .sort((a, b) => b.total - a.total);
}
```

`src/app/(buyer)/billing/actions.ts`:

```ts
"use server";

import { revalidatePath } from "next/cache";
import { backend } from "@/lib/backend";
import { actionError, type ActionResult } from "@/lib/problems";
import { getSession } from "@/lib/session";

export async function setSpendLimitAction(monthlyLimit: number | null): Promise<ActionResult> {
  const session = await getSession();
  if (!session) return { ok: false, error: "Sign in again." };
  try {
    await backend.put(`/api/v1/orgs/${session.orgId}/spend-limit`, { monthlyLimit });
  } catch (e) {
    return actionError(e, { "400": "The limit can't be negative.", "403": "Only workspace admins can change the limit." }, "We couldn't save the limit.");
  }
  revalidatePath("/billing");
  revalidatePath("/home");
  return { ok: true };
}
```

`src/app/(buyer)/billing/BillingView.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Kv } from "@/components/ui/Kv";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Pill, type Tone } from "@/components/ui/Pill";
import { ProgressBar } from "@/components/ui/ProgressBar";
import { dayLabel, inr, shortId } from "@/lib/format";
import { groupStatement } from "@/lib/statement";
import type { DisputeStatus, DisputeView, StatementLineResponse, WalletResponse, WorkspaceHome } from "@/lib/types";
import { setSpendLimitAction } from "./actions";

const DISPUTE: Record<DisputeStatus, { tone: Tone; label: string }> = {
  OPEN: { tone: "wa", label: "Waiting for the developer" },
  AWAITING_DECISION: { tone: "b", label: "Karya is deciding" },
  REFUNDED: { tone: "ok", label: "Refunded" },
  NOT_REFUNDED: { tone: "n", label: "Not refunded" },
  WITHDRAWN: { tone: "n", label: "Withdrawn" },
};

export function BillingView({
  wallet, spend, lines, disputes,
}: {
  wallet: WalletResponse; spend: WorkspaceHome["spend"]; lines: StatementLineResponse[]; disputes: DisputeView[];
}) {
  const groups = groupStatement(lines);
  const [open, setOpen] = useState<string | null>(groups[0]?.agentName ?? null);
  const [editing, setEditing] = useState(false);
  const [limit, setLimit] = useState(spend.monthlyLimit?.toString() ?? "");
  const [error, setError] = useState<string | null>(null);

  async function save(value: number | null) {
    const r = await setSpendLimitAction(value);
    if (r.ok) setEditing(false);
    else setError(r.error);
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Billing</h1>
        <p className="text-ink2">You pay per result, from a prepaid wallet.</p>
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_380px] gap-6 items-start">
        <div className="flex flex-col gap-4 min-w-0">
          <Panel aria-label="Wallet" className="p-5 grid grid-cols-3 gap-4">
            {[["Wallet balance", wallet.balance], ["Held for running agents", wallet.reserved], ["Free to use", wallet.balance - wallet.reserved]].map(([label, value]) => (
              <div key={label as string}>
                <div className="text-xs text-ink3">{label}</div>
                <div className="font-mono text-2xl font-medium mt-1">{inr(value as number)}</div>
              </div>
            ))}
            <p className="col-span-3 text-xs text-ink3">When a run starts, Karya holds its run limit. When it ends, you pay only for results and the rest is released. No agent can charge more than its limit.</p>
          </Panel>

          <Panel className="p-5 flex flex-col gap-3">
            <PanelTitle hint="All time">Statement</PanelTitle>
            {groups.length === 0 && <p className="text-[13px] text-ink3">No charges yet this month.</p>}
            {groups.map((g) => (
              <div key={g.agentName} className="border-t border-line2 first:border-t-0">
                <button
                  type="button"
                  aria-expanded={open === g.agentName}
                  onClick={() => setOpen(open === g.agentName ? null : g.agentName)}
                  className="w-full flex items-center gap-3 py-3 text-[13px]"
                >
                  <span className="font-semibold">{g.agentName}</span>
                  <span className="text-ink3">{g.runs} {g.runs === 1 ? "run" : "runs"}</span>
                  <span className="ml-auto font-mono">{inr(g.total)}</span>
                </button>
                {open === g.agentName && (
                  <ul className="pb-3">
                    {g.lines.map((l, i) => (
                      <li key={i} className="grid grid-cols-[90px_1fr_80px_90px] gap-2 py-1.5 text-[13px]">
                        <Link href={`/runs/${l.runId}`} className="font-mono text-xs text-blue">{shortId(l.runId)}</Link>
                        <span className="text-ink2">{l.type === "REVERSAL" ? "Refund" : "Charge"}</span>
                        <span className="text-xs text-ink3">{dayLabel(l.createdAt)}</span>
                        <span className="font-mono text-right">{l.type === "REVERSAL" ? "−" : ""}{inr(l.amount)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </Panel>
        </div>

        <aside className="flex flex-col gap-4">
          <Panel className="p-5 flex flex-col gap-3">
            <div className="text-xs text-ink3">Spent this month</div>
            <div className="flex items-baseline gap-2">
              <span className="font-mono text-2xl font-medium">{inr(spend.monthToDate)}</span>
              <span className="text-xs text-ink3">{spend.monthlyLimit !== null ? `of ${inr(spend.monthlyLimit)} limit` : "no monthly limit"}</span>
            </div>
            {spend.monthlyLimit !== null && <ProgressBar value={spend.monthToDate} max={spend.monthlyLimit} label="Spend against the monthly limit" />}
            {editing ? (
              <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); void save(limit === "" ? null : Number(limit)); }}>
                <label className="flex flex-col gap-1 text-[13px]">
                  <span className="text-ink2">Monthly limit (₹)</span>
                  <input type="number" min={0} step={1} value={limit} onChange={(e) => setLimit(e.target.value)} className="h-9 px-3 rounded-lg border border-line font-mono" />
                </label>
                {error && <p role="alert" className="text-[13px] text-bad">{error}</p>}
                <div className="flex gap-2">
                  <Button type="submit" size="sm">Save limit</Button>
                  <Button size="sm" variant="secondary" onClick={() => save(null)}>Remove limit</Button>
                </div>
              </form>
            ) : (
              <Button size="sm" variant="secondary" onClick={() => setEditing(true)}>Change limit</Button>
            )}
          </Panel>

          <Panel aria-label="Disputes" className="p-5 flex flex-col gap-2">
            <PanelTitle>Disputes</PanelTitle>
            {disputes.length === 0 ? (
              <p className="text-[13px] text-ink3">None. Open one from a run if a charge looks wrong.</p>
            ) : (
              disputes.map((d) => (
                <div key={d.id} className="py-2 border-t border-line2 first:border-t-0 text-[13px] flex flex-col gap-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium flex-1">{d.agentName}</span>
                    <Pill tone={DISPUTE[d.status].tone}>{DISPUTE[d.status].label}</Pill>
                  </div>
                  <Kv label="Disputed" value={inr(d.amountDisputed)} />
                  {d.amountRefunded !== null && <Kv label="Refunded" value={inr(d.amountRefunded)} />}
                  {(d.status === "OPEN" || d.status === "AWAITING_DECISION") && <span className="text-xs text-ink3">Karya decides by {dayLabel(d.decideBy)}.</span>}
                </div>
              ))
            )}
          </Panel>
        </aside>
      </div>
    </div>
  );
}
```

Replace `src/app/(buyer)/billing/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { DisputeView, StatementLineResponse, WalletResponse, WorkspaceHome } from "@/lib/types";
import { BillingView } from "./BillingView";

export default async function BillingPage() {
  const session = await getSession();
  if (!session) return null;
  const org = `/api/v1/orgs/${session.orgId}`;
  const [wallet, lines, home, disputes] = await Promise.all([
    backend.get<WalletResponse>(`${org}/wallet`),
    backend.get<StatementLineResponse[]>(`${org}/statement`),
    backend.get<WorkspaceHome>(`${org}/home`),
    backend.get<DisputeView[]>(`${org}/disputes`),
  ]);
  return <BillingView wallet={wallet} spend={home.spend} lines={lines} disputes={disputes} />;
}
```

The statement endpoint returns every line, not just this month's, so the panel is labelled "All time". FE-3 switches it to WS10's monthly `billing/summary`.

- [ ] **Step 4: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): billing with held and free money, monthly limit, statement by agent and disputes"
```

---

### Task 13: Add an agent with limits, approvers and channels

**Files:**
- Create: `src/lib/connectCopy.ts`, `src/app/(buyer)/connect/[agentId]/[versionId]/ConnectSetup.tsx`
- Modify: `src/app/(buyer)/connect/[agentId]/[versionId]/page.tsx`, `actions.ts`
- Delete: `src/app/(buyer)/connect/[agentId]/[versionId]/ConnectWizard.tsx`, `ConnectWizard.test.tsx`
- Test: create `src/lib/connectCopy.test.ts`, `src/app/(buyer)/connect/[agentId]/[versionId]/ConnectSetup.test.tsx`

**Interfaces:**
- Consumes:
  - `GET /api/v1/agents/{agentId}/versions/{versionId}` → `AgentVersionDetailResponse` (manifest);
  - `GET /api/v1/orgs/{orgId}/members` → `Member[]`;
  - `POST /api/v1/connections {agentId, grantedScopes, spendCap, monthlyLimit, approverUserIds, notifyChannels, runNow}` → `ConnectionResponse` with `firstRunId` (WS15 codes `SPEND_CAP_OUT_OF_RANGE`, `INVALID_APPROVER`, `ORG_MONTHLY_LIMIT`, `AGENT_MONTHLY_LIMIT`, `INSUFFICIENT_FUNDS`);
  - `RUN_START_MESSAGES` from `src/lib/runStartMessages.ts` (Task 6).
- Produces:
  - `capPresets(maxPerRun): number[]`, `plainWords(manifest, granted: string[], cap): string[]`.
  - `createConnectionAction(input): Promise<ActionFailure>`, which redirects on success.
  - `ConnectSetup({ agentId, agentName, version, manifest, approvers })`.

- [ ] **Step 1: Write the failing tests**

`src/lib/connectCopy.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { capPresets, plainWords } from "./connectCopy";
import type { Manifest } from "./types";

const manifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statements", description: "Read statement lines" },
    { type: "READ", resource: "invoices", description: "Read open invoices" },
    { type: "WRITE", resource: "journal_entries", description: "Propose entries" },
  ],
  networkAllowlist: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "OUTCOME", unitPrice: 2, unitLabel: "invoice_matched" },
  approvalGates: [{ trigger: "post_journal_entries", description: "Approve before posting" }],
  dataClasses: [],
};

describe("capPresets", () => {
  it("offers half and the full declared maximum", () => {
    expect(capPresets(500)).toEqual([250, 500]);
    expect(capPresets(1)).toEqual([1]);
  });
});

describe("plainWords", () => {
  it("says what the agent can and cannot do", () => {
    expect(plainWords(manifest, ["bank_statements", "invoices", "journal_entries"], 250)).toEqual([
      "It reads only bank_statements and invoices, nothing else in your workspace.",
      "It can't write a single entry until one of your approvers says yes.",
      "It can't reach any website. Its AI calls go through Karya.",
      "A run stops the moment it reaches ₹250.",
    ]);
  });
  it("names outside websites and ungated writes honestly", () => {
    const m = { ...manifest, approvalGates: [], networkAllowlist: ["graph.facebook.com"] };
    expect(plainWords(m, ["journal_entries"], 500)).toEqual([
      "It reads nothing from your workspace.",
      "It can write journal_entries without asking first.",
      "It can reach graph.facebook.com.",
      "A run stops the moment it reaches ₹500.",
    ]);
  });
});
```

`src/app/(buyer)/connect/[agentId]/[versionId]/ConnectSetup.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConnectSetup } from "./ConnectSetup";
import type { Manifest, Member } from "@/lib/types";

vi.mock("./actions", () => ({
  createConnectionAction: vi.fn().mockResolvedValue({
    ok: false, code: "INSUFFICIENT_FUNDS",
    error: "Your wallet doesn't hold enough for this agent's run limit. Add money in Billing. The agent was not added.",
  }),
}));

const manifest: Manifest = {
  name: "Bank Reconciliation", version: "1.3.0",
  scopes: [
    { type: "READ", resource: "bank_statements", description: "Read statement lines" },
    { type: "WRITE", resource: "journal_entries", description: "Propose entries" },
  ],
  networkAllowlist: [],
  costEnvelope: { currency: "INR", maxPerRun: 500, pricingUnit: "OUTCOME", unitPrice: 2, unitLabel: "invoice_matched" },
  approvalGates: [{ trigger: "post", description: "Approve before posting" }],
  dataClasses: [],
};
const approvers: Member[] = [
  { userId: "u1", fullName: "Priya Shah", email: "p@m.example", role: "BUYER_ADMIN", title: "Finance lead" },
  { userId: "u2", fullName: "Arjun Mehta", email: "a@m.example", role: "BUYER_APPROVER", title: "Accounts manager" },
];

function renderSetup() {
  return render(<ConnectSetup agentId="a1" agentName="Bank Reconciliation" version="1.3.0" manifest={manifest} approvers={approvers} />);
}

describe("ConnectSetup", () => {
  it("defaults to the full run limit and explains it in plain words", () => {
    renderSetup();
    expect(screen.getByRole("button", { name: "₹500" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("A run stops the moment it reaches ₹500.")).toBeInTheDocument();
    expect(screen.getByText("Fixed until you accept an update")).toBeInTheDocument();
  });

  it("sends limits, approvers, channels and run-now together", async () => {
    const { createConnectionAction } = await import("./actions");
    renderSetup();
    await userEvent.click(screen.getByRole("button", { name: "₹250" }));
    await userEvent.type(screen.getByLabelText("Monthly limit for this agent (₹, optional)"), "3000");
    await userEvent.click(screen.getByRole("checkbox", { name: /Arjun Mehta/ }));
    await userEvent.click(screen.getByRole("checkbox", { name: "Email, with a one-tap link" }));
    await userEvent.click(screen.getByRole("button", { name: "Add and run now" }));

    expect(createConnectionAction).toHaveBeenCalledWith({
      agentId: "a1",
      grantedScopes: ["bank_statements", "journal_entries"],
      spendCap: 250,
      monthlyLimit: 3000,
      approverUserIds: ["u2"],
      notifyChannels: ["WEB"],
      runNow: true,
    });
  });

  it("stays on the page and explains a money refusal", async () => {
    renderSetup();
    await userEvent.click(screen.getByRole("button", { name: "Add and run now" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The agent was not added.");
    expect(screen.getByRole("link", { name: "Go to Billing" })).toHaveAttribute("href", "/billing");
  });

  it("needs at least one permission", async () => {
    renderSetup();
    await userEvent.click(screen.getByRole("checkbox", { name: /bank_statements/ }));
    await userEvent.click(screen.getByRole("checkbox", { name: /journal_entries/ }));
    expect(screen.getByRole("button", { name: "Add and run now" })).toBeDisabled();
  });
});
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `npm test -- src/lib/connectCopy.test.ts "src/app/(buyer)/connect"`
Expected: FAIL.

- [ ] **Step 3: Copy helpers and action**

`src/lib/connectCopy.ts`:

```ts
import { inr } from "./format";
import type { Manifest } from "./types";

export function capPresets(maxPerRun: number): number[] {
  return [...new Set([Math.round(maxPerRun / 2), maxPerRun])].filter((v) => v > 0).sort((a, b) => a - b);
}

function list(items: string[]): string {
  return items.length <= 1 ? items.join("") : `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}

export function plainWords(manifest: Manifest, granted: string[], cap: number): string[] {
  const g = new Set(granted);
  const reads = manifest.scopes.filter((s) => s.type === "READ" && g.has(s.resource)).map((s) => s.resource);
  const writes = manifest.scopes.filter((s) => s.type === "WRITE" && g.has(s.resource)).map((s) => s.resource);
  const lines: string[] = [];
  lines.push(reads.length ? `It reads only ${list(reads)}, nothing else in your workspace.` : "It reads nothing from your workspace.");
  if (writes.length) {
    lines.push(manifest.approvalGates.length
      ? "It can't write a single entry until one of your approvers says yes."
      : `It can write ${list(writes)} without asking first.`);
  }
  lines.push(manifest.networkAllowlist.length
    ? `It can reach ${list(manifest.networkAllowlist)}.`
    : "It can't reach any website. Its AI calls go through Karya.");
  lines.push(`A run stops the moment it reaches ${inr(cap)}.`);
  return lines;
}
```

Replace `src/app/(buyer)/connect/[agentId]/[versionId]/actions.ts`:

```ts
"use server";

import { redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import { actionError, type ActionFailure } from "@/lib/problems";
import { RUN_START_MESSAGES } from "@/lib/runStartMessages";
import type { ConnectionResponse, NotifyChannel } from "@/lib/types";

export interface CreateConnectionInput {
  agentId: string;
  grantedScopes: string[];
  spendCap: number;
  monthlyLimit: number | null;
  approverUserIds: string[];
  notifyChannels: NotifyChannel[];
  runNow: boolean;
}

const NOT_ADDED = " The agent was not added.";

export async function createConnectionAction(input: CreateConnectionInput): Promise<ActionFailure> {
  let connection: ConnectionResponse;
  try {
    connection = await backend.post<ConnectionResponse>("/api/v1/connections", input);
  } catch (e) {
    return actionError(e, {
      SPEND_CAP_OUT_OF_RANGE: "Pick a run limit above ₹0 and within what the agent declares.",
      INVALID_APPROVER: "Approvers must be admins or approvers of this workspace.",
      ORG_MONTHLY_LIMIT: RUN_START_MESSAGES.ORG_MONTHLY_LIMIT + NOT_ADDED,
      AGENT_MONTHLY_LIMIT: "The first run would pass this agent's monthly limit. Raise it or add without running." + NOT_ADDED,
      INSUFFICIENT_FUNDS: RUN_START_MESSAGES.INSUFFICIENT_FUNDS + NOT_ADDED,
    }, "We couldn't add the agent.");
  }
  redirect(connection.firstRunId ? `/runs/${connection.firstRunId}` : "/home");
}
```

- [ ] **Step 4: The setup screen and page**

`src/app/(buyer)/connect/[agentId]/[versionId]/ConnectSetup.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Panel, PanelTitle } from "@/components/ui/Panel";
import { Segmented } from "@/components/ui/Segmented";
import { capPresets, plainWords } from "@/lib/connectCopy";
import { inr } from "@/lib/format";
import { roleLabel } from "@/lib/roles";
import type { Manifest, Member, NotifyChannel } from "@/lib/types";
import { createConnectionAction } from "./actions";

const MONEY_CODES = new Set(["INSUFFICIENT_FUNDS", "ORG_MONTHLY_LIMIT"]);

export function ConnectSetup({
  agentId, agentName, version, manifest, approvers,
}: {
  agentId: string; agentName: string; version: string; manifest: Manifest; approvers: Member[];
}) {
  const presets = capPresets(manifest.costEnvelope.maxPerRun);
  const [granted, setGranted] = useState<string[]>(manifest.scopes.map((s) => s.resource));
  const [cap, setCap] = useState(String(presets[presets.length - 1]));
  const [monthly, setMonthly] = useState("");
  const [chosen, setChosen] = useState<string[]>([]);
  const [channels, setChannels] = useState<NotifyChannel[]>(["WEB", "EMAIL"]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<{ error: string; code?: string } | null>(null);

  const toggle = <T,>(list: T[], value: T) => (list.includes(value) ? list.filter((v) => v !== value) : [...list, value]);

  async function submit(runNow: boolean) {
    setBusy(true);
    setProblem(null);
    const result = await createConnectionAction({
      agentId,
      grantedScopes: granted,
      spendCap: Number(cap),
      monthlyLimit: monthly === "" ? null : Number(monthly),
      approverUserIds: chosen,
      notifyChannels: channels,
      runNow,
    });
    setBusy(false);
    if (result && !result.ok) setProblem({ error: result.error, code: result.code });
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-[26px] font-semibold tracking-tight">Add {agentName}</h1>
        <p className="text-ink2">Choose what it may touch, what it may spend, and who approves its writes.</p>
      </div>
      <div className="grid grid-cols-[minmax(0,1fr)_400px] gap-6 items-start">
        <div className="flex flex-col gap-4 min-w-0">
          <Panel className="p-5 flex flex-col gap-3">
            <PanelTitle>What it can touch</PanelTitle>
            {manifest.scopes.map((s) => (
              <label key={s.resource} className="flex items-start gap-3 py-2 border-t border-line2 first:border-t-0 text-[13px]">
                <input type="checkbox" className="w-4 h-4 mt-0.5 accent-blue" checked={granted.includes(s.resource)} onChange={() => setGranted(toggle(granted, s.resource))} />
                <span className="flex-1">
                  <span className="font-mono">{s.resource}</span> <span className="text-xs text-ink3">{s.type === "READ" ? "reads" : "writes"}</span>
                  <span className="block text-ink2">{s.description}</span>
                </span>
                <Icon name={s.type === "READ" ? "read" : "write"} className="w-4 h-4 text-ink3" />
              </label>
            ))}
          </Panel>
          <Panel className="p-5">
            <PanelTitle>In plain words</PanelTitle>
            <ul className="mt-2 flex flex-col gap-1.5 text-[13px] list-disc pl-5">
              {plainWords(manifest, granted, Number(cap)).map((line) => <li key={line}>{line}</li>)}
            </ul>
          </Panel>
        </div>

        <Panel className="p-5 flex flex-col gap-4">
          <PanelTitle>Limits and approvals</PanelTitle>
          <div className="flex flex-col gap-2">
            <span className="text-[13px] text-ink2">Most one run can cost</span>
            <Segmented label="Most one run can cost" options={presets.map((p) => ({ value: String(p), label: inr(p) }))} value={cap} onChange={setCap} />
            <span className="text-xs text-ink3">{inr(manifest.costEnvelope.maxPerRun)} is the most this agent ever charges.</span>
          </div>
          <label className="flex flex-col gap-1.5 text-[13px]">
            <span className="text-ink2">Monthly limit for this agent (₹, optional)</span>
            <input type="number" min={0} value={monthly} onChange={(e) => setMonthly(e.target.value)} className="h-9 px-3 rounded-lg border border-line font-mono" />
          </label>
          <fieldset className="flex flex-col gap-1.5">
            <legend className="text-[13px] text-ink2 mb-1">Who can approve its writes</legend>
            {approvers.map((m) => {
              const name = m.fullName ?? m.email;
              return (
                <label key={m.userId} className="flex items-center gap-3 text-[13px]">
                  <input type="checkbox" className="w-4 h-4 accent-blue" checked={chosen.includes(m.userId)} onChange={() => setChosen(toggle(chosen, m.userId))} aria-label={`${name}, ${m.title ?? roleLabel(m.role)}`} />
                  <span><span className="font-medium">{name}</span> <span className="text-ink3">{m.title ?? roleLabel(m.role)}</span></span>
                </label>
              );
            })}
            <span className="text-xs text-ink3">{chosen.length === 0 ? "Nobody picked: any admin or approver can decide." : "Only the people ticked can decide."}</span>
          </fieldset>
          <fieldset className="flex flex-col gap-1.5">
            <legend className="text-[13px] text-ink2 mb-1">Ask approvers on</legend>
            <label className="flex items-center gap-3 text-[13px]"><input type="checkbox" checked disabled className="w-4 h-4" aria-label="Karya" /> Karya</label>
            <label className="flex items-center gap-3 text-[13px]">
              <input type="checkbox" className="w-4 h-4 accent-blue" checked={channels.includes("EMAIL")} onChange={() => setChannels(toggle(channels, "EMAIL"))} aria-label="Email, with a one-tap link" />
              Email, with a one-tap link
            </label>
            <label className="flex items-center gap-3 text-[13px]">
              <input type="checkbox" className="w-4 h-4 accent-blue" checked={channels.includes("SLACK")} onChange={() => setChannels(toggle(channels, "SLACK"))} aria-label="Slack" />
              Slack
            </label>
          </fieldset>
          <div className="flex items-center justify-between text-[13px] border-t border-line2 pt-3">
            <span className="text-ink2">Version</span>
            <span className="text-right"><span className="font-mono">{version}</span><span className="block text-xs text-ink3">Fixed until you accept an update</span></span>
          </div>
          {problem && (
            <p role="alert" className="text-[13px] text-bad">
              {problem.error} {problem.code && MONEY_CODES.has(problem.code) && <Link href="/billing" className="text-blue font-semibold">Go to Billing</Link>}
            </p>
          )}
          <Button disabled={busy || granted.length === 0} onClick={() => submit(true)}>Add and run now</Button>
          <Button variant="secondary" disabled={busy || granted.length === 0} onClick={() => submit(false)}>Add without running</Button>
        </Panel>
      </div>
    </div>
  );
}
```

Replace `src/app/(buyer)/connect/[agentId]/[versionId]/page.tsx`:

```tsx
import { backend } from "@/lib/backend";
import { getSession } from "@/lib/session";
import type { AgentVersionDetailResponse, Member } from "@/lib/types";
import { ConnectSetup } from "./ConnectSetup";

export default async function ConnectPage({ params }: { params: Promise<{ agentId: string; versionId: string }> }) {
  const { agentId, versionId } = await params;
  const session = await getSession();
  if (!session) return null;
  const [detail, members] = await Promise.all([
    backend.get<AgentVersionDetailResponse>(`/api/v1/agents/${agentId}/versions/${versionId}`),
    backend.get<Member[]>(`/api/v1/orgs/${session.orgId}/members`),
  ]);
  const approvers = members.filter((m) => m.role === "BUYER_ADMIN" || m.role === "BUYER_APPROVER");
  return (
    <ConnectSetup
      agentId={agentId}
      agentName={detail.agentName}
      version={detail.versionNumber}
      manifest={detail.manifest}
      approvers={approvers}
    />
  );
}
```

Delete `ConnectWizard.tsx` and `ConnectWizard.test.tsx`.

- [ ] **Step 5: Run the tests**

Run: `npm test && npx tsc --noEmit`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src
git commit -m "feat(frontend): add an agent with run and monthly limits, named approvers, channels and run-now"
```

---

### Task 14: Store link, end-to-end core loop

**Files:**
- Create: `src/app/(buyer)/store/[agentId]/page.tsx`
- Modify: `e2e/core-loop.spec.ts`

**Interfaces:**
- Consumes: `GET /api/v1/catalog` → `CatalogListingResponse[]` (existing).
- Produces: `/store/{agentId}`, which for now redirects to the existing agent detail page. FE-2 replaces it with the flow view.

- [ ] **Step 1: The store route**

`src/app/(buyer)/store/[agentId]/page.tsx`:

```tsx
import { notFound, redirect } from "next/navigation";
import { backend } from "@/lib/backend";
import type { CatalogListingResponse } from "@/lib/types";

/** Stable URL for an agent. FE-2 renders the flow view here; for now it opens the current detail page. */
export default async function StorePage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = await params;
  const listings = await backend.get<CatalogListingResponse[]>("/api/v1/catalog");
  const listing = listings.find((l) => l.agentId === agentId);
  if (!listing) notFound();
  redirect(`/agents/${agentId}/versions/${listing.versionId}`);
}
```

- [ ] **Step 2: Rewrite the core-loop E2E**

Replace `e2e/core-loop.spec.ts`:

```ts
import { expect, test } from "@playwright/test";

test("a buyer signs in, adds an agent with limits, runs it, and lands on the run console", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("e2e-buyer@example.com");
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByLabel("Keep me signed in on this device").check();
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/home/);
  await expect(page.getByRole("heading", { name: /Good (morning|afternoon|evening)/ })).toBeVisible();

  await page.getByRole("link", { name: "Browse agents" }).click();
  await page.getByText("E2E Bank Reconciliation").click();
  await expect(page.getByRole("heading", { name: "E2E Bank Reconciliation" })).toBeVisible();
  await page.getByRole("link", { name: "Connect this agent" }).click();

  await expect(page.getByRole("heading", { name: "Add E2E Bank Reconciliation" })).toBeVisible();
  await page.getByRole("checkbox", { name: /E2E Approver/ }).check();
  await page.getByRole("button", { name: "Add and run now" }).click();

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]+/);
  await expect(page.getByRole("heading", { name: /^Run / })).toBeVisible();
  await expect(page.getByText(/Starting|Running/).first()).toBeVisible();
});

test("the approver sees the waiting count in the rail and can open notifications", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Work email").fill("e2e-approver@example.com");
  await page.getByLabel("Password").fill("e2e-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(/\/home/);
  await page.getByRole("link", { name: /^Notifications/ }).click();
  await expect(page.getByRole("heading", { name: "Notifications" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Where we reach you" })).toBeVisible();
});
```

WS13's seed gives the approver the title "Accounts manager". The approver checkbox's accessible name is "E2E Approver, Accounts manager", which `/E2E Approver/` matches.

- [ ] **Step 3: Run the E2E against a fresh stack**

With Postgres and the backend running on a fresh database (see `frontend/README.md`), run: `npm run seed:e2e && npm run test:e2e`
Expected: both tests PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/\(buyer\)/store frontend/e2e/core-loop.spec.ts
git commit -m "test(frontend): core loop E2E through sign-in, home, add-and-run and notifications"
```

---

### Task 15: Remove what the new screens replaced, and verify

**Files:**
- Delete: `src/components/ApprovalsBadge.tsx`
- Modify: `frontend/README.md`

- [ ] **Step 1: Delete unused legacy components**

Run: `grep -rln "ApprovalsBadge\|RunsList\|StartRun\|ConnectWizard\|RunTraceStep" src`
Expected: only `src/components/ApprovalsBadge.tsx` itself. Delete it. `StatusPill`, `PermissionStatementDoc`, `ScopeToggleGrant`, `WizardRail` and `SubmissionWizard` are still used by the catalog, developer and admin screens, and stay until FE-2, FE-3 and FE-4.

- [ ] **Step 2: Document the new environment variables**

In `frontend/README.md`, under the environment section, add:

```markdown
- `NEXT_PUBLIC_GOOGLE_CLIENT_ID` (optional): shows "Continue with Google" on the sign-in screen. Must match the backend's `GOOGLE_CLIENT_ID`.
- Sessions: the access token lives in `karya_session`. "Keep me signed in" adds a 30-day `karya_refresh` cookie; the middleware refreshes on page navigations only.
- Design reference: open `fe-mockups/latest/Karya — Agentic UI.html` in Chrome. Tokens live in `src/app/globals.css`, primitives in `src/components/ui/`.
```

- [ ] **Step 3: Full verification**

Run: `npx tsc --noEmit && npm test && npm run build`
Expected: no type errors, all tests PASS, and the production build succeeds (this also proves the fonts load).

- [ ] **Step 4: Commit**

```bash
git add -A frontend
git commit -m "chore(frontend): remove components the new screens replaced; document sessions and design tokens"
```
