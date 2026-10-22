# UI/UX roadmap

Consolidates three reviews of the portal (code read, a click-through of the running app as buyer and developer on 2026-10-22, and an outside review) into one list, sorted by what each change depends on. Phase UX-1 has a plan (`docs/superpowers/plans/2026-10-22-ux1-portal-polish.md`); later phases get their own spec or plan when they start.

## Principles

- **A calm operations console with a store in front, not a consumer app store.** Keep the current tokens, type and flow-block language (Reads, Drafts, Asks you, Charges). They are the product's clearest idea.
- **Never promise a control the platform does not enforce.** Data classes, outside websites and the developer's cost cap are declarations today (`docs/known-issues.md`). Say so on screen.
- **Adding an agent and spending money are separate steps.** Nothing a buyer clicks while setting up starts paid work.
- **The approval screen is the most important screen.** It shows what will change, how much money is involved, who decides and when it expires, in the same form in the app and from the email link.
- **Motion shows state changes only.** No decorative "agent thinking" effects, gradients or chat bubbles.
- **Hide unfinished features instead of disabling them.** Examples: search, Google sign-in, withdrawals.

## Findings by phase

### UX-1 Portal polish (frontend only, no backend change)

Shell and navigation
- No sign-out and no account menu: the avatar is a plain span, and `logoutAction` is never called.
- The rail is icons only, with no tooltip on desktop and no label on mobile. Billing's wallet icon reads as a folder.
- A disabled "Search is coming soon" box appears in every header (G1).
- "Continue with Google" shows on login although Google is not configured (G10), and the SSO note under it points at it.
- Signed-in users can open `/login`.
- No `error.tsx`, `loading.tsx` or `not-found.tsx`: a backend error or slow load gives Next's default screens.
- An admin's Home says "The developer console is being rebuilt".

Store and setup
- The store shows one card per LIVE version, so Bank Reconciliation appears twice.
- Store blocks show the run state "Not started", and the block panel opens on "Pick a block" and repeats the card.
- "Data it handles" and "Outside websites" read as guarantees. Label them "Declared by the developer".
- The store does not say an agent is already added, so buyers add the same agent again (Home shows four Bank Reconciliation rows).
- The connect page never mentions the write scope it grants (Bank Reconciliation's `ledger`) or the approval before posting.
- "Add and run now" adds the agent and starts a paid run in one click.
- Home's "Your agents" rows have no version, link or action, and there is no way to run an added agent again.

Runs and approvals
- Times show without a date in Runs, Files and the timeline.
- The Runs "Reached" column reads "Charges Charges" and "Drafts Ledger".
- The timeline shows "Agent note" with no text; `RunStep.detail` carries the note.
- The approval table shows raw keys (`debit`), unformatted numbers (`185000`) and left-aligned amounts.
- "You approved this." shows to whoever views the run, including a buyer admin who cannot approve.
- A decided gate stays as a full amber card above the flow on a finished run.

Developer and money
- The Studio shows a disabled "Submit for review" on a LIVE version.
- Earnings is titled "Available to withdraw" though withdrawals do not exist (WS10c).
- Billing silently hides any payment panel whose call fails. On a stale backend, Add money simply vanishes.
- Sparse pages feel empty on the dotted background. Keep the dots on auth and empty states only, and use a plain background behind dense content.

### UX-2 Small backend additions with their screens

| Change | Backend need | Screen |
|---|---|---|
| Rich store cards: one-line job, price, maximum per run, "Can write: … after your approval", publisher | `CatalogListingResponse` gains `description`, `unitPrice`, `unitLabel`, `maxPerRun`, `currency`, `writes[]`, `approvalRequired`, `publisherName`, `publisherVerified`; one row per agent (latest LIVE) | Store grid |
| Who decided a gate | `GateDetail` gains `decidedBy: { name } \| null` (the run list already has it) | GateCard |
| Approval email page with the same detail as the app | `ApprovalCard` gains `costIfApproved`, `preview` and the number of eligible approvers | `/approve/{token}` |
| Revoke an added agent | `POST /connections/{id}/revoke` wired to `BindingService.unbindAll` (known issue) | Home / agent row "Remove" |
| Block descriptions on a live run (G11) | Run detail gains `agentId`, `versionId` | LiveRun |
| Unique agent names per developer org | 409 on duplicate name | New agent form |
| Real reasons on version create/submit failures (G17) | `{code, message}` body | Studio |
| Agent output summary on a finished run | Run detail gains outputs list (known gap in `docs/demo.md`) | LiveRun "Result" panel |

### UX-3 Home and developer overview

- **Buyer Home becomes the place to act:** Needs you, Running now, Recent results, Spend this month against wallet, Your agents with Run now, and a first-run checklist (add money, upload a file, add an agent, run it). Data from existing endpoints plus UX-2.
- **Developer overview at `/developer`** replaces landing on Get verified: drafts and their readiness blockers, versions in review, live versions with runs this month and earnings, and verification status as one card.

### UX-4 Trust evidence (follows WS8, the WS6 network proxy and WS5d)

- Each control on the store page and Studio shows one of four states: **Declared** (manifest), **Checked** (review), **Enforced** (the platform blocks violations at run time), **Observed** (sandbox or eval runs). UX-1 ships only the "Declared" label.
- Separate badges replace a single "Verified": Publisher verified (WS7, exists), Security reviewed, Behaviour tested (WS8), Network enforced (proxy). Each badge links to dated evidence and a version.
- Studio "what it did" half: declared versus observed (P3c/G16).

### UX-5 Follows other workstreams

| Change | Waits on |
|---|---|
| Try on sample data before adding (G8) | WS9 trial runs |
| Version updates: price, scope and host diff before upgrading; re-approval if access grows; "1 new" badge (G9) | WS11 |
| Search across agents, runs and files, ⌘K (G1) | Search backend |
| Notification bell (G2) | WS11 / WS14b |
| Org monthly limit, spend alerts, activity feed (G5) | WS15c |
| Disputes from a run or bill line | WS16 |
| Withdraw and payout account (G21) | WS10c |

## Not doing, and why

- **A "confidence" figure on approvals:** the runtime API carries no such value, and an invented number undermines the approval screen.
- **Server-sent events for live runs:** polling every 3 seconds is enough at current volumes. Revisit once runs stream many steps.
- **Rename the product:** the UI already says Karya everywhere; "Agent store" is a section name, and AgentStore is the repository.
- **Dark mode:** cheap later because colours are tokens in `ui/tokens.css`, but no user has asked for it.
- **Policy templates ("Observe only", "Autopilot"):** the backend has no per-connection policy beyond scopes and the spend cap.

## Correction to the outside review

Developers do not land on "The developer console is being rebuilt": the middleware sends them to Get verified (`lib/gate.ts`). That text shows only to admins, and UX-1 fixes it.
