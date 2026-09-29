# Frontend rebuild from the Karya mockup: P0 and P1

Status: draft for review, 2026-09-29.

## 1. Outcome

Replace `frontend/` (Next.js 14, 108 files) with a new Next.js 14 app built to `fe-mockups/latest/Karya — Agentic UI.html`. The mockup is the visual reference: its tokens, type, rail, spacing and components. It is static (1440x900 boards plus one 390x844 phone board) and shows layout, not behaviour.

This spec covers **P0** (foundation) and **P1** (the buyer screens that already have a backend, the FE-2 subset). Later phases are listed in section 9 and get their own specs.

Decisions already made with the owner:

- Delete the old frontend entirely, including the developer console, admin review queue, buyer catalog and connect wizard. The developer and admin UIs return in P3 and P4. The CLI and API keys cover developer needs meanwhile.
- Stack: Next.js 14 App Router, fresh. Plain CSS variables from the mockup, no Tailwind, no default component library.
- Fully responsive at every screen, down to phone width.
- Where the backend has no data for a mockup element, omit the element and record it in `docs/frontend-gaps.md`. No fake numbers. No backend changes in this work.
- Screens whose backend does not exist yet (track record, versions and consent, notifications, earnings, agent health) are out of scope until WS8, WS9 and WS11.

## 2. Repo changes

- The first P0 commit deletes `frontend/` and fixes stale references (README, `docker-compose.yml`, `docs/known-issues.md` mentions of `frontend/src/lib/types.ts`, `.gitignore` entries). The new app lives at `frontend/` again, so infra paths do not change.
- New file `docs/frontend-gaps.md`, maintained by every phase.
- Environment: `BACKEND_URL` is required, as today.

## 3. Structure

```
frontend/src/
  app/
    (auth)/login, signup, verify        public
    (app)/layout.tsx                    session gate + role rail
    (app)/home
    (app)/files
    (app)/runs, runs/[id]
    (app)/store/[agentId]               agent as a flow (read-only)
    (app)/connect/[agentId]/[versionId] plug in data
    approve/[runId]/[gateId]            mobile-first, no rail
    api/runs/*                          polling proxies
  ui/                                   tokens + primitives, no data knowledge
  features/flow  features/gates  features/files  features/sockets  features/runs
  lib/api/                              typed clients per backend area
  lib/session.ts                        cookie session, refresh
middleware.ts                           redirect unauthenticated users
```

Rules:

- `ui/` depends on nothing. Features depend on `ui/` and `lib/api/` only. The one allowed cross-feature use is `flow` and `gates` inside the run pages.
- Later phases add `(app)/dev/*` and `(app)/admin/*` under the same layout. The layout chooses the rail (buyer, developer, admin) from the session's memberships and role.

## 4. Design system (P0)

- Tokens from the mockup as CSS variables on `:root`: `--g --dot --s --l --l2 --ink --ink2 --ink3 --b --b2 --b3 --sky --sky2 --ok --okS --wa --waS --bad --badS`. Fonts: Instrument Sans (400/500/600/700) and Geist Mono (400/500), self-hosted via `next/font`.
- Primitives: Rail, Topbar (search field shown but non-functional in P0/P1, see gaps), Pill (status), Table, Dropzone, Drawer, Button, Field, EmptyState, ErrorState, Skeleton.
- Layout: the mockup's 72px icon rail becomes a bottom bar below 768px. The topbar collapses the search to an icon. Tables become stacked cards below 768px. The dot-grid background is kept.
- Accessibility: visible focus rings, buttons and links as real elements, status conveyed by text and not colour alone, touch targets of at least 44px on the phone layout.

## 5. Session and data access

