# WS5b — Build Pipeline Implementation Plan


**Goal:** A developer's code bundle becomes a runnable image that the platform owns:
1. The bundle is uploaded to the object store and validated by Spring.
2. The runner builds it (download → unpack → secret scan → install → audit → smoke test) on E2B.
3. The resulting image is attached to the agent version, and runs start the agent through the manifest's `runtime` and `entrypoint`.

**Architecture:**
- **Storage.** Spring owns the object store (SeaweedFS, used only through the AWS SDK v2 S3 client). It hands out pre-signed URLs: a PUT URL for the CLI to upload a bundle, a GET URL for the runner to download it, and a PUT URL for the runner to write the build log. The runner never holds store credentials.
- **Spring's build flow.**
  - `BundleService` validates an upload at finalize time, then creates or updates a DRAFT version and queues a `template_build`.
  - A ShedLocked `BuildJob` polls running builds (`BuildPoller`), then dispatches the oldest queued one (`BuildDispatcher`).
- **Runner's build flow.**
  - `POST /builds` runs one build at a time in the runner's `BuildService`: fetch → unpack → gitleaks → `IsolationBackend.buildImage`.
  - On E2B, the Node shim builds an E2B template from the unpacked directory. It follows the same recipe that WS5d's gVisor backend will follow.
- **Runs.** They use the version's built image and the manifest's run command. If the configured backend differs from the backend the image was built on, the run waits in `PENDING_BUILD` while a rebuild is queued.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Maven, JUnit 5 + AssertJ + Mockito, Testcontainers (Postgres, SeaweedFS), AWS SDK for Java v2 (`s3`), JDK `HttpClient`/`HttpServer`, gitleaks 8.30+, Node 22+ (ESM), the `e2b` JS SDK 2.51, Vitest, Next.js 14 + Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-25-ws5-sandbox-runner-build-pipeline-cli-design.md`. Read §1, §2, §3, §4 (including **§4.2**, the decisions made while planning this), §5, §7 and §8 (the WS5b row). Constraint **C5** replaced MinIO with SeaweedFS. The WS5a plan (`docs/superpowers/plans/2026-09-25-ws5a-sandbox-runner.md`) shows how the runner is put together.

## Global Constraints

- **Repo layout.**
  - `runner/` is a standalone Maven project (package `com.agentstore.runner`).
  - `backend/` is the Spring app (package `com.agentstore.platform`).
  - `runner/e2b-shim/` is the Node shim.
  - `frontend/` is Next.js.
  - There's no multi-module build.
- **Object store:** SeaweedFS image `chrislusf/seaweedfs:4.47`, S3 port `8333`, one bucket `agentstore`. Spring reaches it only through the AWS SDK v2 S3 client (path-style addressing, endpoint override, checksums `WHEN_REQUIRED`). Never add MinIO.
- **Object keys (exact):**
  - bundles: `bundles/{agentId}/{bundleId}.zip`
  - build logs: `builds/{buildId}.log`
- **Pre-signed URL lifetimes:**
  - upload PUT: 15 minutes
  - runner bundle GET and log PUT: 1 hour, longer than the 15-minute build timeout plus the queue wait
  - portal log GET: 15 minutes
- **Pre-signed URLs carry signatures, so never log them** or put them in exception messages or `toString()`.
- **Bundle format:** a zip with `manifest.json` at its root.
- **Limits (exact values):**
  - bundle ≤ 100 MB (`104_857_600` bytes)
  - unpacked ≤ 500 MB (`524_288_000` bytes)
  - ≤ 20,000 zip entries
  - `manifest.json` ≤ 256 KiB
  - runner build timeout `PT15M`, and inside it the E2B shim build timeout `PT14M`
  - Spring's safety timeout for a build still `BUILDING`: `PT20M`
  - at most 3 attempts after `RUNNER_RESTARTED`
  - one build at a time
- **Runtimes (exact):**

  | Runtime | Base image | Entrypoint extensions | Interpreter |
  |---|---|---|---|
  | `python-3.12` | `python:3.12-slim` | `.py` | `python` |
  | `node-22` | `node:22-slim` | `.js`, `.mjs`, `.cjs` | `node` |

- **Entrypoint:** a relative path matching `^[A-Za-z0-9_][A-Za-z0-9_./-]*$` with no `..` segment and no `//`.
- **Run command (exact):** `["sh", "-c", "cd /workspace/app && exec <interpreter> '<entrypoint>'"]`. Versions without a `runtime` fall back to `agentstore.runs.agent-command`.
- **App directory in every image:** `/workspace/app`. The run wrapper paths from WS5a (`/workspace/.run/{log,exit,agentstore-run}`) are unchanged, and the wrapper is still installed when the sandbox starts (spec §4.2).
- **Failure codes (exact strings):** `SECRET_FOUND`, `DEP_VULN`, `INSTALL_FAILED`, `SMOKE_FAILED`, `TIMEOUT`, `RUNNER_RESTARTED`, `INVALID_BUNDLE`, `BUILD_FAILED`, `SUPERSEDED`.
- **Statuses (exact):**
  - build: `QUEUED`, `BUILDING`, `SUCCEEDED`, `FAILED` (the runner uses the same four; Spring maps the runner's `QUEUED` to its own `BUILDING`, because a dispatched build counts as building)
  - bundle: `UPLOADING`, `UPLOADED`, `REJECTED`
  - new run status: `PENDING_BUILD` (not terminal)
- **Runner build API (exact):**
  - `POST /builds {buildId, runtime, bundleUrl, bundleSha256, entrypoint, logUploadUrl}` returns 202 with `{buildId, status, backend, imageRef, failureCode, failureReason}`. It's idempotent on `buildId`.
  - `GET /builds/{buildId}` returns the same shape. An unknown id returns `FAILED` with `RUNNER_RESTARTED`.
  - Both need `X-Runner-Secret`.
- **Migration:** `V23__bundles_and_builds.sql` (the next free number after V22). `agent_bundle.sha256` is `VARCHAR(64)`, not `CHAR(64)`, because Hibernate's `ddl-auto: validate` rejects `bpchar` for a `String`.
- **Vendor-neutral names outside backend classes:** *build, image, imageRef, sandbox, bundle*. "E2B" and "template" may appear only in `com.agentstore.runner.backend.e2b`, `runner/e2b-shim/`, and config keys under `agentstore.runner.e2b`. **Exception:** the table and entity are named `template_build`/`TemplateBuild`, as in spec §4.1.
- **Spring profiles:**
  - `runner` enables `RunnerSandboxProvider` and `HttpBuildRunnerClient`.
  - Under `!runner`, `FakeSandboxProvider` and `FakeBuildRunnerClient` are active. Tests run under `!runner`.
- **Spring config keys (new):**
  - `agentstore.storage.{endpoint,region,access-key,secret-key,bucket}`
  - `agentstore.runner.backend` (default `e2b`)
  - `agentstore.runner.build-read-timeout` (default `PT30S`)
  - `agentstore.builds.poll-interval` (default `PT5S`)
- **Error bodies.** The backend hides `ResponseStatusException` reasons (no `server.error.include-message`). Readable bundle rejections therefore go through a dedicated advice that returns `{code, message}` with HTTP 422. Don't turn on `include-message` globally.
- **Secrets:** run env values and pre-signed URLs never appear in logs, exception messages or error bodies. Build logs pass through `LogRedactor`. gitleaks runs with `--redact`.
- **Tests:**
  - Runner: from `runner/`, run `mvn test`. This needs `sh`, and optionally a real `gitleaks` on `PATH` (`brew install gitleaks`). There's no Docker.
  - Shim: from `runner/e2b-shim/`, run `npm test`.
  - Backend: from `backend/`, run `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test` with Colima running. This pulls `chrislusf/seaweedfs:4.47` the first time.
  - Frontend: from `frontend/`, run `npx vitest run && npx tsc --noEmit`.
  - E2B tests are `@Tag("e2b")` and excluded by default.
- **Out of scope:**
  - API keys, the CLI, and removing the wizard's template-ID field (WS5c)
  - gVisor, baking the wrapper into images, and deleting old E2B templates (WS5d)
  - a run queue (spec §9)
  - live log tailing endpoints for the CLI (WS5c reads the log object)

## Review Focus

1. **A hostile zip:**
   - `../` or absolute paths
   - backslash names
   - a zip bomb
   - 20,001 entries
   - duplicate paths after normalizing
   - symlink entries

   Each must fail with `INVALID_BUNDLE`, write nothing outside the build directory, and never create a symlink. [Task 4]
2. **A finalize that is retried or races another** (the CLI retries after a network error) must return the same version and build, and never queue a second build for one bundle. Pushing again to a version that's already submitted is refused and changes nothing. [Task 8]
3. **The runner restarts mid-build, or can't be reached.**
   - Restart: the build is re-queued at most 3 times, then fails with `RUNNER_RESTARTED`.
   - Unreachable: the build stays `BUILDING` until the 20-minute safety timeout. It's never failed on the first failed poll. [Task 9]
4. **Secrets inside a bundle or its build output:**
   - The gitleaks finding reports file and line, never the secret.
   - AWS keys, GitHub tokens, `sk-` keys and URL credentials printed by `pip`/`npm` are masked in the uploaded log.
   - Pre-signed URLs never reach logs. [Task 2, Task 4, Task 5]
5. **A push while an older build of the same DRAFT version is still queued or running.** The older result must never overwrite the version's image or build status. Queued older builds become `SUPERSEDED`. [Task 8, Task 9]

---

## File Structure

**Runner (`runner/`)**

| File | Responsibility | Task |
|---|---|---|
| `src/main/java/.../backend/e2b/ShimClient.java` (modify) | Pipes drained on virtual threads with bounded waits; per-call timeout; error code | 1 |
| `src/main/java/.../backend/e2b/ShimException.java` (modify), `ShimTimeoutException.java` (new) | Carry the shim's `error.code`; separate type for timeouts | 1 |
| `e2b-shim/shim.mjs` (modify) | Ranged `readFile` by command (no whole-file download); `build` op + recipe | 1, 3 |
| `src/main/java/.../backend/AgentRuntime.java`, `ImageBuild.java`, `BuildLog.java`, `BuildFailedException.java` (new) | Vendor-neutral build types | 2 |
| `src/main/java/.../backend/IsolationBackend.java` (modify) | Adds `buildImage` | 2 |
| `src/main/java/.../build/LogRedactor.java`, `BuildLogBuffer.java` (new) | Redacted, size-capped build log | 2 |
| `src/main/java/.../backend/e2b/E2bBackend.java` (modify), `LogFileFollower.java` (new), `E2bConfig.java` (modify) | E2B image build through the shim; live log forwarding | 2 (stub), 3 |
| `src/main/java/.../build/BundleUnpacker.java`, `SecretScanner.java` (new) | Safe unzip; gitleaks | 4 |
| `src/main/java/.../build/BundleFetcher.java`, `LogUploader.java`, `BuildService.java` (new) | Download + verify; log PUT; one-at-a-time build orchestration | 5 |
| `src/main/java/.../api/BuildController.java`, `CreateBuildRequest.java` (new); `ApiErrorHandler.java`, `RunnerConfig.java`, `RunnerProperties.java`, `application.yml` (modify) | `/builds` API and wiring | 3, 5 |

**Backend (`backend/`)**

| File | Responsibility | Task |
|---|---|---|
| `docker-compose.yml`, `infra/seaweedfs/s3.json` (repo root, new) | Local SeaweedFS | 6 |
| `.../storage/ObjectStorage.java`, `StorageException.java`, `StorageStartup.java` (new) | S3 client + presigner wrapper | 6 |
| `db/migration/V23__bundles_and_builds.sql` (new) | Tables and columns from spec §4.1 (+ `attempts`) | 7 |
| `.../build/AgentBundle*.java`, `BundleStatus.java`, `TemplateBuild*.java`, `BuildStatus.java`, `BuildFailureCodes.java` (new); `catalog/AgentVersion*.java` (modify) | Entities and repositories | 7 |
| `.../manifest/AgentRuntime.java`, `BundleManifestRules.java` (new); `Manifest.java`, `ManifestParser.java`, `manifest-schema.json` (modify) | `runtime` + `entrypoint` | 7 |
| `.../execution/RunStatus.java` (modify) | `PENDING_BUILD` | 7 |
| `.../build/BundleInspector.java`, `BundleRejectedException.java`, `BundleService.java`, `BundleController.java`, `BuildApiErrors.java`, request/response records (new) | Upload + finalize | 8 |
| `.../build/BuildRunnerClient.java`, `HttpBuildRunnerClient.java`, `FakeBuildRunnerClient.java`, `BuildRunnerUnavailableException.java`, `BuildTransitions.java`, `BuildFinishedEvent.java`, `BuildFinishedListener.java`, `BuildDispatcher.java`, `BuildPoller.java`, `BuildJob.java`, `BuildStatusController.java`, `BuildResponse.java`, `BuildSummary.java`, `BuildSummaries.java` (new); `publishing/PublishingService.java`, `catalog/CatalogController.java`, `catalog/AgentVersionDetailResponse.java` (modify) | Dispatch, poll, status, submit gate | 9 |
| `.../build/RebuildService.java`, `.../execution/RunLauncher.java`, `PendingBuildRuns.java` (new); `RunController.java`, `RunLifecycleService.java`, `RunRepository.java`, `RunReaper.java` (modify) | Manifest run command, `PENDING_BUILD` rebuilds | 10 |

**Frontend (`frontend/src/`)**: `lib/types.ts`, `components/StatusPill.tsx`, `components/BuildPanel.tsx` (new), and the developer version status view. Task 11.

**Docs**: `runner/README.md`, `README-dev` notes in `docker-compose.yml`, `ONTOLOGY.md` (re-stamp), and the manual E2E. Task 12.

---

### Task 1: Harden the shim client and read logs by byte range

These are the two WS5a items that must be fixed before builds and log tailing rely on the shim:
1. `ShimClient` drains the child's pipes on the common fork-join pool and waits on them with no time limit.
2. The shim's `readFile` downloads the **whole** file on every poll.

**Files:**
- Modify: `runner/src/main/java/com/agentstore/runner/backend/e2b/ShimClient.java`
- Modify: `runner/src/main/java/com/agentstore/runner/backend/e2b/ShimException.java`
- Create: `runner/src/main/java/com/agentstore/runner/backend/e2b/ShimTimeoutException.java`
- Modify: `runner/e2b-shim/shim.mjs`, `runner/e2b-shim/shim.test.mjs`
- Create: `runner/src/test/resources/shim/holds-pipe.sh`, `big-output.sh`, `fail-code.sh`
- Test: `runner/src/test/java/com/agentstore/runner/backend/e2b/ShimClientTest.java`

**Interfaces:**
- Produces:
  - `ShimClient.call(Map<String,Object> request, Duration callTimeout) → JsonNode` (the one-argument `call` keeps the configured default).
  - `ShimException.code() → String` (nullable).
  - `ShimTimeoutException extends ShimException`.
  - Shim export `rangeReadCommand(path, offset, maxBytes) → string`.
  - The `readFile` response shape is unchanged: `{ok, exists, data(base64), size}`.

- [ ] **Step 1: Add the fake shim scripts**

`runner/src/test/resources/shim/holds-pipe.sh`: exits at once but leaves a background child holding stdout and stderr open.
```sh
cat >/dev/null
printf '%s' '{"ok":true}'
sleep 30 &
```

`runner/src/test/resources/shim/big-output.sh`: 300,000 bytes of payload, well past a 64 KiB pipe buffer.
```sh
cat >/dev/null
printf '%s' '{"ok":true,"data":"'
head -c 300000 /dev/zero | tr '\0' 'a'
printf '%s' '"}'
```

`runner/src/test/resources/shim/fail-code.sh`:
```sh
cat >/dev/null
printf '%s' '{"ok":false,"error":{"code":"DEP_VULN","message":"audit found 2 high vulnerabilities"}}'
exit 1
```

- [ ] **Step 2: Write the failing Java tests**

Add these to `ShimClientTest`, keeping the existing tests:

```java
    @Test
    void perCallTimeoutOverridesTheDefault() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> shim("hang.sh", Duration.ofSeconds(30)).call(REQUEST, Duration.ofMillis(300)))
                .isInstanceOf(ShimTimeoutException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aShimThatExitsButLeavesItsPipesOpenFailsWithinTheGracePeriod() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> shim("holds-pipe.sh", Duration.ofSeconds(30)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageContaining("stayed open");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void largeOutputIsReadWithoutDeadlock() {
        var res = shim("big-output.sh", Duration.ofSeconds(10)).call(REQUEST);
        assertThat(res.get("data").asText()).hasSize(300_000);
    }

    @Test
    void theShimsErrorCodeIsKept() {
        assertThatThrownBy(() -> shim("fail-code.sh", Duration.ofSeconds(10)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .satisfies(e -> assertThat(((ShimException) e).code()).isEqualTo("DEP_VULN"))
                .hasMessageContaining("audit found");
    }
```

- [ ] **Step 3: Run them and watch them fail**

Run: `cd runner && mvn -q test -Dtest=ShimClientTest`
Expected: compilation fails (`ShimTimeoutException`, `call(Map, Duration)` and `code()` don't exist).

- [ ] **Step 4: Implement the exceptions**

`ShimException.java` (replace the whole file):
```java
package com.agentstore.runner.backend.e2b;

/** Never carries the request (it holds run secrets). */
public class ShimException extends RuntimeException {

    private final String code;

    public ShimException(String message) {
        this(message, (String) null);
    }

    public ShimException(String message, String code) {
        super(message);
        this.code = code;
    }

    public ShimException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    /** The shim's {@code error.code}, or null when the failure happened outside the shim's own error handling. */
    public String code() {
        return code;
    }
}
```

`ShimTimeoutException.java`:
```java
package com.agentstore.runner.backend.e2b;

/** The shim did not finish within its call timeout and was killed. */
public class ShimTimeoutException extends ShimException {
    public ShimTimeoutException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Implement the bounded `ShimClient`**

Replace the body of `ShimClient` from the `call` method down to (not including) `childEnvironment` with the code below, and add the imports `java.util.concurrent.ExecutionException` and `java.util.concurrent.TimeoutException`. `childEnvironment` and `readAll` stay as they are.

```java
    /** How long to wait for stdout/stderr to close once the process has exited or been killed. */
    static final Duration PIPE_GRACE = Duration.ofSeconds(5);

    public JsonNode call(Map<String, Object> request) {
        return call(request, timeout);
    }

    /** Like {@link #call(Map)} with its own timeout: an image build runs far longer than a lifecycle call. */
    public JsonNode call(Map<String, Object> request, Duration callTimeout) {
        String op = String.valueOf(request.get("op"));
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Map<String, String> env = pb.environment();
            env.clear();
            env.putAll(childEnvironment(System.getenv(), apiKey));
            process = pb.start();
        } catch (IOException e) {
            throw new ShimException("shim " + op + " could not start: " + e.getMessage());
        }
        // Dedicated virtual threads, not the common pool: a child that never closes its pipes must not pin
        // shared threads, and every wait below is bounded.
        CompletableFuture<byte[]> stdout = drain(process.getInputStream());
        CompletableFuture<byte[]> stderr = drain(process.getErrorStream());
        try (OutputStream in = process.getOutputStream()) {
            MAPPER.writeValue(in, request);
        } catch (IOException e) {
            kill(process);
            throw new ShimException("shim " + op + " stdin failed: " + e.getClass().getSimpleName());
        }
        try {
            if (!process.waitFor(callTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new ShimTimeoutException("shim " + op + " timed out after " + callTimeout);
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ShimException("shim " + op + " interrupted");
        }
        String err = new String(awaitPipe(stderr, op, "stderr"), StandardCharsets.UTF_8);
        if (!err.isBlank()) {
            log.warn("shim {} stderr: {}", op, err.length() > 2000 ? err.substring(0, 2000) : err);
        }
        JsonNode response;
        try {
            response = MAPPER.readTree(awaitPipe(stdout, op, "stdout"));
        } catch (IOException e) {
            throw new ShimException("shim " + op + " returned non-JSON output (exit " + process.exitValue() + ")");
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            String message = response == null ? "empty response"
                    : response.path("error").path("message").asText("unknown error");
            String code = response == null ? null : response.path("error").path("code").asText(null);
            throw new ShimException("shim " + op + " failed: " + message, code);
        }
        return response;
    }

    private static CompletableFuture<byte[]> drain(InputStream in) {
        CompletableFuture<byte[]> pipe = new CompletableFuture<>();
        Thread.ofVirtual().name("shim-pipe").start(() -> pipe.complete(readAll(in)));
        return pipe;
    }

    private static byte[] awaitPipe(CompletableFuture<byte[]> pipe, String op, String name) {
        try {
            return pipe.get(PIPE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ShimException("shim " + op + " " + name + " stayed open after the process ended");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShimException("shim " + op + " interrupted");
        } catch (ExecutionException e) {
            return new byte[0];
        }
    }

    /** Kills the shim and anything it started while it is still their parent (a started child keeps our pipes open). */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
```

- [ ] **Step 6: Run the Java tests**

Run: `cd runner && mvn -q test -Dtest=ShimClientTest`
Expected: PASS (all old and new tests).

- [ ] **Step 7: Write the failing shim tests**

In `runner/e2b-shim/shim.test.mjs`:

**(a)** Change the import line to:
```js
import { handle, shellQuote, isEntryPoint, rangeReadCommand, WRAPPER, CONNECT_TIMEOUT_MS } from './shim.mjs';
```

**(b)** Replace `fakeSandboxClass` with the version below. `files.read` now throws, which proves the whole-file download is gone.
```js
function fakeSandboxClass({ failRun = false, runStdout = '' } = {}) {
  const calls = [];
  const sandbox = {
    commands: {
      run: async (cmd, opts) => {
        calls.push({ kind: 'run', cmd, opts });
        if (failRun) throw new Error('boom from e2b');
        return { exitCode: 0, stdout: runStdout, stderr: '' };
      },
    },
    files: {
      write: async (path, data) => { calls.push({ kind: 'write', path, data }); },
      read: async () => { throw new Error('files.read must not be used: it downloads the whole file'); },
    },
  };
  return { calls, Sandbox: { connect: async (id, opts) => { calls.push({ kind: 'connect', id, opts }); return sandbox; } } };
}
```

**(c)** Replace the whole `describe('readFile', …)` block with:
```js
describe('readFile', () => {
  it('reads only the requested byte range with one command', async () => {
    const { calls, Sandbox } = fakeSandboxClass({ runStdout: `11\n${Buffer.from('wor').toString('base64')}` });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/log', offset: 6, maxBytes: 3 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: true, data: Buffer.from('wor').toString('base64'), size: 11 });
    const run = calls.find((c) => c.kind === 'run');
    expect(run.cmd).toBe(rangeReadCommand('/workspace/.run/log', 6, 3));
    expect(run.cmd).toContain('tail -c +7');
    expect(run.cmd).toContain('head -c 3');
  });

  it('reports a missing file as exists:false', async () => {
    const { Sandbox } = fakeSandboxClass({ runStdout: 'MISSING\n' });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/exit', offset: 0, maxBytes: 10 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: false });
  });

  it('returns empty data when the offset is at the end of the file', async () => {
    const { Sandbox } = fakeSandboxClass({ runStdout: '5\n' });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/log', offset: 5, maxBytes: 10 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: true, data: '', size: 5 });
  });

  it('quotes the path', () => {
    expect(rangeReadCommand("/tmp/a'b", 0, 1)).toContain(`'/tmp/a'\\''b'`);
  });
});
```

**(d)** In `describe('connect timeout', …)`, change `fakeSandboxClass({ files: { '/workspace/.run/log': 'x' } })` to `fakeSandboxClass({ runStdout: '1\neA==' })`.

- [ ] **Step 8: Run them and watch them fail**

Run: `cd runner/e2b-shim && npm test`
Expected: FAIL (`rangeReadCommand` isn't exported, and `readFile` still calls `files.read`).

- [ ] **Step 9: Implement the ranged read in `shim.mjs`**

Delete `isMissingFileError` and replace `readFile` with:
```js
// Reads [offset, offset + maxBytes) of a file inside the sandbox with one shell command, so a log poll never
// downloads the whole file. Output: "<size>\n<base64 slice>", or "MISSING".
export function rangeReadCommand(path, offset, maxBytes) {
  const p = shellQuote(path);
  const start = Math.max(0, Math.floor(offset)) + 1;
  const count = Math.max(0, Math.floor(maxBytes));
  return `if [ -f ${p} ]; then stat -c %s ${p} && tail -c +${start} ${p} | head -c ${count} | base64 -w0; else echo MISSING; fi`;
}

async function readFile(req, { Sandbox, apiKey }) {
  const sbx = await connect(Sandbox, req.sandboxId, apiKey);
  const res = await sbx.commands.run(rangeReadCommand(req.path, req.offset ?? 0, req.maxBytes ?? 262144), { user: 'root' });
  const out = String(res?.stdout ?? '');
  const newline = out.indexOf('\n');
  const first = (newline < 0 ? out : out.slice(0, newline)).trim();
  if (first === 'MISSING') return { ok: true, exists: false };
  const size = Number(first);
  if (!Number.isFinite(size)) throw new Error('unexpected readFile output');
  return { ok: true, exists: true, data: newline < 0 ? '' : out.slice(newline + 1).trim(), size };
}
```

- [ ] **Step 10: Run all shim and runner tests**

Run: `cd runner/e2b-shim && npm test`, then `cd .. && mvn -q test`
Expected: both PASS. `E2bBackend` is unchanged because the response shape is the same.

- [ ] **Step 11: Commit**

```bash
git add runner/src/main/java/com/agentstore/runner/backend/e2b/ShimClient.java \
  runner/src/main/java/com/agentstore/runner/backend/e2b/ShimException.java \
  runner/src/main/java/com/agentstore/runner/backend/e2b/ShimTimeoutException.java \
  runner/src/test/java/com/agentstore/runner/backend/e2b/ShimClientTest.java \
  runner/src/test/resources/shim/holds-pipe.sh runner/src/test/resources/shim/big-output.sh \
  runner/src/test/resources/shim/fail-code.sh runner/e2b-shim/shim.mjs runner/e2b-shim/shim.test.mjs
git commit -m "fix(runner): bound shim pipe reads and read sandbox files by byte range"
```

---

### Task 2: Build seam — `buildImage`, runtimes, and a redacted build log

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/backend/AgentRuntime.java`, `ImageBuild.java`, `BuildLog.java`, `BuildFailedException.java`
- Modify: `runner/src/main/java/com/agentstore/runner/backend/IsolationBackend.java`
- Create: `runner/src/main/java/com/agentstore/runner/build/LogRedactor.java`, `BuildLogBuffer.java`
- Modify: `runner/src/test/java/com/agentstore/runner/backend/FakeBackend.java`
- Modify: `runner/src/main/java/com/agentstore/runner/backend/e2b/E2bBackend.java` (a temporary refusal; Task 3 replaces it)
- Test: `runner/src/test/java/com/agentstore/runner/build/LogRedactorTest.java`, `BuildLogBufferTest.java`, `runner/src/test/java/com/agentstore/runner/backend/FakeBackendBuildTest.java`, `runner/src/test/java/com/agentstore/runner/backend/AgentRuntimeTest.java`

**Interfaces:**
- Produces:
  - `enum AgentRuntime { PYTHON_3_12("python-3.12","python:3.12-slim"), NODE_22("node-22","node:22-slim") }` with `id()`, `baseImage()` and `static AgentRuntime fromId(String)` (throws `IllegalArgumentException`).
  - `record ImageBuild(String buildId, Path appDir, AgentRuntime runtime, String entrypoint)`.
  - `@FunctionalInterface interface BuildLog { void append(String line); }`.
  - `BuildFailedException(String code, String reason)` with `code()` and the constants `SECRET_FOUND, DEP_VULN, INSTALL_FAILED, SMOKE_FAILED, TIMEOUT, RUNNER_RESTARTED, INVALID_BUNDLE, BUILD_FAILED`.
  - `IsolationBackend.buildImage(ImageBuild build, BuildLog log) → String imageRef`.
  - `LogRedactor.redact(String) → String`, with `LogRedactor.MASK = "[REDACTED]"`.
  - `BuildLogBuffer implements BuildLog` with `snapshot() → byte[]`.
  - `FakeBackend.FAIL_MARKER = ".fake-build-fail"`, `FakeBackend.LOG_MARKER = ".fake-build-log"`, and the public fields `buildCalls` and `buildDelay`.

- [ ] **Step 1: Write the failing tests**

`runner/src/test/java/com/agentstore/runner/build/LogRedactorTest.java`:
```java
package com.agentstore.runner.build;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            key=AKIAXWNVK4PQ2JT6ZM3C done                                        | key=[REDACTED] done
            aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYzEXAMPLEKEY     | aws_secret_access_key = [REDACTED]
            pip install https://user:hunter2@pypi.example.com/simple              | pip install https://user:[REDACTED]@pypi.example.com/simple
            token ghp_abcdefghijklmnopqrstuvwxyz0123456789                        | token [REDACTED]
            OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv                         | OPENAI_API_KEY=[REDACTED]
            slack xoxb-1234567890-abcdefghij                                      | slack [REDACTED]
            -----BEGIN RSA PRIVATE KEY-----                                       | [REDACTED]
            Collecting requests==2.32.3                                           | Collecting requests==2.32.3
            """)
    void masksKnownSecretFormatsAndLeavesOrdinaryLinesAlone(String line, String expected) {
        assertThat(LogRedactor.redact(line)).isEqualTo(expected);
    }
}
```

`runner/src/test/java/com/agentstore/runner/build/BuildLogBufferTest.java`:
```java
package com.agentstore.runner.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BuildLogBufferTest {

    @Test
    void appendsRedactedLines() {
        BuildLogBuffer log = new BuildLogBuffer();
        log.append("Installing");
        log.append("AKIAXWNVK4PQ2JT6ZM3C  \n");
        assertThat(new String(log.snapshot(), StandardCharsets.UTF_8)).isEqualTo("Installing\n[REDACTED]\n");
    }

    @Test
    void stopsAtTheCapAndSaysSo() {
        BuildLogBuffer log = new BuildLogBuffer(25);
        log.append("0123456789");
        log.append("0123456789");
        log.append("0123456789");
        log.append("after the cap");
        String text = new String(log.snapshot(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("0123456789\n0123456789\n").endsWith(BuildLogBuffer.TRUNCATED)
                .doesNotContain("after the cap");
    }
}
```

`runner/src/test/java/com/agentstore/runner/backend/AgentRuntimeTest.java`:
```java
package com.agentstore.runner.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeTest {

    @Test
    void mapsIdsToBaseImages() {
        assertThat(AgentRuntime.fromId("python-3.12").baseImage()).isEqualTo("python:3.12-slim");
        assertThat(AgentRuntime.fromId("node-22").baseImage()).isEqualTo("node:22-slim");
    }

    @Test
    void rejectsUnknownRuntimes() {
        assertThatThrownBy(() -> AgentRuntime.fromId("ruby-3")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruby-3");
    }
}
```

`runner/src/test/java/com/agentstore/runner/backend/FakeBackendBuildTest.java`:
```java
package com.agentstore.runner.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeBackendBuildTest {

    final FakeBackend backend = new FakeBackend("runner-test");

    @Test
    void buildsAFakeImage(@TempDir Path app) {
        List<String> log = new ArrayList<>();
        String image = backend.buildImage(new ImageBuild("b1", app, AgentRuntime.PYTHON_3_12, "main.py"), log::add);
        assertThat(image).isEqualTo("fake-image-b1");
        assertThat(log).anyMatch(l -> l.contains("main.py"));
    }

    @Test
    void failsWithTheCodeInTheMarkerFile(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve(FakeBackend.FAIL_MARKER), "DEP_VULN\n");
        assertThatThrownBy(() -> backend.buildImage(new ImageBuild("b2", app, AgentRuntime.NODE_22, "index.js"), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("DEP_VULN"));
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd runner && mvn -q test -Dtest='LogRedactorTest,BuildLogBufferTest,AgentRuntimeTest,FakeBackendBuildTest'`
Expected: compilation fails (the types don't exist).

- [ ] **Step 3: Implement the backend types**

`runner/src/main/java/com/agentstore/runner/backend/AgentRuntime.java`:
```java
package com.agentstore.runner.backend;

import java.util.Arrays;

/** Runtimes a bundle's manifest can declare (spec §4.1 "Manifest"). */
public enum AgentRuntime {
    PYTHON_3_12("python-3.12", "python:3.12-slim"),
    NODE_22("node-22", "node:22-slim");

    private final String id;
    private final String baseImage;

    AgentRuntime(String id, String baseImage) {
        this.id = id;
        this.baseImage = baseImage;
    }

    public String id() { return id; }

    public String baseImage() { return baseImage; }

    public static AgentRuntime fromId(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown runtime: " + id));
    }
}
```

`ImageBuild.java`:
```java
package com.agentstore.runner.backend;

import java.nio.file.Path;

/** Input to {@link IsolationBackend#buildImage}: an unpacked, scanned app directory and how to run it. */
public record ImageBuild(String buildId, Path appDir, AgentRuntime runtime, String entrypoint) {}
```

`BuildLog.java`:
```java
package com.agentstore.runner.backend;

/** Where a build writes its progress. Implementations redact known secret formats. */
@FunctionalInterface
public interface BuildLog {
    void append(String line);
}
```

`BuildFailedException.java`:
```java
package com.agentstore.runner.backend;

/** A build ended without an image. {@code code} is one of the failure codes below; the message is shown to developers. */
public class BuildFailedException extends RuntimeException {

    public static final String SECRET_FOUND = "SECRET_FOUND";
    public static final String DEP_VULN = "DEP_VULN";
    public static final String INSTALL_FAILED = "INSTALL_FAILED";
    public static final String SMOKE_FAILED = "SMOKE_FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String RUNNER_RESTARTED = "RUNNER_RESTARTED";
    public static final String INVALID_BUNDLE = "INVALID_BUNDLE";
    public static final String BUILD_FAILED = "BUILD_FAILED";

    private final String code;

    public BuildFailedException(String code, String reason) {
        super(reason);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

In `IsolationBackend.java`:
- Replace the class Javadoc's second sentence with `Vendor vocabulary stays inside implementations.` This fixes the stale "templates, envd" mention noted in WS5a.
- Add this method after `name()`:
```java
    /**
     * Builds a runnable image from {@code build.appDir()} following the shared recipe (spec §4 step 4): the runtime's
     * base image, the app at /workspace/app, dependency install, dependency audit, entrypoint smoke test. Returns an
     * imageRef that {@link #create} accepts. Throws {@link BuildFailedException} (INSTALL_FAILED, DEP_VULN,
     * SMOKE_FAILED, TIMEOUT or BUILD_FAILED).
     */
    String buildImage(ImageBuild build, BuildLog log);
```

- [ ] **Step 4: Implement the log types**

`runner/src/main/java/com/agentstore/runner/build/LogRedactor.java`:
```java
package com.agentstore.runner.build;

import java.util.List;
import java.util.regex.Pattern;

/** Masks known secret formats before a line reaches a build log (spec §5 "Secrets"). */
public final class LogRedactor {

    public static final String MASK = "[REDACTED]";

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), MASK),
            new Rule(Pattern.compile("(?i)(aws_secret_access_key\\s*[=:]\\s*)\\S+"), "$1" + MASK),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"), MASK),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"), MASK),
            new Rule(Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), MASK),
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), MASK),
            new Rule(Pattern.compile("(?i)\\b(https?://[^\\s:/@]+):[^\\s@/]+@"), "$1:" + MASK + "@"));

    private LogRedactor() {}

    public static String redact(String line) {
        String out = line;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }
}
```

`BuildLogBuffer.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildLog;

