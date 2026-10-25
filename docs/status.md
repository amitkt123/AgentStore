# Development status

The single place to see what is done and what is next. Update it in the same PR as any change that finishes, starts or reshapes a workstream. Gaps and risks stay in `docs/frontend-gaps.md` and `docs/known-issues.md`; this file links to them, it does not copy them.

Last updated: 2026-10-25 (execution bug-hunt fixes; WS10c money out done incl. final-review fixes and UPI username masking, manual Razorpay and RazorpayX test-mode checks pending; buyer admins can approve gates, BUYER_APPROVER renamed BUYER_MEMBER, members get Run now, gate buttons only for viewers on the approver list; run page: approve from the clicked approval block, "Waiting for an approver" for non-approvers, full-width run page; run replay flow on the run page; UX-G2 flow canvas done)

Parent spec: `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (workstreams in §5, milestones in §7). Specs and plans named below live in `docs/superpowers/specs/` and `docs/superpowers/plans/`.

## Next up

1. The manual Razorpay (WS10b) and RazorpayX (WS10c) test-mode checks (`docs/payments.md`).
2. WS16 disputes.

## Backend workstreams

| Workstream | Status | PRs | Spec / plan | Left to do |
|---|---|---|---|---|
| WS0 execution defect fixes | Done | #2, #3 | plan `2026-09-23-ws0-…` | — |
| WS1 Agent Runtime API v1 | Done | #10 | `2026-09-26-ws1-…` | — |
| WS1b buyer run API | Done | #24, #25, #27 | `2026-09-29-ws1b-…` | — |
| WS2 data plane | Done | #11 | `2026-09-26-ws2-…` | Connection revoke not wired (`unbindAll` unused) |
| WS3 LLM gateway | Done | #12 | `2026-09-26-ws3-…` | — |
| WS4a SDKs (Python, TypeScript) | Done | #19, #20, #22 | `2026-09-29-ws4a-…` | — |
| WS4b base images, conformance suite, publishing to PyPI/npm | Not started | — | none | All |
| WS5a/b/c runner, build pipeline, API keys + CLI | Done | #6, #7, #8, #9 | `2026-09-25-ws5…`, `2026-09-26-ws5c-…` | — |
| WS5d self-hosted sandbox backend (gVisor), off E2B | Not started | — | parent spec §7 | All; needed before production hosting |
| WS6a/b/c developer sandbox | Done | #14, #15, #16 | `2026-09-27-ws6…` | In-sandbox proxy that posts `network-events` |
| WS7a/b developer verification | Done | #17, #18 | `2026-09-28-ws7…` | Reviewer note not returned to developer (G14) |
| WS8 automated review (evals, red-team, cost profiling, CLI `submit`) | Not started | — | parent spec §5 | All |
| WS9 buyer trial runs | Not started | — | parent spec §5 | All |
| WS10a double-entry ledger, revenue split, LLM cost deduction, postpaid mode, buyer statement, earnings | Done | #39 | spec `2026-10-21-ws10-money-in-and-out-design.md` §1, plan `2026-10-21-ws10a-…` | `wallet.balance` and `ledger_entry` still dual-written (`known-issues.md`) |
| WS10b money in and back (top-up, refunds, postpaid bills, reconciliation) | Done (manual Razorpay test-mode check pending) | this PR | spec `2026-10-21-ws10-…` §2, plan `2026-10-21-ws10b-money-in-and-back.md` | Run the checklist in `docs/payments.md` once Razorpay test keys exist |
| WS10c money out (payout accounts, withdrawals, admin payout console) | Done (manual RazorpayX test-mode check pending) | this PR | spec `2026-10-21-ws10-…` §3 | Run the RazorpayX checklist in `docs/payments.md`; known gaps in `known-issues.md` |
| WS11 release management (pinning UX, updates, rollout, delisting) | Not started | — | parent spec §5 | All |
| WS12 platform infrastructure and operations | Partial | #33, #37, #40, #42, #43, #44 | parent spec §5 | Docs site, deploy, observability. CI is one workflow per project (`.github/workflows/{backend,runner,e2b-shim,frontend,cli,sdk}.yml`), each run only on pull requests to master that touch its paths (backend also on `contracts/` and `sdk/`; CLI also on the backend manifest schema); actions pinned to Node 24 majors (checkout v7, setup-node v7, setup-java v6, setup-python v7) |
| WS13 identity, workspaces, teams | Done | #4, #5 | `2026-09-23-ws13-…` | SSO; Google sign-in needs a client id (G10) |
| WS14a notifications, email approval links | Done | #32, #33, #34 | `2026-09-30-ws14a-…` | — |
| WS14b Slack, quiet hours, weekly digest | Not started | — | `2026-09-23-ws14-…` | All |
| WS15 per-agent monthly limit, approver list | Done | #29 | — | — |
| WS15b outbox, database-checked gate decisions | Done | #30, #31 | `2026-09-30-ws15b-…` | — |
| WS15c org monthly limit, spend alerts, activity feed, home, search | Not started | — | `2026-09-23-ws15-…` | All |
| WS16 disputes | Not started (needs WS10) | — | `2026-09-23-ws16-…` | All |

## Frontend

| Phase | Status | PRs | Spec / plan | Left to do |
|---|---|---|---|---|
| P0 + P1 buyer rebuild | Done | #26 | `2026-09-29-fe-rebuild-p0-p1…` | — |
| Approval link page `/approve/{token}` | Done | #34 | — | — |
| P3a developer shell, Get verified, API keys | Done | #35 | `2026-09-30-p3a-…` | — |
| P3b agents list and Studio | Done | #36 | `2026-10-01-p3b-…` | — |
| P3c Studio "what it did" half and flow canvas | Not started | — | none | All (G16) |
| Buyer Billing page `/billing` | Done (WS10a, WS10b) | #39, this PR | plans `2026-10-21-ws10a-…`, `2026-10-21-ws10b-…` | — |
| Developer Earnings page `/developer/earnings` | Done (WS10a, WS10c) | #39, this PR | plan `2026-10-21-ws10a-…` | — |
| Admin Money page `/admin/money` (fee, credit limit, billing mode, wallet adjustments, platform accounts, ledger checks, money freezes) | Done (WS10a, WS10c) | #39, this PR | plan `2026-10-21-ws10a-…` | — |
| Admin Payouts page `/admin/payouts` (open withdrawals, name-mismatch payout accounts to approve) | Done (WS10c) | this PR | plan `2026-10-22-ws10c-…` | — |
| Admin Reconciliation page `/admin/reconciliation` (payment and payout issues by day, resolve) | Done (WS10c) | this PR | plan `2026-10-22-ws10c-…` | — |
| FE-3 developer money (earnings, payouts) | Superseded by the Earnings page above | — | plan `2026-09-23-fe3-…` | — |
| FE-4 admin consoles | Not started | — | plan `2026-09-23-fe4-…` | All |
| UX-1 portal polish (frontend only) | Done | this PR | spec `2026-10-22-ux-roadmap.md`, plan `2026-10-22-ux1-portal-polish.md` | — |
| UX-G glass and flow | G1, G2 done, run replay (this PR); G3 next | #48, #49 | spec `2026-10-25-glass-flow-ui-design.md`, plan `2026-10-25-ux-g1-g2-glass-and-flow-canvas.md` | G2 to G5 |
| UX-2 to UX-5 (store cards, approvals, Home, trust evidence) | Not started | — | spec `2026-10-22-ux-roadmap.md` | All; UX-4 and UX-5 wait on WS8, WS9, WS11 and others |

## Small fixes waiting

- Return `{code, message}` on version create and submit failures (G17).
- Diff a manifest patch against the previous draft, not LIVE (G18).
- Return the reviewer's `decisionNote` on developer verification (G14).
- Return 404, not 403, for another org's run on buyer run paths (`known-issues.md`).
- `EmailDeliveryTest` and `ApprovalLinkTest` fail from Colima clock skew (`known-issues.md`).
- `GET /api/v1/runs/mine` has no paging and nothing on master calls it: add paging or retire it.

## Execution bug-hunt fixes

From a static bug hunt of `platform/execution` on 2026-10-25, fixed in this PR (plan `2026-10-25-execution-bug-hunt-fixes.md`):

- The monthly limit now counts what in-flight runs have already charged.
- The legacy COST callback quantity is bounded like `/runtime/v1/usage`.
- `run.reached_block` is TEXT (migration V51), so long block names no longer fail the run or drop the violation count.
- Gate decisions are refused (409) after the gate's expiry.

## Demo

End-to-end demo on real E2B works (PR #37). Runbook: `docs/demo.md`.
