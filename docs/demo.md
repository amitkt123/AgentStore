# Running the demo

Everything below runs on your machine except the sandbox, which runs on E2B and reaches your backend through a free
cloudflared tunnel.

## Start

Needs Colima/Docker, Node 22+, Maven, Java 17+, `gitleaks` and `cloudflared` (`brew install gitleaks cloudflared`).

```bash
echo 'E2B_API_KEY=e2b_...' > .dev/env       # git-ignored; create .dev/ first
scripts/dev-up.sh                            # stores, tunnel, runner, backend (profile dev,runner), frontend
(cd cli && npm run build && npm link)        # once
```

Portal: http://localhost:3000. Logs: `.dev/*.log`. Stop: `scripts/dev-down.sh`. The tunnel URL changes on every start;
that is fine, the backend is started with the new one.

Do not start the backend without the `runner` profile: Spring then uses a fake build and sandbox client, builds
"succeed" and runs sit in RUNNING with a `fake-sandbox-…` id.

## Developer

1. Sign up as a developer, then API keys, create a key, and `export AGENTSTORE_API_KEY=ask_live_...`.
2. `scripts/demo-agent.sh` creates "Expense Totaller" (read-only, one `expenses` CSV scope, no LLM) and pushes it. The
   build takes about 30 seconds on E2B.
3. Open the agent in the portal, Studio, and Submit. A read-only agent goes straight to LIVE.

## Buyer

1. Sign up as a buyer and fund the wallet: `scripts/demo-fund-wallet.sh you@example.com 10000` (no top-up exists yet). The script logs in as the demo admin
   (`ADMIN_EMAIL`/`ADMIN_PASSWORD`, default `demo-admin@example.com` / `Demo-pass-12345`, so run
   `demo-make-admin.sh` first) and adds the amount through `POST /api/v1/admin/orgs/{orgId}/wallet/adjustments`; it prints
   the new balance. Running it again adds again.
2. Files, upload `examples/demo-agent/expenses.csv`.
3. Store, open Expense Totaller, connect it (grant `expenses`), bind the file, run.
4. The run page shows the steps (reading, 5 rows, summary), the usage and the charge.

## Bank reconciliation (approval gate, admin review)

The richer demo: reads a bank statement CSV, maps lines to accounts, **stages** journal entries and opens an approval
gate. Approving it commits the entries; the buyer is charged per reconciled transaction. It has a WRITE scope, so a
version needs an admin review before it goes live.

Email is in log mode and never prints links (they carry one-time tokens), so invitations, password resets and
approval emails cannot be followed locally. These dev scripts stand in for them:

```bash
# one-time accounts: sign up in the portal (or POST /api/v1/auth/signup) as
#   demo-developer@example.com (developer), demo-buyer@example.com (buyer), demo-admin@example.com (developer),
#   demo-approver@example.com (buyer). Password for all: Demo-pass-12345
scripts/demo-make-admin.sh demo-admin@example.com
scripts/demo-add-approver.sh demo-approver@example.com demo-buyer@example.com
scripts/demo-fund-wallet.sh demo-buyer@example.com 10000

EXAMPLE=bank-reconciliation DEMO_AGENT_NAME="Bank Reconciliation" scripts/demo-agent.sh   # push a first version
# later versions: re-run with the same directory as the argument and VERSION=0.2.0
```

1. Developer: Studio, Submit. The version goes to IN_REVIEW.
2. Admin (demo-admin): review queue, approve. (API: `GET /api/v1/review/queue`, `POST /api/v1/review/{id}/approve`.)
3. Buyer (demo-buyer): upload `examples/bank-reconciliation/bank_statement.csv`, connect the agent granting
   `bank_statement` and `ledger`, bind the file to `bank_statement`, run.
4. Approver (demo-approver): the run waits at the `post_entries` gate. It shows the staged rows and the debit total.
   Approve it; the run finishes and the ledger output is COMMITTED. Rejecting discards the rows and cancels the run
   (not exercised yet).

Order matters in the agent: it stages the ledger rows first and opens the gate second. Rows written after the gate is
decided have nothing left to commit them and are DISCARDED.

## Known gaps

- A connection needs at least one scope, so an agent with no scopes cannot be connected.
- Wallet top-up and payouts do not exist; use the script above.
- Run outputs from `complete(outputs=...)` are not listed under Outputs; only written files are.
- The Studio's "what it did" half and the flow canvas are not built (P3c).
