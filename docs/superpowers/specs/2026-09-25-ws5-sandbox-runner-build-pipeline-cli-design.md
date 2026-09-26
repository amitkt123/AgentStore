# WS5 — Sandbox runner, build pipeline, CLI — Design

> **Status:** Draft for review · **Date:** 2026-09-25
> **Parent spec:** `2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (WS5). This document replaces the parent's E2B `sandbox-bridge` design (D6, §3, §3.1) and refines WS5. Where the two disagree, this document wins.
> **Plans:** WS5a (runner + sandbox provider), WS5b (build pipeline), WS5c (API keys + CLI), and WS5d (gVisor backend, later). Each gets its own plan under `docs/superpowers/plans/`.

---

## 1. Intent and constraints

**Outcome:** a developer runs `agentstore push`. The platform builds a runnable image that it owns and attaches it to the agent version. When a run is triggered, the agent process actually starts (this fixes defect **B2**), reaches `/runtime/v1`, and exits, and Spring sees the exit code and logs.

**Constraints decided by the owner:**

| # | Constraint | Consequence |
|---|---|---|
| C1 | **No dependence on paid vendors in the long run.** Sandboxing must end up running on our own infrastructure with free, open-source software. | A self-hosted gVisor backend (WS5d) is the production target. |
| C2 | **Use the $100 of free E2B credit for testing now.** | E2B is the first backend (WS5a). A budget guard protects the credit. |
| C3 | **Easy to switch vendors.** | Exactly one vendor seam, `IsolationBackend`, inside the runner. Every backend must pass the same conformance tests. Vendor terms never leak outside the backend class. |
| C4 | **Hosting:** development runs on a Mac (Colima). M1 production is one self-hosted Linux box running Postgres, Spring, SeaweedFS (object store), the registry and the runner. | One runner instance in WS5. Nothing in the design may prevent adding more runners later (§9). |
| C5 | **Object store: SeaweedFS (Apache-2.0), used only through the S3 API.** MinIO stopped publishing free images in October 2025 and is in maintenance mode, so it was dropped on 2026-09-25. | Spring uses the AWS SDK v2 S3 client, so any S3-compatible store (SeaweedFS, Garage, AWS S3) can replace it through config alone. The runner never holds store credentials: it only uses pre-signed URLs. |

**Success criteria:**
- `agentstore init && agentstore push --wait` produces a SUCCEEDED build.
- A bundle containing a hard-coded AWS key fails with `SECRET_FOUND` and a readable reason.
- A triggered run starts the agent. The agent calls `/runtime/v1`, then exits, and Spring records the exit and the logs.
- The egress conformance tests pass on E2B.
- The submission wizard no longer asks for a template ID.

---

## 2. Architecture

```
Spring backend ──HTTP + X-Runner-Secret──▶ sandbox-runner (Maven module runner/, Java 21)
  RunnerSandboxProvider                      ├─ RunnerApi        (/builds, /sandboxes)
  (replaces E2bSandboxProvider)              ├─ BuildService / SandboxService
  BuildDispatcher / BuildPoller              ├─ Shared: BundleFetcher (S3), Unpacker, SecretScanner (gitleaks),
                                             │          LogSink (S3), BudgetGuard, Reaper
                                             └─ IsolationBackend  ◀── the only vendor seam
                                                  ├─ E2bBackend    (E2B REST + Node shim)      WS5a/b
                                                  └─ GvisorBackend (Docker + runsc + BuildKit
                                                                    + registry:2 + egress proxy) WS5d