- httpOnly cookies hold the access and refresh tokens. On a 401, the server client calls `POST /api/v1/auth/refresh` once and retries. If that fails, it clears the session and redirects to login.
- Server components call the backend directly. Mutations are server actions. The client never sees a token.
- Polling: `app/api/runs/[id]` and `app/api/runs/list` proxy the backend for the two live screens. Interval 3 seconds while a run is non-terminal, stopping when it reaches a terminal status or the tab is hidden. No SSE is added.
- Errors: the backend's error message text is shown as returned. Network failures show a retryable error state. Mutations disable their button until the request settles and are safe to repeat.
- Types in `lib/api/` are written from the backend DTOs listed in section 6 and covered by mapping tests. They are not generated. (`contracts/platform-v1.yaml` currently describes only `/runs/{id}/flow`.)

## 6. Screens and their data

Backend shapes below were read from the DTOs on `master` (fd8f8e7).

### Sign in / create account (P0)

`POST /auth/login`, `/signup`, `/google`, `/email/verify`, `/email/resend`, `/password/forgot`, `/password/reset`, `/logout`, `/switch-org`. `GET /me` returns `(id, email, fullName, emailVerified, orgId, memberships[])`. Signup takes `side` and `orgName`, as the backend defines.

### Workspace home (P0)

`GET /me`, `GET /runs?group=needs_you` (counts and items), `GET /connections/mine` (`id, agentId, agentName, grantedScopes, spendCap, status`). Shows the greeting from `fullName`, the runs waiting for approval, and the connected agents.

### Files (P1)

`GET /datasets?include=usage` returns `(id, displayName, sourceLabel, format, rowCount, sizeBytes, columns, sha256, uploadedBy, createdAt, piiScan, usage[])`, where `usage` is `(connectionId, resource, agent, version, access)`. This gives the file table and the "plugged into" column. `POST /datasets` (multipart `file`) uploads, `DELETE /datasets/{id}` deletes. `GET /orgs/{orgId}/storage` gives `(usedBytes, limitBytes)` for the storage bar.
The "Where this file goes" drawer lists the `usage` rows. "Unplug and delete" unbinds each usage row, then deletes. "Keep it private" and "Review the request" are omitted (gap G3).

### Plug in data (P1)

Route `connect/[agentId]/[versionId]`. Uses the catalog version detail for the declared resources (the sockets), `POST /datasets/{id}/check` with `agentVersionId` for the column check, `PUT /connections/{id}/bindings/{resource}` and `DELETE` to bind and unbind, and `POST /connections` (`agentId, grantedScopes, spendCap`) to create the connection. "Add and run now" creates the connection, binds, then calls `POST /runs` with `connectionId`. The per-run limit input maps to `spendCap`. Approver and notification choices are omitted (G4, G5). The exact shape of the catalog version detail is confirmed in the plan, not here.

### Live run (P1)

`GET /runs/{id}` gives `(id, displayId, status, terminalReason, costCap, totalCharged, steps[])`. `GET /runs/{id}/flow` gives `blocks[] (block, state, metrics, gateId, flag)`, `reachedBlock`, `spend (charged, cap)`, `deadlineAt`, `blockedAttempts`. Block states are DONE, WAITING, FAILED, SKIPPED, IDLE. `GET /runs/{id}/gates` gives each gate's `(gateId, action, summary, payload, status, expiresAt, estimatedUnits, costIfApproved, preview, decidedVia, decidedAt, note)`.

- The flow strip renders `blocks`, with a block flagged `UNDECLARED` shown as such.
- The gate card renders `summary`, `preview` and `costIfApproved`, with Approve and Reject calling `POST /runs/{id}/gates/{gateId}/decision`. Legacy runs without gates use `POST /runs/{id}/approve` and `/reject`.
- Timeline is built from `steps`. "Time left in sandbox" is computed from `deadlineAt`. "Held in your wallet" is `held` from the run list item.
- Replay maps to `POST /sandbox/runs/{id}/rerun` only if the buyer route allows it. Otherwise it is omitted (G6, confirmed in the plan).
- "Ask about this run" is omitted (G1).