import java.nio.charset.StandardCharsets;

/** In-memory build log: redacted, size-capped, safe to snapshot while a build is still appending. */
public class BuildLogBuffer implements BuildLog {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final String TRUNCATED = "[build log truncated: size limit reached]\n";

    private final int maxBytes;
    private final StringBuilder text = new StringBuilder();
    private int bytes;
    private boolean truncated;

    public BuildLogBuffer() {
        this(MAX_BYTES);
    }

    BuildLogBuffer(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public synchronized void append(String line) {
        if (truncated) {
            return;
        }
        String entry = LogRedactor.redact(line == null ? "" : line.stripTrailing()) + "\n";
        int size = entry.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > maxBytes) {
            text.append(TRUNCATED);
            truncated = true;
            return;
        }
        text.append(entry);
        bytes += size;
    }

    public synchronized byte[] snapshot() {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 5: Teach `FakeBackend` to build**

In `runner/src/test/java/com/agentstore/runner/backend/FakeBackend.java`, add the imports `java.time.Duration` and `java.nio.charset.StandardCharsets` (if missing), then add these fields and the method:
```java
    /** Test hook: an app dir containing this file fails the build with the failure code written in it. */
    public static final String FAIL_MARKER = ".fake-build-fail";
    /** Test hook: an app dir containing this file has its content appended to the build log. */
    public static final String LOG_MARKER = ".fake-build-log";

    public final AtomicInteger buildCalls = new AtomicInteger();
    public volatile Duration buildDelay = Duration.ZERO;

    @Override
    public String buildImage(ImageBuild build, BuildLog log) {
        buildCalls.incrementAndGet();
        log.append("fake build of " + build.entrypoint() + " on " + build.runtime().id());
        try {
            Thread.sleep(buildDelay.toMillis());
            Path extraLog = build.appDir().resolve(LOG_MARKER);
            if (Files.exists(extraLog)) {
                Files.readString(extraLog, StandardCharsets.UTF_8).lines().forEach(log::append);
            }
            Path marker = build.appDir().resolve(FAIL_MARKER);
            if (Files.exists(marker)) {
                String code = Files.readString(marker, StandardCharsets.UTF_8).trim();
                throw new BuildFailedException(code, "fake " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException("fake build interrupted");
        } catch (IOException e) {
            throw new BackendException("fake build failed", e);
        }
        return "fake-image-" + build.buildId();
    }
```

- [ ] **Step 6: Make `E2bBackend` refuse builds until Task 3**

Add these imports to `E2bBackend.java`: `com.agentstore.runner.backend.BuildFailedException`, `BuildLog` and `ImageBuild`. Then add:
```java
    @Override
    public String buildImage(ImageBuild build, BuildLog log) {
        // Replaced in WS5b Task 3 by the shim-driven image build.
        throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "Image builds are not wired for this backend yet");
    }
```

- [ ] **Step 7: Run the runner tests**

Run: `cd runner && mvn -q test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add runner/src/main/java/com/agentstore/runner/backend runner/src/main/java/com/agentstore/runner/build \
  runner/src/test/java/com/agentstore/runner/build runner/src/test/java/com/agentstore/runner/backend/FakeBackend.java \
  runner/src/test/java/com/agentstore/runner/backend/FakeBackendBuildTest.java \
  runner/src/test/java/com/agentstore/runner/backend/AgentRuntimeTest.java
git commit -m "feat(runner): add the image-build seam, runtimes and a redacted build log"
```

---

### Task 3: E2B image build (shim `build` op, recipe, `E2bBackend.buildImage`)

**How the recipe runs on E2B.** The shim builds an E2B template from the runner-local app directory. `Template({ fileContextPath })` plus `.copy('.', '/workspace/app')` uploads the directory from the runner, so E2B never has to reach our object store (spec §4 step 5). Every recipe step runs as root in its own `sh -c`. When a step fails it prints `AGENTSTORE_STEP_FAILED:<CODE>`. The shim watches the build log for that marker, so the runner can report `INSTALL_FAILED`, `DEP_VULN` or `SMOKE_FAILED` instead of a generic error.

**Files:**
- Modify: `runner/e2b-shim/shim.mjs`, `runner/e2b-shim/shim.test.mjs`
- Create: `runner/src/main/java/com/agentstore/runner/backend/e2b/LogFileFollower.java`
- Modify: `runner/src/main/java/com/agentstore/runner/backend/e2b/E2bBackend.java`, `E2bConfig.java`
- Modify: `runner/src/main/java/com/agentstore/runner/RunnerProperties.java`, `runner/src/main/resources/application.yml`, `runner/src/test/java/com/agentstore/runner/RunnerPropertiesTest.java`
- Test: `runner/src/test/java/com/agentstore/runner/backend/e2b/LogFileFollowerTest.java`, `E2bBackendTest.java` (add tests), `E2bImageBuildLiveTest.java` (new, `@Tag("e2b")`)

**Interfaces:**
- Consumes (Task 1): `ShimClient.call(Map, Duration)`, `ShimException.code()`, `ShimTimeoutException`.
- Consumes (Task 2): `ImageBuild`, `BuildLog`, `BuildFailedException`, `AgentRuntime`.
- Produces:
  - The shim request `{op:"build", name, appDir, runtime, entrypoint, logPath, cpu, memoryMb}`. It returns `{ok:true, imageRef}` or `{ok:false, error:{code, message}}`, where `code` is one of `INSTALL_FAILED|DEP_VULN|SMOKE_FAILED|BUILD_FAILED|BAD_REQUEST`.
  - Shim exports `recipe(runtime, entrypoint)`, `BASE_IMAGES`, `FAIL_MARK`, `APP_DIR`, `PY_SMOKE`.
  - `E2bBackend(E2bRestClient, ShimClient, String runnerId, Duration buildTimeout)`; the 3-argument constructor stays and defaults to 14 minutes.
  - `RunnerProperties.E2b` gains a `Duration buildTimeout` component, **last**.

- [ ] **Step 1: Write the failing shim tests**

Append to `runner/e2b-shim/shim.test.mjs`. Also add `recipe, BASE_IMAGES, FAIL_MARK` to the import from `./shim.mjs`, and add `readFileSync, writeFileSync` to the `node:fs` import.
```js
function fakeTemplate({ logs = [], fail = null } = {}) {
  const calls = [];
  const builder = {
    fromImage(image) { calls.push(['fromImage', image]); return builder; },
    runCmd(cmd, opts) { calls.push(['runCmd', cmd, opts]); return builder; },
    copy(src, dest) { calls.push(['copy', src, dest]); return builder; },
  };
  const Template = (opts) => { calls.push(['Template', opts]); return builder; };
  Template.build = async (template, name, opts) => {
    calls.push(['build', name, { apiKey: opts.apiKey, cpuCount: opts.cpuCount, memoryMB: opts.memoryMB }]);
    for (const message of logs) opts.onBuildLogs({ message });
    if (fail) throw fail;
    return { templateId: 'tpl-123', buildId: 'b-1', name, alias: name, tags: [] };
  };
  return { calls, Template };
}

describe('recipe', () => {
  it('installs, audits and smoke-tests a python app in that order, each step tagged with its failure code', () => {
    const steps = recipe('python-3.12', 'agent/main.py');
    expect(steps).toHaveLength(3);
    expect(steps[0]).toContain('pip install --no-cache-dir -r requirements.txt');
    expect(steps[0]).toContain(`${FAIL_MARK}INSTALL_FAILED`);
    expect(steps[1]).toContain('pip-audit');
    expect(steps[1]).toContain(`${FAIL_MARK}DEP_VULN`);
    expect(steps[2]).toContain('/workspace/app/agent/main.py');
    expect(steps[2]).toContain(`${FAIL_MARK}SMOKE_FAILED`);
  });

  it('uses npm ci, npm audit and node --check for node', () => {
    const steps = recipe('node-22', 'index.mjs');
    expect(steps[0]).toContain('npm ci --omit=dev');
    expect(steps[1]).toContain('npm audit --omit=dev --audit-level=high');
    expect(steps[2]).toContain('node --check');
  });

  it('rejects unknown runtimes', () => {
    expect(() => recipe('ruby-3', 'main.rb')).toThrow(/unknown runtime/);
  });
});

describe('build', () => {
  const tmp = () => mkdtempSync(join(tmpdir(), 'shim-build-'));

  it('builds from the app directory on the runtime base image and returns the image ref', async () => {
    const dir = tmp();
    const logPath = join(dir, 'build.log');
    writeFileSync(logPath, '');
    const { calls, Template } = fakeTemplate({ logs: ['step 1/5', 'step 2/5'] });
    const res = await handle({ op: 'build', name: 'agentstore-b1', appDir: '/tmp/app', runtime: 'python-3.12',
      entrypoint: 'main.py', logPath, cpu: 1, memoryMb: 1024 }, { Template, apiKey: 'k' });

    expect(res).toEqual({ ok: true, imageRef: 'tpl-123' });
    expect(calls[0]).toEqual(['Template', { fileContextPath: '/tmp/app' }]);
    expect(calls).toContainEqual(['fromImage', BASE_IMAGES['python-3.12']]);
    expect(calls).toContainEqual(['copy', '.', '/workspace/app']);
    expect(calls.filter((c) => c[0] === 'runCmd').every((c) => c[2]?.user === 'root')).toBe(true);
    expect(calls.at(-1)).toEqual(['build', 'agentstore-b1', { apiKey: 'k', cpuCount: 1, memoryMB: 1024 }]);
    expect(readFileSync(logPath, 'utf8')).toBe('step 1/5\nstep 2/5\n');
    rmSync(dir, { recursive: true, force: true });
  });

  it('reports the failing step code from the build log marker', async () => {
    const dir = tmp();
    const { Template } = fakeTemplate({ logs: ['found 2 vulnerabilities', `${FAIL_MARK}DEP_VULN`],
      fail: Object.assign(new Error('step failed'), { name: 'BuildError' }) });
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'python-3.12', entrypoint: 'main.py',
      logPath: join(dir, 'l.log') }, { Template, apiKey: 'k' });
    expect(res.ok).toBe(false);
    expect(res.error.code).toBe('DEP_VULN');
    expect(res.error.message).toContain('BuildError');
    rmSync(dir, { recursive: true, force: true });
  });

  it('reports BUILD_FAILED when no step marker was seen', async () => {
    const dir = tmp();
    const { Template } = fakeTemplate({ fail: new Error('upload failed') });
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'node-22', entrypoint: 'index.js',
      logPath: join(dir, 'l.log') }, { Template, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BUILD_FAILED' } });
    rmSync(dir, { recursive: true, force: true });
  });

  it('rejects an unknown runtime without building', async () => {
    const { calls, Template } = fakeTemplate();
    const res = await handle({ op: 'build', name: 'n', appDir: '/a', runtime: 'ruby-3', entrypoint: 'x.rb' },
      { Template, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST' } });
    expect(calls).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd runner/e2b-shim && npm test`
Expected: FAIL (`recipe` isn't exported, and there's no `build` op).

- [ ] **Step 3: Implement the recipe and the `build` op**

In `runner/e2b-shim/shim.mjs`, change the fs import to `import { appendFileSync, realpathSync } from 'node:fs';`, then add this after `rangeReadCommand`/`readFile`:
```js
// ---- Image build (spec §4 steps 4-5). The same recipe is what WS5d's gVisor backend implements. ----

export const APP_DIR = '/workspace/app';
export const FAIL_MARK = 'AGENTSTORE_STEP_FAILED:';
export const BASE_IMAGES = { 'python-3.12': 'python:3.12-slim', 'node-22': 'node:22-slim' };

// Imports the entrypoint under a name other than __main__, so `if __name__ == "__main__":` code does not run.
// sys.path[0] is set to the entrypoint's directory, as `python <entrypoint>` would do at run time.
export const PY_SMOKE = "import importlib.util as u, os, sys; sys.path[0] = os.path.dirname(sys.argv[1]); "
  + "s = u.spec_from_file_location('agentstore_smoke', sys.argv[1]); s.loader.exec_module(u.module_from_spec(s))";

// Runs one recipe step; on failure prints the marker the runner maps to a failure code.
function step(code, script) {
  return `sh -c ${shellQuote(script)} || { echo "${FAIL_MARK}${code}"; exit 1; }`;
}

export function recipe(runtime, entrypoint) {
  const ep = shellQuote(`${APP_DIR}/${entrypoint}`);
  if (runtime === 'python-3.12') {
    return [
      step('INSTALL_FAILED', `cd ${APP_DIR} && if [ -f requirements.txt ]; then pip install --no-cache-dir -r requirements.txt; fi`),
      step('DEP_VULN', `cd ${APP_DIR} && if [ -f requirements.txt ]; then python -m venv /opt/agentstore-audit`
        + ` && /opt/agentstore-audit/bin/pip install --no-cache-dir --quiet pip-audit`
        + ` && /opt/agentstore-audit/bin/pip-audit --progress-spinner off -r requirements.txt; rc=$?;`
        + ` rm -rf /opt/agentstore-audit; exit $rc; fi`),
      step('SMOKE_FAILED', `cd ${APP_DIR} && timeout 60 python -c ${shellQuote(PY_SMOKE)} ${ep}`),
    ];
  }
  if (runtime === 'node-22') {
    return [
      step('INSTALL_FAILED', `cd ${APP_DIR} && if [ -f package.json ]; then`
        + ` [ -f package-lock.json ] || { echo "package-lock.json is required next to package.json"; exit 1; };`
        + ` npm ci --omit=dev; fi`),
      step('DEP_VULN', `cd ${APP_DIR} && if [ -f package.json ]; then npm audit --omit=dev --audit-level=high; fi`),
      step('SMOKE_FAILED', `cd ${APP_DIR} && node --check ${ep}`),
    ];
  }
  throw new Error(`unknown runtime ${runtime}`);
}

function lineWriter(path) {
  return (line) => {
    if (path) appendFileSync(path, `${String(line).replace(/\n+$/, '')}\n`);
  };
}

async function build(req, { Template, apiKey }) {
  const base = BASE_IMAGES[req.runtime];
  if (!base) return fail('BAD_REQUEST', `unknown runtime ${String(req.runtime)}`);
  const writeLog = lineWriter(req.logPath);
  let template = Template({ fileContextPath: req.appDir })
    .fromImage(base)
    .runCmd(`mkdir -p ${APP_DIR}`, { user: 'root' })
    .copy('.', APP_DIR)
    .runCmd(`chmod -R a+rX ${APP_DIR}`, { user: 'root' });
  for (const cmd of recipe(req.runtime, req.entrypoint)) {
    template = template.runCmd(cmd, { user: 'root' });
  }
  const failedSteps = [];
  try {
    const info = await Template.build(template, req.name, {
      apiKey,
      cpuCount: req.cpu ?? 1,
      memoryMB: req.memoryMb ?? 1024,
      onBuildLogs: (entry) => {
        const line = String(entry?.message ?? entry);
        writeLog(line);
        const at = line.indexOf(FAIL_MARK);
        if (at >= 0) failedSteps.push(line.slice(at + FAIL_MARK.length).trim().split(/\s/)[0]);
      },
    });
    return { ok: true, imageRef: info.templateId };
  } catch (e) {
    return fail(failedSteps[0] ?? 'BUILD_FAILED',
      `build failed: ${e?.name ?? 'Error'}: ${e?.message ?? ''}`.slice(0, 500));
  }
}
```

In `handle`, add `case 'build': return await build(req, deps);`. In `main`, change the SDK import and the handle call to:
```js
  const { Sandbox, Template } = await import('e2b');
  const res = await handle(req, { Sandbox, Template, apiKey: process.env.E2B_API_KEY });
```

- [ ] **Step 4: Run the shim tests**

Run: `cd runner/e2b-shim && npm test`
Expected: PASS.

- [ ] **Step 5: Write the failing Java tests**

`runner/src/test/java/com/agentstore/runner/backend/e2b/LogFileFollowerTest.java`:
```java
package com.agentstore.runner.backend.e2b;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static org.assertj.core.api.Assertions.assertThat;

class LogFileFollowerTest {

    @Test
    void forwardsCompleteLinesWhileRunningAndDrainsOnClose(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build.log");
        List<String> lines = new CopyOnWriteArrayList<>();
        try (LogFileFollower ignored = new LogFileFollower(file, lines::add)) {
            Files.writeString(file, "one\ntw", CREATE, APPEND);
            long until = System.currentTimeMillis() + 5_000;
            while (lines.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertThat(lines).containsExactly("one");
            Files.writeString(file, "o\nthree\n", APPEND);
        }
        assertThat(lines).containsExactly("one", "two", "three");
    }

    @Test
    void aMissingFileIsNotAnError(@TempDir Path dir) {
        List<String> lines = new CopyOnWriteArrayList<>();
        try (LogFileFollower ignored = new LogFileFollower(dir.resolve("never-written.log"), lines::add)) {
            // nothing
        }
        assertThat(lines).isEmpty();
    }
}
```

Add to `E2bBackendTest` (imports: `com.agentstore.runner.backend.AgentRuntime`, `BuildFailedException`, `ImageBuild`, `java.nio.file.*`, `java.time.Duration`, `java.util.ArrayList`, `org.junit.jupiter.api.io.TempDir`, `static org.mockito.ArgumentMatchers.eq`):
```java
    ImageBuild imageBuild(Path app) {
        return new ImageBuild("B1", app, AgentRuntime.PYTHON_3_12, "agent/main.py");
    }

    @Test
    void buildSendsTheRecipeInputsAndReturnsTheImageRef(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenReturn(om.createObjectNode().put("ok", true).put("imageRef", "tpl-1"));

        assertThat(backend.buildImage(imageBuild(app), l -> { })).isEqualTo("tpl-1");
        verify(shim).call(argThat(req -> "build".equals(req.get("op")) && "agentstore-b1".equals(req.get("name"))
                && app.toAbsolutePath().toString().equals(req.get("appDir")) && "python-3.12".equals(req.get("runtime"))
                && "agent/main.py".equals(req.get("entrypoint")) && req.get("logPath") != null), eq(Duration.ofMinutes(14)));
    }

    @Test
    void linesTheShimWritesReachTheBuildLog(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            Files.writeString(Path.of((String) req.get("logPath")), "Collecting requests\nInstalled\n");
            return om.createObjectNode().put("ok", true).put("imageRef", "tpl-1");
        });
        List<String> lines = new ArrayList<>();
        backend.buildImage(imageBuild(app), lines::add);
        assertThat(lines).containsExactly("Collecting requests", "Installed");
    }

    @Test
    void aRecipeStepFailureKeepsItsCode(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimException("shim build failed: audit", "DEP_VULN"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("DEP_VULN"));
    }

    @Test
    void anyOtherShimFailureIsBuildFailed(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimException("shim build failed: x", "E2B_ERROR"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("BUILD_FAILED"));
    }

    @Test
    void aShimTimeoutIsTimeout(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimTimeoutException("shim build timed out"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("TIMEOUT"));
    }

    @Test
    void aBlankImageRefIsBuildFailed(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenReturn(om.createObjectNode().put("ok", true));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("BUILD_FAILED"));
    }
```

- [ ] **Step 6: Run them and watch them fail**

Run: `cd runner && mvn -q test -Dtest='LogFileFollowerTest,E2bBackendTest'`
Expected: compilation fails (`LogFileFollower` doesn't exist), then failures from the Task 2 stub.

- [ ] **Step 7: Implement `LogFileFollower`**

`runner/src/main/java/com/agentstore/runner/backend/e2b/LogFileFollower.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.BuildLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Copies the complete lines the shim appends to a local file into a {@link BuildLog} while a long call runs. */
final class LogFileFollower implements AutoCloseable {

    private static final int CHUNK = 1 << 20;

    private final Path file;
    private final BuildLog log;
    private final Thread thread;
    private long position;
    private volatile boolean stopping;

    LogFileFollower(Path file, BuildLog log) {
        this.file = file;
        this.log = log;
        this.thread = Thread.ofVirtual().name("build-log-follower").start(this::loop);
    }

    private void loop() {
        while (!stopping) {
            while (pump()) {
                // keep forwarding while there is more
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Forwards the complete lines appended since the last call. Returns true when it forwarded something. */
    synchronized boolean pump() {
        if (!Files.exists(file)) {
            return false;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long available = channel.size() - position;
            if (available <= 0) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(available, CHUNK));
            channel.position(position);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            byte[] bytes = buffer.array();
            int end = buffer.position();
            int lastNewline = -1;
            for (int i = end - 1; i >= 0; i--) {
                if (bytes[i] == '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline < 0) {
                if (end == CHUNK) {   // one line longer than a chunk: forward it as it is
                    log.append(new String(bytes, 0, end, StandardCharsets.UTF_8));
                    position += end;
                    return true;
                }
                return false;
            }
            new String(bytes, 0, lastNewline + 1, StandardCharsets.UTF_8).lines().forEach(log::append);
            position += lastNewline + 1;
            return true;
        } catch (IOException e) {
            return false;   // retried on the next tick; close() makes a final attempt
        }
    }

    @Override
    public void close() {
        stopping = true;
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        while (pump()) {
            // drain what is left
        }
    }
}
```

- [ ] **Step 8: Implement `E2bBackend.buildImage`**

In `E2bBackend.java`:
- Replace the Task 2 stub with the code below.
- Add a `buildTimeout` field and the 4-argument constructor, and make the 3-argument constructor delegate: `this(rest, shim, runnerId, DEFAULT_BUILD_TIMEOUT);`.
- Add the imports `java.io.IOException`, `java.nio.file.Files`, `java.nio.file.Path`, `java.time.Duration`, `java.util.HashMap`, `java.util.Locale`, `java.util.Set`.

```java
    static final Duration DEFAULT_BUILD_TIMEOUT = Duration.ofMinutes(14);
    static final int BUILD_CPU = 1;
    static final int BUILD_MEMORY_MB = 1024;
    private static final Set<String> STEP_CODES = Set.of(BuildFailedException.INSTALL_FAILED,
            BuildFailedException.DEP_VULN, BuildFailedException.SMOKE_FAILED);

    private final Duration buildTimeout;

    public E2bBackend(E2bRestClient rest, ShimClient shim, String runnerId, Duration buildTimeout) {
        this.rest = rest;
        this.shim = shim;
        this.runnerId = runnerId;
        this.buildTimeout = buildTimeout;
    }

    @Override
    public String buildImage(ImageBuild build, BuildLog log) {
        Path logFile;
        try {
            logFile = Files.createTempFile("e2b-build-", ".log");
        } catch (IOException e) {
            throw new BackendException("Could not create a build log file", e);
        }
        Map<String, Object> request = new HashMap<>();
        request.put("op", "build");
        request.put("name", templateName(build.buildId()));
        request.put("appDir", build.appDir().toAbsolutePath().toString());
        request.put("runtime", build.runtime().id());
        request.put("entrypoint", build.entrypoint());
        request.put("logPath", logFile.toString());
        request.put("cpu", BUILD_CPU);
        request.put("memoryMb", BUILD_MEMORY_MB);
        try {
            JsonNode response;
            try (LogFileFollower ignored = new LogFileFollower(logFile, log)) {
                response = shim.call(request, buildTimeout);
            }
            String imageRef = response.path("imageRef").asText("");
            if (imageRef.isBlank()) {
                throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "The image build returned no image reference");
            }
            return imageRef;
        } catch (ShimTimeoutException e) {
            throw new BuildFailedException(BuildFailedException.TIMEOUT, "The image build took longer than " + buildTimeout);
        } catch (ShimException e) {
            String code = STEP_CODES.contains(e.code()) ? e.code() : BuildFailedException.BUILD_FAILED;
            throw new BuildFailedException(code, e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
                // temp file; the OS cleans it up
            }
        }
    }

    /** E2B template names are lowercase; build ids are UUIDs. */
    static String templateName(String buildId) {
        return "agentstore-" + buildId.toLowerCase(Locale.ROOT);
    }
```

- [ ] **Step 9: Add the `build-timeout` setting**

In `RunnerProperties.E2b`, add `Duration buildTimeout` as the **last** record component and include `", buildTimeout=" + buildTimeout` in its `toString`. In `application.yml`, under `agentstore.runner.e2b`, add `build-timeout: PT14M`. In `E2bConfig`, construct the backend with `new E2bBackend(rest, shim, props.runnerId(), e2b.buildTimeout())`. In `RunnerPropertiesTest`, add `, Duration.ofMinutes(14)` as the last argument of `new RunnerProperties.E2b(...)`.

- [ ] **Step 10: Add the live E2B build test (tagged; it runs only by hand)**

`runner/src/test/java/com/agentstore/runner/backend/e2b/E2bImageBuildLiveTest.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.AgentRuntime;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.ImageBuild;
import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Builds real E2B templates (a few minutes of credit each). Run:
 * E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bImageBuildLiveTest
 * Templates are left behind in the E2B dashboard; deleting them is WS5d's cleanup.
 */
@Tag("e2b")
class E2bImageBuildLiveTest {

    static final String RUNNER_ID = "runner-e2b-build-test";
    static E2bBackend backend;

    @BeforeAll
    static void connect() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        var rest = new E2bRestClient(RestClient.builder(), "https://api.e2b.app", apiKey);
        var shim = new ShimClient(List.of("node", "e2b-shim/shim.mjs"), Duration.ofSeconds(60), apiKey);
        backend = new E2bBackend(rest, shim, RUNNER_ID, Duration.ofMinutes(14));
    }

    @Test
    void buildsAPythonAppThatRunsAndExits(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve("main.py"), "if __name__ == '__main__':\n    print('hello from build')\n");
        Files.writeString(app.resolve("requirements.txt"), "");
        List<String> log = new CopyOnWriteArrayList<>();

        String image = backend.buildImage(
                new ImageBuild(UUID.randomUUID().toString(), app, AgentRuntime.PYTHON_3_12, "main.py"), log::add);
        assertThat(log).isNotEmpty();

        String id = backend.create(new SandboxLaunch(image, List.of("sh", "-c", "cd /workspace/app && exec python 'main.py'"),
                Map.of(), List.of(), 1, 1024, 120,
                Map.of(Labels.RUNNER_ID, RUNNER_ID, Labels.REQUEST_ID, "build-live-" + UUID.randomUUID())));
        try {
            SandboxState state = SandboxState.running();
            long until = System.currentTimeMillis() + 90_000;
            while (!state.isTerminal() && System.currentTimeMillis() < until) {
                Thread.sleep(2_000);
                state = backend.status(id);
            }
            assertThat(state).isEqualTo(SandboxState.exited(0));
            assertThat(backend.readLogs(id, 0, 4096).data()).contains("hello from build");
        } finally {
            backend.kill(id);
        }
    }

    @Test
    void aMissingDependencyFailsWithInstallFailed(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve("main.py"), "print('x')\n");
        Files.writeString(app.resolve("requirements.txt"), "agentstore-no-such-package-3f9c==1.0.0\n");
        assertThatThrownBy(() -> backend.buildImage(
                new ImageBuild(UUID.randomUUID().toString(), app, AgentRuntime.PYTHON_3_12, "main.py"), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INSTALL_FAILED));
    }
}
```

- [ ] **Step 11: Run the runner tests (without E2B)**

Run: `cd runner && mvn -q test`
Expected: PASS. `E2bImageBuildLiveTest` is excluded by its tag.

- [ ] **Step 12: Commit**

```bash
git add runner/e2b-shim/shim.mjs runner/e2b-shim/shim.test.mjs runner/src/main/java/com/agentstore/runner \
  runner/src/main/resources/application.yml runner/src/test/java/com/agentstore/runner
git commit -m "feat(runner): build images on E2B through the shim with the shared recipe"
```

---

### Task 4: Safe unpacking and the gitleaks secret scan

**Prerequisite (once per machine):** `brew install gitleaks` (Linux: download the release binary from github.com/gitleaks/gitleaks). The real-gitleaks test is skipped when it isn't installed, and every other test uses fake scripts. The runner host needs gitleaks installed (Task 12 documents this).

**Why unpacking can't create symlinks.** `java.util.zip` doesn't read Unix modes, so an entry stored as a symlink is written out as a regular file containing the link target. The unpacker therefore never creates a symlink, and a link can't point outside the directory. A test pins this.

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/build/BundleUnpacker.java`, `SecretScanner.java`
- Create: `runner/src/test/java/com/agentstore/runner/build/TestZips.java`
- Create: `runner/src/test/resources/gitleaks/clean.sh`, `found.sh`, `broken.sh`
- Test: `runner/src/test/java/com/agentstore/runner/build/BundleUnpackerTest.java`, `SecretScannerTest.java`

**Interfaces:**
- Consumes (Task 2): `BuildFailedException`, `BuildLog`, `BackendException`.
- Produces:
  - `new BundleUnpacker()`, with the package-private `BundleUnpacker(long maxUnpackedBytes, int maxEntries)`.
  - `void unpack(Path zip, Path emptyTargetDir)`, which throws `BuildFailedException(INVALID_BUNDLE, …)`.
  - `new SecretScanner(List<String> gitleaksCommand, Duration timeout)`.
  - `void scan(Path appDir, BuildLog log)`, which throws `BuildFailedException` with `SECRET_FOUND` or `BUILD_FAILED`.
  - Test helper `TestZips.zip(Map<String,String>)`, `TestZips.zipBytes(Map<String,byte[]>)`, `TestZips.sha256(byte[])`.

- [ ] **Step 1: Add the test helpers and fake scanners**

`runner/src/test/java/com/agentstore/runner/build/TestZips.java`:
```java
package com.agentstore.runner.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds zips in memory. ZipOutputStream accepts any entry name, including hostile ones. */
public final class TestZips {

    private TestZips() {}

    public static byte[] zip(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((name, text) -> bytes.put(name, text.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    public static byte[] zipBytes(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`runner/src/test/resources/gitleaks/clean.sh`:
```sh
# Fake gitleaks: no findings.
exit 0
```

`runner/src/test/resources/gitleaks/found.sh`:
```sh
# Fake gitleaks: one finding in <source>/config.py, written to --report-path; exit code 1 means "leaks found".
report=""
source=""
while [ $# -gt 0 ]; do
  case "$1" in
    --report-path) shift; report="$1" ;;
    --*) ;;
    *) source="$1" ;;
  esac
  shift
done
printf '[{"File":"%s/config.py","StartLine":3,"RuleID":"aws-access-token","Secret":"REDACTED"}]' "$source" > "$report"
exit 1
```

`runner/src/test/resources/gitleaks/broken.sh`:
```sh
# Fake gitleaks: crashes.
exit 2
```

- [ ] **Step 2: Write the failing unpacker tests**

`runner/src/test/java/com/agentstore/runner/build/BundleUnpackerTest.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BundleUnpackerTest {

    @TempDir Path work;

    Path target() throws Exception {
        return Files.createDirectory(work.resolve("app"));
    }

    Path write(byte[] zip) throws Exception {
        return Files.write(work.resolve("bundle.zip"), zip);
    }

    void assertInvalid(Runnable unpack, String messagePart) {
        assertThatThrownBy(unpack::run)
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining(messagePart)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INVALID_BUNDLE));
    }

    @Test
    void unpacksNestedFiles() throws Exception {
        Path zip = write(TestZips.zip(Map.of("manifest.json", "{}", "agent/main.py", "print(1)")));
        Path app = target();
        new BundleUnpacker().unpack(zip, app);
        assertThat(app.resolve("agent/main.py")).hasContent("print(1)");
        assertThat(app.resolve("manifest.json")).exists();
    }

    @Test
    void rejectsParentTraversalAndWritesNothingOutside() throws Exception {
        Path zip = write(TestZips.zip(Map.of("../evil.txt", "x")));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker().unpack(zip, app), "escapes");
        assertThat(work.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsAbsoluteAndBackslashPaths() throws Exception {
        Path app = target();
        Path absolute = write(TestZips.zip(Map.of("/etc/evil", "x")));
        assertInvalid(() -> new BundleUnpacker().unpack(absolute, app), "Absolute");
        Path backslash = write(TestZips.zip(Map.of("..\\evil.txt", "x")));
        assertInvalid(() -> new BundleUnpacker().unpack(backslash, app), "Absolute");
    }

    @Test
    void rejectsAZipBomb() throws Exception {
        Path zip = write(TestZips.zipBytes(Map.of("big.bin", new byte[10_000])));   // compresses to a few bytes
        assertInvalid(() -> new BundleUnpacker(1024, 100).unpack(zip, target()), "unpacked-size limit");
    }

    @Test
    void rejectsTooManyEntries() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            files.put("f" + i + ".txt", "x");
        }
        Path zip = write(TestZips.zip(files));
        assertInvalid(() -> new BundleUnpacker(1_000_000, 3).unpack(zip, target()), "more than 3 entries");
    }

    @Test
    void rejectsDuplicatePathsAfterNormalizing() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", "a");
        files.put("./main.py", "b");
        Path zip = write(TestZips.zip(files));
        assertInvalid(() -> new BundleUnpacker().unpack(zip, target()), "Duplicate");
    }

    @Test
    void rejectsSomethingThatIsNotAZip() throws Exception {
        Path zip = Files.write(work.resolve("bundle.zip"), "definitely not a zip".getBytes());
        assertInvalid(() -> new BundleUnpacker().unpack(zip, target()), "empty");
    }

    @Test
    void symlinkEntriesBecomePlainFiles() throws Exception {
        assumeTrue(commandExists("zip"), "zip CLI not installed");
        Path src = Files.createDirectory(work.resolve("src"));
        Files.createSymbolicLink(src.resolve("link"), Path.of("/etc/passwd"));
        Process p = new ProcessBuilder("zip", "-q", "-y", work.resolve("links.zip").toString(), "link")
                .directory(src.toFile()).inheritIO().start();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();

        Path app = target();
        new BundleUnpacker().unpack(work.resolve("links.zip"), app);
        assertThat(Files.isSymbolicLink(app.resolve("link"))).isFalse();
        assertThat(app.resolve("link")).hasContent("/etc/passwd");
    }

    static boolean commandExists(String name) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + name).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `cd runner && mvn -q test -Dtest=BundleUnpackerTest`
Expected: compilation fails (`BundleUnpacker` doesn't exist).

- [ ] **Step 4: Implement `BundleUnpacker`**

`runner/src/main/java/com/agentstore/runner/build/BundleUnpacker.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a bundle zip into an empty directory, refusing anything that could escape it (spec §4 step 3).
 * java.util.zip ignores Unix modes, so symlink entries are written as plain files: no symlink is ever created.
 */
public class BundleUnpacker {

    public static final long MAX_UNPACKED_BYTES = 500L * 1024 * 1024;
    public static final int MAX_ENTRIES = 20_000;

    private final long maxUnpackedBytes;
    private final int maxEntries;

    public BundleUnpacker() {
        this(MAX_UNPACKED_BYTES, MAX_ENTRIES);
    }

    BundleUnpacker(long maxUnpackedBytes, int maxEntries) {
        this.maxUnpackedBytes = maxUnpackedBytes;
        this.maxEntries = maxEntries;
    }

    public void unpack(Path zip, Path target) {
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw invalid("Bundle has more than " + maxEntries + " entries");
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.matches("^[A-Za-z]:.*")) {
                    throw invalid("Absolute or Windows-style path in bundle: " + name);
                }
                Path dest = root.resolve(name).normalize();
                if (!dest.startsWith(root)) {
                    throw invalid("Path escapes the bundle: " + name);
                }
                if (dest.equals(root)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    total = copyWithinLimit(in, out, total);
                }
            }
        } catch (FileAlreadyExistsException e) {
            throw invalid("Duplicate path in bundle: " + root.relativize(Path.of(e.getFile())));
        } catch (ZipException e) {
            throw invalid("Bundle is not a valid zip: " + e.getMessage());
        } catch (IOException e) {
            throw invalid("Bundle could not be unpacked: " + e.getClass().getSimpleName());
        }
        if (entries == 0) {
            throw invalid("Bundle is empty or not a zip");
        }
    }

    private long copyWithinLimit(InputStream in, OutputStream out, long total) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > maxUnpackedBytes) {
                throw invalid("Bundle is larger than the unpacked-size limit (" + human(maxUnpackedBytes) + ")");
            }
            out.write(buffer, 0, n);
        }
        return total;
    }

    private static String human(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : bytes + " bytes";
    }

    private static BuildFailedException invalid(String reason) {
        return new BuildFailedException(BuildFailedException.INVALID_BUNDLE, reason);
    }
}
```

- [ ] **Step 5: Run the unpacker tests**

Run: `cd runner && mvn -q test -Dtest=BundleUnpackerTest`
Expected: PASS. `symlinkEntriesBecomePlainFiles` is skipped where `zip` isn't installed.

- [ ] **Step 6: Write the failing scanner tests**

`runner/src/test/java/com/agentstore/runner/build/SecretScannerTest.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretScannerTest {

    static SecretScanner fake(String script) {
        return new SecretScanner(List.of("sh", "src/test/resources/gitleaks/" + script), Duration.ofSeconds(10));
    }

    static void assertCode(Throwable e, String code) {
        assertThat(((BuildFailedException) e).code()).isEqualTo(code);
    }

    @Test
    void noFindingsPasses(@TempDir Path app) {
        List<String> log = new ArrayList<>();
        fake("clean.sh").scan(app, log::add);
        assertThat(log).containsExactly("Secret scan: no findings");
    }

    @Test
    void aFindingFailsWithFileLineAndRuleButNotTheAbsolutePath(@TempDir Path app) {
        List<String> log = new ArrayList<>();
        assertThatThrownBy(() -> fake("found.sh").scan(app, log::add))
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining("config.py:3 (aws-access-token)")
                .hasMessageNotContaining(app.toString())
                .satisfies(e -> assertCode(e, BuildFailedException.SECRET_FOUND));
        assertThat(log).contains("Secret found: config.py:3 (aws-access-token)");
    }

    @Test
    void aCrashedScannerIsBuildFailed(@TempDir Path app) {
        assertThatThrownBy(() -> fake("broken.sh").scan(app, l -> { }))
                .satisfies(e -> assertCode(e, BuildFailedException.BUILD_FAILED));
    }

    @Test
    void aMissingScannerIsBuildFailed(@TempDir Path app) {
        var scanner = new SecretScanner(List.of("/definitely/not/gitleaks"), Duration.ofSeconds(5));
        assertThatThrownBy(() -> scanner.scan(app, l -> { }))
                .satisfies(e -> assertCode(e, BuildFailedException.BUILD_FAILED));
    }

    @Test
    @EnabledIf("gitleaksInstalled")
    void realGitleaksCatchesAPlantedAwsKeyWithoutEchoingIt(@TempDir Path app) throws Exception {
        String key = "AKIAXWNVK4PQ2JT6ZM3C";
        Files.writeString(app.resolve("config.py"), "AWS_ACCESS_KEY_ID = \"" + key + "\"\n");
        assertThatThrownBy(() -> new SecretScanner(List.of("gitleaks"), Duration.ofSeconds(60)).scan(app, l -> { }))
                .hasMessageContaining("config.py:1")
                .hasMessageNotContaining(key)
                .satisfies(e -> assertCode(e, BuildFailedException.SECRET_FOUND));
    }

    static boolean gitleaksInstalled() {
        try {
            return new ProcessBuilder("gitleaks", "version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 7: Run it and watch it fail**

Run: `cd runner && mvn -q test -Dtest=SecretScannerTest`
Expected: compilation fails.

- [ ] **Step 8: Implement `SecretScanner`**

`runner/src/main/java/com/agentstore/runner/build/SecretScanner.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.BuildLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs gitleaks over an unpacked bundle (spec §4 step 3, §4.2: {@code gitleaks dir}). Any finding fails the build
 * with SECRET_FOUND, naming file, line and rule. {@code --redact} keeps the secret itself out of the report.
 */
public class SecretScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_LISTED = 5;

    private final List<String> command;
    private final Duration timeout;

    public SecretScanner(List<String> command, Duration timeout) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    public void scan(Path appDir, BuildLog log) {
        Path report;
        try {
            report = Files.createTempFile("gitleaks-", ".json");
        } catch (IOException e) {
            throw new BackendException("Could not create the secret-scan report file", e);
        }
        try {
            List<String> cmd = new ArrayList<>(command);
            cmd.addAll(List.of("dir", "--no-banner", "--redact", "--exit-code", "1", "--report-format", "json",
                    "--report-path", report.toString(), appDir.toAbsolutePath().toString()));
            int exit = run(cmd);
            if (exit == 0) {
                log.append("Secret scan: no findings");
                return;
            }
            if (exit != 1) {
                throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "Secret scan failed (gitleaks exit " + exit + ")");
            }
            List<String> findings = describe(report, appDir);
            findings.forEach(f -> log.append("Secret found: " + f));
            String listed = String.join("; ", findings.subList(0, Math.min(MAX_LISTED, findings.size())));
            String more = findings.size() > MAX_LISTED ? " and " + (findings.size() - MAX_LISTED) + " more" : "";
            throw new BuildFailedException(BuildFailedException.SECRET_FOUND, "Secrets found in the bundle: " + listed + more
                    + ". Remove them and pass them to the agent as connection secrets instead.");
        } finally {
            try {
                Files.deleteIfExists(report);
            } catch (IOException ignored) {
                // temp file
            }
        }
    }

    private int run(List<String> cmd) {
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "Secret scanner could not start: " + e.getMessage());
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "Secret scan timed out after " + timeout);
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BackendException("Secret scan interrupted");
        }
    }

    /** "relative/file:line (rule)" per finding; never the matched secret. */
    static List<String> describe(Path report, Path appDir) {
        JsonNode root;
        try {
            root = MAPPER.readTree(report.toFile());
        } catch (IOException e) {
            return List.of("(the scanner reported findings but its report was unreadable)");
        }
        Path base = appDir.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        if (root != null) {
            for (JsonNode finding : root) {
                String file = finding.path("File").asText("?");
                Path path = Path.of(file).normalize();
                if (path.isAbsolute() && path.startsWith(base)) {
                    file = base.relativize(path).toString();
                }
                out.add(file + ":" + finding.path("StartLine").asInt(0) + " (" + finding.path("RuleID").asText("secret") + ")");
            }
        }
        if (out.isEmpty()) {
            out.add("(no details reported)");
        }
        return out;
    }
}
```

- [ ] **Step 9: Run the runner tests**

Run: `cd runner && mvn -q test`
Expected: PASS. If gitleaks is installed, check that `realGitleaksCatchesAPlantedAwsKeyWithoutEchoingIt` ran rather than being skipped.

- [ ] **Step 10: Commit**

```bash
git add runner/src/main/java/com/agentstore/runner/build runner/src/test/java/com/agentstore/runner/build \
  runner/src/test/resources/gitleaks
git commit -m "feat(runner): unpack bundles safely and scan them for secrets with gitleaks"
```

---

### Task 5: Runner build service and the `/builds` API

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/build/BundleFetcher.java`, `LogUploader.java`, `BuildService.java`
- Create: `runner/src/main/java/com/agentstore/runner/api/BuildController.java`, `CreateBuildRequest.java`
- Modify: `runner/src/main/java/com/agentstore/runner/api/ApiErrorHandler.java` (make the 400 message generic)
- Modify: `runner/src/main/java/com/agentstore/runner/RunnerProperties.java`, `RunnerConfig.java`, `runner/src/main/resources/application.yml`, `runner/src/test/resources/application-test.yml`, `runner/src/test/java/com/agentstore/runner/RunnerPropertiesTest.java`
- Create: `runner/src/test/java/com/agentstore/runner/build/BundleServer.java`
- Test: `runner/src/test/java/com/agentstore/runner/build/BuildServiceTest.java`, `runner/src/test/java/com/agentstore/runner/api/BuildControllerTest.java`

**Interfaces:**
- Consumes: Task 2 (`IsolationBackend.buildImage`, `ImageBuild`, `BuildLogBuffer`, `BuildFailedException`, `AgentRuntime`), Task 4 (`BundleUnpacker`, `SecretScanner`, `TestZips`).
- Produces:
  - `BuildService.submit(BuildCommand) → BuildView`, `BuildService.get(String buildId) → BuildView`, `shutdown()`.
  - `record BuildCommand(String buildId, String runtime, String bundleUrl, String bundleSha256, String entrypoint, String logUploadUrl)`.
  - `record BuildView(String buildId, Status status, String backend, String imageRef, String failureCode, String failureReason)`.
  - `enum Status { QUEUED, BUILDING, SUCCEEDED, FAILED }`.
  - The HTTP API described in Global Constraints.
  - `RunnerProperties` becomes `(secret, runnerId, backend, springBaseUrl, budget, build, e2b)`, with `record Build(List<String> gitleaksCommand, Duration timeout, Duration scanTimeout, Duration downloadTimeout, Duration logFlushInterval, Path workDir)`.

- [ ] **Step 1: Add the test HTTP server**

`runner/src/test/java/com/agentstore/runner/build/BundleServer.java`:
```java
package com.agentstore.runner.build;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Stands in for the object store's pre-signed URLs: GET /bundle.zip serves bytes, PUT /log records the last body. */
public final class BundleServer implements AutoCloseable {

    private final HttpServer server;
    private volatile byte[] bundle = new byte[0];
    private final AtomicReference<byte[]> lastLog = new AtomicReference<>(new byte[0]);
    public final AtomicInteger logPuts = new AtomicInteger();

    public BundleServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/bundle.zip", exchange -> {
            byte[] body = bundle;
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/log", exchange -> {
            lastLog.set(exchange.getRequestBody().readAllBytes());
            logPuts.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    public void serve(byte[] zip) { bundle = zip; }

    public String bundleUrl() { return base() + "/bundle.zip?X-Amz-Signature=secret-signature"; }

    public String logUrl() { return base() + "/log?X-Amz-Signature=secret-signature"; }

    public String lastLog() { return new String(lastLog.get(), StandardCharsets.UTF_8); }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Override
    public void close() { server.stop(0); }
}
```

- [ ] **Step 2: Write the failing service tests**

`runner/src/test/java/com/agentstore/runner/build/BuildServiceTest.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.FakeBackend;
import com.agentstore.runner.build.BuildService.BuildCommand;
import com.agentstore.runner.build.BuildService.BuildView;
import com.agentstore.runner.build.BuildService.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildServiceTest {

    @TempDir Path work;
    BundleServer server;
    FakeBackend backend;
    BuildService service;

    @BeforeEach
    void setUp() {
        server = new BundleServer();
        backend = new FakeBackend("runner-test");
        service = newService(Duration.ofSeconds(30), "clean.sh");
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        server.close();
    }

    BuildService newService(Duration timeout, String gitleaksScript) {
        HttpClient http = HttpClient.newHttpClient();
        return new BuildService(backend,
                new BundleFetcher(http, BundleFetcher.MAX_BUNDLE_BYTES, Duration.ofSeconds(10)),
                new BundleUnpacker(),
                new SecretScanner(List.of("sh", "src/test/resources/gitleaks/" + gitleaksScript), Duration.ofSeconds(10)),
                new LogUploader(http, Duration.ofSeconds(5)),
                work, timeout, Duration.ofMillis(200), Clock.systemUTC());
    }

    static Map<String, String> app(String... extra) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("manifest.json", "{}");
        files.put("main.py", "print('hi')");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            files.put(extra[i], extra[i + 1]);
        }
        return files;
    }

    BuildView submit(String buildId, Map<String, String> files) {
        byte[] zip = TestZips.zip(files);
        server.serve(zip);
        return service.submit(new BuildCommand(buildId, "python-3.12", server.bundleUrl(), TestZips.sha256(zip),
                "main.py", server.logUrl()));
    }

    BuildView awaitFinished(String buildId) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        BuildView view = service.get(buildId);
        while ((view.status() == Status.QUEUED || view.status() == Status.BUILDING) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            view = service.get(buildId);
        }
        return view;
    }

    @Test
    void aGoodBundleSucceedsAndItsLogIsUploadedBeforeTheStatusFlips() throws Exception {
        submit("b1", app());
        BuildView view = awaitFinished("b1");

        assertThat(view.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(view.imageRef()).isEqualTo("fake-image-b1");
        assertThat(view.backend()).isEqualTo("fake");
        assertThat(server.lastLog()).contains("Secret scan: no findings").contains("fake build of main.py")
                .contains("Build succeeded: fake-image-b1");
    }

    @Test
    void theSameBuildIdIsBuiltOnce() throws Exception {
        submit("b2", app());
        submit("b2", app());
        awaitFinished("b2");
        assertThat(backend.buildCalls.get()).isEqualTo(1);
    }

    @Test
    void aShaMismatchIsAnInvalidBundle() throws Exception {
        byte[] zip = TestZips.zip(app());
        server.serve(zip);
        service.submit(new BuildCommand("b3", "python-3.12", server.bundleUrl(), "0".repeat(64), "main.py", server.logUrl()));
        BuildView view = awaitFinished("b3");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.INVALID_BUNDLE);
        assertThat(view.failureReason()).contains("sha256").doesNotContain("secret-signature");
        assertThat(backend.buildCalls.get()).isZero();
    }

    @Test
    void aMissingEntrypointIsAnInvalidBundle() throws Exception {
        byte[] zip = TestZips.zip(Map.of("manifest.json", "{}"));
        server.serve(zip);
        service.submit(new BuildCommand("b4", "python-3.12", server.bundleUrl(), TestZips.sha256(zip), "main.py", server.logUrl()));
        BuildView view = awaitFinished("b4");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.INVALID_BUNDLE);
        assertThat(view.failureReason()).contains("Entrypoint main.py");
    }

    @Test
    void anUnknownRuntimeIsAnInvalidBundle() throws Exception {
        byte[] zip = TestZips.zip(app());
        server.serve(zip);
        service.submit(new BuildCommand("b5", "ruby-3", server.bundleUrl(), TestZips.sha256(zip), "main.py", server.logUrl()));
        assertThat(awaitFinished("b5").failureCode()).isEqualTo(BuildFailedException.INVALID_BUNDLE);
    }

    @Test
    void aSecretFailsTheBuildBeforeTheBackendRuns() throws Exception {
        service.shutdown();
        service = newService(Duration.ofSeconds(30), "found.sh");
        submit("b6", app());
        BuildView view = awaitFinished("b6");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.SECRET_FOUND);
        assertThat(view.failureReason()).contains("config.py:3");
        assertThat(backend.buildCalls.get()).isZero();
    }

    @Test
    void aBackendStepFailureKeepsItsCode() throws Exception {
        submit("b7", app(FakeBackend.FAIL_MARKER, "DEP_VULN"));
        BuildView view = awaitFinished("b7");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo("DEP_VULN");
        assertThat(server.lastLog()).contains("Build failed: DEP_VULN");
    }

    @Test
    void aBuildPastItsTimeoutFailsWithTimeout() throws Exception {
        service.shutdown();
        service = newService(Duration.ofMillis(500), "clean.sh");
        backend.buildDelay = Duration.ofSeconds(10);
        submit("b8", app());
        BuildView view = awaitFinished("b8");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.TIMEOUT);
    }