Agent code inside a sandbox ──HTTPS, per-run token──▶ Spring /runtime/v1
```

- **Spring doesn't know which vendor is used.** It keeps the `SandboxProvider` interface. `RunnerSandboxProvider` replaces `E2bSandboxProvider`, which is deleted along with its tests; `FakeSandboxProvider` stays.
- **The runner is the only process holding vendor credentials** (the E2B key) and, in WS5d, the Docker socket. It binds to the internal network only.
- **The backend is chosen by configuration:** `agentstore.runner.backend=e2b|gvisor`.
- **Vendor-neutral vocabulary** is used everywhere outside the backend classes: *build, image, imageRef, sandbox, exec, logs, kill*. Terms like "E2B template" and "envd" appear only inside `E2bBackend` and the shim.

### 2.1 `IsolationBackend`

```java
public interface IsolationBackend {
    String name();                                            // "e2b", "gvisor"
    ImageRef buildImage(Path appDir, Runtime runtime, String entrypoint, BuildLog log);
    String create(ImageRef image, SandboxLaunch launch);      // creates the sandbox AND starts the wrapper; returns sandboxId
    SandboxState status(String sandboxId);                    // RUNNING | EXITED(code) | GONE | UNKNOWN
    LogChunk readLogs(String sandboxId, long offset);
    void kill(String sandboxId);                              // idempotent
    List<OwnedSandbox> listOwned();                           // sandboxes carrying this runner's labels
}
```

`SandboxLaunch` carries `requestId`, `env`, `allowOut`, `cpu`, `memoryMb`, `timeoutSeconds`, and `labels` (`agentstore.requestId` and `agentstore.runnerId`).

### 2.2 The run wrapper

Every image built by any backend contains `/usr/local/bin/agentstore-run`. It runs the entrypoint, sends stdout and stderr to `/workspace/.run/log`, and writes the exit code to `/workspace/.run/exit` when the process ends. `status` and `readLogs` read these two files. As a result, the meaning of "exit code" and "logs" doesn't depend on the vendor.

### 2.3 E2B backend and the Node shim

- **Lifecycle calls that E2B exposes over REST** (create, kill, list, and alive or not) use E2B's REST API from Java.
- **Calls that exist only in E2B's SDK** go through `runner/e2b-shim/`, a small Node script (about 150 lines) built on the official E2B JS SDK: starting the wrapper with `commands.run` in the background, reading files, and template builds.
- **One shim process per call**, with JSON on stdin and a JSON result on stdout, a non-zero exit on error, and a per-call timeout enforced by Java. Call volume is low (create, a few status polls, kill), so a long-running shim process isn't needed.
- **The runner needs Node only when `backend=e2b`.** The shim is deleted if E2B is retired.

---

## 3. Runner API (internal)

All requests need `X-Runner-Secret`, which is compared in constant time. The API is never exposed publicly.

| Method | Path | Behaviour |
|---|---|---|
| POST | `/builds` | Body: `{buildId, runtime, bundleUrl (pre-signed), bundleSha256, entrypoint}`. Returns 202 and runs the build asynchronously. Repeating it with the same `buildId` returns the existing build. |
| GET | `/builds/{buildId}` | Returns `{status: QUEUED/BUILDING/SUCCEEDED/FAILED, backend, imageRef, logKey, failureCode, failureReason}`. An unknown `buildId` returns `FAILED` with `RUNNER_RESTARTED`. |
| POST | `/sandboxes` | Body: `{requestId, imageRef, command?, env, allowOut, cpu, memoryMb, timeoutSeconds}`. Creates the sandbox and starts the agent through the wrapper. **Idempotent on `requestId`:** a retry returns the existing `sandboxId`. The budget guard or a capacity limit returns 429 with `BUDGET_EXHAUSTED` or `CAPACITY_EXHAUSTED`. |
| GET | `/sandboxes/{id}` | Returns `{state: RUNNING/EXITED/GONE/UNKNOWN, exitCode?}`. `UNKNOWN` means the backend couldn't be reached, and callers must not treat it as exited (the same rule as today's `SandboxStatus`). |
| GET | `/sandboxes/{id}/logs?offset=N` | Returns `{data, nextOffset, eof}` so the CLI and portal can tail logs. |
| DELETE | `/sandboxes/{id}` | Kills the sandbox. Idempotent: returns 204 even if the sandbox is already gone. |

**The runner keeps no database.** Running sandboxes are rebuilt from `listOwned()` labels when the runner starts. Builds in progress are held in memory; if they're lost in a restart, Spring sees `RUNNER_RESTARTED` and queues the build again. The final record of every build lives in Spring's `template_build` table.

**Spring-side changes:**
- `SandboxSpec` becomes `(requestId, imageRef, command, env, allowOut, cpu, memoryMb, timeoutSeconds)`, replacing `templateId`.
- `SandboxStatus` gains `GONE` and an exit code.
- `RunController.trigger` passes the run ID as the `requestId`.
- A 429 from the runner fails the run with `CAPACITY_EXHAUSTED` or `BUDGET_EXHAUSTED` and releases the wallet reservation. There is no queue in WS5 (see §9).

---

## 4. Build pipeline (WS5b)

1. **Upload.** `POST /api/v1/agents/{id}/bundles/uploads` returns a pre-signed PUT URL for the object store. The CLI uploads the bundle, then calls `POST /bundles/{id}/finalize {sha256}`. Spring checks the size and hash, reads `manifest.json` from the bundle, and validates it (the backend's answer is final). It then creates or updates a DRAFT `agent_version` and inserts a `template_build` row in `QUEUED`.
2. **Dispatch.** `BuildDispatcher` (`@Scheduled` + ShedLock) sends queued builds to `POST /builds` with a pre-signed GET URL. `BuildPoller` polls `GET /builds/{id}` and copies the result into `template_build` and `agent_version`.
3. **Shared runner steps (the same for every backend):**
   - Download the bundle and verify its sha256.
   - Unpack it, rejecting absolute paths, `..` and symlinks that point outside the directory, and enforcing an unpacked-size limit (500 MB) against zip bombs.
   - Run `gitleaks detect --no-git` on the unpacked directory. A finding fails the build with `SECRET_FOUND`, listing the file and line, with the secret redacted.
   - Call `backend.buildImage(...)`.
4. **Build recipe (each backend implements the same steps):**
   - Start from the base image for the runtime (`python:3.12-slim` or `node:22-slim` plus the platform setup layer).
   - Copy the app to `/workspace/app`.
   - Install dependencies with `pip install -r requirements.txt --no-cache-dir` or `npm ci --omit=dev`; a failure is `INSTALL_FAILED`.
   - Run `pip-audit` or `npm audit --audit-level=high`; a failure is `DEP_VULN`.
   - Run the entrypoint import smoke test; a failure is `SMOKE_FAILED`.
   - Install `agentstore-run`.
   The build log streams to the object store under `builds/{buildId}.log`, with known secret formats redacted.
5. **E2B specifics:**
   - E2B's build servers can't reach the object store on a private network, so the shim uploads the unpacked directory from the runner with the template builder's `.copy()`.
   - The template starts from the official public images plus our setup steps, so **WS5a/b need no registry**.
   - E2B can't restrict egress during template builds; see §5.
6. **Result.** On success, `template_build.image_ref` and `agent_version.image_ref` are set and `build_status=SUCCEEDED`. A version can be submitted only when `build_status=SUCCEEDED`, and its bundle can't be changed once submitted.
7. **Rebuilding on a backend switch.** If a run's configured backend differs from `template_build.backend`, a rebuild is queued automatically and the run waits in `PENDING_BUILD`, starting once the build succeeds.

**Limits:**
- Bundle size: 100 MB.
- Unpacked size: 500 MB.
- Build timeout: 15 minutes; exceeding it is `TIMEOUT`.
- One build at a time per runner.

### 4.1 Data model (migration V23, the next free number after WS13's V17–V22)

```sql
CREATE TABLE agent_bundle (
  id UUID PRIMARY KEY,
  agent_id UUID NOT NULL REFERENCES agent(id),
  uploaded_by_user_id UUID NOT NULL REFERENCES app_user(id),
  storage_key VARCHAR(512) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,                  -- UPLOADING, UPLOADED, REJECTED
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE template_build (
  id UUID PRIMARY KEY,
  agent_version_id UUID NOT NULL REFERENCES agent_version(id),
  bundle_id UUID NOT NULL REFERENCES agent_bundle(id),
  backend VARCHAR(20) NOT NULL,                 -- e2b, gvisor
  runtime VARCHAR(30) NOT NULL,                 -- python-3.12, node-22
  entrypoint VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,                  -- QUEUED, BUILDING, SUCCEEDED, FAILED
  image_ref VARCHAR(255),
  failure_code VARCHAR(30),                     -- SECRET_FOUND, DEP_VULN, INSTALL_FAILED, SMOKE_FAILED, TIMEOUT, RUNNER_RESTARTED, INVALID_BUNDLE
  failure_reason TEXT,
  log_storage_key VARCHAR(512),
  queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ
);
ALTER TABLE agent_version ADD COLUMN bundle_id UUID REFERENCES agent_bundle(id);
ALTER TABLE agent_version ADD COLUMN build_status VARCHAR(20);
ALTER TABLE agent_version ALTER COLUMN image_ref DROP NOT NULL;
```

If another workstream merges a migration first, WS5 takes the next free number. `PENDING_BUILD` is added to `RunStatus`.

**Manifest:** WS5b adds `runtime` (`python-3.12` or `node-22`) and `entrypoint` to the manifest. The rest of manifest v2 stays with WS1.


### 4.2 Decisions made while planning WS5b (2026-09-25)

- **Bundle format:** a zip with `manifest.json` at its root. `entrypoint` is a path relative to the zip root (`.py` for `python-3.12`; `.js`, `.mjs` or `.cjs` for `node-22`).
- **Run command** comes from the manifest: `sh -c "cd /workspace/app && exec python|node '<entrypoint>'"`. Versions without a `runtime` (created through the older JSON endpoint with a hand-supplied image) keep the `agentstore.runs.agent-command` fallback.
- **The upload request declares `sha256` and `sizeBytes`** (`POST .../bundles/uploads {sha256, sizeBytes}`). That keeps `agent_bundle.sha256` NOT NULL and lets Spring refuse a bundle over 100 MB before handing out a URL. `finalize {sha256}` must match what was declared, and Spring re-hashes the stored bytes.
- **`POST /builds` also carries `logUploadUrl`,** a pre-signed PUT URL for `builds/{buildId}.log`. The runner rewrites the whole object every few seconds, because object stores can't append. The runner therefore never holds store credentials, and `GET /builds/{id}` doesn't return `logKey`.
- **Two more failure codes:** `BUILD_FAILED` (a backend or build-system error that isn't tied to one recipe step) and `SUPERSEDED` (a queued build replaced by a newer push of the same DRAFT version).
- **`template_build.attempts`:** `RUNNER_RESTARTED` re-queues a build at most 3 times, so a bundle that crashes the runner can't loop forever.
- **Secret scan command:** `gitleaks dir` (gitleaks 8.19+ replaced `detect --no-git` with `dir`).
- **Python smoke test:** the entrypoint is imported under a module name other than `__main__`, so code behind `if __name__ == "__main__":` doesn't run during the build. **Node smoke test:** `node --check`.
- **The run wrapper** is still installed when the sandbox starts, as in WS5a. Baking it into the image (§2.2) is left to WS5d, where there's a Dockerfile to put it in.
- **Rebuild trigger:** Spring compares the backend of the version's latest successful build with its own `agentstore.runner.backend` setting.
- **The legacy JSON version endpoint** (`POST /agents/{id}/versions` with `imageRef`) stays until WS5c changes the submission wizard. Submitting a version is refused when it has a bundle whose build hasn't succeeded, or when it has no image at all.
---

## 5. Egress and security

**Egress rules.** A sandbox may reach only the runtime API host plus the manifest's `allowOut` hosts; everything else is denied. The rule is expressed once, in `SandboxLaunch.allowOut`, and enforced per backend:
- **E2B:** its sandbox network controls at creation, denying all outbound traffic except the allowlisted hosts.
- **gVisor (WS5d):** each run on its own `internal` Docker network, with traffic forced through the egress proxy, which allows only the run's hosts plus the object store.

**Build-time egress.**
- **gVisor** limits dependency installs to PyPI and npm through the proxy.
- **E2B can't restrict template-build egress.** This is an **accepted gap for development and testing only**. Scans still run, and production uses gVisor.

**Secrets:**
- Per-run env vars (the run token from `RunTokenService` and connection secrets) are passed only at `create` and never baked into an image.
- The runner never logs env values.
- Build logs are redacted for known secret formats.
- The runner secret comes from env or config.

**Resource limits.** CPU, memory and timeout come from the manifest, clamped to platform maximums. Timeouts are enforced by the backend **and** independently by Spring's `RunReaper`, so a vendor-side failure can't leave a run going forever.

**Budget guard (E2B):**
- `maxConcurrentSandboxes` (default 2).
- `maxSandboxLifetime` (default 10 minutes; also caps `timeoutSeconds`).
- `monthlySandboxMinutes` (a ceiling that counts create-to-kill time and is kept in a small runner-local file; losing it only resets the count).
- Breaching any of these returns 429 `BUDGET_EXHAUSTED`.

**Runner reaper.** Every minute it lists owned sandboxes and kills any that is past its deadline. At startup it kills any whose `requestId` Spring no longer reports as an active run (checked through an internal Spring endpoint, `GET /internal/runs/active-ids`, which uses the same shared secret).

---

## 6. Developer API keys and CLI (WS5c)

### 6.1 API keys
- **Creating a key.** `POST /api/v1/developer/api-keys {name}` returns `ask_live_<32 random bytes, base62>` **once**.
- **Table `developer_api_key`:** `id`, `org_id`, `created_by_user_id`, `name`, `prefix` (the first 12 characters, for display), `key_hash` (SHA-256; the keys are high-entropy, so a slow hash isn't needed), `created_at`, `last_used_at`, `revoked_at`. It's added in the same migration series as §4.1.
- **Listing and revoking.** `GET /api/v1/developer/api-keys` lists keys; `DELETE /api/v1/developer/api-keys/{id}` revokes one.
- **Authentication.** `ApiKeyAuthFilter` sits next to `JwtAuthFilter` and handles `Authorization: Bearer ask_live_…`. The principal is the creating user within the key's org.
- **Scope.** A key works only on **developer endpoints** of its own org: agents, versions, bundles and builds. Wallet, buyer, admin, identity and api-key-management endpoints return 403 for key requests.

### 6.2 CLI (`cli/`, TypeScript)
- **Distribution.** Published on npm and as standalone binaries from `bun build --compile` for macOS, Linux and Windows.

| Command | Behaviour |
|---|---|
| `login` | Opens the portal's API-key page and asks for the key to be pasted. Stores it in `~/.config/agentstore/credentials` with permissions 0600. `AGENTSTORE_API_KEY` overrides the file. |
| `init [--python\|--typescript]` | Scaffolds `agentstore.yaml`, `manifest.json`, `agent/`, the dependency file, `fixtures/`, `evals/`, `schemas/` and `.agentstoreignore`. |
| `validate` | Checks the manifest against the JSON schema, and checks `runtime`, `entrypoint` (the file exists), the dependency file, and the packed size limit. This is fast feedback only; the backend's check is final. |
| `push [--wait]` | Validate → pack (applying `.agentstoreignore`) → upload through the pre-signed URL → finalize. With `--wait` it polls the build and tails its log, exiting 0 on SUCCEEDED and 1 on FAILED with the `failureCode` and reason printed. |

`dev`, `test`, `sandbox`, `logs` and `submit` belong to WS6 and WS8, as in the parent spec.

### 6.3 Frontend
- `SubmissionWizard` drops the template-ID field.
- The version page shows the build status and the log (read from the object store through a pre-signed URL).
- Developer settings gets an **API keys** section to create (showing the key once), list and revoke keys.

---

## 7. Testing

1. **Shared runner logic** (unit tests with a `FakeBackend`):
   - unpacking: traversal, symlinks, zip bombs, sha256 mismatch
   - gitleaks catching a planted AWS key
   - budget guard: concurrency, lifetime and the monthly ceiling returning 429
   - the reaper
   - `requestId` idempotency
   - rebuilding state after a restart
2. **`IsolationBackendConformanceTest` (abstract; the vendor-switch guarantee).** It covers:
   - create, then run; the exit code is reported
   - log tailing by offset
   - kill is idempotent
   - the timeout is enforced
   - `listOwned` labels
   - env vars reach the process and are absent from the image
   - egress: an allowed host is reachable; an unlisted host, a raw IP and `169.254.169.254` are blocked; DNS for unlisted names doesn't leak; the runtime API is reachable Accepted E2B gaps (live run 2026-09-26): DNS filtering is not verified, and `169.254.169.254` cannot be blocked (it is Firecracker's own metadata service), so those two conformance checks are skipped for E2B.

   Implementations:
   - `FakeBackend`: lifecycle subset, every build.
   - `E2bBackend`: `@Tag("e2b")`, runs only when `E2B_API_KEY` is set, manually or nightly. Sandboxes are small and short-lived. This adds the "unlisted host unreachable" check that `E2bSandboxProviderLiveTest` postponed.
   - `GvisorBackend`: `@Tag("gvisor")` in WS5d.
3. **Shim.** Vitest with the E2B SDK mocked. On the Java side, the subprocess protocol (JSON I/O, errors, timeout, a crashed shim) is tested against a fake shim script.
4. **Spring:**
   - `RunnerSandboxProvider` against `MockRestServiceServer`.
   - `BuildDispatcher` and `BuildPoller` with Testcontainers Postgres and SeaweedFS: success, each `failure_code`, the `RUNNER_RESTARTED` re-queue, a backend-mismatch rebuild, and `CAPACITY_EXHAUSTED` releasing the reservation.
   - The migration.
   - API keys: shown once, stored hashed, revoked keys rejected, 403 on excluded endpoints.
   - Submit blocked unless the build succeeded.
5. **CLI.** Vitest with a mock HTTP server: `.agentstoreignore` when packing, the size limit, `validate` rules, `push --wait` exit codes and log tailing, and credential file permissions.
6. **End-to-end acceptance on real E2B** (tagged, manual): the §1 success criteria, including a real run reaching `/runtime/v1` through a `cloudflared` quick tunnel.

Local test prerequisites follow the existing setup: Colima, with `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

---

## 8. Plan split

| Plan | Contents | Depends on |
|---|---|---|
| **WS5a** | `runner/` module, `IsolationBackend`, `FakeBackend`, `E2bBackend` + Node shim (sandbox lifecycle and the run wrapper), runner API `/sandboxes`, budget guard, reaper, conformance tests, `RunnerSandboxProvider`, the `SandboxSpec`/`SandboxStatus` changes, deleting `E2bSandboxProvider`. **Fixes B2.** Until WS5b lands, the conformance tests and E2E use a hand-built image. | WS0 (merged) |
| **WS5b** | SeaweedFS in docker-compose, the §4.1 migration, bundle upload/finalize, `BuildDispatcher`/`BuildPoller`, runner `/builds`, unpacking and gitleaks, `E2bBackend.buildImage` (recipe, scans, smoke test), manifest `runtime` + `entrypoint`, `PENDING_BUILD` rebuild, build log in the portal. | WS5a |
| **WS5c** | `developer_api_key` + `ApiKeyAuthFilter`, the CLI (`login`/`init`/`validate`/`push --wait`), the API-keys settings UI, removing the wizard's template-ID field. | WS5b (for `push`) |
| **WS5d** (later) | `GvisorBackend`: Docker + runsc, BuildKit, `registry:2`, per-run internal network + egress proxy, build-time PyPI/npm-only egress, `@Tag("gvisor")` conformance. | WS5b; start when moving off E2B |

---

## 9. Scaling later (recorded, not built in WS5)

**Model.** One image per agent version; one fresh sandbox per run, never reused across runs or buyers. Many buyers running the same agent means many sandboxes from one image.

**Rough sizing for 10K concurrent users.** Assumptions: about 2-minute runs, about 0.5 GB per agent, mostly waiting on I/O so CPU can be shared about 4×.

| | Realistic (a run every ~10 min per user) | Worst case (all running at once) |
|---|---|---|
| Concurrent sandboxes | ~2,000 | ~10,000 |
| Sandbox RAM / CPU | ~1.2 TB / ~500 vCPU | ~6 TB / ~2,500 vCPU |
| Runner hosts (64-core / 256 GB) | ~6–8 | ~30–35 |

The rest of the platform, realistic case:
- Spring: 3–4 instances behind a load balancer.
- Postgres: one primary (16 vCPU / 64 GB, NVMe) plus a replica, with PgBouncer.
- Object store: a distributed SeaweedFS cluster (or another S3-compatible store).
- Registry: cached on each runner.

At that scale a home server isn't viable; rented dedicated servers running the same open-source software are, at roughly €2–4K/month (an estimate to re-check). E2B at that volume would cost tens of thousands of dollars a month. LLM token spend will probably cost more than compute, which makes WS3's BYOK and per-org spend caps important.

**Deferred work, in rough order of need:**
1. **Run queue in Spring.** Runs start as `QUEUED` and are dispatched when the runner reports free slots through a new `GET /capacity` endpoint. **Per-org fair share** (a cap on each org's concurrent runs, dispatched in turn), an optional per-agent concurrency limit set by the developer, and queue expiry with `CAPACITY_TIMEOUT` plus release of the wallet reservation. This replaces WS5's "429 fails the run".
2. **Multiple runners.** Spring picks a runner by capacity. The runner already keeps no database; gVisor runners pull from the shared registry.
3. **One egress proxy per host instead of per run (WS5d decision point).** It identifies runs by source IP and applies each run's allowlist, saving about 20 MB of RAM per run.
4. **Pools of pre-started sandboxes** for popular agents, if cold starts matter.
5. Agent logs go to the object store in batches, **never** row by row into Postgres (already the rule).

**Alternatives considered (2026-09-25).** The platform-hosted model was kept.
- **Who runs the code:** A) platform-hosted (**chosen**; gives control, keeps data on the platform, and allows review); B) developer-hosted, via webhook or MCP endpoint (how Shopify and Slack apps work; almost no compute cost, but data leaves the platform and review is weak); C) declarative agents (config only, run by a platform engine); D) buyer-hosted (in the buyer's own cloud; an enterprise option for later). B or C could later be added as a separately labelled agent type in its own workstream.
- **Isolation technology:**
  - Plain runc: rejected for untrusted code.
  - **gVisor:** chosen for self-hosting; no KVM needed.
  - **Firecracker / Kata:** VM-level isolation, possible later backends behind `IsolationBackend`.
  - Wasm / V8 isolates: rejected because the Python native-package ecosystem isn't well supported.

---

## 10. Changes to the parent spec

- **D6:** the TypeScript `sandbox-bridge` is replaced by the Java `sandbox-runner` (+ a Node shim used only for E2B).
- **§3 / §3.1:** the architecture diagram and bridge API are replaced by this document's §2–§3.
- **§3.2:** the "Sandbox provider" row now reads *runner with pluggable `IsolationBackend` (E2B for testing, gVisor self-hosted)*.
- **§6:** trust boundaries (4)/(5) become backend ↔ runner and runner ↔ isolation backend. Isolation layer 1 becomes gVisor or Firecracker (E2B).
- **§7:** the M0/M1 "bridge" references become the runner; WS5 is split into a/b/c, and WS5d is added before production hosting.
- **§8 Q3:** closed. Run a single backend at a time; E2B only while the credit lasts; the long-term backend is self-hosted gVisor.
- **§9 glossary:** "Template" becomes "Image"; "sandbox-bridge" becomes "sandbox-runner".
