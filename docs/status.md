# Development status

The single place to see what is done and what is next. Update it in the same PR as any change that finishes, starts or reshapes a workstream. Gaps and risks stay in `docs/frontend-gaps.md` and `docs/known-issues.md`; this file links to them, it does not copy them.

Last updated: 2026-10-22 (WS10b money in and back done, manual Razorpay test-mode check pending)

Parent spec: `docs/superpowers/specs/2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (workstreams in §5, milestones in §7). Specs and plans named below live in `docs/superpowers/specs/` and `docs/superpowers/plans/`.

## Next up

1. WS10c money out: payout accounts, withdrawals, admin payout console (spec `2026-10-21-ws10-money-in-and-out-design.md` §3); needs Razorpay test keys.
2. The WS10b manual Razorpay test-mode check (`docs/payments.md`).

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
| WS10c money out (payout accounts, withdrawals, admin payout console) | Not started | — | spec `2026-10-21-ws10-…` §3 | All |
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
| Developer Earnings page `/developer/earnings` | Done (WS10a) | #39 | plan `2026-10-21-ws10a-…` | Withdraw, payout account (WS10c) |
| Admin Money page `/admin/money` (fee, credit limit, billing mode, wallet adjustments, platform accounts, ledger checks) | Done (WS10a) | #39 | plan `2026-10-21-ws10a-…` | Withdrawals, freezes (WS10c). Reconciliation issues done in WS10b |
| FE-3 developer money (earnings, payouts) | Partly superseded by the Earnings page above | — | plan `2026-09-23-fe3-…` | Payouts (WS10c) |
| FE-4 admin consoles | Not started | — | plan `2026-09-23-fe4-…` | All |

## Small fixes waiting

- Return `{code, message}` on version create and submit failures (G17).
- Diff a manifest patch against the previous draft, not LIVE (G18).
- Return the reviewer's `decisionNote` on developer verification (G14).
- Return 404, not 403, for another org's run on buyer run paths (`known-issues.md`).
- `EmailDeliveryTest` and `ApprovalLinkTest` fail from Colima clock skew (`known-issues.md`).

## Demo

End-to-end demo on real E2B works (PR #37). Runbook: `docs/demo.md`.
