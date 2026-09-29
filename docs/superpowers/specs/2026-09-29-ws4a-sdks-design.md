# WS4a — Python + TypeScript SDKs

**Status:** design approved, awaiting implementation plan.
**Depends on:** WS1 (runtime API, merged), WS2 (data plane, merged), WS3 (LLM gateway, merged).
**Precedes:** WS4b (base images, conformance suite, publishing).

## 1. Intent and scope

A developer writes ~20 lines to have a working agent in either Python or TypeScript. WS4a delivers both SDKs — generated client, idiomatic layer, and runner — proven end-to-end against the real backend. It does **not** cover:

- Base images (`runtimes/python-3.12`, `runtimes/node-22`) — WS4b.
- The shared YAML conformance suite — WS4b.
- Publishing to PyPI/npm — WS4b.
- Any change to the sandbox/build pipeline (`AgentRuntime.runCommand`, `manifest.entrypoint()` handling) — none needed; see §4.

## 2. Repository layout

```
sdk/python/                        # package: agentstore  (PyPI, published in WS4b)
sdk/typescript/                    # package: @agentstore/sdk (npm, published in WS4b)
```

Generated clients live under each package (e.g. `sdk/python/agentstore/_generated/`, `sdk/typescript/src/generated/`), **committed to the repo**, never hand-edited. Each `sdk/*` directory has a regeneration script (`sdk/python/regenerate.sh`, `sdk/typescript/regenerate.sh`) that runs the codegen tool against `contracts/runtime-v1.yaml` and overwrites the generated directory. A CI check regenerates into a scratch dir and diffs against the committed output, failing with a message that says which script to run — the same pattern as WS1's `runtime-v1.yaml` drift test.

Codegen tools: `openapi-python-client` (Python), `openapi-typescript` + `openapi-fetch` (TypeScript). Both are dev-only dependencies — never required to *use* the SDK, only to regenerate it.

## 3. Layering

### 3.1 Generated client

Thin, mechanical bindings for the 13 `/runtime/v1/**` operations in `contracts/runtime-v1.yaml`. No hand-written logic. The `/llm/chat` request/response bodies are untyped (`additionalProperties: {}`) in the contract, since the wire shape is a passthrough WS3 concern — the generated client sends/receives raw JSON there, and layer 2 gives it a typed face.

### 3.2 Idiomatic layer

Hand-written, built once per run as `AgentContext`, constructed from `GET /context` at startup:

| Member | Backs onto | Notes |
|---|---|---|
| `ctx.run_id`, `ctx.mode`, `ctx.input`, `ctx.limits` | `ContextResponse` | `limits.deadline_at`, `limits.cost_cap_remaining`, `limits.llm.*` straight off the response. |
| `ctx.data.records(resource)` | `GET /resources/{resource}/records` | Lazy iterator; pages via `cursor`/`nextCursor` (default page size 100, server-side). |
| `ctx.data.write(resource, records)` | `POST /resources/{resource}/records` | Returns `accepted` count and whether the write was `staged` (i.e. `requiresApproval`). |
| `ctx.data.file(resource)` | `GET /resources/{resource}/file` + `.../file/content` | Returns a handle exposing the signed `url`/`expiresAt` and a `.read()` that fetches the bytes. |
| `ctx.llm.chat(model, messages, max_tokens, system=None, tools=None, temperature=None)` | `POST /llm/chat` | `messages[i].content` may be passed as a plain string — the server's `LlmRequestNormalizer` accepts string or content-block-array and normalizes. Response wrapper exposes `.content` (raw blocks), `.text` (first text block, convenience), `.stop_reason`, `.usage.{input_tokens,output_tokens}`. |
| `ctx.gates.request(action, summary, payload, affects=[])` | `POST /gates` then polls `GET /gates/{id}?wait=25` | Loops until status is `APPROVED`/`REJECTED`/`EXPIRED` (server caps `wait` at 30s per call); returns a `Decision` with `.approved` (bool) and `.status`. |
| `ctx.usage.report(quantity, unit, outcome_ref=None)` | `POST /usage` | Returns `charged`, `total_charged`, `cost_cap_remaining`. |
| `ctx.log.info/warn/error(message, **fields)` | `POST /logs` | Buffered, flushed in batches (on a size/time threshold and always before `/complete` or `/fail`). |

