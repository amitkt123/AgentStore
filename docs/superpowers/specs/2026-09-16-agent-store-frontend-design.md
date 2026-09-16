# Agent Store Frontend — Design

> Companion to `docs/superpowers/plans/2026-09-16-agent-store-backend-platform.md` (the backend implementation plan this frontend consumes) and `docs/superpowers/specs/2026-09-16-agent-store-mvp-design.md` (the MVP scope both plans implement). Visual reference: `fe-mockups/karya_agent_store_mockup.html` (buyer console), `fe-mockups/karya_connect_flow.html` (connection wizard), `fe-mockups/karya_developer_console.html` (developer portal) — three static, self-contained HTML mockups with no backend behind them.

## 1. Purpose and scope

**Goal:** the Next.js/TypeScript frontend for the fundraising-timeline MVP — the UI that makes the backend platform's real loop (publish → review → connect → run → approve → bill) demoable. This plan targets **backend parity**: every screen is built only against endpoints the backend plan (26 tasks) actually implements or the small set of additive read endpoints this plan proposes below. It does not build mockup fidelity for features the backend doesn't power.

**In scope:**
- Buyer console: catalog, agent detail (permission statement), connect flow, run console with live trace and approval gates, billing statement
- Developer console: dashboard, submission wizard, review-status view, manage/version-history
- Admin review queue (no mockup precedent — net-new screen)
- Login / buyer signup
- Shared manifest-rendering component used across buyer detail, connect flow, and developer wizard preview