### Runs history (P1)

`GET /runs?group&agentId&q&from&to&page&size` returns `items[]` of `(id, displayId, agent, version, status, terminalReason, reachedBlock, startedAt, completedAt, charged, held, decidedBy)`, plus `counts` per group (`needs_you`, `running`, `finished`, `stopped`) and paging. The filter chips use `counts`. The search box uses `q`. The timeline bars use `startedAt` and `completedAt`. Roles: the list requires BUYER_ADMIN or BUYER_APPROVER.

### Store: the agent as a flow (P1)

Read-only render of the catalog version's declared blocks with "what you agree to" from the manifest (price, cap, network, data classes). Test-case, attack-probe and run-count figures are omitted until WS8 (G7). "Try on sample data" is omitted (G8). "Add to workspace" links to the connect route.

### Phone approval (P1)

`approve/[runId]/[gateId]` renders the same `GateCard` at phone width with no rail. It requires a session. If the user is signed out, login returns them to this URL. Sending the link (email, push) is not built, because notifications are out of scope.

## 7. Errors, empty and loading states

Each data screen has three states: a skeleton while loading, an empty state with the next action (for example "Upload a file"), and an error state showing the backend message and a retry. A run that is terminal shows its `terminalReason` in plain words. A gate past `expiresAt` shows as expired and its buttons are disabled.

## 8. Testing

- Vitest and Testing Library: `lib/api` mapping tests against fixture JSON copied from the DTOs, component tests for `FlowBlocks` (every `BlockState`, the `UNDECLARED` flag), `GateCard` (pending, decided, expired), the runs table (empty, paging, filters), and the polling hook (stops on terminal status).
- Playwright e2e, one per phase against the local backend: P0 sign-in to home. P1 upload a file, plug it in, run, approve the gate, see the run in history.
- Responsive check at 390, 768 and 1440 px for the four P1 screens, by screenshot review against the mockup. There is no pixel-diff tooling.
- Old frontend tests are deleted with the code.

## 9. Later phases (not specified here)

- P2: billing and wallet (`/orgs/{id}/wallet`, `/statement`), catalog browse and connect wizard polish.
- P3: developer studio, sandbox runs and quota, test files and profiles, API keys, get verified.
- P4: admin review queue and pipeline, developer verification.
- After WS8, WS9, WS11: track record, versions and consent, notifications, earnings, agent health.

## 10. Gap list (seed for `docs/frontend-gaps.md`)

| Id | Mockup element | Why omitted |
|---|---|---|
| G1 | Search and "Ask about this run" (⌘K) | No search or run-question backend |
| G2 | Notification bell and feed | Waits on WS11 |
| G3 | "Keep it private", "Review the request" on a file | No such dataset action found in `DatasetController` |
| G4 | Approver list and "Ask approvers on" | No approver-assignment or notification-channel endpoint found |
| G5 | Monthly limit per agent | Only per-run `spendCap` exists on a connection |
| G6 | Replay run from the buyer side | Rerun is a sandbox route and needs an ownership check |
| G7 | Test cases passed, attack probes, runs and dispute rate, typical cost | Waits on WS8 |
| G8 | Try on sample data | No backend |
| G9 | Version pinning and "1 new" update badge | Waits on WS9 |

## 11. Risks

- Deleting `frontend/` first leaves no developer or admin UI until P3 and P4. Accepted by the owner.
- The buyer run list allows only BUYER_ADMIN and BUYER_APPROVER. A signed-in user with another role sees an explicit "no access" state and not an empty list.
- DTO shapes are hand-copied, so they can drift. Mapping tests against fixtures catch a shape change only when the fixtures are refreshed. The gap is noted here and not solved in this work.
- The connect screen depends on the catalog version response, which was not read in this pass. The plan's first task confirms it. If it lacks the declared resources, that becomes a backend follow-up and the screen shows a clear error.