Cross-cutting:
- **Retries with backoff** on `429`/`5xx` responses, wrapping every generated-client call (shared policy, not per-call).
- **Typed exceptions**, one per RFC 7807 `code`: `ScopeNotGrantedError` (403), `RunTerminatedError` (409), `BudgetExceededError`, `GateRejectedError`, `RateLimitedError` (429). A `NOT_IMPLEMENTED` (501) response — theoretically unreachable now that WS2/WS3 are merged — maps to a generic `AgentStoreApiError`.
- **Heartbeat** — background thread (Python) / timer (Node) posting `POST /heartbeat` every 15s for the lifetime of the handler call, stopped in a `finally` before `/complete` or `/fail`.

### 3.3 Runner

A plain function, not a CLI or module invocation: `agentstore.run(handler)` (Python), `run(handler)` exported from `@agentstore/sdk` (TypeScript). No change to `AgentRuntime.runCommand` or how the sandbox invokes the entrypoint file — the entrypoint script calls the runner itself:

```python
from agentstore import AgentContext, run

def handler(ctx: AgentContext) -> dict:
    ...

if __name__ == "__main__":
    run(handler)
```

```ts
import { run, type AgentContext } from "@agentstore/sdk";

async function handler(ctx: AgentContext) {
  ...
}

run(handler);
```

`run()`:
1. Reads `AGENTSTORE_API_URL`, `AGENTSTORE_RUN_TOKEN`, `AGENTSTORE_RUN_ID`, `AGENTSTORE_MODE` from the environment.
2. Builds `AgentContext` (`GET /context`).
3. Starts the heartbeat.
4. Calls `handler(ctx)`.
5. On normal return: flushes logs, stops the heartbeat, `POST /complete {outputs: <return value>}`.
6. On exception: flushes logs (including the stack trace as an `error`-level entry), stops the heartbeat, `POST /fail {error, retriable: false}`, then re-raises so the process exits non-zero.

## 4. Why no sandbox/build-pipeline change

The spec's original runner sketch (`python -m agentstore.run`, importing the manifest's entrypoint file as a module) would have required changing `AgentRuntime.runCommand` (`backend/src/main/java/com/agentstore/platform/manifest/AgentRuntime.java`) to route execution through the SDK rather than `exec`-ing the entrypoint file directly. Decided against this: it would touch code owned by WS6a/the build pipeline for no behavioral gain. Instead, the entrypoint file is ordinary Python/Node that imports and calls the runner itself (§3.3) — `exec python '<entrypoint>'` already does the right thing with zero backend changes.

## 5. Testing

- Each SDK gets one hand-written test agent exercising the full path — `read → gate approved → write → usage → complete` — run against the real Spring Boot backend via Testcontainers (same pattern as the existing runtime-API end-to-end tests).
- A second scenario per SDK for the failure path: undeclared resource → `ScopeNotGrantedError`; gate rejected → `GateRejectedError` surfaced to the handler's caller (not swallowed).
- Unit tests per idiomatic-layer component with a mocked HTTP layer: retry/backoff on 429 and 5xx, error-code-to-exception mapping, gate polling (multiple `PENDING` responses before a terminal status), heartbeat start/stop timing, log batching/flush-on-completion.
- No shared YAML conformance suite yet — that is WS4b, once both SDKs exist to run it against.

## 6. Acceptance

Both of the spec's worked examples (bank-statement/invoice matcher) run end-to-end, in CI, against a locally-run backend + `LocalDockerSandboxProvider`-equivalent test harness, producing a `COMPLETED` run with the expected `journal_entries` write and a `GATE_DECIDED` step. Both SDKs' unit and integration suites are green.

## 7. Follow-ups (deferred to WS4b or later)

- Base images (`runtimes/python-3.12`, `runtimes/node-22`): pinned OS + runtime + SDK version + common libs, non-root user, read-only SDK install.
- Shared YAML conformance suite (`sdk/conformance/`) run against both SDKs in CI.
- PyPI/npm publishing on tag, semver tied to the runtime API major version.
- Docs site generation from docstrings/TSDoc (§WS12 in the master spec).
