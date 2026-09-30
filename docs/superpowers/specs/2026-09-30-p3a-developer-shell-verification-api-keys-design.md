# P3a: Developer shell, Get verified, API keys — Design

> **Status:** design approved by the user in conversation on 2026-09-30. First slice of P3 (developer area) from `2026-09-29-fe-rebuild-p0-p1-design.md` §9. Frontend only; the backend (WS5c, WS7a) is merged.

## 1. Outcome

A developer can sign in, land in a developer area with its own rail, complete and submit identity verification, and manage API keys for the CLI. Buyers never see these screens.

**Aesthetics come only from `fe-mockups/latest/`** (decoded from the "Karya — Agentic UI" bundle). The "Get verified" screen exists there; the API keys screen does not, so it is built from the same components and tokens.

**Out of scope:** Studio, sandbox runs, test files and profiles, earnings (P3b/P3c); the admin review queue (P4); SMS OTP and payout account (see §3).

## 2. Shell and routes

- Routes live inside the existing `app/(app)/` group: `/developer/verification`, `/developer/api-keys`. No new route group.
- `app/(app)/layout.tsx` picks the rail with `railFor(session.role)`. Developers get `developerRailItems()` (next to `buyerRailItems` in `ui/Rail.tsx`): **Get verified** and **API keys**. Later slices add Studio, Sandbox runs and Test files to the same function. The buyer-only `listRuns` call stays behind `isBuyer`.
- Gating: a `DEVELOPER` hitting a buyer route (`/home`, `/store`, `/runs`, `/files`, `/connect`) is redirected to `/developer/verification`; a non-developer hitting `/developer/*` is redirected to `/home`. Post-login landing for developers is `/developer/verification`. Gating is done in the layout (server), not middleware, because the role comes from the session decode the layout already does.
- Tokens: `ui/tokens.css` already matches the mockup variables. New CSS modules add only the missing components: the stepper (`.st` with connector tabs and `cur/done/karya` states), the segmented control (`.seg`), the key-value row (`.kv`), and the one-time key reveal card.

## 3. Get verified (`features/verification/`)

**Backend contract (WS7a):** `GET/PATCH /api/v1/developer/verification`, `POST .../pan-document` (multipart, 5 MB, JPEG/PNG/PDF), `POST .../submit`. Steps are `ABOUT → TAX_IDS → PHONE → AGREEMENT`. Statuses: `DRAFT`, `PENDING`, `VERIFIED`, `FIX_REQUESTED`, `REJECTED`. Only masked PAN/GSTIN/phone ever come back.

**Departures from the mockup (decided with the user):** the mockup's SMS-OTP entry and "Payout account" step are **not built**. WS7a captures the phone number and a person confirms it manually (no paid SMS vendor); bank fields are deferred to WS10. The flow is four steps, not five.

**Layout** (mockup: 300px stepper panel + 608px form panel):

- Left panel: title "Get verified", "About 10 minutes. You can stop and come back.", four step buttons (done / current / todo from `currentStep`; earlier steps are clickable), then the dashed "Karya checks it" card with status-dependent text.
- Right panel, one form per step, mockup copy:
  - **About you:** entity segmented control (An individual / A company), legal name, country, name shown on listings, city. Entity maps to `INDIVIDUAL` / `PRIVATE_LIMITED`; `LLP`, `PARTNERSHIP`, `OTHER` are reachable through a select shown when "A company" is chosen.
  - **Tax IDs:** PAN (mono), GSTIN (optional), PAN card `Dropzone`. After saving, fields show the masked value and a "replace" affordance; the raw value is never re-displayed.
  - **Phone:** mobile number only, with the note "A person at Karya confirms it. Buyers never see it."
  - **Developer agreement:** version 2.1 bullets in plain words, an accept checkbox that sends `agreementVersion: "2.1"`.
- Footer: **Continue** saves the step via `PATCH` and advances; on the last step the button is **Submit for review**.
- Status handling: `PENDING` and `VERIFIED` render read-only with a status pill and banner; `FIX_REQUESTED` shows the reviewer's note and is editable; `REJECTED` is read-only with the note.

**Errors:** `INVALID_FIELD` shows under the offending input; `VERIFICATION_INCOMPLETE` names the missing steps and focuses the first; `VERIFICATION_LOCKED`, 413 and 415 each show one plain sentence. Uses the existing `ErrorState`/`Field` components and `ApiError`.

**Data access:** `lib/api/verification.ts` (wrappers and types). The page is a server component that fetches state; the form is a client component calling server actions, matching how `files` works.

## 4. API keys (`features/apikeys/`)

**Backend contract (WS5c):** `POST /api/v1/developer/api-keys {name}` returns the full `ask_live_…` value once; `GET` lists `id, name, prefix, createdAt, lastUsedAt, revokedAt`; `DELETE /{id}` revokes (idempotent).

- A panel with a table: name, prefix (mono), created, last used, status pill (Active / Revoked), Revoke button. `ResponsiveTable` gives the mobile layout. Revoke opens a `Drawer` to confirm.
- Create: name field + button. The response shows a reveal card with the full key in mono, a Copy button and the line "You won't see this again." The key lives in component state only, never in a URL, cookie or storage, and is cleared when dismissed.
- Empty state: one line pointing at `karya login`.
- Data access: `lib/api/apikeys.ts`.

## 5. Testing

Vitest, matching the repo's existing style:

- API wrappers: request shape and error mapping for both modules.
- Verification: stepper state per `currentStep`; per-step validation messages; locked statuses render read-only; each error code renders its sentence; masked values shown and raw never requested back; submit disabled until steps complete.
- API keys: the key is shown once after create and gone after dismiss; revoke confirm flow; empty state.
- Routing: role gating for both directions; developer rail items; buyer rail unchanged.
- `tsc` clean. `next lint` is not configured in this repo.
- **Not checked in a browser** until the user runs the stack; stated in the PR.

## 6. Docs

Update `docs/known-issues.md` / `docs/frontend-gaps.md` to note that SMS OTP and the payout step from the mockup are intentionally absent.