    @Test
    void secretsPrintedDuringTheBuildAreMaskedInTheUploadedLog() throws Exception {
        submit("b9", app(FakeBackend.LOG_MARKER, "Downloading from https://bot:hunter2@pypi.example.com\nAKIAXWNVK4PQ2JT6ZM3C"));
        awaitFinished("b9");
        assertThat(server.lastLog()).doesNotContain("hunter2").doesNotContain("AKIAXWNVK4PQ2JT6ZM3C").contains("[REDACTED]");
    }

    @Test
    void anUnknownBuildIdMeansTheRunnerRestarted() {
        BuildView view = service.get("never-submitted");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.RUNNER_RESTARTED);
    }

    @Test
    void theBuildDirectoryIsRemovedAfterwards() throws Exception {
        submit("b10", app());
        awaitFinished("b10");
        try (var entries = Files.list(work)) {
            assertThat(entries.toList()).isEmpty();
        }
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `cd runner && mvn -q test -Dtest=BuildServiceTest`
Expected: compilation fails.

- [ ] **Step 4: Implement `BundleFetcher` and `LogUploader`**

`runner/src/main/java/com/agentstore/runner/build/BundleFetcher.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.BuildFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads a bundle from its pre-signed URL, enforcing the size limit and checking sha256 (spec §4 step 3).
 * The URL carries a signature, so it never appears in messages.
 */
public class BundleFetcher {

    public static final long MAX_BUNDLE_BYTES = 100L * 1024 * 1024;

    private final HttpClient http;
    private final long maxBytes;
    private final Duration timeout;

    public BundleFetcher(HttpClient http, long maxBytes, Duration timeout) {
        this.http = http;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    public void fetch(String url, String expectedSha256, Path target) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw invalid("Bundle download failed (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException("Bundle download interrupted");
        } catch (IllegalArgumentException e) {
            throw invalid("Bundle URL is malformed");
        }
        MessageDigest sha = sha256();
        long total = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            if (response.statusCode() != 200) {
                throw invalid("Bundle download failed with HTTP " + response.statusCode());
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw invalid("Bundle is larger than " + (maxBytes / (1024 * 1024)) + " MB");
                }
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw invalid("Bundle download failed (" + e.getClass().getSimpleName() + ")");
        }
        String actual = HexFormat.of().formatHex(sha.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw invalid("Bundle sha256 does not match: expected " + expectedSha256 + ", got " + actual);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BuildFailedException invalid(String reason) {
        return new BuildFailedException(BuildFailedException.INVALID_BUNDLE, reason);
    }
}
```

`runner/src/main/java/com/agentstore/runner/build/LogUploader.java`:
```java
package com.agentstore.runner.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * PUTs the whole build log to its pre-signed URL. Object stores can't append, so every flush rewrites the object.
 * Failures are logged (without the URL) and ignored: a missing log must never fail a build.
 */
public class LogUploader {

    private static final Logger log = LoggerFactory.getLogger(LogUploader.class);

    private final HttpClient http;
    private final Duration timeout;

    public LogUploader(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    public boolean upload(String url, byte[] body) {
        try {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                log.warn("Build log upload returned HTTP {}", response.statusCode());
                return false;
            }
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Build log upload failed: {}", e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
```

- [ ] **Step 5: Implement `BuildService`**

`runner/src/main/java/com/agentstore/runner/build/BuildService.java`:
```java
package com.agentstore.runner.build;

import com.agentstore.runner.backend.AgentRuntime;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.ImageBuild;
import com.agentstore.runner.backend.IsolationBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs builds one at a time (spec §4): download → unpack → secret scan → backend.buildImage. State lives only in
 * memory; after a restart Spring sees RUNNER_RESTARTED and queues the build again. The log is flushed to its
 * pre-signed URL every few seconds and once more before the final status is published.
 */
public class BuildService {

    static final Duration RETENTION = Duration.ofHours(24);

    public enum Status { QUEUED, BUILDING, SUCCEEDED, FAILED }

    public record BuildCommand(String buildId, String runtime, String bundleUrl, String bundleSha256, String entrypoint,
                               String logUploadUrl) {
        /** Pre-signed URLs carry signatures: never print them. */
        @Override
        public String toString() {
            return "BuildCommand[buildId=" + buildId + ", runtime=" + runtime + ", entrypoint=" + entrypoint + "]";
        }
    }

    public record BuildView(String buildId, Status status, String backend, String imageRef, String failureCode,
                            String failureReason) {}

    private static final class Entry {
        final BuildCommand cmd;
        final BuildLogBuffer log = new BuildLogBuffer();
        volatile Status status = Status.QUEUED;
        volatile String imageRef;
        volatile String failureCode;
        volatile String failureReason;
        volatile Instant finishedAt;
        volatile boolean timedOut;

        Entry(BuildCommand cmd) {
            this.cmd = cmd;
        }
    }

    private final IsolationBackend backend;
    private final BundleFetcher fetcher;
    private final BundleUnpacker unpacker;
    private final SecretScanner scanner;
    private final LogUploader logUploader;
    private final Path workDir;
    private final Duration timeout;
    private final Duration flushInterval;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "build-worker"));
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "build-timers");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Entry> builds = new ConcurrentHashMap<>();

    public BuildService(IsolationBackend backend, BundleFetcher fetcher, BundleUnpacker unpacker, SecretScanner scanner,
                        LogUploader logUploader, Path workDir, Duration timeout, Duration flushInterval, Clock clock) {
        this.backend = backend;
        this.fetcher = fetcher;
        this.unpacker = unpacker;
        this.scanner = scanner;
        this.logUploader = logUploader;
        this.workDir = workDir;
        this.timeout = timeout;
        this.flushInterval = flushInterval;
        this.clock = clock;
    }

    /** Idempotent on buildId: a repeat returns the existing build. */
    public synchronized BuildView submit(BuildCommand cmd) {
        Instant cutoff = clock.instant().minus(RETENTION);
        builds.values().removeIf(e -> e.finishedAt != null && e.finishedAt.isBefore(cutoff));
        Entry existing = builds.get(cmd.buildId());
        if (existing != null) {
            return view(existing);
        }
        Entry entry = new Entry(cmd);
        builds.put(cmd.buildId(), entry);
        worker.execute(() -> run(entry));
        return view(entry);
    }

    public BuildView get(String buildId) {
        Entry entry = builds.get(buildId);
        if (entry == null) {
            return new BuildView(buildId, Status.FAILED, backend.name(), null, BuildFailedException.RUNNER_RESTARTED,
                    "The runner has no record of this build (it probably restarted); queue it again");
        }
        return view(entry);
    }

    public void shutdown() {
        worker.shutdownNow();
        timers.shutdownNow();
    }

    private BuildView view(Entry e) {
        return new BuildView(e.cmd.buildId(), e.status, backend.name(), e.imageRef, e.failureCode, e.failureReason);
    }

    private void run(Entry entry) {
        entry.status = Status.BUILDING;
        Thread current = Thread.currentThread();
        ScheduledFuture<?> watchdog = timers.schedule(() -> {
            entry.timedOut = true;
            current.interrupt();
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> flusher = timers.scheduleWithFixedDelay(() -> flush(entry),
                flushInterval.toMillis(), flushInterval.toMillis(), TimeUnit.MILLISECONDS);
        Path dir = null;
        String imageRef = null;
        String code = null;
        String reason = null;
        try {
            dir = Files.createTempDirectory(Files.createDirectories(workDir), "build-");
            imageRef = build(entry, dir);
        } catch (BuildFailedException e) {
            code = e.code();
            reason = e.getMessage();
        } catch (IOException | RuntimeException e) {
            code = BuildFailedException.BUILD_FAILED;
            reason = "Build failed: " + e.getMessage();
        } finally {
            watchdog.cancel(false);
            flusher.cancel(false);
            Thread.interrupted();   // clear a watchdog interrupt that landed as the work finished
        }
        if (imageRef == null && entry.timedOut) {
            code = BuildFailedException.TIMEOUT;
            reason = "The build took longer than " + timeout;
        }
        entry.log.append(imageRef != null ? "Build succeeded: " + imageRef : "Build failed: " + code + ": " + reason);
        flush(entry);
        deleteRecursively(dir);
        entry.imageRef = imageRef;
        entry.failureCode = code;
        entry.failureReason = reason;
        entry.finishedAt = clock.instant();
        entry.status = imageRef != null ? Status.SUCCEEDED : Status.FAILED;   // last, so pollers see a complete result
    }

    private String build(Entry entry, Path dir) throws IOException {
        BuildCommand cmd = entry.cmd;
        AgentRuntime runtime;
        try {
            runtime = AgentRuntime.fromId(cmd.runtime());
        } catch (IllegalArgumentException e) {
            throw new BuildFailedException(BuildFailedException.INVALID_BUNDLE, e.getMessage());
        }
        Path zip = dir.resolve("bundle.zip");
        Path app = Files.createDirectory(dir.resolve("app"));
        entry.log.append("Downloading bundle");
        fetcher.fetch(cmd.bundleUrl(), cmd.bundleSha256(), zip);
        entry.log.append("Unpacking bundle");
        unpacker.unpack(zip, app);
        requireEntrypoint(app, cmd.entrypoint());
        entry.log.append("Scanning for secrets");
        scanner.scan(app, entry.log);
        entry.log.append("Building image (" + runtime.id() + ", entrypoint " + cmd.entrypoint() + ") on " + backend.name());
        return backend.buildImage(new ImageBuild(cmd.buildId(), app, runtime, cmd.entrypoint()), entry.log);
    }

    static void requireEntrypoint(Path app, String entrypoint) {
        Path root = app.toAbsolutePath().normalize();
        Path file = root.resolve(entrypoint).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new BuildFailedException(BuildFailedException.INVALID_BUNDLE,
                    "Entrypoint " + entrypoint + " is not a file in the bundle");
        }
    }

    private void flush(Entry entry) {
        synchronized (entry) {
            logUploader.upload(entry.cmd.logUploadUrl(), entry.log.snapshot());
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
```

- [ ] **Step 6: Run the service tests**

Run: `cd runner && mvn -q test -Dtest=BuildServiceTest`
Expected: PASS.

- [ ] **Step 7: Write the failing controller test**

Add to `runner/src/test/resources/application-test.yml`, under `agentstore.runner`:
```yaml
    build:
      gitleaks-command: [sh, src/test/resources/gitleaks/clean.sh]
      work-dir: ${java.io.tmpdir}/agentstore-builds-test
```

`runner/src/test/java/com/agentstore/runner/api/BuildControllerTest.java`:
```java
package com.agentstore.runner.api;

import com.agentstore.runner.TestBackendConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBackendConfig.class)
class BuildControllerTest {

    static final String SECRET = "test-runner-secret";

    @Autowired MockMvc mvc;

    static String body(String buildId, String sha) {
        return """
                {"buildId":"%s","runtime":"python-3.12","bundleUrl":"http://127.0.0.1:1/bundle.zip",
                 "bundleSha256":"%s","entrypoint":"main.py","logUploadUrl":"http://127.0.0.1:1/log"}
                """.formatted(buildId, sha);
    }

    @Test
    void acceptsABuildIdempotentlyAndReportsItsOutcome() throws Exception {
        String sha = "a".repeat(64);
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ctl-1", sha)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildId").value("ctl-1"))
                .andExpect(jsonPath("$.status").value(anyOf(is("QUEUED"), is("BUILDING"), is("FAILED"))));
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ctl-1", sha)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.buildId").value("ctl-1"));

        // The bundle URL is unreachable, so the build ends as an invalid bundle.
        String state = "QUEUED";
        long until = System.currentTimeMillis() + 10_000;
        String json = "";
        while (!state.equals("FAILED") && System.currentTimeMillis() < until) {
            json = mvc.perform(get("/builds/ctl-1").header("X-Runner-Secret", SECRET)).andReturn().getResponse()
                    .getContentAsString();
            state = JsonPath.read(json, "$.status");
        }
        mvc.perform(get("/builds/ctl-1").header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("INVALID_BUNDLE"))
                .andExpect(jsonPath("$.backend").value("fake"));
    }

    @Test
    void anUnknownBuildIsRunnerRestarted() throws Exception {
        mvc.perform(get("/builds/nope").header("X-Runner-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("RUNNER_RESTARTED"));
    }

    @Test
    void aBadShaOrBuildIdIs400() throws Exception {
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ctl-2", "not-a-sha")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/builds").header("X-Runner-Secret", SECRET).contentType(MediaType.APPLICATION_JSON)
                        .content(body("../../etc", "a".repeat(64))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buildsNeedTheRunnerSecret() throws Exception {
        mvc.perform(get("/builds/ctl-1")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 8: Run it and watch it fail**

Run: `cd runner && mvn -q test -Dtest=BuildControllerTest`
Expected: FAIL (there's no `/builds` endpoint, and the `build` properties aren't bound).

- [ ] **Step 9: Add the build properties and wiring**

In `RunnerProperties`:
- Insert `@Valid @NotNull Build build` between `budget` and `e2b`, and add `", build=" + build` to `toString` after `budget`.
- Add the record below, with the import `jakarta.validation.constraints.NotEmpty`.

```java
    public record Build(@NotEmpty List<String> gitleaksCommand, @NotNull Duration timeout, @NotNull Duration scanTimeout,
                        @NotNull Duration downloadTimeout, @NotNull Duration logFlushInterval, @NotNull Path workDir) {}
```

In `runner/src/main/resources/application.yml`, under `agentstore.runner` (next to `budget`):
```yaml
    build:
      gitleaks-command: [gitleaks]
      timeout: PT15M
      scan-timeout: PT2M
      download-timeout: PT5M
      log-flush-interval: PT5S
      work-dir: ${RUNNER_BUILD_DIR:${java.io.tmpdir}/agentstore-builds}
```

In `RunnerPropertiesTest`, pass a `Build` as the new sixth argument:
```java
                new RunnerProperties.Build(List.of("gitleaks"), Duration.ofMinutes(15), Duration.ofMinutes(2),
                        Duration.ofMinutes(5), Duration.ofSeconds(5), Path.of("builds")),
```

In `RunnerConfig`, add this bean (imports: `com.agentstore.runner.build.*`, `java.net.http.HttpClient`, `java.time.Duration`):
```java
    @Bean(destroyMethod = "shutdown")
    BuildService buildService(IsolationBackend backend, RunnerProperties props, Clock clock) {
        RunnerProperties.Build b = props.build();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new BuildService(backend,
                new BundleFetcher(http, BundleFetcher.MAX_BUNDLE_BYTES, b.downloadTimeout()),
                new BundleUnpacker(),
                new SecretScanner(b.gitleaksCommand(), b.scanTimeout()),
                new LogUploader(http, Duration.ofSeconds(30)),
                b.workDir(), b.timeout(), b.logFlushInterval(), clock);
    }
```

- [ ] **Step 10: Add the controller**

`runner/src/main/java/com/agentstore/runner/api/CreateBuildRequest.java`:
```java
package com.agentstore.runner.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateBuildRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String buildId,
                                 @NotBlank String runtime,
                                 @NotBlank String bundleUrl,
                                 @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String bundleSha256,
                                 @NotBlank String entrypoint,
                                 @NotBlank String logUploadUrl) {
    /** Pre-signed URLs carry signatures: never print them. */
    @Override
    public String toString() {
        return "CreateBuildRequest[buildId=" + buildId + ", runtime=" + runtime + ", entrypoint=" + entrypoint + "]";
    }
}
```

`runner/src/main/java/com/agentstore/runner/api/BuildController.java`:
```java
package com.agentstore.runner.api;

