# sandbox-runner

Internal service that creates, starts, inspects and kills agent sandboxes behind a pluggable `IsolationBackend`
(spec: `docs/superpowers/specs/2026-09-25-ws5-sandbox-runner-build-pipeline-cli-design.md`).
Only this process holds vendor credentials. Never expose it publicly.

## Run locally (E2B backend)

```bash
cd runner/e2b-shim && npm ci && cd ..
RUNNER_SECRET=dev-runner-secret E2B_API_KEY=e2b_... mvn spring-boot:run
```

| Env | Default | Meaning |
|---|---|---|
| `RUNNER_SECRET` | (required) | Shared secret; Spring sends it as `X-Runner-Secret`. |
| `RUNNER_BIND` | `127.0.0.1` | Listen address. Loopback only by default; set `0.0.0.0` when Spring runs on another host. |
| `RUNNER_BACKEND` | `e2b` | `e2b` now; `gvisor` arrives in WS5d. |
| `E2B_API_KEY` | (required for e2b) | Only the runner has it. |
| `SPRING_BASE_URL` | `http://localhost:8080` | Asked at startup and on every 60 s sweep which runs are active, so orphaned sandboxes are killed. |
| `RUNNER_STATE_FILE` | `./runner-budget.json` | Monthly sandbox-time ledger. |

Budget guard (protects E2B credit): 2 concurrent sandboxes, 10 min max each, 10 h per month. Change under
`agentstore.runner.budget` in `application.yml`.

## Tests

- `mvn test` covers unit tests and the conformance suite on the local fake backend. No Docker or E2B needed.
- `cd e2b-shim && npx vitest run` runs the shim tests.
- `E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bBackendConformanceTest` runs the
  conformance suite on real E2B, including the egress tests. It costs a few cents.

## Point Spring at the runner

Run the backend with `SPRING_PROFILES_ACTIVE=runner`, `RUNNER_BASE_URL=http://localhost:8090` and the same
`RUNNER_SECRET`. E2B sandboxes must reach Spring's callback URL, so for local development expose Spring with
`cloudflared tunnel --url http://localhost:8080` and set `CALLBACK_BASE_URL` to the printed https URL.
