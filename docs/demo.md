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

1. Sign up as a buyer and fund the wallet: `scripts/demo-fund-wallet.sh you@example.com 10000` (no top-up exists yet).
2. Files, upload `examples/demo-agent/expenses.csv`.
3. Store, open Expense Totaller, connect it (grant `expenses`), bind the file, run.
4. The run page shows the steps (reading, 5 rows, summary), the usage and the charge.

## Known gaps

- A connection needs at least one scope, so an agent with no scopes cannot be connected.
- Wallet top-up and payouts do not exist; use the script above.
- Run outputs from `complete(outputs=...)` are not listed under Outputs; only written files are.
- The Studio's "what it did" half and the flow canvas are not built (P3c).