import com.agentstore.runner.build.BuildService;
import com.agentstore.runner.build.BuildService.BuildCommand;
import com.agentstore.runner.build.BuildService.BuildView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/builds")
public class BuildController {

    private final BuildService buildService;

    public BuildController(BuildService buildService) {
        this.buildService = buildService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BuildView submit(@Valid @RequestBody CreateBuildRequest r) {
        return buildService.submit(new BuildCommand(r.buildId(), r.runtime(), r.bundleUrl(), r.bundleSha256(),
                r.entrypoint(), r.logUploadUrl()));
    }

    @GetMapping("/{buildId}")
    public BuildView get(@PathVariable String buildId) {
        return buildService.get(buildId);
    }
}
```

In `ApiErrorHandler.invalid`, change the message `"Invalid sandbox request"` to `"Invalid request"`.

- [ ] **Step 11: Run all runner tests**

Run: `cd runner && mvn -q test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add runner/src runner/src/main/resources/application.yml
git commit -m "feat(runner): add the /builds API with fetch, unpack, secret scan and build"
```

---

### Task 6: Object store — SeaweedFS in docker-compose and Spring's `ObjectStorage`

**Files:**
- Create: `docker-compose.yml` (repo root), `infra/seaweedfs/s3.json`
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/agentstore/platform/storage/ObjectStorage.java`, `StorageException.java`, `StorageStartup.java`
- Create: `backend/src/test/java/com/agentstore/platform/AbstractStorageIntegrationTest.java`
- Test: `backend/src/test/java/com/agentstore/platform/storage/ObjectStorageTest.java`

**Interfaces:**
- Produces:
  - `ObjectStorage.ensureBucket()`
  - `presignPut(String key, Duration ttl) → String`
  - `presignGet(String key, Duration ttl) → String`
  - `size(String key) → Optional<Long>`
  - `open(String key) → InputStream`
  - `put(String key, byte[] bytes)`
  - `deleteQuietly(String key)`
  - `StorageException extends RuntimeException`
  - `AbstractStorageIntegrationTest` (extends `AbstractIntegrationTest`, starts SeaweedFS once per JVM, exposes a `protected ObjectStorage storage`)

- [ ] **Step 1: Add local SeaweedFS**

`infra/seaweedfs/s3.json` (dev-only credentials; production sets its own through env):
```json
{
  "identities": [
    {
      "name": "agentstore",
      "credentials": [
        { "accessKey": "agentstore-dev", "secretKey": "agentstore-dev-secret-change-me" }
      ],
      "actions": ["Admin", "Read", "List", "Tagging", "Write"]
    }
  ]
}
```

`docker-compose.yml`:
```yaml
# Local infrastructure for AgentStore development.
#   docker compose up -d seaweedfs      (or: docker-compose up -d seaweedfs)
# SeaweedFS is the object store (bundles, build logs), used only through its S3 API on :8333 (spec C5).
# Postgres still runs the way it does today (see the backend README).
services:
  seaweedfs:
    image: chrislusf/seaweedfs:4.47
    command: server -s3 -s3.config=/etc/seaweedfs/s3.json -dir=/data -ip.bind=0.0.0.0
    ports:
      - "8333:8333"
    volumes:
      - ./infra/seaweedfs/s3.json:/etc/seaweedfs/s3.json:ro
      - seaweedfs-data:/data

volumes:
  seaweedfs-data:
```

- [ ] **Step 2: Add the AWS SDK**

In `backend/pom.xml`, inside `<dependencyManagement><dependencies>` (next to the Testcontainers BOM):
```xml
      <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>bom</artifactId>
        <version>2.46.7</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
```
Inside `<dependencies>`:
```xml
    <dependency>
      <groupId>software.amazon.awssdk</groupId>
      <artifactId>s3</artifactId>
      <exclusions>
        <exclusion>
          <groupId>software.amazon.awssdk</groupId>
          <artifactId>netty-nio-client</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
```
If `2.46.7` doesn't resolve, use the newest `2.x` on Maven Central. The API used below has been stable across 2.x.

In `backend/src/main/resources/application.yml`, under `agentstore:`:
```yaml
  storage:
    endpoint: ${STORAGE_ENDPOINT:http://localhost:8333}
    region: ${STORAGE_REGION:us-east-1}
    access-key: ${STORAGE_ACCESS_KEY:agentstore-dev}
    secret-key: ${STORAGE_SECRET_KEY:agentstore-dev-secret-change-me}
    bucket: ${STORAGE_BUCKET:agentstore}
```

- [ ] **Step 3: Write the failing test and its base class**

`backend/src/test/java/com/agentstore/platform/AbstractStorageIntegrationTest.java`:
```java
package com.agentstore.platform;

import com.agentstore.platform.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/** Adds a real SeaweedFS (S3 API) to the shared Postgres test setup. Started once per JVM. */
public abstract class AbstractStorageIntegrationTest extends AbstractIntegrationTest {

    static final String ACCESS_KEY = "test-access";
    static final String SECRET_KEY = "test-secret-0123456789";
    static final String S3_CONFIG = """
            {"identities":[{"name":"test","credentials":[{"accessKey":"%s","secretKey":"%s"}],
              "actions":["Admin","Read","List","Tagging","Write"]}]}
            """.formatted(ACCESS_KEY, SECRET_KEY);

    static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
            .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data", "-ip.bind=0.0.0.0")
            .withExposedPorts(8333)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    private static volatile boolean bucketReady;

    static {
        SEAWEEDFS.start();
    }

    @Autowired
    protected ObjectStorage storage;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("agentstore.storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
        registry.add("agentstore.storage.access-key", () -> ACCESS_KEY);
        registry.add("agentstore.storage.secret-key", () -> SECRET_KEY);
        registry.add("agentstore.storage.bucket", () -> "agentstore-test");
    }

    /** SeaweedFS opens its port before the filer is ready, so the first bucket call is retried. */
    @BeforeEach
    void bucketReady() throws InterruptedException {
        if (bucketReady) {
            return;
        }
        long until = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                storage.ensureBucket();
                bucketReady = true;
                return;
            } catch (RuntimeException e) {
                if (System.currentTimeMillis() > until) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }
}
```

`backend/src/test/java/com/agentstore/platform/storage/ObjectStorageTest.java`:
```java
package com.agentstore.platform.storage;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageTest extends AbstractStorageIntegrationTest {

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aPresignedPutIsReadableThroughTheClientAndAPresignedGet() throws Exception {
        String key = "tests/" + UUID.randomUUID() + ".txt";
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(storage.presignPut(key, Duration.ofMinutes(5))))
                .PUT(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);

        assertThat(storage.size(key)).contains(5L);
        try (InputStream in = storage.open(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(storage.presignGet(key, Duration.ofMinutes(5))))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo("hello");
    }

    @Test
    void aPresignedPutCanBeReusedToRewriteTheObject() throws Exception {
        String key = "tests/" + UUID.randomUUID() + ".log";
        String url = storage.presignPut(key, Duration.ofMinutes(5));
        for (String body : new String[] {"one\n", "one\ntwo\n"}) {
            HttpResponse<Void> put = http.send(HttpRequest.newBuilder(URI.create(url))
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(put.statusCode()).isEqualTo(200);
        }
        assertThat(storage.size(key)).contains(8L);
    }

    @Test
    void aTamperedSignatureIsRefused() throws Exception {
        String url = storage.presignGet("tests/whatever.txt", Duration.ofMinutes(5)).replaceAll("X-Amz-Signature=[0-9a-f]+", "X-Amz-Signature=00");
        HttpResponse<Void> get = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(get.statusCode()).isEqualTo(403);
    }

    @Test
    void theSizeOfAMissingObjectIsEmptyAndDeleteRemoves() {
        String key = "tests/" + UUID.randomUUID() + ".bin";
        assertThat(storage.size(key)).isEmpty();
        storage.put(key, new byte[] {1, 2, 3});
        assertThat(storage.size(key)).contains(3L);
        storage.deleteQuietly(key);
        assertThat(storage.size(key)).isEmpty();
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=ObjectStorageTest`
Expected: compilation fails (`ObjectStorage` doesn't exist).

- [ ] **Step 5: Implement the storage classes**

`backend/src/main/java/com/agentstore/platform/storage/StorageException.java`:
```java
package com.agentstore.platform.storage;

/** The object store failed. Messages never contain pre-signed URLs or credentials. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`backend/src/main/java/com/agentstore/platform/storage/ObjectStorage.java`:
```java
package com.agentstore.platform.storage;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * S3-compatible object storage (SeaweedFS in dev and M1; spec C5). Pre-signed URLs let the CLI and the runner move
 * bytes without holding credentials. Any S3-compatible store works through config alone.
 */
@Component
public class ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public ObjectStorage(@Value("${agentstore.storage.endpoint}") String endpoint,
                         @Value("${agentstore.storage.region:us-east-1}") String region,
                         @Value("${agentstore.storage.access-key}") String accessKey,
                         @Value("${agentstore.storage.secret-key}") String secretKey,
                         @Value("${agentstore.storage.bucket}") String bucket) {
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        URI uri = URI.create(endpoint);
        this.s3 = S3Client.builder()
                .endpointOverride(uri)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(true)
                // Non-AWS stores don't all accept the SDK's default CRC32 trailers.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(uri)
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.bucket = bucket;
    }

    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            create();
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw new StorageException("Could not check bucket " + bucket, e);
            }
            create();
        } catch (SdkException e) {
            throw new StorageException("Could not check bucket " + bucket, e);
        }
    }

    private void create() {
        try {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (SdkException e) {
            throw new StorageException("Could not create bucket " + bucket, e);
        }
    }

    public String presignPut(String key, Duration ttl) {
        return presigner.presignPutObject(r -> r.signatureDuration(ttl)
                .putObjectRequest(p -> p.bucket(bucket).key(key))).url().toString();
    }

    public String presignGet(String key, Duration ttl) {
        return presigner.presignGetObject(r -> r.signatureDuration(ttl)
                .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toString();
    }

    public Optional<Long> size(String key) {
        try {
            return Optional.of(s3.headObject(h -> h.bucket(bucket).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageException("Could not stat " + key, e);
        } catch (SdkException e) {
            throw new StorageException("Could not stat " + key, e);
        }
    }

    public InputStream open(String key) {
        try {
            return s3.getObject(g -> g.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageException("Could not read " + key, e);
        }
    }

    public void put(String key, byte[] bytes) {
        try {
            s3.putObject(p -> p.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new StorageException("Could not write " + key, e);
        }
    }

    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(d -> d.bucket(bucket).key(key));
        } catch (SdkException ignored) {
            // an orphaned object costs a few bytes; never fail the caller over it
        }
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }
}
```

`backend/src/main/java/com/agentstore/platform/storage/StorageStartup.java`:
```java
package com.agentstore.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Creates the bucket at startup. Only warns when the store is down, so the rest of the app (and tests) still start. */
@Component
public class StorageStartup {

    private static final Logger log = LoggerFactory.getLogger(StorageStartup.class);

    private final ObjectStorage storage;

    public StorageStartup(ObjectStorage storage) {
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            storage.ensureBucket();
        } catch (RuntimeException e) {
            log.warn("Object store not ready at startup ({}); bundle uploads will fail until it is",
                    e.getClass().getSimpleName());
        }
    }
}
```

- [ ] **Step 6: Run the storage test, then the whole backend suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=ObjectStorageTest`
Expected: PASS.

**If `aPresignedPut…` or `aPresignedPutCanBeReused…` fails with 403,** SeaweedFS is rejecting the pre-signed signature. Stop and report it: the whole design relies on this. Don't loosen the bucket to anonymous writes.

Then run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS. Other test classes start without SeaweedFS; `StorageStartup` only warns.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml infra/seaweedfs/s3.json backend/pom.xml backend/src/main/resources/application.yml \
  backend/src/main/java/com/agentstore/platform/storage \
  backend/src/test/java/com/agentstore/platform/AbstractStorageIntegrationTest.java \
  backend/src/test/java/com/agentstore/platform/storage
git commit -m "feat(storage): add SeaweedFS and an S3 ObjectStorage with pre-signed URLs"
```

---

### Task 7: Schema, entities, manifest `runtime`/`entrypoint`, and `PENDING_BUILD`

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__bundles_and_builds.sql`
- Create: `backend/src/main/java/com/agentstore/platform/build/AgentBundle.java`, `BundleStatus.java`, `AgentBundleRepository.java`, `TemplateBuild.java`, `BuildStatus.java`, `TemplateBuildRepository.java`, `BuildFailureCodes.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java`, `AgentVersionRepository.java`
- Create: `backend/src/main/java/com/agentstore/platform/manifest/AgentRuntime.java`, `BundleManifestRules.java`
- Modify: `backend/src/main/java/com/agentstore/platform/manifest/Manifest.java`, `ManifestParser.java`, `backend/src/main/resources/manifest/manifest-schema.json`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunStatus.java`
- Create: `backend/src/test/resources/manifest/valid-bundle-python.json`
- Test: `backend/src/test/java/com/agentstore/platform/manifest/BundleManifestRulesTest.java`, `AgentRuntimeTest.java`, `BundleManifestParsingTest.java`, `backend/src/test/java/com/agentstore/platform/build/BuildSchemaTest.java`

**Interfaces:**
- Produces:
  - Entities:
    - `AgentBundle(UUID id, Agent agent, UUID uploadedByUserId, String storageKey, String sha256, long sizeBytes, BundleStatus status)` (id assigned by the caller).
    - `TemplateBuild(AgentVersion version, AgentBundle bundle, String backend, String runtime, String entrypoint)`: status `QUEUED`, `attempts` 0.
  - Getters and setters via Lombok. `AgentVersion` gains `bundle` and `buildStatus`.
  - Repository methods (exact names):
    - `AgentBundleRepository.findByIdForUpdate(UUID)`
    - `TemplateBuildRepository`: `findByIdForUpdate`, `findFirstByStatusOrderByQueuedAtAsc`, `findByStatusOrderByQueuedAtAsc`, `existsByStatus`, `findByAgentVersionIdAndStatus`, `findFirstByAgentVersionIdOrderByQueuedAtDesc`, `findFirstByBundleIdOrderByQueuedAtDesc`, `findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc`, `existsByAgentVersionIdAndBackendAndStatusIn`
    - `AgentVersionRepository`: `findByAgentIdAndVersionNumber(UUID, String)`, `findByBundleId(UUID)`
  - `BuildFailureCodes` string constants (the nine codes).
  - `Manifest` gains `String runtime, String entrypoint` as its **last two** components (null when absent).
  - `enum AgentRuntime { PYTHON_3_12, NODE_22 }` with `id()`, `static Optional<AgentRuntime> fromId(String)`, `acceptsEntrypoint(String)`, `runCommand(String entrypoint) → List<String>`.
  - `BundleManifestRules.check(Manifest) → List<String>` errors.
  - `RunStatus.PENDING_BUILD`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/resources/manifest/valid-bundle-python.json`:
```json
{
  "name": "Bank Reconciliation Agent",
  "version": "1.0.0",
  "runtime": "python-3.12",
  "entrypoint": "agent/main.py",
  "scopes": [
    { "type": "READ", "resource": "bank_statement", "description": "Read uploaded bank statement rows" }
  ],
  "network": { "allowlist": [] },
  "costEnvelope": { "currency": "INR", "maxPerRun": 500.00, "pricingUnit": "METERED", "unitPrice": 2.00, "unitLabel": "reconciled_transaction" },
  "approvalGates": [],
  "dataClasses": ["BANK_STATEMENT"]
}
```

`backend/src/test/java/com/agentstore/platform/manifest/AgentRuntimeTest.java`:
```java
package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeTest {

    @Test
    void runCommandsStartFromTheAppRoot() {
        assertThat(AgentRuntime.PYTHON_3_12.runCommand("agent/main.py"))
                .isEqualTo(List.of("sh", "-c", "cd /workspace/app && exec python 'agent/main.py'"));
        assertThat(AgentRuntime.NODE_22.runCommand("index.mjs"))
                .isEqualTo(List.of("sh", "-c", "cd /workspace/app && exec node 'index.mjs'"));
    }

    @Test
    void idsAndExtensions() {
        assertThat(AgentRuntime.fromId("node-22")).contains(AgentRuntime.NODE_22);
        assertThat(AgentRuntime.fromId("ruby-3")).isEmpty();
        assertThat(AgentRuntime.fromId(null)).isEmpty();
        assertThat(AgentRuntime.NODE_22.acceptsEntrypoint("a.cjs")).isTrue();
        assertThat(AgentRuntime.PYTHON_3_12.acceptsEntrypoint("a.js")).isFalse();
    }
}
```

`backend/src/test/java/com/agentstore/platform/manifest/BundleManifestRulesTest.java`:
```java
package com.agentstore.platform.manifest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BundleManifestRulesTest {

    static Manifest manifest(String runtime, String entrypoint) {
        return new Manifest("n", "1.0.0", List.of(), List.of(), null, List.of(), List.of(), runtime, entrypoint);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", delimiter = '|', textBlock = """
            python-3.12 | agent/main.py | ''
            node-22     | src/index.mjs | ''
            NULL        | main.py       | runtime is required
            ruby-3      | main.rb       | runtime must be python-3.12 or node-22
            python-3.12 | NULL          | entrypoint is required
            python-3.12 | ../main.py    | entrypoint must be a relative path
            python-3.12 | /abs/main.py  | entrypoint must be a relative path
            python-3.12 | a//main.py    | entrypoint must be a relative path
            python-3.12 | main.js       | does not match runtime python-3.12
            node-22     | main.py       | does not match runtime node-22
            """)
    void checksRuntimeAndEntrypoint(String runtime, String entrypoint, String expectedError) {
        List<String> errors = BundleManifestRules.check(manifest(runtime, entrypoint));
        if (expectedError.isEmpty()) {
            assertThat(errors).isEmpty();
        } else {
            assertThat(errors).anyMatch(e -> e.contains(expectedError));
        }
    }
}
```

`backend/src/test/java/com/agentstore/platform/manifest/BundleManifestParsingTest.java`:
```java
package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleManifestParsingTest {

    static String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    @Test
    void parsesRuntimeAndEntrypointWhenPresent() throws Exception {
        Manifest m = new ManifestParser().parse(read("valid-bundle-python.json"));
        assertThat(m.runtime()).isEqualTo("python-3.12");
        assertThat(m.entrypoint()).isEqualTo("agent/main.py");
    }

    @Test
    void leavesThemNullWhenAbsent() throws Exception {
        Manifest m = new ManifestParser().parse(read("valid-tier-a.json"));
        assertThat(m.runtime()).isNull();
        assertThat(m.entrypoint()).isNull();
    }

    @Test
    void theSchemaAcceptsTheBundleManifestAndRejectsBadValues() throws Exception {
        ManifestValidator validator = new ManifestValidator();
        String good = read("valid-bundle-python.json");
        assertThat(validator.validate(good).valid()).isTrue();
        assertThat(validator.validate(good.replace("python-3.12", "ruby-3")).valid()).isFalse();
        assertThat(validator.validate(good.replace("agent/main.py", "/agent/main.py")).valid()).isFalse();
    }
}
```

`backend/src/test/java/com/agentstore/platform/build/BuildSchemaTest.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSchemaTest extends AbstractIntegrationTest {

    @Autowired OrgRepository orgs;
    @Autowired UserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired AgentBundleRepository bundles;
    @Autowired TemplateBuildRepository builds;

    @Test
    void bundlesBuildsAndVersionsWithoutAnImageRoundTrip() {
        Org org = orgs.save(new Org(null, "SchemaCo", OrgType.DEVELOPER));
        User user = users.save(new User(null, "schema-" + UUID.randomUUID() + "@example.com", null, "Dev"));
        Agent agent = agents.save(new Agent(null, org, "Recon", "finance", "d"));
        UUID bundleId = UUID.randomUUID();
        AgentBundle bundle = bundles.save(new AgentBundle(bundleId, agent, user.getId(),
                "bundles/" + agent.getId() + "/" + bundleId + ".zip", "a".repeat(64), 1234, BundleStatus.UPLOADED));

        AgentVersion version = new AgentVersion(null, agent, "1.0.0", "{}", null, VersionStatus.DRAFT, null, null);
        version.setBundle(bundle);
        version.setBuildStatus(BuildStatus.QUEUED);
        version = versions.save(version);

        TemplateBuild build = builds.save(new TemplateBuild(version, bundle, "e2b", "python-3.12", "agent/main.py"));

        TemplateBuild loaded = builds.findById(build.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BuildStatus.QUEUED);
        assertThat(loaded.getAttempts()).isZero();
        assertThat(loaded.getQueuedAt()).isNotNull();
        assertThat(loaded.getBundle().getId()).isEqualTo(bundleId);
        assertThat(versions.findById(version.getId()).orElseThrow().getImageRef()).isNull();
        assertThat(versions.findByBundleId(bundleId)).isPresent();
        assertThat(versions.findByAgentIdAndVersionNumber(agent.getId(), "1.0.0")).isPresent();
        assertThat(builds.existsByAgentVersionIdAndBackendAndStatusIn(version.getId(), "e2b", List.of(BuildStatus.QUEUED)))
                .isTrue();
    }

    @Test
    void pendingBuildIsNotTerminal() {
        assertThat(RunStatus.PENDING_BUILD.isTerminal()).isFalse();
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='AgentRuntimeTest,BundleManifestRulesTest,BundleManifestParsingTest,BuildSchemaTest'`
Expected: compilation fails.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V23__bundles_and_builds.sql`:
```sql
-- WS5b build pipeline (spec §4.1, §4.2). sha256 is VARCHAR(64), not CHAR(64): Hibernate's schema validation
-- rejects bpchar for a String column.
CREATE TABLE agent_bundle (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agent(id),
    uploaded_by_user_id UUID NOT NULL REFERENCES app_user(id),
    storage_key VARCHAR(512) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE template_build (
    id UUID PRIMARY KEY,
    agent_version_id UUID NOT NULL REFERENCES agent_version(id),
    bundle_id UUID NOT NULL REFERENCES agent_bundle(id),
    backend VARCHAR(20) NOT NULL,
    runtime VARCHAR(30) NOT NULL,
    entrypoint VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    image_ref VARCHAR(255),
    failure_code VARCHAR(30),
    failure_reason TEXT,
    log_storage_key VARCHAR(512),
    attempts INT NOT NULL DEFAULT 0,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX template_build_status_idx ON template_build (status, queued_at);
CREATE INDEX template_build_version_idx ON template_build (agent_version_id);
CREATE INDEX template_build_bundle_idx ON template_build (bundle_id);

ALTER TABLE agent_version ADD COLUMN bundle_id UUID REFERENCES agent_bundle(id);
ALTER TABLE agent_version ADD COLUMN build_status VARCHAR(20);
ALTER TABLE agent_version ALTER COLUMN image_ref DROP NOT NULL;
```

- [ ] **Step 4: Add the entities and repositories**

`backend/src/main/java/com/agentstore/platform/build/BundleStatus.java`:
```java
package com.agentstore.platform.build;

public enum BundleStatus { UPLOADING, UPLOADED, REJECTED }
```

`BuildStatus.java`:
```java
package com.agentstore.platform.build;

public enum BuildStatus { QUEUED, BUILDING, SUCCEEDED, FAILED }
```

`BuildFailureCodes.java`:
```java
package com.agentstore.platform.build;

/** {@code template_build.failure_code} values (spec §4.1 and §4.2). The runner uses the same strings. */
public final class BuildFailureCodes {
    public static final String SECRET_FOUND = "SECRET_FOUND";
    public static final String DEP_VULN = "DEP_VULN";
    public static final String INSTALL_FAILED = "INSTALL_FAILED";
    public static final String SMOKE_FAILED = "SMOKE_FAILED";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String RUNNER_RESTARTED = "RUNNER_RESTARTED";
    public static final String INVALID_BUNDLE = "INVALID_BUNDLE";
    public static final String BUILD_FAILED = "BUILD_FAILED";
    public static final String SUPERSEDED = "SUPERSEDED";

    private BuildFailureCodes() {}
}
```

`AgentBundle.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.Agent;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** An uploaded code bundle. The id is chosen before insert because it is part of the object-store key. */
@Entity
@Table(name = "agent_bundle")
@Getter
@Setter
@NoArgsConstructor
public class AgentBundle {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    private UUID uploadedByUserId;
    private String storageKey;
    private String sha256;
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    private BundleStatus status;

    private Instant createdAt;

    public AgentBundle(UUID id, Agent agent, UUID uploadedByUserId, String storageKey, String sha256, long sizeBytes,
                       BundleStatus status) {
        this.id = id;
        this.agent = agent;
        this.uploadedByUserId = uploadedByUserId;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

`AgentBundleRepository.java`:
```java
package com.agentstore.platform.build;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AgentBundleRepository extends JpaRepository<AgentBundle, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AgentBundle b where b.id = :id")
    Optional<AgentBundle> findByIdForUpdate(@Param("id") UUID id);
}
```

`TemplateBuild.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One attempt to turn a bundle into an image on one backend (spec §4.1). */
@Entity
@Table(name = "template_build")
@Getter
@Setter
@NoArgsConstructor
public class TemplateBuild {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bundle_id")
    private AgentBundle bundle;

    private String backend;
    private String runtime;
    private String entrypoint;

    @Enumerated(EnumType.STRING)
    private BuildStatus status;

    private String imageRef;
    private String failureCode;
    private String failureReason;
    private String logStorageKey;
    private int attempts;
    private Instant queuedAt;
    private Instant startedAt;
    private Instant finishedAt;

    public TemplateBuild(AgentVersion agentVersion, AgentBundle bundle, String backend, String runtime, String entrypoint) {
        this.agentVersion = agentVersion;
        this.bundle = bundle;
        this.backend = backend;
        this.runtime = runtime;
        this.entrypoint = entrypoint;
        this.status = BuildStatus.QUEUED;
    }

    @PrePersist
    void onCreate() {
        if (queuedAt == null) queuedAt = Instant.now();
    }
}
```

`TemplateBuildRepository.java`:
```java
package com.agentstore.platform.build;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateBuildRepository extends JpaRepository<TemplateBuild, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from TemplateBuild b where b.id = :id")
    Optional<TemplateBuild> findByIdForUpdate(@Param("id") UUID id);

    Optional<TemplateBuild> findFirstByStatusOrderByQueuedAtAsc(BuildStatus status);

    List<TemplateBuild> findByStatusOrderByQueuedAtAsc(BuildStatus status);

    boolean existsByStatus(BuildStatus status);

    List<TemplateBuild> findByAgentVersionIdAndStatus(UUID agentVersionId, BuildStatus status);

    Optional<TemplateBuild> findFirstByAgentVersionIdOrderByQueuedAtDesc(UUID agentVersionId);

    Optional<TemplateBuild> findFirstByBundleIdOrderByQueuedAtDesc(UUID bundleId);

    Optional<TemplateBuild> findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(UUID agentVersionId, BuildStatus status);

    boolean existsByAgentVersionIdAndBackendAndStatusIn(UUID agentVersionId, String backend, Collection<BuildStatus> statuses);
}
```

In `catalog/AgentVersion.java`, add these fields (after `imageRef`) with the imports `com.agentstore.platform.build.AgentBundle` and `com.agentstore.platform.build.BuildStatus`:
```java
    /** The bundle the current image is built from; null for versions created with a hand-supplied imageRef. */
    @ManyToOne
    @JoinColumn(name = "bundle_id")
    private AgentBundle bundle;

    @Enumerated(EnumType.STRING)
    private BuildStatus buildStatus;
```

In `catalog/AgentVersionRepository.java`, add:
```java
    Optional<AgentVersion> findByAgentIdAndVersionNumber(UUID agentId, String versionNumber);

    Optional<AgentVersion> findByBundleId(UUID bundleId);
```

- [ ] **Step 5: Add `runtime` and `entrypoint` to the manifest**

`Manifest.java` (replace):
```java
package com.agentstore.platform.manifest;

import java.util.List;

/** {@code runtime} and {@code entrypoint} are required for bundles (BundleManifestRules) and null otherwise. */
public record Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                        CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                        List<String> dataClasses, String runtime, String entrypoint) {
}
```

In `ManifestParser.parse`, replace the final `return` with:
```java
        return new Manifest(root.get("name").asText(), root.get("version").asText(), scopes, allowlist,
                costEnvelope, gates, dataClasses, textOrNull(root, "runtime"), textOrNull(root, "entrypoint"));
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
```
The closing brace of `parse` becomes the closing brace of `textOrNull`, so check that the braces still balance.

In `manifest-schema.json`, add to `properties` (next to `"version"`):
```json
    "runtime": { "type": "string", "enum": ["python-3.12", "node-22"] },
    "entrypoint": { "type": "string", "pattern": "^[A-Za-z0-9_][A-Za-z0-9_./-]*$" },
```

`backend/src/main/java/com/agentstore/platform/manifest/AgentRuntime.java`:
```java
package com.agentstore.platform.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Runtimes a bundle can declare. Must match the runner's AgentRuntime ids (spec §4.2). */
public enum AgentRuntime {
    PYTHON_3_12("python-3.12", "python", Set.of(".py")),
    NODE_22("node-22", "node", Set.of(".js", ".mjs", ".cjs"));

    private final String id;
    private final String interpreter;
    private final Set<String> extensions;

    AgentRuntime(String id, String interpreter, Set<String> extensions) {
        this.id = id;
        this.interpreter = interpreter;
        this.extensions = extensions;
    }

    public String id() { return id; }

    public static Optional<AgentRuntime> fromId(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
    }

    public boolean acceptsEntrypoint(String entrypoint) {
        return extensions.stream().anyMatch(entrypoint::endsWith);
    }

    /**
     * The sandbox command: run from the app root, as the build's smoke test did. The entrypoint has already passed
     * BundleManifestRules (no quotes or spaces), so single-quoting it is safe.
     */
    public List<String> runCommand(String entrypoint) {
        return List.of("sh", "-c", "cd /workspace/app && exec " + interpreter + " '" + entrypoint + "'");
    }
}
```

`BundleManifestRules.java`:
```java
package com.agentstore.platform.manifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extra checks for a manifest that arrives inside a bundle. There, runtime and entrypoint are required; they stay
 * optional in the JSON schema because the rest of manifest v2 is WS1's.
 */
public final class BundleManifestRules {

    static final Pattern ENTRYPOINT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]*");

    private BundleManifestRules() {}

    public static List<String> check(Manifest manifest) {
        List<String> errors = new ArrayList<>();
        Optional<AgentRuntime> runtime = AgentRuntime.fromId(manifest.runtime());
        if (manifest.runtime() == null) {
            errors.add("runtime is required (python-3.12 or node-22)");
        } else if (runtime.isEmpty()) {
            errors.add("runtime must be python-3.12 or node-22");
        }
        String entrypoint = manifest.entrypoint();
        if (entrypoint == null || entrypoint.isBlank()) {
            errors.add("entrypoint is required");
        } else if (!ENTRYPOINT.matcher(entrypoint).matches() || entrypoint.contains("//")
                || Arrays.asList(entrypoint.split("/")).contains("..")) {
            errors.add("entrypoint must be a relative path inside the bundle (letters, digits, '_', '-', '.', '/')");
        } else if (runtime.isPresent() && !runtime.get().acceptsEntrypoint(entrypoint)) {
            errors.add("entrypoint " + entrypoint + " does not match runtime " + manifest.runtime());
        }
        return errors;
    }
}
```

- [ ] **Step 6: Add `PENDING_BUILD`**

`RunStatus.java` (replace):
```java
package com.agentstore.platform.execution;

public enum RunStatus {
    PENDING, PENDING_BUILD, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED;

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT, EXPIRED -> true;
            case PENDING, PENDING_BUILD, RUNNING, AWAITING_APPROVAL -> false;
        };
    }
}
```

- [ ] **Step 7: Run the new tests, then the whole suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS. Any `Manifest` construction elsewhere fails to compile until it passes the two new arguments; add `null, null` there.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V23__bundles_and_builds.sql \
  backend/src/main/java/com/agentstore/platform/build backend/src/main/java/com/agentstore/platform/catalog \
  backend/src/main/java/com/agentstore/platform/manifest backend/src/main/resources/manifest/manifest-schema.json \
  backend/src/main/java/com/agentstore/platform/execution/RunStatus.java backend/src/test
git commit -m "feat(build): add bundle and build tables, manifest runtime/entrypoint, and PENDING_BUILD"
```

---

### Task 8: Bundle upload and finalize API

**How it flows:**
1. `POST /api/v1/agents/{agentId}/bundles/uploads {sha256, sizeBytes}` returns `201 {bundleId, uploadUrl, expiresAt}`. A declared size over 100 MB gets 413.
2. The client PUTs the zip to `uploadUrl`.
3. `POST /api/v1/agents/{agentId}/bundles/{bundleId}/finalize {sha256}` returns `200 {bundleId, versionId, versionNumber, buildId, buildStatus}`. Along the way it:
   - checks that the sha256 matches the declared one;
   - checks that the stored size equals the declared size and is ≤ 100 MB;
   - re-hashes the stored bytes;
   - reads `manifest.json` and runs the schema, `BundleManifestRules`, and "the entrypoint file is in the zip";
   - checks the version isn't already submitted;
   - creates or updates the DRAFT version and queues a build (older queued builds of that version become `SUPERSEDED`).
4. **Failure responses:**
   - Any rejection returns `422 {code: "INVALID_BUNDLE", message}`, marks the bundle `REJECTED` and deletes the object.
   - A finalize before the upload arrived returns `409`, and the bundle stays `UPLOADING`.
   - Finalizing again after success returns the same answer.

**Files:**
- Create in `backend/src/main/java/com/agentstore/platform/build/`: `BundleInspector.java`, `BundleRejectedException.java`, `BundleService.java`, `BundleController.java`, `BuildApiErrors.java`, `CreateBundleUploadRequest.java`, `BundleUploadResponse.java`, `FinalizeBundleRequest.java`, `FinalizeBundleResponse.java`
- Modify: `backend/src/main/resources/application.yml` (add `agentstore.runner.backend`)
- Create: `backend/src/test/java/com/agentstore/platform/build/TestBundles.java`
- Test: `backend/src/test/java/com/agentstore/platform/build/BundleInspectorTest.java`, `BundleControllerTest.java`

**Interfaces:**
- Consumes: Task 6 (`ObjectStorage`, `AbstractStorageIntegrationTest`), Task 7 (entities, repositories, `BundleManifestRules`, `Manifest.runtime()/entrypoint()`).
- Produces:
  - `BundleService.MAX_BUNDLE_BYTES`.
  - `BundleInspector.inspect(InputStream) → Inspection(String sha256, String manifestJson, Set<String> files)`.
  - `FinalizeBundleResponse(UUID bundleId, UUID versionId, String versionNumber, UUID buildId, BuildStatus buildStatus)`.
  - Every queued build gets `logStorageKey = "builds/" + buildId + ".log"`.
  - Test helper `TestBundles.python(String version)`, `TestBundles.zip(Map<String,String>)`, `TestBundles.sha256(byte[])`.

- [ ] **Step 1: Add the configured backend name**

In `backend/src/main/resources/application.yml`, under `agentstore.runner`:
```yaml
    backend: ${RUNNER_BACKEND:e2b}      # recorded on each build; a mismatch at run time triggers a rebuild
    build-read-timeout: PT30S
```
And under `agentstore:`:
```yaml
  builds:
    poll-interval: PT5S
```

- [ ] **Step 2: Add the test helper**

`backend/src/test/java/com/agentstore/platform/build/TestBundles.java`:
```java
package com.agentstore.platform.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestBundles {

    private TestBundles() {}

    public static String manifest(String version) {
        try {
            return Files.readString(Path.of("src/test/resources/manifest/valid-bundle-python.json"))
                    .replace("\"version\": \"1.0.0\"", "\"version\": \"" + version + "\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A valid python bundle: manifest (runtime python-3.12, entrypoint agent/main.py), code and requirements. */
    public static byte[] python(String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest(version));
        files.put("agent/main.py", "if __name__ == '__main__':\n    print('hi')\n");
        files.put("requirements.txt", "");
        return zip(files);
    }

    public static byte[] zip(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Write the failing inspector test**

`backend/src/test/java/com/agentstore/platform/build/BundleInspectorTest.java`:
```java
package com.agentstore.platform.build;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleInspectorTest {

    final BundleInspector inspector = new BundleInspector();

    @Test
    void hashesTheWholeFileAndFindsTheManifestAndFiles() {
        byte[] zip = TestBundles.python("1.0.0");
        var inspection = inspector.inspect(new ByteArrayInputStream(zip));
        assertThat(inspection.sha256()).isEqualTo(TestBundles.sha256(zip));
        assertThat(inspection.manifestJson()).contains("\"runtime\": \"python-3.12\"");
        assertThat(inspection.files()).contains("agent/main.py", "manifest.json", "requirements.txt");
    }

    @Test
    void normalizesDotSlashNames() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("./manifest.json", "{}");
        files.put("./agent/main.py", "x");
        var inspection = inspector.inspect(new ByteArrayInputStream(TestBundles.zip(files)));
        assertThat(inspection.files()).contains("agent/main.py");
        assertThat(inspection.manifestJson()).isEqualTo("{}");
    }

    @Test
    void aMissingManifestIsRejected() {
        byte[] zip = TestBundles.zip(Map.of("agent/main.py", "x"));
        assertThatThrownBy(() -> inspector.inspect(new ByteArrayInputStream(zip)))
                .isInstanceOf(BundleRejectedException.class).hasMessageContaining("manifest.json");
    }

    @Test
    void somethingThatIsNotAZipIsRejected() {
        assertThatThrownBy(() -> inspector.inspect(new ByteArrayInputStream("nope".getBytes())))
                .isInstanceOf(BundleRejectedException.class).hasMessageContaining("not a zip");
    }

    @Test
    void anOversizedManifestIsRejected() {
        byte[] zip = TestBundles.zip(Map.of("manifest.json", "x".repeat(BundleInspector.MAX_MANIFEST_BYTES + 1)));
        assertThatThrownBy(() -> inspector.inspect(new ByteArrayInputStream(zip)))
                .isInstanceOf(BundleRejectedException.class).hasMessageContaining("too large");
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `cd backend && mvn -q test -Dtest=BundleInspectorTest`
Expected: compilation fails.

- [ ] **Step 5: Implement the inspector**

`BundleRejectedException.java`:
```java
package com.agentstore.platform.build;

/** The bundle can never become a build. The message is shown to the developer. */
public class BundleRejectedException extends RuntimeException {
    public BundleRejectedException(String message) {
        super(message);
    }
}
```

`BundleInspector.java`:
```java
package com.agentstore.platform.build;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads a bundle zip once, as a stream: the sha256 of the raw bytes, manifest.json at the root, and the set of file
 * paths. Nothing is written to disk. Unpacking with traversal checks happens in the runner.
 */
@Component
public class BundleInspector {

    public static final int MAX_MANIFEST_BYTES = 256 * 1024;
    public static final int MAX_ENTRIES = 20_000;

    public record Inspection(String sha256, String manifestJson, Set<String> files) {}

    public Inspection inspect(InputStream raw) {
        MessageDigest sha = sha256();
        DigestInputStream digest = new DigestInputStream(raw, sha);
        String manifest = null;
        Set<String> files = new HashSet<>();
        int entries = 0;
        try {
            ZipInputStream zip = new ZipInputStream(digest);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new BundleRejectedException("Bundle has more than " + MAX_ENTRIES + " entries");
                }
                String name = normalize(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                files.add(name);
                if (name.equals("manifest.json")) {
                    manifest = readCapped(zip);
                }
            }
            digest.transferTo(OutputStream.nullOutputStream());   // hash the central directory too
        } catch (ZipException e) {
            throw new BundleRejectedException("Bundle is not a zip: " + e.getMessage());
        } catch (IOException e) {
            throw new BundleRejectedException("Bundle could not be read: " + e.getClass().getSimpleName());
        }
        if (files.isEmpty()) {
            throw new BundleRejectedException("Bundle is empty or not a zip");
        }
        if (manifest == null) {
            throw new BundleRejectedException("manifest.json is missing from the root of the bundle");
        }
        return new Inspection(HexFormat.of().formatHex(sha.digest()), manifest, files);
    }

    private static String normalize(String name) {
        String n = name;
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        return n;
    }

    private static String readCapped(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zip.read(buffer)) > 0) {
            if (out.size() + n > MAX_MANIFEST_BYTES) {
                throw new BundleRejectedException("manifest.json is too large (limit 256 KiB)");
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 6: Run the inspector test**

Run: `cd backend && mvn -q test -Dtest=BundleInspectorTest`
Expected: PASS.

- [ ] **Step 7: Write the failing API test**

`backend/src/test/java/com/agentstore/platform/build/BundleControllerTest.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BundleControllerTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired AgentBundleRepository bundles;
    @Autowired TemplateBuildRepository builds;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    record Dev(String token, Agent agent) {}

    Dev developer() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        Agent agent = agents.save(new Agent(null, org, "Recon", "finance", "d"));
        return new Dev(identity.tokenFor(m), agent);
    }

    ResponseEntity<String> post(Dev dev, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dev.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    JsonNode read(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody());
    }

    JsonNode createUpload(Dev dev, byte[] zip) throws Exception {
        var response = post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/uploads",
                Map.of("sha256", TestBundles.sha256(zip), "sizeBytes", zip.length));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response);
    }

    void upload(String url, byte[] zip) throws Exception {
        HttpResponse<Void> put = http.send(HttpRequest.newBuilder(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
    }

    ResponseEntity<String> finalizeBundle(Dev dev, String bundleId, String sha) {
        return post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/" + bundleId + "/finalize", Map.of("sha256", sha));
    }

    ResponseEntity<String> push(Dev dev, byte[] zip) throws Exception {
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), zip);
        return finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip));
    }

    @Test
    void aGoodBundleCreatesADraftVersionAndQueuesABuild() throws Exception {
        Dev dev = developer();
        var response = push(dev, TestBundles.python("1.0.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        assertThat(body.get("versionNumber").asText()).isEqualTo("1.0.0");
        assertThat(body.get("buildStatus").asText()).isEqualTo("QUEUED");

        AgentVersion version = versions.findById(UUID.fromString(body.get("versionId").asText())).orElseThrow();
        assertThat(version.getStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(version.getBuildStatus()).isEqualTo(BuildStatus.QUEUED);
        assertThat(version.getImageRef()).isNull();
        assertThat(version.getBundle().getId().toString()).isEqualTo(body.get("bundleId").asText());

        TemplateBuild build = builds.findById(UUID.fromString(body.get("buildId").asText())).orElseThrow();
        assertThat(build.getBackend()).isEqualTo("e2b");
        assertThat(build.getRuntime()).isEqualTo("python-3.12");
        assertThat(build.getEntrypoint()).isEqualTo("agent/main.py");
        assertThat(build.getLogStorageKey()).isEqualTo("builds/" + build.getId() + ".log");
        assertThat(bundles.findById(version.getBundle().getId()).orElseThrow().getStatus()).isEqualTo(BundleStatus.UPLOADED);
    }

    @Test
    void finalizingTwiceReturnsTheSameBuild() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), zip);
        JsonNode first = read(finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip)));
        JsonNode second = read(finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip)));
        assertThat(second.get("buildId")).isEqualTo(first.get("buildId"));
        assertThat(second.get("versionId")).isEqualTo(first.get("versionId"));
    }

    @Test
    void finalizingBeforeTheUploadArrivesIsAConflictAndKeepsTheBundle() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(dev, zip);
        var response = finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bundles.findById(UUID.fromString(upload.get("bundleId").asText())).orElseThrow().getStatus())
                .isEqualTo(BundleStatus.UPLOADING);
    }

    @Test
    void bytesThatDoNotMatchTheDeclaredShaAreRejectedAndDeleted() throws Exception {
        Dev dev = developer();
        byte[] zip = TestBundles.python("1.0.0");
        byte[] other = TestBundles.python("9.9.9");
        JsonNode upload = createUpload(dev, zip);
        upload(upload.get("uploadUrl").asText(), other);   // same length, different bytes
        var response = finalizeBundle(dev, upload.get("bundleId").asText(), TestBundles.sha256(zip));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(response).get("code").asText()).isEqualTo("INVALID_BUNDLE");
        AgentBundle bundle = bundles.findById(UUID.fromString(upload.get("bundleId").asText())).orElseThrow();
        assertThat(bundle.getStatus()).isEqualTo(BundleStatus.REJECTED);
        assertThat(storage.size(bundle.getStorageKey())).isEmpty();
    }

    @Test
    void manifestProblemsAreRejectedWithAReadableReason() throws Exception {
        Dev dev = developer();
        Map<String, String> noRuntime = new LinkedHashMap<>();
        noRuntime.put("manifest.json", TestBundles.manifest("1.0.0").replace("\"runtime\": \"python-3.12\",", ""));
        noRuntime.put("agent/main.py", "x");
        var r1 = push(dev, TestBundles.zip(noRuntime));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(r1).get("message").asText()).contains("runtime is required");

        var r2 = push(dev, TestBundles.zip(Map.of("manifest.json", TestBundles.manifest("1.0.0"))));
        assertThat(read(r2).get("message").asText()).contains("Entrypoint agent/main.py is not in the bundle");

        var r3 = push(dev, TestBundles.zip(Map.of("agent/main.py", "x")));
        assertThat(read(r3).get("message").asText()).contains("manifest.json is missing");
    }

    @Test
    void aDeclaredSizeOver100MbIsRefusedUpFront() {
        Dev dev = developer();
        var response = post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/uploads",
                Map.of("sha256", "a".repeat(64), "sizeBytes", BundleService.MAX_BUNDLE_BYTES + 1));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void anotherOrgsAgentIsForbidden() throws Exception {
        Dev owner = developer();
        Dev intruder = developer();
        var response = post(intruder, "/api/v1/agents/" + owner.agent().getId() + "/bundles/uploads",
                Map.of("sha256", "a".repeat(64), "sizeBytes", 10));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        byte[] zip = TestBundles.python("1.0.0");
        JsonNode upload = createUpload(owner, zip);
        var finalize = post(intruder, "/api/v1/agents/" + owner.agent().getId() + "/bundles/"
                + upload.get("bundleId").asText() + "/finalize", Map.of("sha256", TestBundles.sha256(zip)));
        assertThat(finalize.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void pushingToASubmittedVersionIsRejectedAndChangesNothing() throws Exception {
        Dev dev = developer();
        JsonNode first = read(push(dev, TestBundles.python("1.0.0")));
        AgentVersion version = versions.findById(UUID.fromString(first.get("versionId").asText())).orElseThrow();
        version.setStatus(VersionStatus.IN_REVIEW);
        versions.save(version);

        var again = push(dev, TestBundles.python("1.0.0"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(again).get("message").asText()).contains("already IN_REVIEW");
        assertThat(versions.findById(version.getId()).orElseThrow().getBundle().getId().toString())
                .isEqualTo(first.get("bundleId").asText());
    }

    @Test
    void pushingAgainToADraftSupersedesTheQueuedBuild() throws Exception {
        Dev dev = developer();
        JsonNode first = read(push(dev, TestBundles.python("1.0.0")));
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put("manifest.json", TestBundles.manifest("1.0.0"));
        changed.put("agent/main.py", "print('v2')\n");
        JsonNode second = read(push(dev, TestBundles.zip(changed)));

        assertThat(second.get("versionId")).isEqualTo(first.get("versionId"));
        TemplateBuild old = builds.findById(UUID.fromString(first.get("buildId").asText())).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(old.getFailureCode()).isEqualTo(BuildFailureCodes.SUPERSEDED);
        AgentVersion version = versions.findById(UUID.fromString(second.get("versionId").asText())).orElseThrow();
        assertThat(version.getBundle().getId().toString()).isEqualTo(second.get("bundleId").asText());
    }
}
```

- [ ] **Step 8: Run it and watch it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=BundleControllerTest`
Expected: compilation fails, or 404s (there's no controller yet).

- [ ] **Step 9: Implement the DTOs, the error advice and the controller**

`CreateBundleUploadRequest.java`:
```java
package com.agentstore.platform.build;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateBundleUploadRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256,
                                        @Positive long sizeBytes) {}
```

`BundleUploadResponse.java`:
```java
package com.agentstore.platform.build;

import java.time.Instant;
import java.util.UUID;

public record BundleUploadResponse(UUID bundleId, String uploadUrl, Instant expiresAt) {
    /** The upload URL carries a signature: never print it. */
    @Override
    public String toString() {
        return "BundleUploadResponse[bundleId=" + bundleId + ", expiresAt=" + expiresAt + "]";
    }
}
```

`FinalizeBundleRequest.java`:
```java
package com.agentstore.platform.build;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FinalizeBundleRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) {}
```

`FinalizeBundleResponse.java`:
```java
package com.agentstore.platform.build;

import java.util.UUID;

public record FinalizeBundleResponse(UUID bundleId, UUID versionId, String versionNumber, UUID buildId,
                                     BuildStatus buildStatus) {}
```

`BuildApiErrors.java`:
```java
package com.agentstore.platform.build;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Bundle rejections carry a reason the developer must see (spec §1: "a readable reason"). The app hides
 * ResponseStatusException reasons, so this advice returns them explicitly, only for these controllers.
 */
@RestControllerAdvice(assignableTypes = BundleController.class)
public class BuildApiErrors {

    @ExceptionHandler(BundleRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(BundleRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", BuildFailureCodes.INVALID_BUNDLE, "message", e.getMessage()));
    }
}
```

`BundleController.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.identity.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/bundles")
public class BundleController {

    private final BundleService bundleService;

    public BundleController(BundleService bundleService) {
        this.bundleService = bundleService;
    }

    @PostMapping("/uploads")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleUploadResponse createUpload(@PathVariable UUID agentId,
                                             @Valid @RequestBody CreateBundleUploadRequest request) {
        return bundleService.createUpload(agentId, request.sha256(), request.sizeBytes(), claims());
    }

    @PostMapping("/{bundleId}/finalize")
    @PreAuthorize("hasRole('DEVELOPER')")
    public FinalizeBundleResponse finalizeBundle(@PathVariable UUID agentId, @PathVariable UUID bundleId,
                                                 @Valid @RequestBody FinalizeBundleRequest request) {
        return bundleService.finalizeBundle(agentId, bundleId, request.sha256(), claims());
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 10: Implement `BundleService`**

`BundleService.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.manifest.BundleManifestRules;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import com.agentstore.platform.storage.ObjectStorage;
import com.agentstore.platform.storage.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Bundle upload and finalize (spec §4 step 1). Validation reads the stored object outside any transaction; only the
 * final bookkeeping runs in one, under a row lock on the bundle, so a retried or concurrent finalize queues one build.
 */
@Service
public class BundleService {

    public static final long MAX_BUNDLE_BYTES = 100L * 1024 * 1024;
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

    private final AgentRepository agentRepository;
    private final AgentBundleRepository bundleRepository;
    private final AgentVersionRepository versionRepository;
    private final TemplateBuildRepository buildRepository;
    private final ObjectStorage storage;
    private final BundleInspector inspector;
    private final ManifestValidator validator;
    private final ManifestParser parser;
    private final TransactionTemplate tx;
    private final String backend;

    public BundleService(AgentRepository agentRepository, AgentBundleRepository bundleRepository,
                         AgentVersionRepository versionRepository, TemplateBuildRepository buildRepository,
                         ObjectStorage storage, BundleInspector inspector, ManifestValidator validator,
                         ManifestParser parser, PlatformTransactionManager transactionManager,
                         @Value("${agentstore.runner.backend:e2b}") String backend) {
        this.agentRepository = agentRepository;
        this.bundleRepository = bundleRepository;
        this.versionRepository = versionRepository;
        this.buildRepository = buildRepository;
        this.storage = storage;
        this.inspector = inspector;
        this.validator = validator;
        this.parser = parser;
        this.tx = new TransactionTemplate(transactionManager);
        this.backend = backend;
    }

    public BundleUploadResponse createUpload(UUID agentId, String sha256, long sizeBytes, JwtService.Claims caller) {
        Agent agent = ownedAgent(agentId, caller);
        if (sizeBytes > MAX_BUNDLE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Bundle is larger than 100 MB");
        }
        UUID id = UUID.randomUUID();
        String key = "bundles/" + agentId + "/" + id + ".zip";
        bundleRepository.save(new AgentBundle(id, agent, caller.userId(), key, sha256.toLowerCase(Locale.ROOT),
                sizeBytes, BundleStatus.UPLOADING));
        return new BundleUploadResponse(id, storage.presignPut(key, UPLOAD_URL_TTL), Instant.now().plus(UPLOAD_URL_TTL));
    }

    public FinalizeBundleResponse finalizeBundle(UUID agentId, UUID bundleId, String sha256, JwtService.Claims caller) {
        ownedAgent(agentId, caller);
        AgentBundle bundle = bundleRepository.findById(bundleId)
                .filter(b -> b.getAgent().getId().equals(agentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bundle not found"));
        if (bundle.getStatus() == BundleStatus.UPLOADED) {
            return existingResult(bundle);
        }
        if (bundle.getStatus() == BundleStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This bundle was rejected; upload a new one");
        }
        if (!bundle.getSha256().equalsIgnoreCase(sha256)) {
            throw reject(bundle, "sha256 does not match the one declared when the upload was created");
        }
        long size = storage.size(bundle.getStorageKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The bundle has not been uploaded yet"));
        if (size != bundle.getSizeBytes() || size > MAX_BUNDLE_BYTES) {
            throw reject(bundle, "Uploaded size " + size + " bytes does not match the declared " + bundle.getSizeBytes()
                    + " bytes (limit 100 MB)");
        }
        Manifest manifest;
        String manifestJson;
        try (InputStream in = storage.open(bundle.getStorageKey())) {
            BundleInspector.Inspection inspection = inspector.inspect(in);
            manifestJson = inspection.manifestJson();
            manifest = check(inspection, bundle);
        } catch (BundleRejectedException e) {
            throw reject(bundle, e.getMessage());
        } catch (IOException e) {
            throw new StorageException("Could not read bundle " + bundleId, e);
        }
        String json = manifestJson;
        return tx.execute(status -> record(bundleId, manifest, json));
    }

    private Manifest check(BundleInspector.Inspection inspection, AgentBundle bundle) {
        if (!inspection.sha256().equalsIgnoreCase(bundle.getSha256())) {
            throw new BundleRejectedException("The uploaded bytes do not match the declared sha256");
        }
        var validation = validator.validate(inspection.manifestJson());
        if (!validation.valid()) {
            throw new BundleRejectedException("manifest.json is invalid: " + String.join("; ", validation.errors()));
        }
        Manifest manifest = parser.parse(inspection.manifestJson());
        List<String> errors = BundleManifestRules.check(manifest);
        if (!errors.isEmpty()) {
            throw new BundleRejectedException("manifest.json is invalid: " + String.join("; ", errors));
        }
        if (!inspection.files().contains(manifest.entrypoint())) {
            throw new BundleRejectedException("Entrypoint " + manifest.entrypoint() + " is not in the bundle");
        }
        versionRepository.findByAgentIdAndVersionNumber(bundle.getAgent().getId(), manifest.version())
                .filter(v -> v.getStatus() != VersionStatus.DRAFT)
                .ifPresent(v -> {
                    throw new BundleRejectedException("Version " + manifest.version() + " is already " + v.getStatus()
                            + "; bump the version in manifest.json");
                });
        return manifest;
    }

    private FinalizeBundleResponse record(UUID bundleId, Manifest manifest, String manifestJson) {
        AgentBundle bundle = bundleRepository.findByIdForUpdate(bundleId).orElseThrow();
        if (bundle.getStatus() == BundleStatus.UPLOADED) {
            return existingResult(bundle);   // a concurrent finalize won the race
        }
        if (bundle.getStatus() != BundleStatus.UPLOADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This bundle was rejected; upload a new one");
        }
        AgentVersion version = versionRepository.findByAgentIdAndVersionNumber(bundle.getAgent().getId(), manifest.version())
                .orElseGet(() -> new AgentVersion(null, bundle.getAgent(), manifest.version(), manifestJson, null,
                        VersionStatus.DRAFT, null, null));
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Version " + manifest.version() + " is no longer a draft");
        }
        if (version.getId() != null) {
            supersedeQueuedBuilds(version.getId());
        }
        version.setManifestJson(manifestJson);
        version.setBundle(bundle);
        version.setImageRef(null);
        version.setBuildStatus(BuildStatus.QUEUED);
        version = versionRepository.save(version);
        TemplateBuild build = buildRepository.save(new TemplateBuild(version, bundle, backend, manifest.runtime(),
                manifest.entrypoint()));
        build.setLogStorageKey("builds/" + build.getId() + ".log");
        bundle.setStatus(BundleStatus.UPLOADED);
        return new FinalizeBundleResponse(bundle.getId(), version.getId(), version.getVersionNumber(), build.getId(),
                build.getStatus());
    }

    private void supersedeQueuedBuilds(UUID versionId) {
        for (TemplateBuild old : buildRepository.findByAgentVersionIdAndStatus(versionId, BuildStatus.QUEUED)) {
            old.setStatus(BuildStatus.FAILED);
            old.setFailureCode(BuildFailureCodes.SUPERSEDED);
            old.setFailureReason("A newer bundle was pushed for this version");
            old.setFinishedAt(Instant.now());
        }
    }

    private FinalizeBundleResponse existingResult(AgentBundle bundle) {
        TemplateBuild build = buildRepository.findFirstByBundleIdOrderByQueuedAtDesc(bundle.getId())
                .orElseThrow(() -> new IllegalStateException("Uploaded bundle " + bundle.getId() + " has no build"));
        AgentVersion version = build.getAgentVersion();
        return new FinalizeBundleResponse(bundle.getId(), version.getId(), version.getVersionNumber(), build.getId(),
                build.getStatus());
    }

    private BundleRejectedException reject(AgentBundle bundle, String reason) {
        tx.executeWithoutResult(status -> bundleRepository.findByIdForUpdate(bundle.getId())
                .filter(b -> b.getStatus() == BundleStatus.UPLOADING)
                .ifPresent(b -> b.setStatus(BundleStatus.REJECTED)));
        storage.deleteQuietly(bundle.getStorageKey());
        return new BundleRejectedException(reason);
    }

    private Agent ownedAgent(UUID agentId, JwtService.Claims caller) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        if (!agent.getDeveloperOrg().getId().equals(caller.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent not owned by caller's org");
        }
        return agent;
    }
}
```

- [ ] **Step 11: Run the bundle tests, then the whole suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='BundleInspectorTest,BundleControllerTest'`
Expected: PASS.

Then: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/build backend/src/main/resources/application.yml \
  backend/src/test/java/com/agentstore/platform/build
git commit -m "feat(build): bundle upload and finalize with manifest checks and build queueing"
```

---

### Task 9: Dispatch and poll builds, build status API, and the submit gate

**The loop.** `BuildJob` runs every `agentstore.builds.poll-interval` under a ShedLock, doing two things:
1. **Poll first.** For each `BUILDING` build it asks the runner, then:
   - copies a `SUCCEEDED` or `FAILED` result into the build and, *if the build is for the version's current bundle*, into the version;
   - re-queues after `RUNNER_RESTARTED` (up to 3 attempts);
   - fails the build with `TIMEOUT` after 20 minutes without an answer;
   - does nothing when the runner can't be reached.
2. **Dispatch second.** It sends the oldest `QUEUED` build, but only when none is `BUILDING`, with pre-signed GET (bundle) and PUT (log) URLs.

**Listeners.** A finished build notifies the `BuildFinishedListener` beans *after* its transaction commits. Task 10 adds the listener that resumes waiting runs.

**Files:**
- Create in `backend/src/main/java/com/agentstore/platform/build/`: `BuildRunnerClient.java`, `BuildRunnerUnavailableException.java`, `HttpBuildRunnerClient.java`, `FakeBuildRunnerClient.java`, `BuildFinishedEvent.java`, `BuildFinishedListener.java`, `BuildTransitions.java`, `BuildDispatcher.java`, `BuildPoller.java`, `BuildJob.java`, `BuildStatusController.java`, `BuildResponse.java`, `BuildSummary.java`, `BuildSummaries.java`
- Modify: `backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`
- Modify: `backend/src/main/java/com/agentstore/platform/catalog/AgentVersionDetailResponse.java`, `CatalogController.java`
- Modify: `backend/src/test/java/com/agentstore/platform/common/SchedulingWiringTest.java`
- Create: `backend/src/test/java/com/agentstore/platform/build/BuildFixtures.java`
- Test: `backend/src/test/java/com/agentstore/platform/build/HttpBuildRunnerClientTest.java`, `BuildPipelineTest.java`, `BuildStatusControllerTest.java`, `backend/src/test/java/com/agentstore/platform/publishing/SubmitBuildGateTest.java`

**Interfaces:**
- Consumes: Tasks 6–8.
- Produces:
  - `BuildRunnerClient` with `submit(RunnerBuildRequest) → RunnerBuild` and `get(String buildId) → RunnerBuild`. Both throw `BuildRunnerUnavailableException`.
  - `record RunnerBuildRequest(buildId, runtime, bundleUrl, bundleSha256, entrypoint, logUploadUrl)`.
  - `record RunnerBuild(String buildId, String status, String backend, String imageRef, String failureCode, String failureReason)`.
  - `BuildTransitions` with `markBuilding(UUID)`, `succeed(UUID, String imageRef)`, `fail(UUID, String code, String reason)`, `requeue(UUID)`. `succeed` and `fail` return `Optional<BuildFinishedEvent>`.
  - `record BuildFinishedEvent(UUID buildId, UUID versionId, String backend, boolean succeeded, String failureCode)`.
  - `interface BuildFinishedListener { void onBuildFinished(BuildFinishedEvent event); }`.
  - `BuildDispatcher.dispatchOnce() → boolean`, `BuildPoller.pollOnce()`/`pollAt(Instant) → int`.
  - `GET /api/v1/agents/{agentId}/builds/{buildId}` returns `BuildResponse`.
  - `AgentVersionDetailResponse` gains a last component, `BuildSummary build` (null unless the caller is the owning developer or an admin).
  - `record BuildSummary(UUID buildId, BuildStatus status, String failureCode, String failureReason, String logUrl)`.

- [ ] **Step 1: Write the failing runner-client test**

`backend/src/test/java/com/agentstore/platform/build/HttpBuildRunnerClientTest.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import com.agentstore.platform.build.BuildRunnerClient.RunnerBuildRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpBuildRunnerClientTest {

    MockRestServiceServer server;
    HttpBuildRunnerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpBuildRunnerClient(builder, "http://runner.test", "shh");
    }

    @Test
    void submitsWithTheSecretAndParsesTheAnswer() {
        server.expect(requestTo("http://runner.test/builds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Runner-Secret", "shh"))
                .andExpect(jsonPath("$.buildId").value("b1"))
                .andExpect(jsonPath("$.bundleUrl").value("http://store/bundle?sig=1"))
                .andExpect(jsonPath("$.logUploadUrl").value("http://store/log?sig=2"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"buildId\":\"b1\",\"status\":\"QUEUED\",\"backend\":\"e2b\"}"));

        RunnerBuild build = client.submit(new RunnerBuildRequest("b1", "python-3.12", "http://store/bundle?sig=1",
                "a".repeat(64), "main.py", "http://store/log?sig=2"));
        assertThat(build.status()).isEqualTo("QUEUED");
        assertThat(build.backend()).isEqualTo("e2b");
    }

    @Test
    void getsAFinishedBuild() {
        server.expect(requestTo("http://runner.test/builds/b1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"buildId\":\"b1\",\"status\":\"FAILED\",\"backend\":\"e2b\","
                        + "\"failureCode\":\"DEP_VULN\",\"failureReason\":\"2 high\"}", MediaType.APPLICATION_JSON));
        RunnerBuild build = client.get("b1");
        assertThat(build.failureCode()).isEqualTo("DEP_VULN");
        assertThat(build.failureReason()).isEqualTo("2 high");
    }

    @Test
    void anUnreachableRunnerIsUnavailableWithoutLeakingTheUrls() {
        server.expect(requestTo("http://runner.test/builds")).andRespond(withServerError());
        assertThatThrownBy(() -> client.submit(new RunnerBuildRequest("b1", "python-3.12", "http://store/bundle?sig=SECRET",
                "a".repeat(64), "main.py", "http://store/log?sig=SECRET")))
                .isInstanceOf(BuildRunnerUnavailableException.class)
                .hasMessageNotContaining("SECRET");
    }
}
```

- [ ] **Step 2: Implement the runner clients**

`BuildRunnerClient.java`:
```java
package com.agentstore.platform.build;

/** Spring's view of the sandbox-runner's /builds API (spec §3). */
public interface BuildRunnerClient {

    RunnerBuild submit(RunnerBuildRequest request);

    RunnerBuild get(String buildId);

    record RunnerBuildRequest(String buildId, String runtime, String bundleUrl, String bundleSha256, String entrypoint,
                              String logUploadUrl) {
        /** Pre-signed URLs carry signatures: never print them. */
        @Override
        public String toString() {
            return "RunnerBuildRequest[buildId=" + buildId + ", runtime=" + runtime + ", entrypoint=" + entrypoint + "]";
        }
    }

    /** {@code status}: QUEUED, BUILDING, SUCCEEDED or FAILED. */
    record RunnerBuild(String buildId, String status, String backend, String imageRef, String failureCode,
                       String failureReason) {}
}
```

`BuildRunnerUnavailableException.java`:
```java
package com.agentstore.platform.build;

/** The runner could not be asked. Callers leave the build as it is and try again on the next tick. */
public class BuildRunnerUnavailableException extends RuntimeException {
    public BuildRunnerUnavailableException(String message) {
        super(message);
    }
}
```

`HttpBuildRunnerClient.java`:
```java
package com.agentstore.platform.build;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Component
@Profile("runner")
public class HttpBuildRunnerClient implements BuildRunnerClient {

    private final RestClient restClient;

    @Autowired
    public HttpBuildRunnerClient(RestClient.Builder builder,
                                 @Value("${agentstore.runner.base-url}") String baseUrl,
                                 @Value("${agentstore.runner.secret}") String secret,
                                 @Value("${agentstore.runner.connect-timeout:PT5S}") Duration connectTimeout,
                                 @Value("${agentstore.runner.build-read-timeout:PT30S}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Runner-Secret", secret).build();
    }

    /** Test-only: keeps the caller's request factory (e.g. MockRestServiceServer). */
    HttpBuildRunnerClient(RestClient.Builder builder, String baseUrl, String secret) {
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("X-Runner-Secret", secret).build();
    }

    @Override
    public RunnerBuild submit(RunnerBuildRequest r) {
        Map<String, Object> body = Map.of("buildId", r.buildId(), "runtime", r.runtime(), "bundleUrl", r.bundleUrl(),
                "bundleSha256", r.bundleSha256(), "entrypoint", r.entrypoint(), "logUploadUrl", r.logUploadUrl());
        return call(() -> restClient.post().uri("/builds").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(RunnerBuild.class));
    }

    @Override
    public RunnerBuild get(String buildId) {
        return call(() -> restClient.get().uri("/builds/{id}", buildId).retrieve().body(RunnerBuild.class));
    }

    /** Only the exception type is kept: a RestClientException's message can quote the request. */
    private static RunnerBuild call(Supplier<RunnerBuild> request) {
        RunnerBuild build;
        try {
            build = request.get();
        } catch (RestClientException e) {
            throw new BuildRunnerUnavailableException("Build runner unavailable (" + e.getClass().getSimpleName() + ")");
        }
        if (build == null || build.status() == null) {
            throw new BuildRunnerUnavailableException("Build runner returned an empty answer");
        }
        return build;
    }
}
```

`FakeBuildRunnerClient.java`:
```java
package com.agentstore.platform.build;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local/dev and test stand-in: every build succeeds with a fake image unless a test says otherwise. */
@Component
@Profile("!runner")
public class FakeBuildRunnerClient implements BuildRunnerClient {

    private final List<RunnerBuildRequest> submitted = new CopyOnWriteArrayList<>();
    private final Map<String, RunnerBuild> answers = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public RunnerBuild submit(RunnerBuildRequest request) {
        if (unavailable) {
            throw new BuildRunnerUnavailableException("fake runner unavailable");
        }
        submitted.add(request);
        return new RunnerBuild(request.buildId(), "BUILDING", "fake", null, null, null);
    }

    @Override
    public RunnerBuild get(String buildId) {
        if (unavailable) {
            throw new BuildRunnerUnavailableException("fake runner unavailable");
        }
        return answers.getOrDefault(buildId,
                new RunnerBuild(buildId, "SUCCEEDED", "fake", "fake-image-" + buildId, null, null));
    }

    public void answer(String buildId, RunnerBuild build) { answers.put(buildId, build); }

    public void setUnavailable(boolean unavailable) { this.unavailable = unavailable; }

    public List<RunnerBuildRequest> submitted() { return submitted; }

    public void reset() {
        submitted.clear();
        answers.clear();
        unavailable = false;
    }
}
```

Run: `cd backend && mvn -q test -Dtest=HttpBuildRunnerClientTest`
Expected: PASS.

- [ ] **Step 3: Add the test fixtures**

`backend/src/test/java/com/agentstore/platform/build/BuildFixtures.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Builds and versions in known states, with the bundle object stored, for pipeline tests. */
@Component
public class BuildFixtures {

    private final OrgRepository orgs;
    private final UserRepository users;
    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final AgentBundleRepository bundles;
    private final TemplateBuildRepository builds;
    private final ObjectStorage storage;
    private final JdbcTemplate jdbc;

    public BuildFixtures(OrgRepository orgs, UserRepository users, AgentRepository agents, AgentVersionRepository versions,
                         AgentBundleRepository bundles, TemplateBuildRepository builds, ObjectStorage storage,
                         JdbcTemplate jdbc) {
        this.orgs = orgs;
        this.users = users;
        this.agents = agents;
        this.versions = versions;
        this.bundles = bundles;
        this.builds = builds;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public record Built(Org org, Agent agent, AgentVersion version, AgentBundle bundle, TemplateBuild build) {}

    /**
     * The test DB is shared by every test class: finish any build other tests left QUEUED or BUILDING, so they can't
     * block dispatch ("one at a time") or be dispatched instead of this test's build.
     */
    public void clearActiveBuilds() {
        jdbc.update("update template_build set status = 'FAILED', failure_code = 'SUPERSEDED' "
                + "where status in ('QUEUED', 'BUILDING')");
    }

    /** A DRAFT version with a stored bundle and one QUEUED build on {@code backend}. */
    public Built queued(String backend) {
        Org org = orgs.save(new Org(null, "BuildCo " + UUID.randomUUID(), OrgType.DEVELOPER));
        User user = users.save(new User(null, "builder-" + UUID.randomUUID() + "@example.com", null, "Builder"));
        Agent agent = agents.save(new Agent(null, org, "Recon", "finance", "d"));
        byte[] zip = TestBundles.python("1.0.0");
        UUID bundleId = UUID.randomUUID();
        String key = "bundles/" + agent.getId() + "/" + bundleId + ".zip";
        storage.put(key, zip);
        AgentBundle bundle = bundles.save(new AgentBundle(bundleId, agent, user.getId(), key, TestBundles.sha256(zip),
                zip.length, BundleStatus.UPLOADED));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", TestBundles.manifest("1.0.0"), null,
                VersionStatus.DRAFT, null, null);
        version.setBundle(bundle);
        version.setBuildStatus(BuildStatus.QUEUED);
        version = versions.save(version);
        TemplateBuild build = new TemplateBuild(version, bundle, backend, "python-3.12", "agent/main.py");
        build = builds.save(build);
        build.setLogStorageKey("builds/" + build.getId() + ".log");
        build = builds.save(build);
        return new Built(org, agent, version, bundle, build);
    }

    /** Like {@link #queued} but already dispatched: BUILDING since {@code startedAt}, one attempt made. */
    public Built building(String backend, Instant startedAt, int attempts) {
        Built b = queued(backend);
        b.build().setStatus(BuildStatus.BUILDING);
        b.build().setStartedAt(startedAt);
        b.build().setAttempts(attempts);
        TemplateBuild build = builds.save(b.build());
        return new Built(b.org(), b.agent(), b.version(), b.bundle(), build);
    }

    public TemplateBuild reload(TemplateBuild build) {
        return builds.findById(build.getId()).orElseThrow();
    }

    public AgentVersion reload(AgentVersion version) {
        return versions.findById(version.getId()).orElseThrow();
    }
}
```

- [ ] **Step 4: Write the failing pipeline tests**

`backend/src/test/java/com/agentstore/platform/build/BuildPipelineTest.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import com.agentstore.platform.catalog.AgentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildPipelineTest extends AbstractStorageIntegrationTest {

    @Autowired BuildFixtures fixtures;
    @Autowired FakeBuildRunnerClient runner;
    @Autowired BuildDispatcher dispatcher;
    @Autowired BuildPoller poller;
    @Autowired AgentBundleRepository bundles;
    @Autowired AgentVersionRepository versions;

    @BeforeEach
    void reset() {
        fixtures.clearActiveBuilds();
        runner.reset();
    }

    @Test
    void dispatchSendsTheOldestQueuedBuildWithWorkingPresignedUrls() throws Exception {
        var built = fixtures.queued("e2b");

        assertThat(dispatcher.dispatchOnce()).isTrue();

        var request = runner.submitted().get(0);
        assertThat(request.buildId()).isEqualTo(built.build().getId().toString());
        assertThat(request.runtime()).isEqualTo("python-3.12");
        assertThat(request.entrypoint()).isEqualTo("agent/main.py");
        assertThat(request.bundleSha256()).isEqualTo(built.bundle().getSha256());
        HttpResponse<byte[]> bundle = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(request.bundleUrl())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(bundle.statusCode()).isEqualTo(200);
        assertThat(TestBundles.sha256(bundle.body())).isEqualTo(built.bundle().getSha256());
        HttpResponse<Void> log = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(request.logUploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofString("line\n")).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(log.statusCode()).isEqualTo(200);

        var build = fixtures.reload(built.build());
        assertThat(build.getStatus()).isEqualTo(BuildStatus.BUILDING);
        assertThat(build.getAttempts()).isEqualTo(1);
        assertThat(build.getStartedAt()).isNotNull();
        assertThat(fixtures.reload(built.version()).getBuildStatus()).isEqualTo(BuildStatus.BUILDING);
    }

    @Test
    void onlyOneBuildRunsAtATime() {
        fixtures.building("e2b", Instant.now(), 1);
        fixtures.queued("e2b");
        assertThat(dispatcher.dispatchOnce()).isFalse();
        assertThat(runner.submitted()).isEmpty();
    }

    @Test
    void anUnreachableRunnerLeavesTheBuildQueued() {
        var built = fixtures.queued("e2b");
        runner.setUnavailable(true);
        assertThat(dispatcher.dispatchOnce()).isFalse();
        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.QUEUED);
    }

    @Test
    void successIsCopiedToTheBuildAndTheVersion() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "SUCCEEDED", "e2b", "tpl-42", null, null));

        poller.pollOnce();

        var build = fixtures.reload(built.build());
        assertThat(build.getStatus()).isEqualTo(BuildStatus.SUCCEEDED);
        assertThat(build.getImageRef()).isEqualTo("tpl-42");
        assertThat(build.getFinishedAt()).isNotNull();
        var version = fixtures.reload(built.version());
        assertThat(version.getImageRef()).isEqualTo("tpl-42");
        assertThat(version.getBuildStatus()).isEqualTo(BuildStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SECRET_FOUND", "DEP_VULN", "INSTALL_FAILED", "SMOKE_FAILED", "TIMEOUT", "INVALID_BUNDLE",
            "BUILD_FAILED"})
    void eachFailureCodeIsCopied(String code) {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "FAILED", "e2b", null, code, "because " + code));

        poller.pollOnce();

        var build = fixtures.reload(built.build());
        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getFailureCode()).isEqualTo(code);
        assertThat(build.getFailureReason()).isEqualTo("because " + code);
        assertThat(fixtures.reload(built.version()).getBuildStatus()).isEqualTo(BuildStatus.FAILED);
    }

    @Test
    void aRunnerRestartRequeuesUntilTheThirdAttemptThenFails() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "FAILED", "e2b", null, "RUNNER_RESTARTED", "restarted"));

        poller.pollOnce();
        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.QUEUED);

        var third = fixtures.building("e2b", Instant.now(), 3);
        String thirdId = third.build().getId().toString();
        runner.answer(thirdId, new RunnerBuild(thirdId, "FAILED", "e2b", null, "RUNNER_RESTARTED", "restarted"));
        poller.pollOnce();
        var failed = fixtures.reload(third.build());
        assertThat(failed.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("RUNNER_RESTARTED");
    }

    @Test
    void anUnreachableRunnerDuringPollChangesNothingUntilTheSafetyTimeout() {
        var recent = fixtures.building("e2b", Instant.now(), 1);
        var stale = fixtures.building("e2b", Instant.now().minus(Duration.ofMinutes(21)), 1);
        runner.setUnavailable(true);

        poller.pollOnce();

        assertThat(fixtures.reload(recent.build()).getStatus()).isEqualTo(BuildStatus.BUILDING);
        var timedOut = fixtures.reload(stale.build());
        assertThat(timedOut.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(timedOut.getFailureCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void aResultForABundleThatIsNoLongerCurrentNeverTouchesTheVersion() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        AgentBundle newer = bundles.save(new AgentBundle(UUID.randomUUID(), built.agent(),
                built.bundle().getUploadedByUserId(), "bundles/x/newer.zip", "b".repeat(64), 10, BundleStatus.UPLOADED));
        var version = fixtures.reload(built.version());
        version.setBundle(newer);
        version.setBuildStatus(BuildStatus.QUEUED);
        versions.save(version);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "SUCCEEDED", "e2b", "tpl-stale", null, null));

        poller.pollOnce();

        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.SUCCEEDED);
        var after = fixtures.reload(built.version());
        assertThat(after.getImageRef()).isNull();
        assertThat(after.getBuildStatus()).isEqualTo(BuildStatus.QUEUED);
    }

    @Test
    void aSucceededAnswerWithoutAnImageIsBuildFailed() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "SUCCEEDED", "e2b", " ", null, null));
        poller.pollOnce();
        assertThat(fixtures.reload(built.build()).getFailureCode()).isEqualTo("BUILD_FAILED");
    }
}
```


- [ ] **Step 5: Run it and watch it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=BuildPipelineTest`
Expected: compilation fails (`BuildDispatcher`, `BuildPoller` don't exist).

- [ ] **Step 6: Implement transitions, the dispatcher, the poller and the job**

`BuildFinishedEvent.java`:
```java
package com.agentstore.platform.build;

import java.util.UUID;

public record BuildFinishedEvent(UUID buildId, UUID versionId, String backend, boolean succeeded, String failureCode) {}
```

`BuildFinishedListener.java`:
```java
package com.agentstore.platform.build;

/** Told about every finished build, after its transaction committed. Must not throw. */
public interface BuildFinishedListener {
    void onBuildFinished(BuildFinishedEvent event);
}
```

`BuildTransitions.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Every build state change, each under a row lock. A result reaches the version only while the build is for the
 * version's current bundle, so an older build can never overwrite a newer push. A version that already has a
 * working image keeps build_status SUCCEEDED while a rebuild runs or fails.
 */
@Service
public class BuildTransitions {

    static final int MAX_REASON_LENGTH = 4000;

    private final TemplateBuildRepository builds;

    public BuildTransitions(TemplateBuildRepository builds) {
        this.builds = builds;
    }

    @Transactional
    public void markBuilding(UUID buildId) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() != BuildStatus.QUEUED) {
            return;
        }
        build.setStatus(BuildStatus.BUILDING);
        build.setStartedAt(Instant.now());
        build.setAttempts(build.getAttempts() + 1);
        AgentVersion version = currentVersion(build);
        if (version != null && version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            version.setBuildStatus(BuildStatus.BUILDING);
        }
    }

    @Transactional
    public Optional<BuildFinishedEvent> succeed(UUID buildId, String imageRef) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() != BuildStatus.BUILDING) {
            return Optional.empty();
        }
        build.setStatus(BuildStatus.SUCCEEDED);
        build.setImageRef(imageRef);
        build.setFinishedAt(Instant.now());
        AgentVersion version = currentVersion(build);
        if (version != null) {
            version.setImageRef(imageRef);
            version.setBuildStatus(BuildStatus.SUCCEEDED);
        }
        return Optional.of(new BuildFinishedEvent(build.getId(), build.getAgentVersion().getId(), build.getBackend(),
                true, null));
    }

    @Transactional
    public Optional<BuildFinishedEvent> fail(UUID buildId, String code, String reason) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() != BuildStatus.BUILDING && build.getStatus() != BuildStatus.QUEUED) {
            return Optional.empty();
        }
        build.setStatus(BuildStatus.FAILED);
        build.setFailureCode(code);
        build.setFailureReason(reason == null || reason.length() <= MAX_REASON_LENGTH ? reason
                : reason.substring(0, MAX_REASON_LENGTH));
        build.setFinishedAt(Instant.now());
        AgentVersion version = currentVersion(build);
        if (version != null && version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            version.setBuildStatus(BuildStatus.FAILED);
        }
        return Optional.of(new BuildFinishedEvent(build.getId(), build.getAgentVersion().getId(), build.getBackend(),
                false, code));
    }

    @Transactional
    public void requeue(UUID buildId) {
        TemplateBuild build = lock(buildId);
        if (build.getStatus() == BuildStatus.BUILDING) {
            build.setStatus(BuildStatus.QUEUED);
            build.setStartedAt(null);
        }
    }

    private TemplateBuild lock(UUID buildId) {
        return builds.findByIdForUpdate(buildId)
                .orElseThrow(() -> new IllegalArgumentException("Build not found: " + buildId));
    }

    private static AgentVersion currentVersion(TemplateBuild build) {
        AgentVersion version = build.getAgentVersion();
        boolean current = version.getBundle() != null && version.getBundle().getId().equals(build.getBundle().getId());
        return current ? version : null;
    }
}
```

`BuildDispatcher.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuildRequest;
import com.agentstore.platform.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Sends the oldest queued build to the runner, one build at a time (spec §4 "Limits"). */
@Component
public class BuildDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BuildDispatcher.class);
    /** Longer than the runner's 15-minute build timeout plus time spent queued. */
    static final Duration URL_TTL = Duration.ofHours(1);

    private final TemplateBuildRepository builds;
    private final BuildTransitions transitions;
    private final BuildRunnerClient runner;
    private final ObjectStorage storage;

    public BuildDispatcher(TemplateBuildRepository builds, BuildTransitions transitions, BuildRunnerClient runner,
                           ObjectStorage storage) {
        this.builds = builds;
        this.transitions = transitions;
        this.runner = runner;
        this.storage = storage;
    }

    /** True when a build was handed to the runner. */
    public boolean dispatchOnce() {
        if (builds.existsByStatus(BuildStatus.BUILDING)) {
            return false;
        }
        Optional<TemplateBuild> next = builds.findFirstByStatusOrderByQueuedAtAsc(BuildStatus.QUEUED);
        if (next.isEmpty()) {
            return false;
        }
        TemplateBuild build = next.get();
        RunnerBuildRequest request = new RunnerBuildRequest(build.getId().toString(), build.getRuntime(),
                storage.presignGet(build.getBundle().getStorageKey(), URL_TTL), build.getBundle().getSha256(),
                build.getEntrypoint(), storage.presignPut(build.getLogStorageKey(), URL_TTL));
        try {
            runner.submit(request);
        } catch (BuildRunnerUnavailableException e) {
            log.warn("Could not dispatch build {}: {}", build.getId(), e.getMessage());
            return false;
        }
        // A crash before this line leaves the build QUEUED; the next tick resubmits the same buildId, which the
        // runner treats as the same build.
        transitions.markBuilding(build.getId());
        return true;
    }
}
```

`BuildPoller.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;

/** Copies runner results into template_build and agent_version (spec §4 step 2). */
@Component
public class BuildPoller {

    private static final Logger log = LoggerFactory.getLogger(BuildPoller.class);
    static final int MAX_ATTEMPTS = 3;
    /** The runner enforces 15 minutes; this only catches a runner that went silent. */
    static final Duration SAFETY_TIMEOUT = Duration.ofMinutes(20);

    private final TemplateBuildRepository builds;
    private final BuildTransitions transitions;
    private final BuildRunnerClient runner;
    private final ObjectProvider<BuildFinishedListener> listeners;

    /** Listeners come through an ObjectProvider so the poller works with none registered. */
    public BuildPoller(TemplateBuildRepository builds, BuildTransitions transitions, BuildRunnerClient runner,
                       ObjectProvider<BuildFinishedListener> listeners) {
        this.builds = builds;
        this.transitions = transitions;
        this.runner = runner;
        this.listeners = listeners;
    }

    public int pollOnce() {
        return pollAt(Instant.now());
    }

    /** Returns how many builds finished during this poll. */
    public int pollAt(Instant now) {
        int finished = 0;
        for (TemplateBuild build : builds.findByStatusOrderByQueuedAtAsc(BuildStatus.BUILDING)) {
            try {
                if (poll(build, now)) {
                    finished++;
                }
            } catch (RuntimeException e) {
                log.error("Polling build {} failed", build.getId(), e);
            }
        }
        return finished;
    }

    private boolean poll(TemplateBuild build, Instant now) {
        if (build.getStartedAt() != null && build.getStartedAt().plus(SAFETY_TIMEOUT).isBefore(now)) {
            return notify(transitions.fail(build.getId(), BuildFailureCodes.TIMEOUT,
                    "No result from the build runner within " + SAFETY_TIMEOUT.toMinutes() + " minutes"));
        }
        RunnerBuild answer;
        try {
            answer = runner.get(build.getId().toString());
        } catch (BuildRunnerUnavailableException e) {
            return false;   // try again next tick; the safety timeout bounds this
        }
        return switch (answer.status()) {
            case "SUCCEEDED" -> answer.imageRef() == null || answer.imageRef().isBlank()
                    ? notify(transitions.fail(build.getId(), BuildFailureCodes.BUILD_FAILED,
                            "The build runner reported success without an image"))
                    : notify(transitions.succeed(build.getId(), answer.imageRef()));
            case "FAILED" -> failed(build, answer);
            default -> false;   // QUEUED or BUILDING on the runner
        };
    }

    private boolean failed(TemplateBuild build, RunnerBuild answer) {
        if (BuildFailureCodes.RUNNER_RESTARTED.equals(answer.failureCode())) {
            if (build.getAttempts() < MAX_ATTEMPTS) {
                transitions.requeue(build.getId());
                return false;
            }
            return notify(transitions.fail(build.getId(), BuildFailureCodes.RUNNER_RESTARTED,
                    "The build runner restarted during " + MAX_ATTEMPTS + " attempts; push again to retry"));
        }
        String code = answer.failureCode() == null ? BuildFailureCodes.BUILD_FAILED : answer.failureCode();
        return notify(transitions.fail(build.getId(), code, answer.failureReason()));
    }

    /** Runs after the transition's transaction has committed. */
    private boolean notify(Optional<BuildFinishedEvent> event) {
        event.ifPresent(e -> listeners.orderedStream().forEach(listener -> {
            try {
                listener.onBuildFinished(e);
            } catch (RuntimeException ex) {
                log.error("Build-finished listener failed for build {}", e.buildId(), ex);
            }
        }));
        return event.isPresent();
    }
}
```

`BuildJob.java`:
```java
package com.agentstore.platform.build;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BuildJob {

    private final BuildPoller poller;
    private final BuildDispatcher dispatcher;

    public BuildJob(BuildPoller poller, BuildDispatcher dispatcher) {
        this.poller = poller;
        this.dispatcher = dispatcher;
    }

    /** Poll first so a finished build frees the single slot before dispatch looks for the next one. */
    @Scheduled(fixedDelayString = "${agentstore.builds.poll-interval:PT5S}",
            initialDelayString = "${agentstore.builds.poll-interval:PT5S}")
    @SchedulerLock(name = "buildPipeline", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        poller.pollOnce();
        dispatcher.dispatchOnce();
    }
}
```

In `SchedulingWiringTest`, add `"agentstore.builds.poll-interval=PT1H"` to its `@TestPropertySource` properties, so the build job doesn't fire during that test.

- [ ] **Step 7: Run the pipeline tests**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='BuildPipelineTest,HttpBuildRunnerClientTest'`
Expected: PASS.

- [ ] **Step 8: Write the failing status-API and submit-gate tests**

`backend/src/test/java/com/agentstore/platform/build/BuildStatusControllerTest.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildStatusControllerTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired BuildFixtures fixtures;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;

    ResponseEntity<String> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void theOwnerSeesStatusFailureAndALogLink() throws Exception {
        var built = fixtures.queued("e2b");
        built.build().setStatus(BuildStatus.FAILED);
        built.build().setFailureCode("DEP_VULN");
        built.build().setFailureReason("2 high vulnerabilities");
        storage.put(built.build().getLogStorageKey(), "audit output\n".getBytes());
        fixtures.saveBuild(built.build());
        String token = identity.tokenFor(identity.member(built.org(), "owner-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));

        var response = get(token, "/api/v1/agents/" + built.agent().getId() + "/builds/" + built.build().getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("failureCode").asText()).isEqualTo("DEP_VULN");
        assertThat(body.get("versionId").asText()).isEqualTo(built.version().getId().toString());
        assertThat(body.get("logUrl").asText()).contains(built.build().getId() + ".log");
    }

    @Test
    void theLogLinkIsNullBeforeAnyLogExists() throws Exception {
        var built = fixtures.queued("e2b");
        String token = identity.tokenFor(identity.member(built.org(), "owner-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        JsonNode body = new ObjectMapper().readTree(
                get(token, "/api/v1/agents/" + built.agent().getId() + "/builds/" + built.build().getId()).getBody());
        assertThat(body.get("logUrl").isNull()).isTrue();
    }

    @Test
    void anotherOrgIsForbiddenAndAWrongAgentIsNotFound() {
        var built = fixtures.queued("e2b");
        Org other = orgs.save(new Org(null, "Other " + UUID.randomUUID(), OrgType.DEVELOPER));
        String intruder = identity.tokenFor(identity.member(other, "x-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        assertThat(get(intruder, "/api/v1/agents/" + built.agent().getId() + "/builds/" + built.build().getId())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String owner = identity.tokenFor(identity.member(built.org(), "o-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        assertThat(get(owner, "/api/v1/agents/" + UUID.randomUUID() + "/builds/" + built.build().getId())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

Add to `BuildFixtures`:
```java
    public TemplateBuild saveBuild(TemplateBuild build) {
        return builds.save(build);
    }
```

`backend/src/test/java/com/agentstore/platform/publishing/SubmitBuildGateTest.java`:
```java
package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildFixtures;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitBuildGateTest extends AbstractStorageIntegrationTest {

    @Autowired BuildFixtures fixtures;
    @Autowired PublishingService publishing;
    @Autowired AgentVersionRepository versions;

    @Test
    void aVersionWhoseBuildHasNotSucceededCannotBeSubmitted() {
        var built = fixtures.building("e2b", java.time.Instant.now(), 1);
        assertThatThrownBy(() -> publishing.submit(built.version().getId(), built.org().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("build has not succeeded");
    }

    @Test
    void aVersionWithASucceededBuildCanBeSubmitted() {
        var built = fixtures.queued("e2b");
        AgentVersion version = fixtures.reload(built.version());
        version.setBuildStatus(BuildStatus.SUCCEEDED);
        version.setImageRef("tpl-1");
        versions.save(version);
        assertThat(publishing.submit(version.getId(), built.org().getId()).getStatus()).isEqualTo(VersionStatus.LIVE);
    }

    @Test
    void aVersionWithNoImageAtAllCannotBeSubmitted() {
        var built = fixtures.queued("e2b");
        AgentVersion version = fixtures.reload(built.version());
        version.setBundle(null);
        version.setBuildStatus(null);
        versions.save(version);
        assertThatThrownBy(() -> publishing.submit(version.getId(), built.org().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no image");
    }
}
```

The manifest in `valid-bundle-python.json` has READ scopes only, so a successful submit makes the version Tier A and `LIVE`.

- [ ] **Step 9: Run them and watch them fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='BuildStatusControllerTest,SubmitBuildGateTest'`
Expected: FAIL (there's no endpoint, and submit isn't gated).

- [ ] **Step 10: Implement the status API, build summaries and the submit gate**

`BuildResponse.java`:
```java
package com.agentstore.platform.build;

import java.time.Instant;
import java.util.UUID;

public record BuildResponse(UUID id, UUID versionId, BuildStatus status, String backend, String imageRef,
                            String failureCode, String failureReason, String logUrl, Instant queuedAt,
                            Instant startedAt, Instant finishedAt) {

    static BuildResponse from(TemplateBuild b, String logUrl) {
        return new BuildResponse(b.getId(), b.getAgentVersion().getId(), b.getStatus(), b.getBackend(), b.getImageRef(),
                b.getFailureCode(), b.getFailureReason(), logUrl, b.getQueuedAt(), b.getStartedAt(), b.getFinishedAt());
    }
}
```

`BuildSummary.java`:
```java
package com.agentstore.platform.build;

import java.util.UUID;

/** The latest build of a version, as the developer's version page shows it. */
public record BuildSummary(UUID buildId, BuildStatus status, String failureCode, String failureReason, String logUrl) {}
```

`BuildSummaries.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class BuildSummaries {

    static final Duration LOG_URL_TTL = Duration.ofMinutes(15);

    private final TemplateBuildRepository builds;
    private final ObjectStorage storage;

    public BuildSummaries(TemplateBuildRepository builds, ObjectStorage storage) {
        this.builds = builds;
        this.storage = storage;
    }

    public Optional<BuildSummary> latest(AgentVersion version) {
        return builds.findFirstByAgentVersionIdOrderByQueuedAtDesc(version.getId())
                .map(b -> new BuildSummary(b.getId(), b.getStatus(), b.getFailureCode(), b.getFailureReason(), logUrl(b)));
    }

    /** A pre-signed link to the log, or null while no log has been written yet (or the store is down). */
    String logUrl(TemplateBuild build) {
        if (build.getLogStorageKey() == null) {
            return null;
        }
        try {
            return storage.size(build.getLogStorageKey()).isPresent()
                    ? storage.presignGet(build.getLogStorageKey(), LOG_URL_TTL) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
```

`BuildStatusController.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Build status for the owning developer (and admins). WS5c's `push --wait` polls this. */
@RestController
@RequestMapping("/api/v1/agents/{agentId}/builds")
public class BuildStatusController {

    private final TemplateBuildRepository builds;
    private final BuildSummaries summaries;

    public BuildStatusController(TemplateBuildRepository builds, BuildSummaries summaries) {
        this.builds = builds;
        this.summaries = summaries;
    }

    @GetMapping("/{buildId}")
    @PreAuthorize("hasAnyRole('DEVELOPER','ADMIN')")
    public BuildResponse get(@PathVariable UUID agentId, @PathVariable UUID buildId) {
        TemplateBuild build = builds.findById(buildId)
                .filter(b -> b.getAgentVersion().getAgent().getId().equals(agentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Build not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean owner = build.getAgentVersion().getAgent().getDeveloperOrg().getId().equals(claims.orgId());
        if (!owner && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Build not visible to caller");
        }
        return BuildResponse.from(build, summaries.logUrl(build));
    }
}
```

In `catalog/AgentVersionDetailResponse.java` (replace):
```java
package com.agentstore.platform.catalog;

import com.agentstore.platform.build.BuildSummary;
import com.agentstore.platform.manifest.Manifest;

import java.util.UUID;

/** {@code build} is null unless the caller is the owning developer or an admin. */
public record AgentVersionDetailResponse(UUID id, UUID agentId, String agentName, String versionNumber,
                                         VersionStatus status, Manifest manifest, BuildSummary build) {
    static AgentVersionDetailResponse from(AgentVersion version, Manifest manifest, BuildSummary build) {
        return new AgentVersionDetailResponse(version.getId(), version.getAgent().getId(),
                version.getAgent().getName(), version.getVersionNumber(), version.getStatus(), manifest, build);
    }
}
```

In `CatalogController`:
- Inject `BuildSummaries buildSummaries` through the constructor.
- Replace the last two lines of `detail` with:
```java
        var manifest = manifestParser.parse(version.getManifestJson());
        var build = (isOwningDeveloper || isAdmin) ? buildSummaries.latest(version).orElse(null) : null;
        return AgentVersionDetailResponse.from(version, manifest, build);
```

In `PublishingService.submit`, directly after the `DRAFT` status check, add:
```java
        if (version.getBundle() != null && version.getBuildStatus() != BuildStatus.SUCCEEDED) {
            throw new IllegalStateException("The version's build has not succeeded (build status: "
                    + version.getBuildStatus() + ")");
        }
        if (version.getImageRef() == null) {
            throw new IllegalStateException("The version has no image; push a bundle and wait for its build");
        }
```
(Import `com.agentstore.platform.build.BuildStatus`.) `SubmissionController` already maps `IllegalStateException` to 409.

- [ ] **Step 11: Run the whole backend suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS. Any existing test that builds `AgentVersionDetailResponse` by hand needs the extra `null` argument.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/build backend/src/main/java/com/agentstore/platform/catalog \
  backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java backend/src/test
git commit -m "feat(build): dispatch and poll builds, expose build status, gate submit on a built image"
```

---

### Task 10: Run the manifest's entrypoint, and rebuild on a backend switch (`PENDING_BUILD`)

**What changes for runs:**
1. **Launch code moves.** The sandbox-launch code moves out of `RunController` into `RunLauncher`, so a run can be launched after its trigger request has returned.
2. **The command comes from the manifest.** It's `AgentRuntime.runCommand(entrypoint)`. Versions without a `runtime` keep the old `agentstore.runs.agent-command` fallback.
3. **A fresh callback token at launch.** The launcher issues a new callback token for every launch and stores only its hash. That way a run that waited in `PENDING_BUILD` still gets a raw token, which the trigger request never keeps.
4. **Rebuild on a backend mismatch.** When a version was built by the platform (it has a bundle) and its latest successful build ran on a different backend from `agentstore.runner.backend`:
   - the trigger queues one rebuild (unless one is already queued or running for that backend);
   - the run is created as `PENDING_BUILD` with a 25-minute deadline, and the wallet reservation is held.
5. **When the rebuild finishes,** `PendingBuildRuns` (a `BuildFinishedListener`) either:
   - moves each waiting run to `PENDING` with a fresh 10-minute deadline and launches it, or
   - fails them all, which releases their reservations.
6. **The reaper** now also watches `PENDING_BUILD` runs, and their deadline times them out.

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/build/RebuildService.java`
- Create: `backend/src/main/java/com/agentstore/platform/execution/RunLauncher.java`, `PendingBuildRuns.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/RunController.java`, `RunLifecycleService.java`, `RunRepository.java`, `RunReaper.java`
- Modify: `backend/src/test/java/com/agentstore/platform/execution/ExecutionFixtures.java`
- Test: `backend/src/test/java/com/agentstore/platform/execution/RunLaunchFromBuildTest.java`

**Interfaces:**
- Consumes:
  - Task 7: `AgentRuntime`, `RunStatus.PENDING_BUILD`, `TemplateBuildRepository`.
  - Task 9: `BuildFinishedListener`, `BuildFinishedEvent`, `BuildPoller`, `FakeBuildRunnerClient`.
  - Existing: `RunLifecycleService.terminate/markStarted`, `FakeSandboxProvider.specForRun(UUID)`.
- Produces:
  - `RebuildService` with `needsRebuild(AgentVersion) → boolean` and `queueRebuild(AgentVersion)`.
  - `RunLauncher.launch(UUID runId)`, which throws `ResponseStatusException` the way the old `RunController.launch` did.
  - `RunLifecycleService.promotePendingBuild(UUID runId, Instant deadline) → boolean`.
  - `RunRepository.findByStatusAndAgentVersionId(RunStatus, UUID)` and `RunRepository.updateCallbackTokenHash(UUID, String)`.
  - `RunController.PENDING_BUILD_TIMEOUT = Duration.ofMinutes(25)`.
  - `ExecutionFixtures.liveBuiltTierA(BigDecimal spendCap, BigDecimal walletBalance, String builtOnBackend) → Scenario`.

- [ ] **Step 1: Extend the execution fixtures**

In `ExecutionFixtures`:
- Add the constructor parameters `UserRepository userRepository`, `AgentBundleRepository agentBundleRepository` and `TemplateBuildRepository templateBuildRepository`, stored in fields of the same names. Imports: `com.agentstore.platform.build.*`, `com.agentstore.platform.identity.User`, `com.agentstore.platform.identity.UserRepository`, `java.util.UUID`.
- Add:

```java
    /**
     * A LIVE Tier A version built by the platform from a bundle (runtime python-3.12, entrypoint agent/main.py), whose
     * image was built on {@code builtOnBackend}; buyer, wallet and ACTIVE connection as in {@link #liveTierA}.
     */
    public Scenario liveBuiltTierA(BigDecimal spendCap, BigDecimal walletBalance, String builtOnBackend) {
        Scenario scenario = liveTierA(spendCap, walletBalance);
        AgentVersion version = scenario.version();
        User user = userRepository.save(new User(null, "builder-" + UUID.randomUUID() + "@example.com", null, "Builder"));
        UUID bundleId = UUID.randomUUID();
        AgentBundle bundle = agentBundleRepository.save(new AgentBundle(bundleId, version.getAgent(), user.getId(),
                "bundles/" + version.getAgent().getId() + "/" + bundleId + ".zip", "c".repeat(64), 100,
                BundleStatus.UPLOADED));
        version.setManifestJson(readManifest("valid-bundle-python.json"));
        version.setBundle(bundle);
        version.setBuildStatus(BuildStatus.SUCCEEDED);
        version.setImageRef("img-" + builtOnBackend);
        version = agentVersionRepository.save(version);
        TemplateBuild build = new TemplateBuild(version, bundle, builtOnBackend, "python-3.12", "agent/main.py");
        build.setStatus(BuildStatus.SUCCEEDED);
        build.setImageRef("img-" + builtOnBackend);
        build.setFinishedAt(Instant.now());
        build = templateBuildRepository.save(build);
        build.setLogStorageKey("builds/" + build.getId() + ".log");
        templateBuildRepository.save(build);
        return new Scenario(scenario.buyer(), version, scenario.connection());
    }
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/agentstore/platform/execution/RunLaunchFromBuildTest.java`:
```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.build.*;
import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunLaunchFromBuildTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ExecutionFixtures fixtures;
    @Autowired FakeSandboxProvider sandboxes;
    @Autowired FakeBuildRunnerClient runner;
    @Autowired BuildPoller poller;
    @Autowired BuildDispatcher dispatcher;
    @Autowired RunRepository runs;
    @Autowired TemplateBuildRepository builds;
    @Autowired WalletRepository wallets;
    @Autowired RunReaper reaper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("update template_build set status = 'FAILED', failure_code = 'SUPERSEDED' "
                + "where status in ('QUEUED', 'BUILDING')");
        runner.reset();
    }

    RunResponse trigger(ExecutionFixtures.Scenario scenario) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), scenario.buyer().getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<RunResponse> response = rest.postForEntity("http://localhost:" + port + "/api/v1/runs",
                new HttpEntity<>(Map.of("connectionId", scenario.connection().getId()), headers), RunResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    BigDecimal reserved(ExecutionFixtures.Scenario scenario) {
        return wallets.findByOrgId(scenario.buyer().getId()).orElseThrow().getReserved();
    }

    @Test
    void aBuiltVersionRunsItsManifestEntrypoint() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "e2b");
        RunResponse run = trigger(scenario);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        SandboxSpec spec = sandboxes.specForRun(run.id());
        assertThat(spec.command()).isEqualTo(List.of("sh", "-c", "cd /workspace/app && exec python 'agent/main.py'"));
        assertThat(spec.imageRef()).isEqualTo("img-e2b");
    }

    @Test
    void aVersionWithoutARuntimeKeepsTheConfiguredCommand() {
        var scenario = fixtures.liveTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"));
        RunResponse run = trigger(scenario);
        assertThat(sandboxes.specForRun(run.id()).command())
                .isEqualTo(List.of("sh", "-c", "/workspace/app/agentstore-entrypoint"));
    }

    @Test
    void aBackendMismatchQueuesOneRebuildAndTheRunWaits() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");

        RunResponse first = trigger(scenario);
        RunResponse second = trigger(scenario);

        assertThat(first.status()).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(second.status()).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(sandboxes.createdSpecs()).noneMatch(s -> s.requestId().equals(first.id().toString()));
        var queued = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).getBackend()).isEqualTo("e2b");
        assertThat(queued.get(0).getBundle().getId()).isEqualTo(scenario.version().getBundle().getId());
        assertThat(reserved(scenario)).isEqualByComparingTo("200.00");
    }

    @Test
    void whenTheRebuildSucceedsTheWaitingRunStartsWithAFreshToken() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario);
        String hashBefore = runs.findById(run.id()).orElseThrow().getCallbackTokenHash();
        TemplateBuild rebuild = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED).get(0);

        assertThat(dispatcher.dispatchOnce()).isTrue();
        runner.answer(rebuild.getId().toString(),
                new RunnerBuild(rebuild.getId().toString(), "SUCCEEDED", "e2b", "img-e2b-new", null, null));
        poller.pollOnce();

        Run started = runs.findById(run.id()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(started.getCallbackTokenHash()).isNotEqualTo(hashBefore);
        SandboxSpec spec = sandboxes.specForRun(run.id());
        assertThat(spec.imageRef()).isEqualTo("img-e2b-new");
        assertThat(RunTokenService.hash(spec.envVars().get("AGENT_CALLBACK_TOKEN"))).isEqualTo(started.getCallbackTokenHash());
        assertThat(started.getDeadlineAt()).isAfter(Instant.now().plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS - 60));
    }

    @Test
    void whenTheRebuildFailsTheWaitingRunFailsAndReleasesItsReservation() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario);
        TemplateBuild rebuild = builds.findByAgentVersionIdAndStatus(scenario.version().getId(), BuildStatus.QUEUED).get(0);

        dispatcher.dispatchOnce();
        runner.answer(rebuild.getId().toString(),
                new RunnerBuild(rebuild.getId().toString(), "FAILED", "e2b", null, "INSTALL_FAILED", "pip failed"));
        poller.pollOnce();

        assertThat(runs.findById(run.id()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(reserved(scenario)).isEqualByComparingTo("0.00");
    }

    @Test
    void aRunWaitingPastItsDeadlineTimesOut() {
        var scenario = fixtures.liveBuiltTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"), "gvisor");
        RunResponse run = trigger(scenario);

        reaper.reapAt(Instant.now().plus(RunController.PENDING_BUILD_TIMEOUT).plusSeconds(60));

        assertThat(runs.findById(run.id()).orElseThrow().getStatus()).isEqualTo(RunStatus.TIMED_OUT);
        assertThat(reserved(scenario)).isEqualByComparingTo("0.00");
    }
}
```

`liveTierA` saves a new buyer org on every call, so wallet reservations can't mix across tests.

- [ ] **Step 3: Run it and watch it fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RunLaunchFromBuildTest`
Expected: compilation fails (`PENDING_BUILD_TIMEOUT`, `liveBuiltTierA`), then assertion failures.

- [ ] **Step 4: Add the repository and lifecycle methods**

In `RunRepository`, add (imports `org.springframework.data.jpa.repository.Modifying`, `org.springframework.transaction.annotation.Transactional`):
```java
    List<Run> findByStatusAndAgentVersionId(RunStatus status, UUID agentVersionId);

    @Transactional
    @Modifying
    @Query("update Run r set r.callbackTokenHash = :hash where r.id = :id")
    int updateCallbackTokenHash(@Param("id") UUID id, @Param("hash") String hash);
```

In `RunLifecycleService`, add:
```java
    /**
     * PENDING_BUILD → PENDING once the rebuild succeeded; the run's time limit restarts. False when the run has
     * moved on meanwhile (reaped, failed), in which case nothing changes.
     */
    @Transactional
    public boolean promotePendingBuild(UUID runId, Instant deadline) {
        Run run = lock(runId);
        if (run.getStatus() != RunStatus.PENDING_BUILD) {
            return false;
        }
        run.setStatus(RunStatus.PENDING);
        run.setDeadlineAt(deadline);
        runRepository.save(run);
        return true;
    }
```

In `RunReaper`, change `ACTIVE` to include the new status:
```java
    private static final List<RunStatus> ACTIVE =
            List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);
```
Its existing "deadline passed → TIMED_OUT" branch then covers waiting runs. (`InternalRunsController.ACTIVE` stays as it is: a `PENDING_BUILD` run has no sandbox.)

- [ ] **Step 5: Implement `RebuildService`**

`backend/src/main/java/com/agentstore/platform/build/RebuildService.java`:
```java
package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Rebuilds a platform-built version when the configured backend changed since its image was built (spec §4 step 7). */
@Service
public class RebuildService {

    private final TemplateBuildRepository builds;
    private final String backend;

    public RebuildService(TemplateBuildRepository builds, @Value("${agentstore.runner.backend:e2b}") String backend) {
        this.builds = builds;
        this.backend = backend;
    }

    public boolean needsRebuild(AgentVersion version) {
        if (version.getBundle() == null) {
            return false;   // hand-supplied image (legacy path): nothing to rebuild from
        }
        return builds.findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(version.getId(), BuildStatus.SUCCEEDED)
                .filter(b -> b.getBundle().getId().equals(version.getBundle().getId()))
                .map(b -> !b.getBackend().equals(backend))
                .orElse(false);
    }

    /** Queues one rebuild of the version's current bundle on the configured backend, unless one is already pending. */
    @Transactional
    public void queueRebuild(AgentVersion version) {
        if (builds.existsByAgentVersionIdAndBackendAndStatusIn(version.getId(), backend,
                List.of(BuildStatus.QUEUED, BuildStatus.BUILDING))) {
            return;
        }
        TemplateBuild last = builds.findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(version.getId(),
                BuildStatus.SUCCEEDED).orElseThrow();
        TemplateBuild rebuild = builds.save(new TemplateBuild(version, version.getBundle(), backend, last.getRuntime(),
                last.getEntrypoint()));
        rebuild.setLogStorageKey("builds/" + rebuild.getId() + ".log");
    }
}
```

- [ ] **Step 6: Move the launch into `RunLauncher`**

`backend/src/main/java/com/agentstore/platform/execution/RunLauncher.java`:
```java
package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.manifest.AgentRuntime;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Starts the sandbox for a PENDING run: fresh callback token, env, egress allowlist, the manifest's run command.
 * On failure the run is FAILED (its reservation released) and a ResponseStatusException describes why.
 */
@Service
public class RunLauncher {

    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final RunLifecycleService runLifecycleService;
    private final String callbackBaseUrl;
    /** For versions without a manifest runtime (hand-supplied images). */
    private final String fallbackCommand;

    public RunLauncher(RunRepository runRepository, SecretRepository secretRepository, SecretService secretService,
                       ManifestParser manifestParser, SandboxProvider sandboxProvider, RunTokenService runTokenService,
                       RunLifecycleService runLifecycleService,
                       @Value("${agentstore.callback.base-url}") String callbackBaseUrl,
                       @Value("${agentstore.runs.agent-command:/workspace/app/agentstore-entrypoint}") String fallbackCommand) {
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.runLifecycleService = runLifecycleService;
        this.callbackBaseUrl = callbackBaseUrl;
        this.fallbackCommand = fallbackCommand;
    }

    public void launch(UUID runId) {
        Run run = runRepository.findById(runId).orElseThrow();
        AgentVersion version = run.getAgentVersion();
        Connection connection = run.getConnection();
        Map<String, String> envVars;
        List<String> allowOut;
        List<String> command;
        try {
            Manifest manifest = manifestParser.parse(version.getManifestJson());
            String rawToken = runTokenService.generate();
            runRepository.updateCallbackTokenHash(runId, RunTokenService.hash(rawToken));

            envVars = new HashMap<>();
            envVars.put("AGENT_RUN_ID", runId.toString());
            envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + runId);
            envVars.put("AGENT_CALLBACK_TOKEN", rawToken);
            for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
                envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
            }

            allowOut = new ArrayList<>();
            allowOut.add(URI.create(callbackBaseUrl).getHost());
            allowOut.addAll(manifest.networkAllowlist());

            command = AgentRuntime.fromId(manifest.runtime())
                    .map(runtime -> runtime.runCommand(manifest.entrypoint()))
                    .orElse(List.of("sh", "-c", fallbackCommand));
        } catch (RuntimeException e) {
            runLifecycleService.terminate(runId, RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Run could not be launched"));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Run could not be launched", e);
        }

        SandboxHandle handle;
        try {
            handle = sandboxProvider.create(new SandboxSpec(runId.toString(), version.getImageRef(), command, envVars,
                    allowOut, RunController.SANDBOX_CPU, RunController.SANDBOX_MEMORY_MB,
                    RunController.SANDBOX_TIMEOUT_SECONDS));
        } catch (SandboxCapacityException e) {
            runLifecycleService.terminate(runId, RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", RunController.capacitySummary(e.code()), "code", e.code()));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e);
        } catch (RuntimeException e) {
            runLifecycleService.terminate(runId, RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Sandbox could not be started"));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sandbox could not be started", e);
        }
        runLifecycleService.markStarted(runId, handle.sandboxId());
    }
}
```

- [ ] **Step 7: Slim down `RunController.trigger`**

In `RunController`:
- Delete the private `launch` method.
- Remove the constructor parameters and fields `secretRepository`, `secretService`, `sandboxProvider`, `callbackBaseUrl` and `agentCommand`.
- Add `RunLauncher runLauncher` and `RebuildService rebuildService`.
- Keep `capacitySummary`, which is now called from `RunLauncher`, so make it package-private static (it already is).
- Add the constant and replace `trigger` with the code below. Import `com.agentstore.platform.build.RebuildService` and `java.time.Duration`.

```java
    /** How long a run may wait for its agent to be rebuilt before the reaper times it out. */
    static final Duration PENDING_BUILD_TIMEOUT = Duration.ofMinutes(25);

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!connection.getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connection not owned by caller's org");
        }
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        AgentVersion version = connection.getAgentVersion();
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Connection is not pinned to an agent version; reconnect to the agent");
        }
        if (version.getStatus() != VersionStatus.LIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pinned agent version is no longer LIVE");
        }
        manifestParser.parse(version.getManifestJson());   // fail fast on a corrupt manifest, before reserving

        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();
        try {
            walletReservationService.reserve(buyerOrgId, spendCap);
        } catch (InsufficientFundsException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        }

        boolean waitForRebuild = rebuildService.needsRebuild(version);
        Instant now = Instant.now();
        // The launcher issues the real callback token; this placeholder hash only satisfies NOT NULL until then.
        Run pending = new Run(null, connection, version, waitForRebuild ? RunStatus.PENDING_BUILD : RunStatus.PENDING,
                spendCap, BigDecimal.ZERO, null, RunTokenService.hash(runTokenService.generate()));
        pending.setReservedAmount(spendCap);
        pending.setStartedAt(now);
        pending.setDeadlineAt(waitForRebuild ? now.plus(PENDING_BUILD_TIMEOUT) : now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        Run run;
        try {
            if (waitForRebuild) {
                rebuildService.queueRebuild(version);
            }
            run = runRepository.save(pending);
        } catch (RuntimeException e) {
            walletReservationService.release(buyerOrgId, spendCap);
            throw e;
        }
        if (waitForRebuild) {
            return RunResponse.from(run);
        }
        runLauncher.launch(run.getId());
        return RunResponse.from(runRepository.findById(run.getId()).orElseThrow());
    }
```

Remove any imports that are now unused (`java.net.URI`, and possibly `java.util.HashMap`/`ArrayList`/`List`/`Map` if nothing else uses them).

- [ ] **Step 8: Resume waiting runs when a build finishes**

`backend/src/main/java/com/agentstore/platform/execution/PendingBuildRuns.java`:
```java
package com.agentstore.platform.execution;

import com.agentstore.platform.build.BuildFailureCodes;
import com.agentstore.platform.build.BuildFinishedEvent;
import com.agentstore.platform.build.BuildFinishedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Starts, or fails, the runs that waited in PENDING_BUILD for a rebuild of their version (spec §4 step 7). */
@Component
public class PendingBuildRuns implements BuildFinishedListener {

    private static final Logger log = LoggerFactory.getLogger(PendingBuildRuns.class);

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;
    private final RunLauncher runLauncher;

    public PendingBuildRuns(RunRepository runRepository, RunLifecycleService runLifecycleService, RunLauncher runLauncher) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
        this.runLauncher = runLauncher;
    }

    @Override
    public void onBuildFinished(BuildFinishedEvent event) {
        for (Run run : runRepository.findByStatusAndAgentVersionId(RunStatus.PENDING_BUILD, event.versionId())) {
            try {
                if (event.succeeded()) {
                    Instant deadline = Instant.now().plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS);
                    if (runLifecycleService.promotePendingBuild(run.getId(), deadline)) {
                        runLauncher.launch(run.getId());   // on failure the launcher has already FAILED the run
                    }
                } else {
                    String code = event.failureCode() == null ? BuildFailureCodes.BUILD_FAILED : event.failureCode();
                    runLifecycleService.terminate(run.getId(), RunStatus.PENDING_BUILD, RunStatus.FAILED,
                            RunStepKind.FAILED, Map.of("summary", "The agent could not be rebuilt for this platform",
                                    "code", code));
                }
            } catch (RuntimeException e) {
                log.warn("Could not resume run {} after build {}: {}", run.getId(), event.buildId(), e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 9: Run the new tests, then the whole suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RunLaunchFromBuildTest`
Expected: PASS.

Then: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS. The existing run, wallet, capacity and version-pinning tests must pass unchanged. They exercise the moved launch code through `RunLauncher`.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/agentstore/platform/build/RebuildService.java \
  backend/src/main/java/com/agentstore/platform/execution backend/src/test/java/com/agentstore/platform/execution
git commit -m "feat(execution): run the manifest entrypoint and rebuild on a backend switch"
```

---

### Task 11: Frontend — build status on the developer's version page, and `PENDING_BUILD` runs

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/components/StatusPill.tsx`
- Create: `frontend/src/components/BuildPanel.tsx`, `frontend/src/components/BuildPanel.test.tsx`
- Modify: `frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/ReviewStatusView.tsx` and `ReviewStatusView.test.tsx`

**Interfaces:**
- Consumes (Task 9): `AgentVersionDetailResponse.build` = `{buildId, status, failureCode, failureReason, logUrl}` or null.
- Produces:
  - `type BuildStatus` and `interface BuildSummary`.
  - `AgentVersionDetailResponse.build?: BuildSummary | null`.
  - `RunStatus` includes `"PENDING_BUILD"`.
  - `Manifest.runtime?`/`entrypoint?`.
  - `<BuildPanel build={...} />`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/BuildPanel.test.tsx`:
```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BuildPanel } from "./BuildPanel";
import type { BuildSummary } from "@/lib/types";

const failed: BuildSummary = {
  buildId: "b1",
  status: "FAILED",
  failureCode: "SECRET_FOUND",
  failureReason: "Secrets found in the bundle: config.py:3 (aws-access-token)",
  logUrl: "https://store.example/builds/b1.log?X-Amz-Signature=abc",
};

describe("BuildPanel", () => {
  it("shows the failure code, the reason and a link to the log", () => {
    render(<BuildPanel build={failed} />);
    expect(screen.getByText("SECRET_FOUND")).toBeInTheDocument();
    expect(screen.getByText(/config\.py:3/)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "View build log" });
    expect(link).toHaveAttribute("href", failed.logUrl);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noreferrer");
  });

  it("explains a running build and hides the log link until a log exists", () => {
    render(<BuildPanel build={{ buildId: "b2", status: "BUILDING", failureCode: null, failureReason: null, logUrl: null }} />);
    expect(screen.getByText(/Installing dependencies/)).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("says a succeeded build can be submitted", () => {
    render(<BuildPanel build={{ buildId: "b3", status: "SUCCEEDED", failureCode: null, failureReason: null, logUrl: null }} />);
    expect(screen.getByText(/You can submit this version/)).toBeInTheDocument();
  });
});
```

Append to `ReviewStatusView.test.tsx`, inside its `describe`:
```tsx
  it("shows the build panel when the version has a build", () => {
    render(
      <ReviewStatusView
        version={{
          ...baseVersion,
          status: "DRAFT",
          build: { buildId: "b1", status: "QUEUED", failureCode: null, failureReason: null, logUrl: null },
        }}
      />,
    );
    expect(screen.getByRole("region", { name: "Build" })).toBeInTheDocument();
    expect(screen.getByText(/Waiting for the build runner/)).toBeInTheDocument();
  });

  it("shows no build panel for versions without a build", () => {
    render(<ReviewStatusView version={baseVersion} />);
    expect(screen.queryByRole("region", { name: "Build" })).not.toBeInTheDocument();
  });
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd frontend && npx vitest run src/components/BuildPanel.test.tsx "src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status/ReviewStatusView.test.tsx"`
Expected: FAIL (`BuildPanel` doesn't exist).

- [ ] **Step 3: Update the types**

In `frontend/src/lib/types.ts`:
- Replace the `RunStatus` union with:
```ts
export type RunStatus =
  | "PENDING" | "PENDING_BUILD" | "RUNNING" | "AWAITING_APPROVAL" | "COMPLETED" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "EXPIRED";
```
- After the `VersionStatus` line, add:
```ts
export type BuildStatus = "QUEUED" | "BUILDING" | "SUCCEEDED" | "FAILED";
```
- In `interface Manifest`, add `runtime?: string | null;` and `entrypoint?: string | null;`.
- In `interface AgentVersionDetailResponse`, add `build?: BuildSummary | null;`.
- Add:
```ts
export interface BuildSummary {
  buildId: string;
  status: BuildStatus;
  failureCode: string | null;
  failureReason: string | null;
  logUrl: string | null;
}
```

- [ ] **Step 4: Implement the panel and wire it in**

`frontend/src/components/BuildPanel.tsx`:
```tsx
import { StatusPill } from "@/components/StatusPill";
import type { BuildStatus, BuildSummary } from "@/lib/types";

const EXPLANATION: Record<BuildStatus, string> = {
  QUEUED: "Waiting for the build runner.",
  BUILDING: "Installing dependencies, scanning for secrets and vulnerabilities, and testing the entrypoint.",
  SUCCEEDED: "Your code is built. You can submit this version.",
  FAILED: "The build failed. Fix the problem below and push again.",
};

export function BuildPanel({ build }: { build: BuildSummary }) {
  return (
    <section aria-label="Build" className="mt-6 rounded-lg border border-line p-4">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="font-medium">Build</h2>
        <StatusPill status={build.status} />
      </div>
      <p className="text-ink2">{EXPLANATION[build.status]}</p>
      {build.status === "FAILED" && build.failureCode && (
        <p className="mt-2 text-red">
          <span className="font-mono">{build.failureCode}</span>
          {build.failureReason ? <span>: {build.failureReason}</span> : null}
        </p>
      )}
      {build.logUrl && (
        <a className="mt-2 inline-block underline" href={build.logUrl} target="_blank" rel="noreferrer">
          View build log
        </a>
      )}
    </section>
  );
}
```

In `ReviewStatusView.tsx`, import `BuildPanel` and render it after the explanation paragraph:
```tsx
      {version.build ? <BuildPanel build={version.build} /> : null}
```

In `StatusPill.tsx`, add to `STYLES`:
```ts
  QUEUED: "bg-amberSoft text-amber",
  BUILDING: "bg-amberSoft text-amber",
  PENDING_BUILD: "bg-amberSoft text-amber",
  SUCCEEDED: "bg-greenSoft text-green",
```

- [ ] **Step 5: Run the frontend checks**

Run: `cd frontend && npx vitest run && npx tsc --noEmit`
Expected: PASS with no type errors. `useRunPolling` keeps polling `PENDING_BUILD`, because it isn't in `TERMINAL_STATUSES`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/lib/types.ts frontend/src/components/StatusPill.tsx frontend/src/components/BuildPanel.tsx \
  frontend/src/components/BuildPanel.test.tsx "frontend/src/app/(developer)/dashboard/agents/[agentId]/versions/[versionId]/status"
git commit -m "feat(frontend): show build status and log on the developer's version page"
```

---

### Task 12: Docs, the ontology, and the manual end-to-end check on E2B

**Files:**
- Modify: `runner/README.md`
- Modify: `ONTOLOGY.md` (and re-stamp it with `scripts/stamp-ontology-hash.sh`)
- Create: `scripts/dev-push-bundle.sh`

**Interfaces:**
- Consumes: everything above.
- Produces: `scripts/dev-push-bundle.sh <agentId> <bundleDir>` (reads `AGENTSTORE_TOKEN`, and optionally `AGENTSTORE_API`, from env). It's a stopgap for `agentstore push` until WS5c's CLI exists.

- [ ] **Step 1: Add the dev push script**

`scripts/dev-push-bundle.sh`:
```bash
#!/usr/bin/env bash
# Dev stopgap for `agentstore push` (WS5c): zips a directory, uploads it and prints the queued build.
# Usage: AGENTSTORE_TOKEN=<developer JWT> scripts/dev-push-bundle.sh <agentId> <bundleDir>
set -euo pipefail

AGENT_ID="${1:?agentId}"
DIR="${2:?bundle directory}"
API="${AGENTSTORE_API:-http://localhost:8080}"
: "${AGENTSTORE_TOKEN:?set AGENTSTORE_TOKEN to a developer access token}"

ZIP="$(mktemp -t agentstore-bundle).zip"
trap 'rm -f "$ZIP"' EXIT
(cd "$DIR" && zip -qr "$ZIP" . -x '.git/*' -x 'node_modules/*' -x '__pycache__/*')
SHA="$(shasum -a 256 "$ZIP" | cut -d' ' -f1)"
SIZE="$(wc -c < "$ZIP" | tr -d ' ')"

auth=(-H "Authorization: Bearer $AGENTSTORE_TOKEN" -H "Content-Type: application/json")
UPLOAD="$(curl -fsS "${auth[@]}" -d "{\"sha256\":\"$SHA\",\"sizeBytes\":$SIZE}" "$API/api/v1/agents/$AGENT_ID/bundles/uploads")"
BUNDLE_ID="$(printf '%s' "$UPLOAD" | python3 -c 'import json,sys; print(json.load(sys.stdin)["bundleId"])')"
URL="$(printf '%s' "$UPLOAD" | python3 -c 'import json,sys; print(json.load(sys.stdin)["uploadUrl"])')"
curl -fsS -X PUT --upload-file "$ZIP" "$URL" >/dev/null
curl -sS "${auth[@]}" -d "{\"sha256\":\"$SHA\"}" "$API/api/v1/agents/$AGENT_ID/bundles/$BUNDLE_ID/finalize"
echo
```
Make it executable: `chmod +x scripts/dev-push-bundle.sh`.

- [ ] **Step 2: Document builds in the runner README**

Add a `## Builds` section to `runner/README.md` covering:
1. `POST /builds` / `GET /builds/{id}`, one build at a time, with state in memory only (a restart shows as `RUNNER_RESTARTED`, and Spring re-queues up to 3 times).
2. The pipeline order (download → unpack → gitleaks → backend build) and every failure code.
3. **Prerequisite:** `gitleaks` on `PATH` (`brew install gitleaks`, or the release binary on Linux). The command can be changed with `agentstore.runner.build.gitleaks-command`.
4. The new settings: `agentstore.runner.build.*` and `agentstore.runner.e2b.build-timeout`.
5. **E2B specifics:**
   - the shim uploads the app directory from the runner;
   - template builds can't restrict network egress (an accepted gap for dev only, spec §5);
   - built templates are left in the E2B dashboard until WS5d adds cleanup.
6. How to run the tagged live build test: `E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bImageBuildLiveTest`.

Write it in the same style as the existing README sections.

- [ ] **Step 3: Update and re-stamp the ontology**

In `ONTOLOGY.md`:
- Add these concepts, in the style of the existing entries, with file paths:
  - `AgentBundle` / `BundleStatus`
  - `TemplateBuild` / `BuildStatus`
  - the failure codes
  - `ObjectStorage` (SeaweedFS through S3)
  - `BundleService` (upload/finalize)
  - `BuildDispatcher` / `BuildPoller` / `BuildTransitions`
  - `RebuildService` / `PendingBuildRuns` / `RunLauncher`
  - `RunStatus.PENDING_BUILD`
  - the manifest's `runtime`/`entrypoint` and `AgentRuntime`
  - on the runner side: `BuildService`, `IsolationBackend.buildImage`, `SecretScanner`, `BundleUnpacker`
- Add the new core files to the `ontology-tracked-files` list: at least the build entities, `BundleService.java`, `BuildTransitions.java`, `RunLauncher.java`, `ObjectStorage.java`, `runner/.../build/BuildService.java`, and `V23__bundles_and_builds.sql`.

Then run: `scripts/stamp-ontology-hash.sh && scripts/check-ontology-staleness.sh`
Expected: the check passes.

- [ ] **Step 4: Commit the docs**

```bash
git add runner/README.md ONTOLOGY.md scripts/dev-push-bundle.sh
git commit -m "docs: document the build pipeline, update the ontology, add a dev push script"
```

- [ ] **Step 5: Run every automated suite once more**

Run each and record the counts in the handoff:
- `cd runner && mvn -q test`
- `cd runner/e2b-shim && npm test`
- `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
- `cd frontend && npx vitest run && npx tsc --noEmit`

Expected: all pass.

- [ ] **Step 6: Manual end-to-end on real E2B (needs the user's `E2B_API_KEY`; ask for it — don't skip silently)**

This also covers the WS5a live checks that are still owed.

1. Start the stores:
   - `docker compose up -d seaweedfs`
   - a disposable migrated Postgres on `:5433`, as in the WS5a manual check
2. Run the WS5a conformance suite first: `cd runner && E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bBackendConformanceTest`.
3. Run `E2bImageBuildLiveTest` the same way.
4. Start the stack:
   - the runner: `RUNNER_SECRET=dev-secret E2B_API_KEY=... SPRING_BASE_URL=http://localhost:8080 mvn spring-boot:run`
   - Spring with `--spring.profiles.active=runner`, with `RUNNER_SECRET=dev-secret`, `CALLBACK_BASE_URL=<cloudflared quick-tunnel URL>` and the `:5433` datasource
5. Push the sample agent. As a developer, create an agent, then push a sample python bundle: a `manifest.json` with `runtime: python-3.12` and `entrypoint: agent/main.py`, and an `agent/main.py` that POSTs one `COMPLETE` event to `AGENT_CALLBACK_URL` with `AGENT_CALLBACK_TOKEN`. Use `scripts/dev-push-bundle.sh`.
6. Watch the build go `QUEUED → BUILDING → SUCCEEDED` through `GET /api/v1/agents/{id}/builds/{buildId}`. Open its `logUrl` and check that the log shows the pip, pip-audit and smoke-test output.
7. Push a bundle with `AWS_ACCESS_KEY_ID = "AKIAXWNVK4PQ2JT6ZM3C"` in a file. The build must fail with `SECRET_FOUND`, naming the file and line and never the key.
8. Submit the good version. As a buyer, connect and trigger a run. The run must reach `RUNNING`, the agent's callback must complete it, and Spring must record the exit.
9. Record what happened in the PR description. If anything fails, stop and report it with the output; don't paper over it.

---

## Self-review notes (for the executor)

- **Spec coverage** (spec §8, the WS5b row):
  - SeaweedFS in compose: Task 6.
  - Migration: Task 7.
  - Upload/finalize: Task 8.
  - Dispatcher/poller: Task 9.
  - Runner `/builds`: Task 5.
  - Unpack + gitleaks: Task 4.
  - `E2bBackend.buildImage` (recipe, scans, smoke test): Task 3.
  - Manifest `runtime` + `entrypoint`: Task 7.
  - `PENDING_BUILD` rebuild: Task 10.
  - Build log in the portal: Tasks 9 and 11.
  - The two WS5a must-fix items: Task 1.
- **Deliberately not here:** the CLI, API keys and the wizard change (WS5c); gVisor, baking the wrapper into images and template cleanup (WS5d).
- **Things most likely to surprise you:**
  - **SeaweedFS pre-signed URLs.** Task 6 Step 6 is the gate: if they don't work, stop.
  - **Hibernate `ddl-auto: validate` on the new columns.** A mismatch fails every Spring test at startup with a clear message naming the column.
  - **The shared test database.** Pipeline tests must call `clearActiveBuilds()` / the equivalent SQL first.