**Explicitly out of scope (cut from the mockups because the backend doesn't power them):**
- Eval scorecards and eval-suite upload (no eval storage/scoring in the backend plan)
- Red-team probes and staged-rollout as distinct visible pipeline stages (backend has only manifest-lint gate + human review; collapsed into the review-status view)
- Real Tally connector / pairing-code install flow (MVP design doc: sample-file upload replaces live Tally/GST as the connection source)
- Connection "test" dry-run step (no dry-run endpoint; connection creation and secret storage are the only real steps)
- Wallet top-up, monthly-cap self-service, GST-invoice download, platform-fee/GST line items on the statement (no payment rails, no fee computation in the ledger schema)
- Developer payouts dashboard, payout account management (no payout model in the backend plan)
- "Track record" / dispute-rate stats on catalog and agent detail (not tracked anywhere in the schema)
- WhatsApp as an approval channel (spec's secondary surface; not part of this web frontend)

## 2. Backend API gaps this plan depends on

The backend plan as written does not expose enough read endpoints for buyers and developers to see their own data. These are small, additive GET endpoints over entities the backend plan already creates — no new modules, no new domain modeling:

| Endpoint | Role | Purpose |
|---|---|---|
| `GET /api/v1/agents/{agentId}/versions/{versionId}` | any authenticated | Full manifest + version detail — powers the permission-statement doc on buyer agent-detail and connect-flow scope grant |
| `GET /api/v1/agents/mine` | DEVELOPER | Own org's agents + all versions regardless of status (draft/in-review/live/rejected) — powers developer dashboard and manage/version-history |
| `GET /api/v1/connections/mine` | BUYER_ADMIN/BUYER_APPROVER | Own org's connections — needed before triggering a run and for a connections-management view |
| `GET /api/v1/runs/mine` (optional `?status=AWAITING_APPROVAL`) | BUYER_ADMIN/BUYER_APPROVER | Own org's runs, list view for the Runs nav + the approvals queue badge/view |

These should land as a small addendum to the backend plan (or a short "Phase 10" in it) before or alongside frontend implementation, since several FE screens are non-functional without them.

## 3. Architecture

**Stack:** Next.js 14+, App Router, TypeScript — per the MVP design doc's mandate. One application, not one per role, mirroring the backend's own modular-monolith-over-microservices reasoning: a single app keeps the shared manifest-rendering component (§5) trivial instead of a cross-repo dependency, and there's no scale reason yet to split.

**Route structure**, grouped by role via App Router route groups:

```
app/
  (auth)/login, (auth)/signup
  (buyer)/catalog
  (buyer)/agents/[agentId]
  (buyer)/connect/[agentId]/...            — 4-step wizard
  (buyer)/runs                             — list + approvals badge
  (buyer)/runs/[runId]                     — run console
  (buyer)/billing
  (developer)/dashboard
  (developer)/agents/new
  (developer)/agents/[agentId]/versions/new     — 4-step submission wizard
  (developer)/agents/[agentId]/versions/[versionId]/status  — review-status view
  (developer)/agents/[agentId]/manage
  (admin)/review
  (admin)/review/[reviewItemId]
```

A root layout reads the JWT's role claim and redirects to the correct group root; a buyer hitting a `/developer/...` or `/admin/...` route is redirected, not just visually hidden. This is a UX convenience only — the backend enforces the real access control (Task 4).

**API client:** one thin typed fetch wrapper (`lib/api.ts`), hand-written against the response shapes documented in the backend plan (`AuthResponse`, `CatalogListingResponse`, `AgentVersionResponse`, `RunDetailResponse`, `StatementLineResponse`, etc. — plus the §2 additions). No codegen tooling for an API surface this small. All calls attach the JWT automatically; any 401 redirects to `/login`.

**Auth transport:** JWT set as an httpOnly cookie by a Next.js route handler wrapping `POST /auth/login` and `/auth/signup` (not stored in localStorage or set directly from client JS, since httpOnly cookies can't be — this avoids XSS token theft). Signup is buyer-role only; developer accounts are manually created out-of-band per the MVP design doc's "no invite-flow code" constraint, so the signup form has no role selector.

**Data fetching:** TanStack Query throughout. Two polling consumers:
- Run console polls `GET /runs/{runId}` every ~2s while status is `RUNNING`/`AWAITING_APPROVAL`, stopping on a terminal status (`COMPLETED`/`FAILED`/`CANCELLED`). This endpoint is explicitly documented in the backend plan as "polling-friendly... a later frontend plan can upgrade it to a WebSocket push without changing this response shape" — polling is the intended MVP mechanism, not a placeholder.
- The buyer's Runs view polls `GET /runs/mine?status=AWAITING_APPROVAL` (§2) on a longer interval (~10s) for the approvals nav badge.

Everything else is fetch-on-navigate with cache invalidation on mutation (e.g. approving a run invalidates that run's query and the approvals list).

## 4. Design system

The mockups' visual language — IBM Plex Sans/Serif pairing, the sage-green/amber/red governance palette, the permission-statement-as-document motif — is preserved rather than redesigned. `business-technical-analysis.md` calls this out by name as executing the product's governance thesis "better than most B2B fintech products at this stage," and it's worth keeping.

**Implementation:** not a literal port of the mockups' hand-rolled CSS. The same design tokens (colors, spacing, type scale, light/dark custom-property pairs already defined in the mockups) become Tailwind theme extensions. A small shared component library carries the reusable interaction patterns already validated in the mockups: `PermissionStatementDoc`, `RunTraceStep`, `StatusPill`, `ScopeToggleGrant`, `WizardRail`. No third-party component library (shadcn/etc.) — the mockups already prove out every pattern needed (toggles, segmented controls, step rails, trace timelines), so adopting one buys nothing here.

## 5. Manifest-driven rendering

Preserved from the mockups as a hard architectural constraint (per the business analysis's own framing): **one manifest schema, three renderers.** A single `PermissionStatementDoc` component takes a `Manifest` — the same shape the backend's `ManifestParser` produces (scopes, cost envelope, approval gates) — and renders it three ways via a `mode` prop:

- `mode="listing"` — buyer's agent-detail page, read-only, sourced from `GET /agents/{agentId}/versions/{versionId}` (§2)
- `mode="grant"` — connect flow's scope-grant step, adds toggle switches, hard-constrained to `manifest.scopes` (cannot grant beyond what's declared — mirrors the backend's own 400 rejection in `POST /connections` for undeclared scopes)
- `mode="preview"` — developer wizard, live-updates as the developer fills in scope/cost-cap fields; same component the buyer eventually sees

The manifest JSON Schema (`manifest-schema.json`, defined in the backend plan) is also the frontend's TypeScript source of truth — one hand-written `Manifest` type shared across all three modes, not three ad-hoc shapes.

## 6. Auth, roles, and error handling

**Roles:** `BUYER_ADMIN`, `BUYER_APPROVER`, `DEVELOPER`, `ADMIN` (per the backend plan's identity module). The JWT's role claim drives route-group redirection and in-page affordances — e.g. only `BUYER_APPROVER` sees approve/reject controls on a gated run.

**Error handling**, three cases get specific treatment, everything else is a generic toast:
- **Manifest validation errors** (400 from `POST .../versions` or `.../submit`) — the backend returns the validator's error list; rendered as inline field-level errors on the wizard step owning that field, not a generic banner.
- **Cost-cap kill switch** (a run transitions to `FAILED` mid-poll because cumulative cost exceeded `costCap`) — shown as a distinct terminal state ("Stopped — spend limit reached"), not a generic failure, since it's the governance system working as designed.
- **Insufficient wallet balance** at trigger time (Task 23's guard) — surfaced at the "Run" button as a disabled state with the shortfall amount, not a failed-request toast after the fact.

## 7. Testing

- **Component tests** (Vitest + Testing Library) for shared components carrying real logic: `PermissionStatementDoc`'s three modes, `ScopeToggleGrant`'s can't-exceed-manifest constraint, the run-console polling state machine.
- **One Playwright E2E per core loop**, run against the real backend (reusing the Testcontainers Postgres fixtures the backend plan builds in Tasks 25/26): publish → review → approve → connect → run → approval-gate → bill.
- No visual/screenshot testing — not worth it for an MVP demo timeline.

## 8. Open questions

- Exact shape of the §2 endpoint additions (pagination? filtering beyond the one `status` param noted for `/runs/mine`?) should be settled when they're added to the backend plan, not blocking this design.
- Whether `BUYER_APPROVER` is a distinct signup role or an admin-assigned flag on an existing `BUYER_ADMIN` user — the backend plan's `UserRole` enum should be checked at implementation time; this design assumes it's a selectable role at buyer-org user creation (out of scope for this MVP's buyer signup form, which creates the first `BUYER_ADMIN` only — additional org users are a later concern not covered by either plan).
