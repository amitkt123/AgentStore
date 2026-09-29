# AgentStore agent SDKs

Two SDKs for agents that run inside the AgentStore sandbox and talk to the Runtime API (`/runtime/v1`):

| SDK | Package | Path |
|---|---|---|
| Python (>=3.10) | `agentstore` | `sdk/python/` |
| TypeScript (Node >=22) | `@agentstore/sdk` | `sdk/typescript/` |

Both are layered the same way: a generated client, then a thin `Api` that owns retries and error mapping, then the
components (`data`, `llm`, `gates`, `usage`, `log`) on an `AgentContext`, then `run(handler)`. The sandbox needs no
change: your entrypoint file calls `run()` itself. Publishing and base images are not part of this yet (WS4b).

## Quickstart

```python
from agentstore import AgentContext, run

def handler(ctx: AgentContext) -> dict:
    rows = list(ctx.data.records("bank_statement"))
    ctx.log.info("read bank statement", rows=len(rows))
    entries = [{"account": "1000", "amount": r["amount"]} for r in rows]
    ctx.gates.request("post_journal_entries", f"Post {len(entries)} entries", {"rows": len(entries)},
                      affects=["journal_entries"], raise_on_reject=True)
    result = ctx.data.write("journal_entries", entries)
    ctx.usage.report(len(entries), "reconciled_transaction")
    return {"posted": result.accepted}

if __name__ == "__main__":
    run(handler)
```

```ts
import { run, type AgentContext } from "@agentstore/sdk";

async function handler(ctx: AgentContext) {
  const rows: Record<string, any>[] = [];
  for await (const r of ctx.data.records("bank_statement")) rows.push(r);
  ctx.log.info("read bank statement", { rows: rows.length });
  const entries = rows.map((r) => ({ account: "1000", amount: r.amount }));
  await ctx.gates.request({ action: "post_journal_entries", summary: `Post ${entries.length} entries`,
    payload: { rows: entries.length }, affects: ["journal_entries"], raiseOnReject: true });
  const result = await ctx.data.write("journal_entries", entries);
  await ctx.usage.report(entries.length, "reconciled_transaction");
  return { posted: result.accepted };
}

run(handler);
```

`run()` reads `AGENTSTORE_API_URL` and `AGENTSTORE_RUN_TOKEN` (the sandbox sets them; a missing one fails at once,
naming the variable). On a normal return it flushes logs, stops the heartbeat and completes the run with the returned
dict/object as outputs (anything else fails the run with a clear error). On an exception it logs the stack, fails the
run and re-raises, so the process exits non-zero.

**Gates.** `gates.request(...)` returns a `Decision` whatever the outcome. Pass `raise_on_reject=True` (TypeScript:
`raiseOnReject: true`) to raise `GateRejectedError` when the gate is rejected or expires, instead of checking
`decision.approved` yourself. A buyer rejection also ends the run `CANCELLED` on the platform.

## Development

```bash
# Python
cd sdk/python && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]' && .venv/bin/pytest

# TypeScript
cd sdk/typescript && npm install && npm run typecheck && npm test
```

## Generated clients

The generated code is committed and never edited by hand. After the contract (`contracts/runtime-v1.yaml`) changes:

```bash
sdk/python/regenerate.sh        # needs the Python venv on PATH (openapi-python-client)
sdk/typescript/regenerate.sh    # needs npm install first
scripts/check-sdk-drift.sh      # exits non-zero, naming the script to run, if either is stale
```

CI (`.github/workflows/sdk.yml`) runs both unit suites and the drift check on pull requests that touch `sdk/**` or the
contract.

## End-to-end tests

Test agents in `sdk/*/tests/agents/` (`happy`, `denied`, `rejected`) run as subprocesses against a real backend
(Testcontainers), speaking only `/runtime/v1`. They skip when the venv or `node_modules` is missing.

```bash
cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='*SdkEndToEndTest'
```

These are not in CI yet.
