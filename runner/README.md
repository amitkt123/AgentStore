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

## Builds

The runner also turns an uploaded code bundle into a sandbox image. Spring queues a build and the runner runs it.

- `POST /builds` (`{buildId, runtime, bundleUrl, bundleSha256, entrypoint, logUploadUrl}`) returns 202 with
  `{buildId, status, backend, imageRef, failureCode, failureReason}`. It is idempotent on `buildId`.
  `GET /builds/{buildId}` returns the same shape. Both need `X-Runner-Secret`.
- One build runs at a time. State is in memory only. After a restart the runner answers `GET` for an unknown id with
  `FAILED` / `RUNNER_RESTARTED`, and Spring re-queues the build, at most 3 attempts in all. Finished builds are
  forgotten after 24 hours.
- Status is `QUEUED`, `BUILDING`, `SUCCEEDED` or `FAILED`.

Pipeline, in order: download the bundle from its pre-signed URL (at most 100 MB, sha256 checked) -> unpack (at most
500 MB and 20,000 entries, no path may leave the build directory, no symlink is created) -> check the entrypoint is a
file in the bundle -> scan for secrets with gitleaks -> build the image on the backend. The log is uploaded to its
pre-signed URL every few seconds and once more at the end. Lines pass through a redactor that masks AWS keys, GitHub
tokens, `sk-` keys and credentials in URLs.

| `failureCode` | Meaning |
|---|---|
| `INVALID_BUNDLE` | Bad download or sha256 mismatch, unknown runtime, hostile or oversized zip, or the entrypoint isn't in the bundle. |
| `SECRET_FOUND` | gitleaks found a secret. The reason names file, line and rule, never the secret. |
| `INSTALL_FAILED` | Installing the dependencies failed (Node: also when `package.json` has no `package-lock.json`). |
| `DEP_VULN` | `pip-audit` (Python) or `npm audit --audit-level=high` (Node) reported a vulnerability. |
| `SMOKE_FAILED` | The entrypoint doesn't load: it is imported without running `__main__` (Python) or checked with `node --check` (Node). |
| `TIMEOUT` | The build took longer than `agentstore.runner.build.timeout` or the backend's build timeout. |
| `RUNNER_RESTARTED` | The runner has no record of the build. Spring re-queues it. |
| `BUILD_FAILED` | Anything else, including a broken scanner or a backend error. |

Spring also uses `SUPERSEDED` when a newer push replaces a queued build. The runner never returns it.

Runtimes: `python-3.12` (`python:3.12-slim`, entrypoint `.py`) and `node-22` (`node:22-slim`, entrypoint `.js`,
`.mjs` or `.cjs`). The app is copied to `/workspace/app` in the image.

**Prerequisite: `gitleaks` must be on `PATH`** (`brew install gitleaks`, or the release binary on Linux). Without it
every build fails with `BUILD_FAILED`. To use another command, set `agentstore.runner.build.gitleaks-command`. The
scan runs `gitleaks dir --redact`, and the runner deletes a bundle's own `.gitleaks.toml` and `.gitleaksignore`
before it runs, so a bundle can't switch off its scan.

Settings in `application.yml`:

| Setting | Default | Meaning |
|---|---|---|
| `agentstore.runner.build.gitleaks-command` | `[gitleaks]` | Command that runs gitleaks. |
| `agentstore.runner.build.timeout` | `PT15M` | Whole build; failing it gives `TIMEOUT`. |
| `agentstore.runner.build.scan-timeout` | `PT2M` | The secret scan alone. |
| `agentstore.runner.build.download-timeout` | `PT5M` | Downloading the bundle. |
| `agentstore.runner.build.log-flush-interval` | `PT5S` | How often the log is uploaded while building. |
| `agentstore.runner.build.work-dir` | `${java.io.tmpdir}/agentstore-builds` | Where bundles are unpacked (env `RUNNER_BUILD_DIR`). Each build's directory is deleted when it ends. |
| `agentstore.runner.e2b.build-timeout` | `PT14M` | The E2B template build inside the shim. Keep it below `build.timeout`. |

**E2B specifics**

- The shim uploads the unpacked app directory from the runner into the template build, then runs the install,
  audit and smoke-test steps inside it. The pip, pip-audit and smoke-test output ends up in the build log.
- Template builds can't restrict network egress, so `pip` and `npm` reach the internet from the build sandbox. This is
  an accepted gap for development only (spec section 5), closed by the gVisor backend in WS5d.
- Built templates stay in the E2B dashboard. `imageRef` is the E2B template id. WS5d adds cleanup.

To run the tagged live build test on real E2B (a few minutes of credit per template):

```bash
E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bImageBuildLiveTest
```

To push a directory through the whole pipeline in development, use the CLI (`cli/README.md`): create an API key in the
portal, then `agentstore login`, `agentstore init` and `agentstore push --wait`.

## Tests

- `mvn test` covers unit tests and the conformance suite on the local fake backend. No Docker or E2B needed.
- `cd e2b-shim && npx vitest run` runs the shim tests.
- `E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bBackendConformanceTest` runs the
  conformance suite on real E2B, including the egress tests. It costs a few cents.

## Point Spring at the runner

Run the backend with `SPRING_PROFILES_ACTIVE=dev,runner` (or set `SECRET_ENCRYPTION_KEY`), `RUNNER_BASE_URL=http://localhost:8090` and the same
`RUNNER_SECRET`. E2B sandboxes must reach Spring's callback URL, so for local development expose Spring with
`cloudflared tunnel --url http://localhost:8080` and set `CALLBACK_BASE_URL` to the printed https URL.
