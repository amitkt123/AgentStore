# WS5a — Sandbox Runner Implementation Plan


**Goal:** Real runs start the agent process (this fixes defect B2). A new `sandbox-runner` service creates, starts, inspects and kills sandboxes through a pluggable `IsolationBackend`, with E2B as the first backend. Spring talks to the runner instead of E2B.

**Architecture:**
- **A new standalone Spring Boot service in `runner/`.** It's the only process holding the E2B key, and it exposes an internal HTTP API (`/sandboxes`) protected by `X-Runner-Secret`.
- **Inside the runner:**
  - `SandboxService` handles `requestId` idempotency, the lifetime cap and labels.
  - `BudgetGuard` enforces the concurrency cap and the monthly sandbox-time ceiling.
  - A reaper handles deadline sweeps and a startup reconcile against Spring.
- **The vendor seam is `IsolationBackend`.**
  - `E2bBackend` uses E2B's REST API for lifecycle calls, plus a small Node shim (`runner/e2b-shim/shim.mjs`, one process per call) for the SDK-only operations: starting the agent and reading files.
  - A shared run wrapper records the agent's output in `/workspace/.run/log` and its exit code in `/workspace/.run/exit`.
- **On the Spring side:** `RunnerSandboxProvider` replaces `E2bSandboxProvider` (deleted). `SandboxSpec` and `SandboxStatus` become vendor-neutral, and the run ID becomes the `requestId`.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Maven, JUnit 5 + AssertJ + Mockito, `MockRestServiceServer`, Node 20+ (ESM), the `e2b` JS SDK, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-25-ws5-sandbox-runner-build-pipeline-cli-design.md` (§2, §3, §5, §7, §8 WS5a). The parent spec is `2026-09-23-agent-store-developer-platform-and-sandbox-design.md` (defect B2 in §2.2). Read the WS5 spec §2–§3 and §5 before starting.

## Global Constraints

- **`runner/` is a standalone Maven project**, a sibling of `backend/` (the backend isn't a multi-module build). It uses the same parent `spring-boot-starter-parent` 3.3.4, `groupId` `com.agentstore`, `artifactId` `sandbox-runner`, Java 21, and root package `com.agentstore.runner`.
- **The runner keeps no database.** Its state lives in backend labels, an in-memory map, and a small budget file.
- **Vendor-neutral names outside the backend classes:** *sandbox, imageRef, command, labels, logs, kill*. The words "E2B", "template" and "envd" may appear only in `com.agentstore.runner.backend.e2b`, `runner/e2b-shim/` and config keys under `agentstore.runner.e2b`.
- **Labels (exact keys):**
  - `agentstore.requestId`
  - `agentstore.runnerId`
  - `agentstore.deadline` (epoch seconds)
- **Wrapper paths (exact):**
  - log: `/workspace/.run/log`
  - exit code: `/workspace/.run/exit`
  - wrapper script: `/workspace/.run/agentstore-run`
- **Runner API error codes (exact):**
  - `CAPACITY_EXHAUSTED` (429): concurrency cap reached; retry later.
  - `BUDGET_EXHAUSTED` (429): monthly ceiling reached.
  - `BACKEND_ERROR` (502).
  - `UNAUTHORIZED` (401).

  The spec's §5 says every budget breach is `BUDGET_EXHAUSTED`, while §3 lists both codes. This plan uses `CAPACITY_EXHAUSTED` for the concurrency cap because it's temporary and retryable.
- **Budget defaults:** `max-concurrent: 2`, `max-lifetime: PT10M`, `monthly-limit: PT10H`.
- **Log chunk cap:** 256 KiB per `GET /sandboxes/{id}/logs` call.
- **Secrets:** env values never appear in runner logs, exception messages or HTTP error bodies. The shim receives env on **stdin only**, never in argv.
- **Spring profile names:** `runner` enables `RunnerSandboxProvider`; `FakeSandboxProvider` is active under `!runner`. The `e2b` profile and every `agentstore.e2b.*` key are removed from the backend.
- **Temporary WS5a entry command:** `agentstore.runs.agent-command` (a single shell string, default `/workspace/app/agentstore-entrypoint`) runs as `["sh","-c",<value>]`. WS5b replaces it with the manifest's `entrypoint`.
- **`IsolationBackend` has no `buildImage` in WS5a.** WS5b adds it. Don't stub it.
- **E2B tests** are tagged `@Tag("e2b")` and excluded from default `mvn test` (the same surefire `excludedGroups` pattern as `backend/pom.xml`). They run only when `E2B_API_KEY` is set.
- **Known E2B gaps accepted for dev/testing** (spec §5): template-build egress isn't restricted, and DNS filtering isn't verified. The DNS conformance test is skipped for E2B through `filtersDns() == false`.
- **Backend tests:** from `backend/`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test`, with Colima running. **Runner tests:** from `runner/`, `mvn test` (no Docker needed).
- **Out of scope:** `/builds`, MinIO, the build pipeline and manifest `runtime`/`entrypoint` (WS5b); API keys and the CLI (WS5c); gVisor (WS5d); the run queue (spec §9).

## Review Focus

1. **A retried `POST /sandboxes` with the same `requestId`** (Spring timed out and the caller retried, or the runner restarted in between) must return the same sandbox and never create a second one. [Task 2]
2. **Sandbox created but the agent fails to start** (a shim error or timeout) must kill the half-created sandbox and return 502, not leak a running VM that burns credits. [Task 6]
3. **Secrets in `env`** must not appear in exception messages, runner logs or error bodies, even when the shim fails. [Task 5, Task 6]
4. **The runner or backend can't be reached during a status check** must report `UNKNOWN`, and Spring's reaper must not fail the run on `UNKNOWN`. [Task 6, Task 8]
5. **An agent writing a large log or non-UTF-8 bytes:** log reads return at most 256 KiB per call, advance the offset by bytes read, and never return 500. [Task 1, Task 6]

---

## File Structure

```
runner/
  pom.xml                                                         (create)
  README.md                                                       (create, Task 10)
  src/main/resources/application.yml                              (create)
  src/main/java/com/agentstore/runner/
    RunnerApplication.java                                        (create: @SpringBootApplication, @EnableScheduling, @ConfigurationPropertiesScan)
    RunnerProperties.java                                         (create: typed config)
    backend/
      IsolationBackend.java                                       (create: the vendor seam)
      SandboxLaunch.java, SandboxState.java, LogChunk.java,
      OwnedSandbox.java, Labels.java, BackendException.java       (create: value types)
      e2b/
        E2bConfig.java                                            (create: wires E2B beans when backend=e2b)
        E2bRestClient.java                                        (create: REST lifecycle calls)
        ShimClient.java, ShimException.java                       (create: Node subprocess protocol)
        E2bBackend.java                                           (create: IsolationBackend over REST + shim)
    sandbox/
      SandboxService.java                                         (create: idempotency, lifetime clamp, labels)
      CreateSandboxCommand.java                                   (create: service input)
      BudgetLedger.java, BudgetGuard.java,
      BudgetExhaustedException.java                               (create)
      RunnerReaper.java, ReaperJob.java, SpringRunsClient.java    (create)
    api/
      SandboxController.java, ApiErrorHandler.java,
      RunnerSecretFilter.java, dto records                        (create)
  src/test/java/com/agentstore/runner/...                         (tests per task)
  src/test/resources/application-test.yml, shim/*.sh              (create)
  e2b-shim/
    package.json, shim.mjs, shim.test.mjs                         (create)

backend/
  src/main/resources/application.yml                              (modify: drop agentstore.e2b, add agentstore.runner + runs.agent-command)
  src/test/resources/application-test.yml                         (modify: drop agentstore.e2b)
  src/main/java/com/agentstore/platform/execution/
    SandboxSpec.java, SandboxStatus.java                          (modify)
    FakeSandboxProvider.java, RunReaper.java, RunController.java  (modify)
    RunnerSandboxProvider.java, SandboxCapacityException.java     (create)
    InternalRunsController.java                                   (create)
    E2bSandboxProvider.java                                       (delete)
  src/test/java/com/agentstore/platform/execution/
    E2bSandboxProviderTest.java, E2bSandboxProviderLiveTest.java  (delete)
    RunnerSandboxProviderTest.java, InternalRunsControllerTest.java (create)
    RunControllerTest.java, RunReaperTest.java, RunVersionPinningTest.java (modify)
```

---

### Task 1: Runner project, backend seam, `FakeBackend` and the conformance tests

**Files:**
- Create: `runner/pom.xml`, `runner/src/main/resources/application.yml`, `runner/src/test/resources/application-test.yml`
- Create: `runner/src/main/java/com/agentstore/runner/RunnerApplication.java`, `RunnerProperties.java`
- Create: `runner/src/main/java/com/agentstore/runner/backend/{IsolationBackend,SandboxLaunch,SandboxState,LogChunk,OwnedSandbox,Labels,BackendException}.java`
- Test: `runner/src/test/java/com/agentstore/runner/backend/{FakeBackend,IsolationBackendConformanceTest,FakeBackendConformanceTest}.java`

**Interfaces:**
- Produces: everything in `com.agentstore.runner.backend` exactly as shown below; `RunnerProperties` (record); `FakeBackend` (test source, used by Tasks 2–4); `IsolationBackendConformanceTest` (abstract, extended by Task 7).

- [ ] **Step 1: Create `runner/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>
  <groupId>com.agentstore</groupId>
  <artifactId>sandbox-runner</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>sandbox-runner</name>

  <properties>
    <java.version>21</java.version>
    <surefire.excludedGroups>e2b</surefire.excludedGroups>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <excludedGroups>${surefire.excludedGroups}</excludedGroups>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create the value types and the interface**

`backend/IsolationBackend.java`:
```java
package com.agentstore.runner.backend;

import java.util.List;

/**
 * The only vendor seam in the runner. Implementations must pass {@code IsolationBackendConformanceTest}.
 * Vendor vocabulary (templates, envd, ...) stays inside implementations.
 */
public interface IsolationBackend {

    String name();

    /** Creates the sandbox and starts {@code launch.command()} through the run wrapper. Returns the sandbox id. */
    String create(SandboxLaunch launch);

    /** Never throws for "not found": that is {@link SandboxState#gone()}. Returns {@link SandboxState#unknown()} when the
     * backend cannot be asked. */
    SandboxState status(String sandboxId);

    /** At most {@code maxBytes} of combined stdout/stderr starting at byte {@code offset}. Empty chunk (same offset) when
     * nothing new or the sandbox is gone. */
    LogChunk readLogs(String sandboxId, long offset, int maxBytes);

    /** Idempotent: killing an unknown or already-gone sandbox is not an error. */
    void kill(String sandboxId);

    /** Live sandboxes carrying this runner's {@link Labels#RUNNER_ID} label. */
    List<OwnedSandbox> listOwned();
}
```

`backend/SandboxLaunch.java`:
```java
package com.agentstore.runner.backend;

import java.util.List;
import java.util.Map;

public record SandboxLaunch(String imageRef, List<String> command, Map<String, String> env, List<String> allowOut,
                            int cpu, int memoryMb, int timeoutSeconds, Map<String, String> labels) {

    public SandboxLaunch {
        command = List.copyOf(command);
        env = Map.copyOf(env);
        allowOut = List.copyOf(allowOut);
        labels = Map.copyOf(labels);
    }

    /** Never print env values. */
    @Override
    public String toString() {
        return "SandboxLaunch[imageRef=" + imageRef + ", command=" + command + ", envKeys=" + env.keySet()
                + ", allowOut=" + allowOut + ", timeoutSeconds=" + timeoutSeconds + ", labels=" + labels + "]";
    }
}
```

`backend/SandboxState.java`:
```java
package com.agentstore.runner.backend;

public record SandboxState(Phase phase, Integer exitCode) {

    public enum Phase { RUNNING, EXITED, GONE, UNKNOWN }

    public static SandboxState running() { return new SandboxState(Phase.RUNNING, null); }
    public static SandboxState exited(int exitCode) { return new SandboxState(Phase.EXITED, exitCode); }
    public static SandboxState gone() { return new SandboxState(Phase.GONE, null); }
    public static SandboxState unknown() { return new SandboxState(Phase.UNKNOWN, null); }

    public boolean isTerminal() { return phase == Phase.EXITED || phase == Phase.GONE; }
}
```

`backend/LogChunk.java`:
```java
package com.agentstore.runner.backend;

/** {@code data} is UTF-8 decoded with replacement; {@code nextOffset} advances by bytes read, not characters. */
public record LogChunk(String data, long nextOffset) {
    public static LogChunk empty(long offset) { return new LogChunk("", offset); }
}
```

`backend/OwnedSandbox.java`:
```java
package com.agentstore.runner.backend;

import java.util.Map;

public record OwnedSandbox(String sandboxId, Map<String, String> labels) {
    public String requestId() { return labels.get(Labels.REQUEST_ID); }
    public Long deadlineEpochSeconds() {
        String v = labels.get(Labels.DEADLINE);
        return v == null ? null : Long.valueOf(v);
    }
}
```

`backend/Labels.java`:
```java
package com.agentstore.runner.backend;

public final class Labels {
    public static final String REQUEST_ID = "agentstore.requestId";
    public static final String RUNNER_ID = "agentstore.runnerId";
    public static final String DEADLINE = "agentstore.deadline";

    private Labels() {}
}
```

`backend/BackendException.java`:
```java
package com.agentstore.runner.backend;

/** Messages must never contain env values or other request secrets. */
public class BackendException extends RuntimeException {
    public BackendException(String message) { super(message); }
    public BackendException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: Create the application class, properties and config**

`RunnerApplication.java`:
```java
package com.agentstore.runner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RunnerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RunnerApplication.class, args);
    }
}
```

`RunnerProperties.java`:
```java
package com.agentstore.runner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("agentstore.runner")
public record RunnerProperties(@NotBlank String secret, @NotBlank String runnerId, @NotBlank String backend,
                               @NotBlank String springBaseUrl, @Valid @NotNull Budget budget, E2b e2b) {

    public record Budget(@Min(1) int maxConcurrent, @NotNull Duration maxLifetime, @NotNull Duration monthlyLimit,
                         @NotNull Path stateFile) {}

    public record E2b(String apiKey, String baseUrl, List<String> shimCommand, Duration shimTimeout,
                      Duration httpTimeout) {}
}
```

`src/main/resources/application.yml`:
```yaml
server:
  port: ${RUNNER_PORT:8090}

spring:
  application:
    name: sandbox-runner

agentstore:
  runner:
    secret: ${RUNNER_SECRET:}          # required; startup fails when blank
    runner-id: ${RUNNER_ID:runner-1}
    backend: ${RUNNER_BACKEND:e2b}
    spring-base-url: ${SPRING_BASE_URL:http://localhost:8080}
    jobs-enabled: true
    budget:
      max-concurrent: 2
      max-lifetime: PT10M
      monthly-limit: PT10H
      state-file: ${RUNNER_STATE_FILE:./runner-budget.json}
    e2b:
      api-key: ${E2B_API_KEY:}
      base-url: https://api.e2b.app
      shim-command: [node, e2b-shim/shim.mjs]
      shim-timeout: PT60S
      http-timeout: PT30S

management:
  endpoints:
    web:
      exposure:
        include: health
```

`jobs-enabled` isn't in `RunnerProperties`; `ReaperJob` (Task 4) reads it with `@ConditionalOnProperty`.

`src/test/resources/application-test.yml`:
```yaml
agentstore:
  runner:
    secret: test-runner-secret
    runner-id: runner-test
    backend: fake
    spring-base-url: http://localhost:1
    jobs-enabled: false
    budget:
      state-file: ${java.io.tmpdir}/runner-budget-test.json
```

- [ ] **Step 4: Write `FakeBackend` (test source)**

It runs commands as **local processes with no isolation**. It exists so the lifecycle conformance tests run on every build without E2B. It must never be used outside tests.

`src/test/java/com/agentstore/runner/backend/FakeBackend.java`:
```java
package com.agentstore.runner.backend;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only backend: runs the command as a local process. No isolation, no egress control. */
public class FakeBackend implements IsolationBackend {

    private record Box(Process process, Path log, Map<String, String> labels) {}

    private final String runnerId;
    private final Map<String, Box> boxes = new ConcurrentHashMap<>();
    private final Map<String, Integer> exitCodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fake-backend-timer");
        t.setDaemon(true);
        return t;
    });
    public final AtomicInteger createCalls = new AtomicInteger();
    private volatile RuntimeException failNextCreate;

    public FakeBackend(String runnerId) {
        this.runnerId = runnerId;
    }

    public void failNextCreate(RuntimeException e) {
        this.failNextCreate = e;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String create(SandboxLaunch launch) {
        createCalls.incrementAndGet();
        RuntimeException fail = failNextCreate;
        if (fail != null) {
            failNextCreate = null;
            throw fail;
        }
        String id = "fake-" + UUID.randomUUID();
        try {
            Path log = Files.createTempFile("fake-sandbox-", ".log");
            ProcessBuilder pb = new ProcessBuilder(launch.command())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            pb.environment().clear();
            pb.environment().put("PATH", System.getenv("PATH"));
            pb.environment().putAll(launch.env());
            Process process = pb.start();
            boxes.put(id, new Box(process, log, launch.labels()));
            process.onExit().thenAccept(p -> {
                if (boxes.containsKey(id)) {
                    exitCodes.put(id, p.exitValue());
                }
            });
            timer.schedule(() -> kill(id), launch.timeoutSeconds(), TimeUnit.SECONDS);
            return id;
        } catch (IOException e) {
            throw new BackendException("fake create failed", e);
        }
    }

    @Override
    public SandboxState status(String sandboxId) {
        Box box = boxes.get(sandboxId);
        if (box == null) {
            return SandboxState.gone();
        }
        Integer code = exitCodes.get(sandboxId);
        return code == null ? SandboxState.running() : SandboxState.exited(code);
    }

    @Override
    public LogChunk readLogs(String sandboxId, long offset, int maxBytes) {
        Box box = boxes.get(sandboxId);
        if (box == null) {
            return LogChunk.empty(offset);
        }
        try (RandomAccessFile file = new RandomAccessFile(box.log().toFile(), "r")) {
            long available = Math.max(0, file.length() - offset);
            int n = (int) Math.min(available, maxBytes);
            byte[] bytes = new byte[n];
            file.seek(offset);
            file.readFully(bytes);
            return new LogChunk(new String(bytes, StandardCharsets.UTF_8), offset + n);
        } catch (IOException e) {
            throw new BackendException("fake readLogs failed", e);
        }
    }

    @Override
    public void kill(String sandboxId) {
        Box box = boxes.remove(sandboxId);
        exitCodes.remove(sandboxId);
        if (box != null) {
            box.process().destroyForcibly();
        }
    }

    @Override
    public List<OwnedSandbox> listOwned() {
        List<OwnedSandbox> owned = new ArrayList<>();
        boxes.forEach((id, box) -> {
            if (runnerId.equals(box.labels().get(Labels.RUNNER_ID))) {
                owned.add(new OwnedSandbox(id, box.labels()));
            }
        });
        return owned;
    }
}
```

- [ ] **Step 5: Write the abstract conformance tests**

`src/test/java/com/agentstore/runner/backend/IsolationBackendConformanceTest.java`:
```java
package com.agentstore.runner.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What every IsolationBackend must do. Switching vendors = a new subclass that passes this suite.
 * Commands use only POSIX sh (+ curl for egress tests).
 */
public abstract class IsolationBackendConformanceTest {

    protected static final String RUNNER_ID = "runner-conformance";

    protected abstract IsolationBackend backend();

    protected abstract String imageRef();

    /** True when the backend enforces allowOut (FakeBackend does not). */
    protected boolean enforcesEgress() { return false; }

    /** True when DNS lookups of non-allowlisted names are blocked. E2B: not verified (accepted gap, spec §5). */
    protected boolean filtersDns() { return false; }

    protected String allowedHost() { return "example.com"; }

    protected Duration terminalWait() { return Duration.ofSeconds(60); }

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void killEverythingCreated() {
        for (String id : created) {
            try {
                backend().kill(id);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        created.clear();
    }

    protected String launch(String shellCommand, Map<String, String> env, List<String> allowOut, int timeoutSeconds) {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> labels = Map.of(
                Labels.REQUEST_ID, requestId,
                Labels.RUNNER_ID, RUNNER_ID,
                Labels.DEADLINE, String.valueOf(Instant.now().plusSeconds(timeoutSeconds).getEpochSecond()));
        String id = backend().create(new SandboxLaunch(imageRef(), List.of("sh", "-c", shellCommand), env, allowOut,
                1, 512, timeoutSeconds, labels));
        created.add(id);
        return id;
    }

    protected String launch(String shellCommand) {
        return launch(shellCommand, Map.of(), List.of(), 120);
    }

    protected SandboxState awaitTerminal(String id) {
        Instant until = Instant.now().plus(terminalWait());
        SandboxState state = backend().status(id);
        while (!state.isTerminal() && Instant.now().isBefore(until)) {
            sleep(500);
            state = backend().status(id);
        }
        return state;
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reportsTheExitCodeOfTheCommand() {
        String id = launch("exit 3");
        assertThat(awaitTerminal(id)).isEqualTo(SandboxState.exited(3));
    }

    @Test
    void successfulCommandExitsZero() {
        String id = launch("true");
        assertThat(awaitTerminal(id)).isEqualTo(SandboxState.exited(0));
    }

    @Test
    void logsAreTailedByByteOffset() {
        String id = launch("echo one; echo two");
        awaitTerminal(id);

        LogChunk first = backend().readLogs(id, 0, 256 * 1024);
        assertThat(first.data()).isEqualTo("one\ntwo\n");
        assertThat(first.nextOffset()).isEqualTo(8);

        LogChunk rest = backend().readLogs(id, first.nextOffset(), 256 * 1024);
        assertThat(rest.data()).isEmpty();
        assertThat(rest.nextOffset()).isEqualTo(8);
    }

    @Test
    void logReadsAreCappedAtMaxBytes() {
        String id = launch("i=0; while [ $i -lt 2000 ]; do echo 0123456789; i=$((i+1)); done");
        awaitTerminal(id);

        LogChunk chunk = backend().readLogs(id, 0, 1000);
        assertThat(chunk.nextOffset()).isEqualTo(1000);
        assertThat(chunk.data()).hasSize(1000);
    }

    @Test
    void envReachesTheProcessAndDoesNotLeakIntoTheNextSandbox() {
        String withEnv = launch("test \"$FOO\" = bar", Map.of("FOO", "bar"), List.of(), 120);
        assertThat(awaitTerminal(withEnv)).isEqualTo(SandboxState.exited(0));

        String withoutEnv = launch("test -z \"$FOO\"");
        assertThat(awaitTerminal(withoutEnv)).isEqualTo(SandboxState.exited(0));
    }

    @Test
    void killIsIdempotentAndLeavesTheSandboxGone() {
        String id = launch("sleep 300");
        backend().kill(id);
        backend().kill(id);
        assertThat(awaitTerminal(id).phase()).isEqualTo(SandboxState.Phase.GONE);
    }

    @Test
    void killingAnUnknownSandboxDoesNotThrow() {
        assertThatCode(() -> backend().kill("does-not-exist-" + UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void statusOfAnUnknownSandboxIsGone() {
        assertThat(backend().status("does-not-exist-" + UUID.randomUUID())).isEqualTo(SandboxState.gone());
    }

    @Test
    void timeoutIsEnforced() {
        String id = launch("sleep 300", Map.of(), List.of(), 5);
        assertThat(awaitTerminal(id).isTerminal()).isTrue();
    }

    @Test
    void listOwnedCarriesLabelsAndDropsKilledSandboxes() {
        String id = launch("sleep 300");
        assertThat(backend().listOwned())
                .anySatisfy(o -> {
                    assertThat(o.sandboxId()).isEqualTo(id);
                    assertThat(o.labels()).containsEntry(Labels.RUNNER_ID, RUNNER_ID).containsKey(Labels.REQUEST_ID);
                });

        backend().kill(id);
        Instant until = Instant.now().plus(terminalWait());
        while (backend().listOwned().stream().anyMatch(o -> o.sandboxId().equals(id)) && Instant.now().isBefore(until)) {
            sleep(500);
        }
        assertThat(backend().listOwned()).noneMatch(o -> o.sandboxId().equals(id));
    }

    // ---- egress (skipped unless the backend enforces it) ----

    private SandboxState curl(String url) {
        String id = launch("curl -sS -m 10 -o /dev/null " + url, Map.of(), List.of(allowedHost()), 120);
        return awaitTerminal(id);
    }

    @Test
    void allowlistedHostIsReachable() {
        assumeTrue(enforcesEgress());
        assertThat(curl("https://" + allowedHost())).isEqualTo(SandboxState.exited(0));
    }

    @Test
    void unlistedHostIsBlocked() {
        assumeTrue(enforcesEgress());
        assertThat(curl("https://www.wikipedia.org").exitCode()).isNotZero();
    }

    @Test
    void rawIpIsBlocked() {
        assumeTrue(enforcesEgress());
        assertThat(curl("http://1.1.1.1").exitCode()).isNotZero();
    }

    @Test
    void cloudMetadataAddressIsBlocked() {
        assumeTrue(enforcesEgress());
        assertThat(curl("http://169.254.169.254/").exitCode()).isNotZero();
    }

    @Test
    void dnsForUnlistedNamesDoesNotResolve() {
        assumeTrue(filtersDns());
        String id = launch("getent hosts www.wikipedia.org", Map.of(), List.of(allowedHost()), 120);
        assertThat(awaitTerminal(id).exitCode()).isNotZero();
    }
}
```

`src/test/java/com/agentstore/runner/backend/FakeBackendConformanceTest.java`:
```java
package com.agentstore.runner.backend;

import java.time.Duration;

class FakeBackendConformanceTest extends IsolationBackendConformanceTest {

    private final FakeBackend backend = new FakeBackend(RUNNER_ID);

    @Override
    protected IsolationBackend backend() { return backend; }

    @Override
    protected String imageRef() { return "fake"; }

    @Override
    protected Duration terminalWait() { return Duration.ofSeconds(15); }
}
```

- [ ] **Step 6: Run the conformance tests**

Run: `cd runner && mvn -q test -Dtest=FakeBackendConformanceTest`
Expected: PASS for the lifecycle tests; the five egress/DNS tests are reported as skipped. If `timeoutIsEnforced` fails, check that `FakeBackend.create` schedules `kill(id)` at `timeoutSeconds`.

- [ ] **Step 7: Commit**

```bash
git add runner/pom.xml runner/src
git commit -m "feat(runner): add sandbox-runner project with IsolationBackend seam and conformance suite"
```

---

### Task 2: `SandboxService` (idempotency, lifetime clamp, labels)

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/sandbox/{SandboxService,CreateSandboxCommand}.java`
- Create (minimal, finished in Task 3): `runner/src/main/java/com/agentstore/runner/sandbox/{BudgetGuard,BudgetExhaustedException}.java`
- Test: `runner/src/test/java/com/agentstore/runner/sandbox/SandboxServiceTest.java`

**Interfaces:**
- Consumes: `IsolationBackend`, `SandboxLaunch`, `Labels`, `FakeBackend` (Task 1).
- Produces:
  - `record CreateSandboxCommand(String requestId, String imageRef, List<String> command, Map<String,String> env, List<String> allowOut, int cpu, int memoryMb, int timeoutSeconds)`
  - `SandboxService(IsolationBackend backend, BudgetGuard budget, String runnerId, Duration maxLifetime, Clock clock)` with:
    - `String create(CreateSandboxCommand)`
    - `SandboxState status(String)`
    - `LogChunk logs(String id, long offset)` (cap `SandboxService.MAX_LOG_CHUNK = 256 * 1024`)
    - `boolean isFinished(String id, LogChunk chunk)`
    - `void kill(String)`
  - `BudgetGuard` methods used here:
    - `void admit(int ownedNow)`, which throws `BudgetExhaustedException`
    - `void opened(String sandboxId, Instant start, int timeoutSeconds)`
    - `void closed(String sandboxId, Instant at)`
  - `BudgetExhaustedException(String code, String message)` with `code()`.

- [ ] **Step 1: Create a minimal `BudgetGuard` so the service compiles** (Task 3 replaces the body and adds the ledger)

`sandbox/BudgetExhaustedException.java`:
```java
package com.agentstore.runner.sandbox;

public class BudgetExhaustedException extends RuntimeException {
    public static final String CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED";
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    private final String code;

    public BudgetExhaustedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
```

`sandbox/BudgetGuard.java` (temporary; Task 3 replaces it):
```java
package com.agentstore.runner.sandbox;

import java.time.Instant;

public class BudgetGuard {
    private final int maxConcurrent;

    public BudgetGuard(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public void admit(int ownedNow) {
        if (ownedNow >= maxConcurrent) {
            throw new BudgetExhaustedException(BudgetExhaustedException.CAPACITY_EXHAUSTED,
                    "All " + maxConcurrent + " sandbox slots are in use");
        }
    }

    public void opened(String sandboxId, Instant start, int timeoutSeconds) {}

    public void closed(String sandboxId, Instant at) {}
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/agentstore/runner/sandbox/SandboxServiceTest.java`:
```java
package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.FakeBackend;
import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.OwnedSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxServiceTest {

    static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final FakeBackend backend = new FakeBackend("runner-a");
    SandboxService service = new SandboxService(backend, new BudgetGuard(2), "runner-a", Duration.ofMinutes(10), clock);

    @AfterEach
    void cleanup() {
        backend.listOwned().forEach(o -> backend.kill(o.sandboxId()));
    }

    static CreateSandboxCommand cmd(String requestId, int timeoutSeconds) {
        return new CreateSandboxCommand(requestId, "fake", List.of("sh", "-c", "sleep 60"), Map.of("FOO", "bar"),
                List.of("example.com"), 1, 512, timeoutSeconds);
    }

    @Test
    void sameRequestIdReturnsTheSameSandboxWithoutCreatingAnother() {
        String first = service.create(cmd("run-1", 60));
        String second = service.create(cmd("run-1", 60));

        assertThat(second).isEqualTo(first);
        assertThat(backend.createCalls.get()).isEqualTo(1);
    }

    @Test
    void requestIdIsFoundFromBackendLabelsAfterARunnerRestart() {
        String first = service.create(cmd("run-2", 60));
        SandboxService restarted = new SandboxService(backend, new BudgetGuard(2), "runner-a",
                Duration.ofMinutes(10), clock);

        assertThat(restarted.create(cmd("run-2", 60))).isEqualTo(first);
        assertThat(backend.createCalls.get()).isEqualTo(1);
    }

    @Test
    void labelsCarryRequestRunnerAndClampedDeadline() {
        String id = service.create(cmd("run-3", 3600));

        OwnedSandbox owned = backend.listOwned().stream().filter(o -> o.sandboxId().equals(id)).findFirst().orElseThrow();
        assertThat(owned.labels())
                .containsEntry(Labels.REQUEST_ID, "run-3")
                .containsEntry(Labels.RUNNER_ID, "runner-a")
                .containsEntry(Labels.DEADLINE, String.valueOf(NOW.plusSeconds(600).getEpochSecond()));
    }

    @Test
    void concurrencyCapRejectsWithCapacityExhausted() {
        service.create(cmd("run-4", 60));
        service.create(cmd("run-5", 60));

        assertThatThrownBy(() -> service.create(cmd("run-6", 60)))
                .isInstanceOf(BudgetExhaustedException.class)
                .extracting(e -> ((BudgetExhaustedException) e).code())
                .isEqualTo(BudgetExhaustedException.CAPACITY_EXHAUSTED);
    }

    @Test
    void retryOfAnExistingRequestIsAllowedEvenAtTheCap() {
        service.create(cmd("run-7", 60));
        service.create(cmd("run-8", 60));

        assertThat(service.create(cmd("run-7", 60))).isNotBlank();
    }

    @Test
    void killedRequestIdCanBeRecreated() {
        String first = service.create(cmd("run-9", 60));
        service.kill(first);

        String second = service.create(cmd("run-9", 60));
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void logsAreFinishedOnlyWhenTerminalAndDrained() {
        String id = service.create(new CreateSandboxCommand("run-10", "fake", List.of("sh", "-c", "echo hi"),
                Map.of(), List.of(), 1, 512, 60));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!service.status(id).isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        var chunk = service.logs(id, 0);
        assertThat(chunk.data()).isEqualTo("hi\n");
        assertThat(service.isFinished(id, chunk)).isFalse();

        var tail = service.logs(id, chunk.nextOffset());
        assertThat(service.isFinished(id, tail)).isTrue();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd runner && mvn -q test -Dtest=SandboxServiceTest`
Expected: compilation FAILURE (`SandboxService` and `CreateSandboxCommand` don't exist).

- [ ] **Step 4: Implement**

`sandbox/CreateSandboxCommand.java`:
```java
package com.agentstore.runner.sandbox;

import java.util.List;
import java.util.Map;

public record CreateSandboxCommand(String requestId, String imageRef, List<String> command, Map<String, String> env,
                                   List<String> allowOut, int cpu, int memoryMb, int timeoutSeconds) {
    @Override
    public String toString() {
        return "CreateSandboxCommand[requestId=" + requestId + ", imageRef=" + imageRef + ", envKeys="
                + (env == null ? "[]" : env.keySet()) + "]";
    }
}
```

`sandbox/SandboxService.java`:
```java
package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.LogChunk;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runner-side sandbox lifecycle: idempotent create keyed by requestId, lifetime clamp, labels, budget accounting. */
public class SandboxService {

    public static final int MAX_LOG_CHUNK = 256 * 1024;

    private final IsolationBackend backend;
    private final BudgetGuard budget;
    private final String runnerId;
    private final Duration maxLifetime;
    private final Clock clock;
    private final Map<String, String> sandboxByRequest = new ConcurrentHashMap<>();

    public SandboxService(IsolationBackend backend, BudgetGuard budget, String runnerId, Duration maxLifetime,
                          Clock clock) {
        this.backend = backend;
        this.budget = budget;
        this.runnerId = runnerId;
        this.maxLifetime = maxLifetime;
        this.clock = clock;
    }

    /** Serialized: one runner, low volume; keeps the "look up then create" step race-free. */
    public synchronized String create(CreateSandboxCommand cmd) {
        String cached = sandboxByRequest.get(cmd.requestId());
        if (cached != null) {
            return cached;
        }
        List<OwnedSandbox> owned = backend.listOwned();
        Optional<OwnedSandbox> existing = owned.stream()
                .filter(o -> cmd.requestId().equals(o.requestId()))
                .findFirst();
        if (existing.isPresent()) {
            sandboxByRequest.put(cmd.requestId(), existing.get().sandboxId());
            return existing.get().sandboxId();
        }

        budget.admit(owned.size());

        Instant now = clock.instant();
        int timeout = (int) Math.min(cmd.timeoutSeconds(), maxLifetime.toSeconds());
        Map<String, String> labels = Map.of(
                Labels.REQUEST_ID, cmd.requestId(),
                Labels.RUNNER_ID, runnerId,
                Labels.DEADLINE, String.valueOf(now.plusSeconds(timeout).getEpochSecond()));
        String sandboxId = backend.create(new SandboxLaunch(cmd.imageRef(), cmd.command(),
                cmd.env() == null ? Map.of() : cmd.env(),
                cmd.allowOut() == null ? List.of() : cmd.allowOut(),
                cmd.cpu(), cmd.memoryMb(), timeout, labels));
        budget.opened(sandboxId, now, timeout);
        sandboxByRequest.put(cmd.requestId(), sandboxId);
        return sandboxId;
    }

    public SandboxState status(String sandboxId) {
        return backend.status(sandboxId);
    }

    public LogChunk logs(String sandboxId, long offset) {
        return backend.readLogs(sandboxId, Math.max(0, offset), MAX_LOG_CHUNK);
    }

    /** Callers stop tailing when this is true: the sandbox is terminal and the chunk brought nothing new. */
    public boolean isFinished(String sandboxId, LogChunk chunk) {
        return chunk.data().isEmpty() && backend.status(sandboxId).isTerminal();
    }

    public void kill(String sandboxId) {
        backend.kill(sandboxId);
        sandboxByRequest.values().removeIf(sandboxId::equals);
        budget.closed(sandboxId, clock.instant());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd runner && mvn -q test -Dtest=SandboxServiceTest`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add runner/src
git commit -m "feat(runner): add SandboxService with requestId idempotency and lifetime clamp"
```

---

### Task 3: Budget guard with a persistent monthly ledger

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/sandbox/BudgetLedger.java`
- Modify (replace entirely): `runner/src/main/java/com/agentstore/runner/sandbox/BudgetGuard.java`
- Modify: `runner/src/test/java/com/agentstore/runner/sandbox/SandboxServiceTest.java` (constructor calls only)
- Test: `runner/src/test/java/com/agentstore/runner/sandbox/BudgetGuardTest.java`

**Interfaces:**
- Consumes: `BudgetExhaustedException` (Task 2).
- Produces:
  - `BudgetLedger` (record-like state plus static `load(Path)` and `save(Path)`).
  - `BudgetGuard(int maxConcurrent, Duration monthlyLimit, Path stateFile, Clock clock)` with:
    - `admit(int)`, `opened(...)`, `closed(...)` (same signatures as Task 2)
    - `void reconcile(Set<String> liveSandboxIds)`, which closes open entries whose sandbox no longer exists
    - `Duration usedThisMonth()`

**Rules:**
- **Usage** = closed seconds this month + Σ over open entries of min(now − start, timeout).
- **Month rollover** (`YearMonth` in UTC): closed seconds reset to 0. Open entries are kept, but only the time spent after the month started counts toward the new month.
- **Reading the file:** a missing file starts at zero. A corrupt file logs a warning **without the file contents** and also starts at zero.
- **Writing the file:** write to a temp file, then atomically move it into place.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/agentstore/runner/sandbox/BudgetGuardTest.java`:
```java
package com.agentstore.runner.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetGuardTest {

    @TempDir
    Path dir;

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };

    BudgetGuard guard(Duration monthlyLimit) {
        return new BudgetGuard(2, monthlyLimit, dir.resolve("budget.json"), clock);
    }

    void advance(Duration d) { now.set(now.get().plus(d)); }

    @Test
    void concurrencyCapIsCapacityExhausted() {
        assertThatThrownBy(() -> guard(Duration.ofHours(10)).admit(2))
                .isInstanceOfSatisfying(BudgetExhaustedException.class,
                        e -> assertThat(e.code()).isEqualTo(BudgetExhaustedException.CAPACITY_EXHAUSTED));
    }

    @Test
    void closedSandboxChargesActualElapsedTime() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(90));
        g.closed("sbx-1", now.get());

        assertThat(g.usedThisMonth()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void openSandboxChargesAtMostItsTimeout() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 60);
        advance(Duration.ofMinutes(30));

        assertThat(g.usedThisMonth()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void monthlyCeilingIsBudgetExhausted() {
        BudgetGuard g = guard(Duration.ofMinutes(1));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(61));
        g.closed("sbx-1", now.get());

        assertThatThrownBy(() -> g.admit(0))
                .isInstanceOfSatisfying(BudgetExhaustedException.class,
                        e -> assertThat(e.code()).isEqualTo(BudgetExhaustedException.BUDGET_EXHAUSTED));
    }

    @Test
    void usageSurvivesARestart() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(100));
        g.closed("sbx-1", now.get());
        g.opened("sbx-2", now.get(), 600);
        advance(Duration.ofSeconds(20));

        BudgetGuard restarted = guard(Duration.ofHours(10));
        assertThat(restarted.usedThisMonth()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void reconcileClosesEntriesWhoseSandboxIsGone() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-live", now.get(), 600);
        g.opened("sbx-dead", now.get(), 600);
        advance(Duration.ofSeconds(30));

        g.reconcile(Set.of("sbx-live"));
        advance(Duration.ofSeconds(30));

        // dead: 30s closed; live: 60s open
        assertThat(g.usedThisMonth()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void newMonthResetsClosedUsage() {
        BudgetGuard g = guard(Duration.ofHours(10));
        g.opened("sbx-1", now.get(), 600);
        advance(Duration.ofSeconds(100));
        g.closed("sbx-1", now.get());

        now.set(Instant.parse("2026-10-01T00:00:05Z"));
        assertThat(g.usedThisMonth()).isEqualTo(Duration.ZERO);
    }

    @Test
    void corruptStateFileStartsFromZeroAndDoesNotCrash() throws Exception {
        Files.writeString(dir.resolve("budget.json"), "{not json");
        assertThatCode(() -> guard(Duration.ofHours(10)).admit(0)).doesNotThrowAnyException();
        assertThat(guard(Duration.ofHours(10)).usedThisMonth()).isEqualTo(Duration.ZERO);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd runner && mvn -q test -Dtest=BudgetGuardTest`
Expected: compilation FAILURE (the 4-argument constructor, `usedThisMonth` and `reconcile` don't exist).

- [ ] **Step 3: Implement**

`sandbox/BudgetLedger.java`:
```java
package com.agentstore.runner.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Persistent budget state. Losing it only resets the count (spec §5). */
public class BudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(BudgetLedger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    public record Open(Instant startedAt, int timeoutSeconds) {}

    public String month;
    public long closedSeconds;
    public Map<String, Open> open = new HashMap<>();

    public static BudgetLedger load(Path file) {
        if (!Files.exists(file)) {
            return new BudgetLedger();
        }
        try {
            BudgetLedger ledger = MAPPER.readValue(file.toFile(), BudgetLedger.class);
            if (ledger.open == null) {
                ledger.open = new HashMap<>();
            }
            return ledger;
        } catch (IOException e) {
            log.warn("Budget state file {} is unreadable; starting from zero", file);
            return new BudgetLedger();
        }
    }

    public void save(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "budget-", ".tmp");
            MAPPER.writeValue(tmp.toFile(), this);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not persist budget state to {}", file, e);
        }
    }
}
```

`jackson-datatype-jsr310` comes with `spring-boot-starter-web`, so no new dependency is needed.

`sandbox/BudgetGuard.java` (replace the Task 2 version entirely):
```java
package com.agentstore.runner.sandbox;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Set;

/** Protects the E2B credit (spec §5): concurrency cap + monthly sandbox-time ceiling. */
public class BudgetGuard {

    private final int maxConcurrent;
    private final Duration monthlyLimit;
    private final Path stateFile;
    private final Clock clock;
    private final BudgetLedger ledger;

    public BudgetGuard(int maxConcurrent, Duration monthlyLimit, Path stateFile, Clock clock) {
        this.maxConcurrent = maxConcurrent;
        this.monthlyLimit = monthlyLimit;
        this.stateFile = stateFile;
        this.clock = clock;
        this.ledger = BudgetLedger.load(stateFile);
    }

    public synchronized void admit(int ownedNow) {
        if (ownedNow >= maxConcurrent) {
            throw new BudgetExhaustedException(BudgetExhaustedException.CAPACITY_EXHAUSTED,
                    "All " + maxConcurrent + " sandbox slots are in use");
        }
        if (usedThisMonth().compareTo(monthlyLimit) >= 0) {
            throw new BudgetExhaustedException(BudgetExhaustedException.BUDGET_EXHAUSTED,
                    "Monthly sandbox time limit of " + monthlyLimit + " reached");
        }
    }

    public synchronized void opened(String sandboxId, Instant start, int timeoutSeconds) {
        rollMonth();
        ledger.open.put(sandboxId, new BudgetLedger.Open(start, timeoutSeconds));
        ledger.save(stateFile);
    }

    public synchronized void closed(String sandboxId, Instant at) {
        rollMonth();
        BudgetLedger.Open entry = ledger.open.remove(sandboxId);
        if (entry != null) {
            ledger.closedSeconds += chargeable(entry, at);
            ledger.save(stateFile);
        }
    }

    public synchronized void reconcile(Set<String> liveSandboxIds) {
        Instant now = clock.instant();
        for (String id : Set.copyOf(ledger.open.keySet())) {
            if (!liveSandboxIds.contains(id)) {
                closed(id, now);
            }
        }
    }

    public synchronized Duration usedThisMonth() {
        rollMonth();
        Instant now = clock.instant();
        long open = ledger.open.values().stream().mapToLong(e -> chargeable(e, now)).sum();
        return Duration.ofSeconds(ledger.closedSeconds + open);
    }

    /** Seconds from max(start, month start) to min(at, start + timeout), never negative. */
    private long chargeable(BudgetLedger.Open entry, Instant at) {
        Instant monthStart = YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant from = entry.startedAt().isAfter(monthStart) ? entry.startedAt() : monthStart;
        Instant cap = entry.startedAt().plusSeconds(entry.timeoutSeconds());
        Instant to = at.isBefore(cap) ? at : cap;
        return Math.max(0, Duration.between(from, to).toSeconds());
    }

    private void rollMonth() {
        String current = YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
        if (!current.equals(ledger.month)) {
            ledger.month = current;
            ledger.closedSeconds = 0;
            ledger.save(stateFile);
        }
    }
}
```

- [ ] **Step 4: Update `SandboxServiceTest` to the new constructor**

Replace every `new BudgetGuard(2)` in `SandboxServiceTest` with:
```java
new BudgetGuard(2, Duration.ofHours(10), budgetFile, clock)
```
Then add a field and initialize `service` in a `@BeforeEach` (the field initializer can't use `@TempDir`):
```java
@org.junit.jupiter.api.io.TempDir
java.nio.file.Path tmp;
java.nio.file.Path budgetFile;

@org.junit.jupiter.api.BeforeEach
void setUp() {
    budgetFile = tmp.resolve("budget.json");
    service = new SandboxService(backend, new BudgetGuard(2, Duration.ofHours(10), budgetFile, clock), "runner-a",
            Duration.ofMinutes(10), clock);
}
```
Change the `service` field declaration to `SandboxService service;`. In `requestIdIsFoundFromBackendLabelsAfterARunnerRestart`, build the restarted service with `new BudgetGuard(2, Duration.ofHours(10), budgetFile, clock)`.

- [ ] **Step 5: Run the runner tests**

Run: `cd runner && mvn -q test`
Expected: PASS (`BudgetGuardTest` 8, `SandboxServiceTest` 7, `FakeBackendConformanceTest` with the egress tests skipped).

- [ ] **Step 6: Commit**

```bash
git add runner/src
git commit -m "feat(runner): add budget guard with persistent monthly sandbox-time ledger"
```

---

### Task 4: HTTP API, shared-secret filter, reaper and wiring

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/api/{SandboxController,ApiErrorHandler,RunnerSecretFilter,CreateSandboxRequest,CreateSandboxResponse,SandboxStatusResponse,LogChunkResponse,ApiError}.java`
- Create: `runner/src/main/java/com/agentstore/runner/sandbox/{RunnerReaper,ReaperJob,SpringRunsClient}.java`
- Create: `runner/src/main/java/com/agentstore/runner/RunnerConfig.java`
- Test: `runner/src/test/java/com/agentstore/runner/api/SandboxControllerTest.java`, `runner/src/test/java/com/agentstore/runner/sandbox/RunnerReaperTest.java`, `runner/src/test/java/com/agentstore/runner/TestBackendConfig.java`

**Interfaces:**
- Consumes: `SandboxService`, `BudgetGuard`, `IsolationBackend`, `RunnerProperties`, `FakeBackend`.
- Produces the HTTP contract that Spring's `RunnerSandboxProvider` (Task 9) relies on. **It's exact:**
  - `POST /sandboxes`
    - body: `{"requestId","imageRef","command":[..],"env":{..},"allowOut":[..],"cpu","memoryMb","timeoutSeconds"}`
    - returns 201 `{"sandboxId": "..."}`
    - errors: 400 on validation; 429 `{"code":"CAPACITY_EXHAUSTED"|"BUDGET_EXHAUSTED","message"}`; 502 `{"code":"BACKEND_ERROR","message":"Sandbox backend failed"}`
  - `GET /sandboxes/{id}` returns 200 `{"state":"RUNNING|EXITED|GONE|UNKNOWN","exitCode":int|null}`
  - `GET /sandboxes/{id}/logs?offset=N` returns 200 `{"data","nextOffset","eof"}`
  - `DELETE /sandboxes/{id}` returns 204
  - Every path except `/actuator/health` needs a matching `X-Runner-Secret` header; otherwise 401 `{"code":"UNAUTHORIZED"}`.
- `SpringRunsClient.activeRunIds()` returns `Optional<Set<String>>`. It calls Spring's `GET {springBaseUrl}/internal/runs/active-ids` with `X-Runner-Secret` and expects `{"runIds":[...]}` (Task 9 builds that endpoint).
- `RunnerReaper(IsolationBackend, SandboxService, BudgetGuard, SpringRunsClient, Clock)` has:
  - `int sweep()`: kills owned sandboxes past their `agentstore.deadline` label and reconciles the budget
  - `int reconcileWithSpring()`: kills owned sandboxes whose `requestId` isn't an active run, and does nothing when Spring can't be reached

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/agentstore/runner/TestBackendConfig.java`:
```java
package com.agentstore.runner;

import com.agentstore.runner.backend.FakeBackend;
import com.agentstore.runner.backend.IsolationBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestBackendConfig {
    @Bean
    @ConditionalOnProperty(name = "agentstore.runner.backend", havingValue = "fake")
    FakeBackend fakeBackend(RunnerProperties props) {
        return new FakeBackend(props.runnerId());
    }
}
```

`src/test/java/com/agentstore/runner/api/SandboxControllerTest.java`:
```java
package com.agentstore.runner.api;

import com.agentstore.runner.TestBackendConfig;
import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.FakeBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBackendConfig.class)
class SandboxControllerTest {

    static final String SECRET = "test-runner-secret";

    @Autowired MockMvc mvc;
    @Autowired FakeBackend backend;

    @AfterEach
    void cleanup() {
        backend.listOwned().forEach(o -> backend.kill(o.sandboxId()));
    }

    static String body(String requestId, String shell) {
        return """
                {"requestId":"%s","imageRef":"fake","command":["sh","-c","%s"],
                 "env":{"TOKEN":"s3cret-value"},"allowOut":["example.com"],"cpu":1,"memoryMb":512,"timeoutSeconds":60}
                """.formatted(requestId, shell);
    }

    String create(String requestId, String shell) throws Exception {
        MvcResult result = mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body(requestId, shell)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sandboxId").isNotEmpty())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.sandboxId");
    }

    @Test
    void missingOrWrongSecretIsUnauthorized() throws Exception {
        mvc.perform(get("/sandboxes/x")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/sandboxes/x").header("X-Runner-Secret", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthNeedsNoSecret() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void createStatusLogsKillRoundTrip() throws Exception {
        String id = create("req-1", "echo hello; exit 4");

        long until = System.currentTimeMillis() + 10_000;
        String state = "RUNNING";
        while (!state.equals("EXITED") && System.currentTimeMillis() < until) {
            state = com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/sandboxes/" + id).header("X-Runner-Secret", SECRET))
                    .andReturn().getResponse().getContentAsString(), "$.state");
        }
        mvc.perform(get("/sandboxes/" + id).header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.state").value("EXITED"))
                .andExpect(jsonPath("$.exitCode").value(4));

        mvc.perform(get("/sandboxes/" + id + "/logs").param("offset", "0").header("X-Runner-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("hello\n"))
                .andExpect(jsonPath("$.nextOffset").value(6))
                .andExpect(jsonPath("$.eof").value(false));
        mvc.perform(get("/sandboxes/" + id + "/logs").param("offset", "6").header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.eof").value(true));

        mvc.perform(delete("/sandboxes/" + id).header("X-Runner-Secret", SECRET)).andExpect(status().isNoContent());
        mvc.perform(delete("/sandboxes/" + id).header("X-Runner-Secret", SECRET)).andExpect(status().isNoContent());
        mvc.perform(get("/sandboxes/" + id).header("X-Runner-Secret", SECRET))
                .andExpect(jsonPath("$.state").value("GONE"));
    }

    @Test
    void sameRequestIdReturnsSameSandbox() throws Exception {
        assertThat(create("req-2", "sleep 30")).isEqualTo(create("req-2", "sleep 30"));
    }

    @Test
    void capacityExhaustedIs429WithCode() throws Exception {
        create("req-3", "sleep 30");
        create("req-4", "sleep 30");
        mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("req-5", "sleep 30")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CAPACITY_EXHAUSTED"));
    }

    @Test
    void backendFailureIs502WithoutLeakingEnv() throws Exception {
        backend.failNextCreate(new BackendException("start failed"));
        MvcResult result = mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content(body("req-6", "true")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("BACKEND_ERROR"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("s3cret-value");
    }

    @Test
    void invalidBodyIs400() throws Exception {
        mvc.perform(post("/sandboxes").header("X-Runner-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
```

`src/test/java/com/agentstore/runner/sandbox/RunnerReaperTest.java`:
```java
package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.FakeBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerReaperTest {

    @TempDir Path tmp;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    final FakeBackend backend = new FakeBackend("runner-a");
    final AtomicReference<Optional<Set<String>>> active = new AtomicReference<>(Optional.empty());

    @AfterEach
    void cleanup() { backend.listOwned().forEach(o -> backend.kill(o.sandboxId())); }

    RunnerReaper reaper(SandboxService service, BudgetGuard budget) {
        SpringRunsClient spring = () -> active.get();
        return new RunnerReaper(backend, service, budget, spring, clock);
    }

    CreateSandboxCommand cmd(String requestId, int timeout) {
        return new CreateSandboxCommand(requestId, "fake", List.of("sh", "-c", "sleep 60"), Map.of(), List.of(),
                1, 512, timeout);
    }

    @Test
    void sweepKillsSandboxesPastTheirDeadlineLabel() {
        BudgetGuard budget = new BudgetGuard(5, Duration.ofHours(10), tmp.resolve("b.json"), clock);
        SandboxService service = new SandboxService(backend, budget, "runner-a", Duration.ofMinutes(10), clock);
        String old = service.create(cmd("r-old", 30));
        String fresh = service.create(cmd("r-fresh", 600));
        now.set(now.get().plusSeconds(31));

        assertThat(reaper(service, budget).sweep()).isEqualTo(1);
        assertThat(backend.status(old).phase().name()).isEqualTo("GONE");
        assertThat(backend.status(fresh).phase().name()).isEqualTo("RUNNING");
    }

    @Test
    void reconcileKillsSandboxesWhoseRunIsNoLongerActive() {
        BudgetGuard budget = new BudgetGuard(5, Duration.ofHours(10), tmp.resolve("b.json"), clock);
        SandboxService service = new SandboxService(backend, budget, "runner-a", Duration.ofMinutes(10), clock);
        String orphan = service.create(cmd("run-done", 600));
        String live = service.create(cmd("run-live", 600));
        active.set(Optional.of(Set.of("run-live")));

        assertThat(reaper(service, budget).reconcileWithSpring()).isEqualTo(1);
        assertThat(backend.status(orphan).phase().name()).isEqualTo("GONE");
        assertThat(backend.status(live).phase().name()).isEqualTo("RUNNING");
    }

    @Test
    void reconcileDoesNothingWhenSpringIsUnreachable() {
        BudgetGuard budget = new BudgetGuard(5, Duration.ofHours(10), tmp.resolve("b.json"), clock);
        SandboxService service = new SandboxService(backend, budget, "runner-a", Duration.ofMinutes(10), clock);
        service.create(cmd("run-x", 600));
        active.set(Optional.empty());

        assertThat(reaper(service, budget).reconcileWithSpring()).isZero();
        assertThat(backend.listOwned()).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd runner && mvn -q test -Dtest='SandboxControllerTest,RunnerReaperTest'`
Expected: compilation FAILURE (the API and reaper classes don't exist).

- [ ] **Step 3: Implement the DTOs**

`api/CreateSandboxRequest.java`:
```java
package com.agentstore.runner.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record CreateSandboxRequest(@NotBlank String requestId, @NotBlank String imageRef,
                                   @NotEmpty List<String> command, Map<String, String> env, List<String> allowOut,
                                   @Min(1) int cpu, @Min(128) int memoryMb, @Min(1) int timeoutSeconds) {
    @Override
    public String toString() {
        return "CreateSandboxRequest[requestId=" + requestId + ", imageRef=" + imageRef + "]";
    }
}
```

`api/CreateSandboxResponse.java`: `public record CreateSandboxResponse(String sandboxId) {}`
`api/SandboxStatusResponse.java`: `public record SandboxStatusResponse(String state, Integer exitCode) {}`
`api/LogChunkResponse.java`: `public record LogChunkResponse(String data, long nextOffset, boolean eof) {}`
`api/ApiError.java`: `public record ApiError(String code, String message) {}`
(Each goes in its own file with `package com.agentstore.runner.api;`.)

- [ ] **Step 4: Implement the controller, error handler and filter**

`api/SandboxController.java`:
```java
package com.agentstore.runner.api;

import com.agentstore.runner.backend.LogChunk;
import com.agentstore.runner.backend.SandboxState;
import com.agentstore.runner.sandbox.CreateSandboxCommand;
import com.agentstore.runner.sandbox.SandboxService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sandboxes")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSandboxResponse create(@Valid @RequestBody CreateSandboxRequest r) {
        return new CreateSandboxResponse(sandboxService.create(new CreateSandboxCommand(r.requestId(), r.imageRef(),
                r.command(), r.env(), r.allowOut(), r.cpu(), r.memoryMb(), r.timeoutSeconds())));
    }

    @GetMapping("/{id}")
    public SandboxStatusResponse status(@PathVariable String id) {
        SandboxState state = sandboxService.status(id);
        return new SandboxStatusResponse(state.phase().name(), state.exitCode());
    }

    @GetMapping("/{id}/logs")
    public LogChunkResponse logs(@PathVariable String id, @RequestParam(defaultValue = "0") long offset) {
        LogChunk chunk = sandboxService.logs(id, offset);
        return new LogChunkResponse(chunk.data(), chunk.nextOffset(), sandboxService.isFinished(id, chunk));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kill(@PathVariable String id) {
        sandboxService.kill(id);
    }
}
```

`api/ApiErrorHandler.java`:
```java
package com.agentstore.runner.api;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.sandbox.BudgetExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(BudgetExhaustedException.class)
    ResponseEntity<ApiError> budget(BudgetExhaustedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ApiError(e.code(), e.getMessage()));
    }

    /** Backend messages are written secret-free, but the body stays generic anyway. */
    @ExceptionHandler(BackendException.class)
    ResponseEntity<ApiError> backend(BackendException e) {
        log.warn("Sandbox backend failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("BACKEND_ERROR", "Sandbox backend failed"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "Invalid sandbox request"));
    }
}
```

`api/RunnerSecretFilter.java`:
```java
package com.agentstore.runner.api;

import com.agentstore.runner.RunnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class RunnerSecretFilter extends OncePerRequestFilter {

    private final byte[] secret;

    public RunnerSecretFilter(RunnerProperties props) {
        this.secret = props.secret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("X-Runner-Secret");
        byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, secret)) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid runner secret\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: Implement the reaper and the Spring client**

`sandbox/SpringRunsClient.java`:
```java
package com.agentstore.runner.sandbox;

import java.util.Optional;
import java.util.Set;

/** Asks Spring which runs are still active. Empty when Spring cannot be reached (callers must then do nothing). */
@FunctionalInterface
public interface SpringRunsClient {
    Optional<Set<String>> activeRunIds();
}
```

`sandbox/RunnerReaper.java`:
```java
package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.OwnedSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RunnerReaper {

    private static final Logger log = LoggerFactory.getLogger(RunnerReaper.class);

    private final IsolationBackend backend;
    private final SandboxService sandboxService;
    private final BudgetGuard budget;
    private final SpringRunsClient spring;
    private final Clock clock;

    public RunnerReaper(IsolationBackend backend, SandboxService sandboxService, BudgetGuard budget,
                        SpringRunsClient spring, Clock clock) {
        this.backend = backend;
        this.sandboxService = sandboxService;
        this.budget = budget;
        this.spring = spring;
        this.clock = clock;
    }

    /** Kills owned sandboxes past their deadline label, then closes budget entries for sandboxes that are gone. */
    public int sweep() {
        long now = clock.instant().getEpochSecond();
        List<OwnedSandbox> owned = backend.listOwned();
        int killed = 0;
        for (OwnedSandbox o : owned) {
            Long deadline = o.deadlineEpochSeconds();
            if (deadline != null && deadline < now) {
                killQuietly(o.sandboxId());
                killed++;
            }
        }
        Set<String> live = backend.listOwned().stream().map(OwnedSandbox::sandboxId).collect(Collectors.toSet());
        budget.reconcile(live);
        return killed;
    }

    /** At startup: kill owned sandboxes whose run Spring no longer considers active. */
    public int reconcileWithSpring() {
        Optional<Set<String>> active = spring.activeRunIds();
        if (active.isEmpty()) {
            log.warn("Spring unreachable; skipping orphan reconcile");
            return 0;
        }
        int killed = 0;
        for (OwnedSandbox o : backend.listOwned()) {
            if (o.requestId() != null && !active.get().contains(o.requestId())) {
                killQuietly(o.sandboxId());
                killed++;
            }
        }
        return killed;
    }

    private void killQuietly(String sandboxId) {
        try {
            sandboxService.kill(sandboxId);
        } catch (RuntimeException e) {
            log.warn("Reaper failed to kill sandbox {}: {}", sandboxId, e.getMessage());
        }
    }
}
```

`sandbox/ReaperJob.java`:
```java
package com.agentstore.runner.sandbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.runner.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class ReaperJob {

    private final RunnerReaper reaper;

    public ReaperJob(RunnerReaper reaper) {
        this.reaper = reaper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reaper.reconcileWithSpring();
        reaper.sweep();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void sweep() {
        reaper.sweep();
    }
}
```

- [ ] **Step 6: Wire the beans**

`RunnerConfig.java`:
```java
package com.agentstore.runner;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.sandbox.BudgetGuard;
import com.agentstore.runner.sandbox.RunnerReaper;
import com.agentstore.runner.sandbox.SandboxService;
import com.agentstore.runner.sandbox.SpringRunsClient;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Configuration
public class RunnerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BudgetGuard budgetGuard(RunnerProperties props, Clock clock) {
        var b = props.budget();
        return new BudgetGuard(b.maxConcurrent(), b.monthlyLimit(), b.stateFile(), clock);
    }

    @Bean
    SandboxService sandboxService(IsolationBackend backend, BudgetGuard budget, RunnerProperties props, Clock clock) {
        return new SandboxService(backend, budget, props.runnerId(), props.budget().maxLifetime(), clock);
    }

    @Bean
    SpringRunsClient springRunsClient(RestClient.Builder builder, RunnerProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        RestClient client = builder.baseUrl(props.springBaseUrl()).requestFactory(factory).build();
        return () -> {
            try {
                Map<?, ?> body = client.get().uri("/internal/runs/active-ids")
                        .header("X-Runner-Secret", props.secret())
                        .retrieve().body(Map.class);
                Set<String> ids = new HashSet<>();
                for (Object id : (List<?>) body.get("runIds")) {
                    ids.add(String.valueOf(id));
                }
                return Optional.of(ids);
            } catch (RestClientException | NullPointerException | ClassCastException e) {
                LoggerFactory.getLogger(RunnerConfig.class).warn("Could not fetch active runs from Spring: {}",
                        e.getMessage());
                return Optional.empty();
            }
        };
    }

    @Bean
    RunnerReaper runnerReaper(IsolationBackend backend, SandboxService service, BudgetGuard budget,
                              SpringRunsClient spring, Clock clock) {
        return new RunnerReaper(backend, service, budget, spring, clock);
    }
}
```

- [ ] **Step 7: Run all runner tests**

Run: `cd runner && mvn -q test`
Expected: PASS. `SandboxControllerTest` needs its context to start: if it fails with "no IsolationBackend bean", check that `@Import(TestBackendConfig.class)` is present and that `application-test.yml` sets `backend: fake`.

- [ ] **Step 8: Commit**

```bash
git add runner/src
git commit -m "feat(runner): add internal sandbox API, shared-secret filter and reaper"
```

---

### Task 5: Node shim for E2B's SDK-only operations

**Files:**
- Create: `runner/e2b-shim/package.json`, `runner/e2b-shim/shim.mjs`, `runner/e2b-shim/shim.test.mjs`, `runner/e2b-shim/.gitignore`

**Interfaces:**
- Produces the **shim protocol** that `ShimClient` (Task 6) relies on. It's exact:
  - **Invocation:** `node e2b-shim/shim.mjs`, with env `E2B_API_KEY`. The request is **one JSON object on stdin**. The response is **one JSON object on stdout**. Exit 0 when `ok:true`, exit 1 otherwise.
  - **`start` operation:**
    - request: `{"op":"start","sandboxId":"…","command":["sh","-c","…"],"env":{…}}`
    - response: `{"ok":true}`
  - **`readFile` operation:**
    - request: `{"op":"readFile","sandboxId":"…","path":"/workspace/.run/log","offset":0,"maxBytes":262144}`
    - response: `{"ok":true,"exists":true,"data":"<base64>","size":<total bytes>}` or `{"ok":true,"exists":false}`
  - **Errors:** `{"ok":false,"error":{"code":"…","message":"…"}}`. Messages must never contain env values or the request.

- [ ] **Step 1: Create the package**

`runner/e2b-shim/package.json`:
```json
{
  "name": "agentstore-e2b-shim",
  "private": true,
  "type": "module",
  "engines": { "node": ">=20" },
  "scripts": { "test": "vitest run" },
  "dependencies": { "e2b": "^1.0.0" },
  "devDependencies": { "vitest": "^2.0.0" }
}
```
`runner/e2b-shim/.gitignore`: `node_modules/`

Run: `cd runner/e2b-shim && npm install e2b@latest && npm install -D vitest@latest`. Commit the resulting `package-lock.json`. Then open `node_modules/e2b/dist/index.d.ts` and confirm three things:
1. `Sandbox.connect(sandboxId, { apiKey })` exists.
2. `sandbox.commands.run(cmd, { envs, user, background, timeoutMs })` accepts `user: 'root'`.
3. `sandbox.files.read(path, { format: 'bytes' })` returns a `Uint8Array` and throws a `NotFoundError` for missing files.

If any name differs in the installed version, adjust `shim.mjs` below to the installed typings. Keep the protocol above unchanged.

- [ ] **Step 2: Write the failing tests**

`runner/e2b-shim/shim.test.mjs`:
```js
import { describe, it, expect } from 'vitest';
import { handle, shellQuote, WRAPPER } from './shim.mjs';

function fakeSandboxClass({ files = {}, failRun = false } = {}) {
  const calls = [];
  const sandbox = {
    commands: {
      run: async (cmd, opts) => {
        calls.push({ kind: 'run', cmd, opts });
        if (failRun) throw new Error('boom from e2b');
        return { exitCode: 0 };
      },
    },
    files: {
      write: async (path, data) => { calls.push({ kind: 'write', path, data }); },
      read: async (path) => {
        if (!(path in files)) { const e = new Error('not found'); e.name = 'NotFoundError'; throw e; }
        return new TextEncoder().encode(files[path]);
      },
    },
  };
  return { calls, Sandbox: { connect: async (id, opts) => { calls.push({ kind: 'connect', id, opts }); return sandbox; } } };
}

describe('shellQuote', () => {
  it('single-quotes and escapes embedded quotes', () => {
    expect(shellQuote("it's")).toBe(`'it'\\''s'`);
  });
});

describe('start', () => {
  it('installs the wrapper as root, then launches the command detached with envs', async () => {
    const { calls, Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'start', sandboxId: 'sbx-1', command: ['sh', '-c', 'echo hi'], env: { TOKEN: 'secret' } },
      { Sandbox, apiKey: 'k' });

    expect(res).toEqual({ ok: true });
    expect(calls[0]).toMatchObject({ kind: 'connect', id: 'sbx-1', opts: { apiKey: 'k' } });
    const write = calls.find((c) => c.kind === 'write');
    expect(write.path).toBe('/workspace/.run/agentstore-run');
    expect(write.data).toBe(WRAPPER);
    const runs = calls.filter((c) => c.kind === 'run');
    expect(runs[0].opts.user).toBe('root');
    const launch = runs[runs.length - 1];
    expect(launch.cmd).toContain(`/workspace/.run/agentstore-run 'sh' '-c' 'echo hi'`);
    expect(launch.cmd).toMatch(/^setsid nohup /);
    expect(launch.opts.envs).toEqual({ TOKEN: 'secret' });
    expect(launch.cmd).not.toContain('secret');
  });

  it('reports failure without echoing env values', async () => {
    const { Sandbox } = fakeSandboxClass({ failRun: true });
    const res = await handle({ op: 'start', sandboxId: 'sbx-1', command: ['true'], env: { TOKEN: 'secret' } },
      { Sandbox, apiKey: 'k' });
    expect(res.ok).toBe(false);
    expect(JSON.stringify(res)).not.toContain('secret');
  });
});

describe('readFile', () => {
  it('returns base64 slice from offset capped at maxBytes, plus total size', async () => {
    const { Sandbox } = fakeSandboxClass({ files: { '/workspace/.run/log': 'hello world' } });
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/log', offset: 6, maxBytes: 3 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: true, data: Buffer.from('wor').toString('base64'), size: 11 });
  });

  it('reports a missing file as exists:false', async () => {
    const { Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'readFile', sandboxId: 's', path: '/workspace/.run/exit', offset: 0, maxBytes: 10 },
      { Sandbox, apiKey: 'k' });
    expect(res).toEqual({ ok: true, exists: false });
  });
});

describe('unknown op', () => {
  it('fails with BAD_REQUEST', async () => {
    const { Sandbox } = fakeSandboxClass();
    const res = await handle({ op: 'nope' }, { Sandbox, apiKey: 'k' });
    expect(res).toMatchObject({ ok: false, error: { code: 'BAD_REQUEST' } });
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd runner/e2b-shim && npx vitest run`
Expected: FAIL (`shim.mjs` doesn't exist).

- [ ] **Step 4: Implement**

`runner/e2b-shim/shim.mjs`:
```js
// Adapter for the E2B operations that exist only in E2B's JS SDK (spec §2.3).
// Protocol: one JSON request on stdin, one JSON response on stdout, exit 0 iff ok.
// Never print the request or env values: they carry run secrets.

export const RUN_DIR = '/workspace/.run';
export const WRAPPER_PATH = `${RUN_DIR}/agentstore-run`;
export const WRAPPER = `#!/bin/sh
"$@" >>${RUN_DIR}/log 2>&1
code=$?
printf '%s' "$code" >${RUN_DIR}/exit.tmp && mv ${RUN_DIR}/exit.tmp ${RUN_DIR}/exit
exit "$code"
`;

export function shellQuote(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

function fail(code, message) {
  return { ok: false, error: { code, message } };
}

async function start(req, { Sandbox, apiKey }) {
  const sbx = await Sandbox.connect(req.sandboxId, { apiKey });
  await sbx.commands.run(`mkdir -p ${RUN_DIR} && chmod 777 ${RUN_DIR} && touch ${RUN_DIR}/log && chmod 666 ${RUN_DIR}/log`,
    { user: 'root' });
  await sbx.files.write(WRAPPER_PATH, WRAPPER);
  await sbx.commands.run(`chmod 755 ${WRAPPER_PATH}`, { user: 'root' });
  const argv = req.command.map(shellQuote).join(' ');
  // Detached so the agent keeps running after this shim process (and its SDK connection) exits.
  await sbx.commands.run(`setsid nohup ${WRAPPER_PATH} ${argv} >/dev/null 2>&1 &`, { envs: req.env ?? {} });
  return { ok: true };
}

async function readFile(req, { Sandbox, apiKey }) {
  const sbx = await Sandbox.connect(req.sandboxId, { apiKey });
  let bytes;
  try {
    bytes = await sbx.files.read(req.path, { format: 'bytes' });
  } catch (e) {
    if (e?.name === 'NotFoundError' || /not found/i.test(e?.message ?? '')) return { ok: true, exists: false };
    throw e;
  }
  const offset = Math.max(0, req.offset ?? 0);
  const slice = Buffer.from(bytes).subarray(offset, offset + (req.maxBytes ?? 262144));
  return { ok: true, exists: true, data: slice.toString('base64'), size: bytes.length };
}

export async function handle(req, deps) {
  try {
    switch (req?.op) {
      case 'start': return await start(req, deps);
      case 'readFile': return await readFile(req, deps);
      default: return fail('BAD_REQUEST', `unknown op ${String(req?.op)}`);
    }
  } catch (e) {
    return fail('E2B_ERROR', `${req?.op} failed: ${e?.name ?? 'Error'}: ${e?.message ?? ''}`.slice(0, 500));
  }
}

async function main() {
  const chunks = [];
  for await (const c of process.stdin) chunks.push(c);
  let req;
  try {
    req = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    process.stdout.write(JSON.stringify(fail('BAD_REQUEST', 'stdin is not JSON')));
    process.exit(1);
  }
  const { Sandbox } = await import('e2b');
  const res = await handle(req, { Sandbox, apiKey: process.env.E2B_API_KEY });
  process.stdout.write(JSON.stringify(res));
  process.exit(res.ok ? 0 : 1);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
```

The error message includes E2B's own message, which doesn't contain our env values (they're sent as `envs` and never put into the command string). The test `reports failure without echoing env values` pins this.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd runner/e2b-shim && npx vitest run`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add runner/e2b-shim/package.json runner/e2b-shim/package-lock.json runner/e2b-shim/shim.mjs runner/e2b-shim/shim.test.mjs runner/e2b-shim/.gitignore
git commit -m "feat(runner): add Node shim for E2B SDK-only operations"
```

---

### Task 6: `E2bBackend` (REST client, shim client, backend)

**Files:**
- Create: `runner/src/main/java/com/agentstore/runner/backend/e2b/{E2bRestClient,ShimClient,ShimException,E2bBackend,E2bConfig}.java`
- Create: `runner/src/test/resources/shim/{ok-read.sh,fail.sh,hang.sh,garbage.sh}`
- Test: `runner/src/test/java/com/agentstore/runner/backend/e2b/{E2bRestClientTest,ShimClientTest,E2bBackendTest}.java`

**Interfaces:**
- Consumes: `IsolationBackend` and the value types (Task 1); the shim protocol (Task 5).
- Produces:
  - `E2bRestClient(RestClient.Builder builder, String baseUrl, String apiKey)` with:
    - `String create(SandboxLaunch)`, which POSTs `/sandboxes` with `templateID`, `timeout`, `metadata`, and `network{allowOut, denyOut:["0.0.0.0/0"]}` (no env)
    - `boolean exists(String id)`: true on 200, false on 404; throws `RestClientException` otherwise
    - `void delete(String id)`: 404 is ignored
    - `List<OwnedSandbox> list(String runnerId)`: GET `/sandboxes`, filtered by `metadata["agentstore.runnerId"]`
  - `ShimClient(List<String> command, Duration timeout, String apiKey)` with:
    - `JsonNode call(Map<String,Object> request)`, which throws `ShimException` (message never includes the request)
  - `E2bBackend(E2bRestClient rest, ShimClient shim, String runnerId)` implements `IsolationBackend`, with `name()` = `"e2b"`.

- [ ] **Step 1: Create the fake shim scripts** (run with `sh <script>`, so no chmod is needed)

`src/test/resources/shim/ok-read.sh`:
```sh
cat >/dev/null
printf '%s' '{"ok":true,"exists":true,"data":"aGk=","size":2}'
```
`src/test/resources/shim/fail.sh`:
```sh
cat >/dev/null
printf '%s' '{"ok":false,"error":{"code":"E2B_ERROR","message":"boom"}}'
exit 1
```
`src/test/resources/shim/hang.sh`:
```sh
sleep 30
```
`src/test/resources/shim/garbage.sh`:
```sh
cat >/dev/null
echo 'not json'
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/agentstore/runner/backend/e2b/ShimClientTest.java`:
```java
package com.agentstore.runner.backend.e2b;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShimClientTest {

    static ShimClient shim(String script, Duration timeout) {
        return new ShimClient(List.of("sh", "src/test/resources/shim/" + script), timeout, "api-key");
    }

    static final Map<String, Object> REQUEST = Map.of("op", "start", "sandboxId", "s",
            "env", Map.of("TOKEN", "s3cret-value"));

    @Test
    void parsesASuccessfulResponse() {
        var res = shim("ok-read.sh", Duration.ofSeconds(10)).call(REQUEST);
        assertThat(res.get("data").asText()).isEqualTo("aGk=");
    }

    @Test
    void failureCarriesTheShimMessageButNotTheRequest() {
        assertThatThrownBy(() -> shim("fail.sh", Duration.ofSeconds(10)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageContaining("boom")
                .hasMessageNotContaining("s3cret-value");
    }

    @Test
    void hangingShimIsKilledAtTheTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> shim("hang.sh", Duration.ofMillis(500)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void nonJsonOutputIsAShimException() {
        assertThatThrownBy(() -> shim("garbage.sh", Duration.ofSeconds(10)).call(REQUEST))
                .isInstanceOf(ShimException.class)
                .hasMessageNotContaining("s3cret-value");
    }

    @Test
    void missingExecutableIsAShimException() {
        var client = new ShimClient(List.of("/definitely/not/here"), Duration.ofSeconds(5), "k");
        assertThatThrownBy(() -> client.call(REQUEST)).isInstanceOf(ShimException.class);
    }
}
```

`src/test/java/com/agentstore/runner/backend/e2b/E2bRestClientTest.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.SandboxLaunch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class E2bRestClientTest {

    MockRestServiceServer server;
    E2bRestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new E2bRestClient(builder, "https://e2b.test", "api-key");
    }

    @Test
    void createSendsTemplateTimeoutMetadataAndDenyAllNetworkButNoEnv() {
        server.expect(requestTo("https://e2b.test/sandboxes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "api-key"))
                .andExpect(jsonPath("$.templateID").value("base"))
                .andExpect(jsonPath("$.timeout").value(60))
                .andExpect(jsonPath("$.metadata['agentstore.requestId']").value("run-1"))
                .andExpect(jsonPath("$.network.allowOut[0]").value("example.com"))
                .andExpect(jsonPath("$.network.denyOut[0]").value("0.0.0.0/0"))
                .andExpect(jsonPath("$.envVars").doesNotExist())
                .andRespond(withSuccess("{\"sandboxID\":\"sbx-1\"}", MediaType.APPLICATION_JSON));

        String id = client.create(new SandboxLaunch("base", List.of("true"), Map.of("TOKEN", "s3cret"),
                List.of("example.com"), 1, 512, 60, Map.of(Labels.REQUEST_ID, "run-1")));

        assertThat(id).isEqualTo("sbx-1");
        server.verify();
    }

    @Test
    void existsIsFalseOn404() {
        server.expect(requestTo("https://e2b.test/sandboxes/sbx-9")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.exists("sbx-9")).isFalse();
    }

    @Test
    void deleteIgnores404() {
        server.expect(requestTo("https://e2b.test/sandboxes/sbx-9")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        client.delete("sbx-9");
        server.verify();
    }

    @Test
    void listReturnsOnlyThisRunnersSandboxes() {
        server.expect(requestTo("https://e2b.test/sandboxes")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"sandboxID":"a","metadata":{"agentstore.runnerId":"runner-1","agentstore.requestId":"r1"}},
                         {"sandboxID":"b","metadata":{"agentstore.runnerId":"other"}},
                         {"sandboxID":"c"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.list("runner-1")).singleElement()
                .satisfies(o -> assertThat(o.requestId()).isEqualTo("r1"));
    }
}
```

`src/test/java/com/agentstore/runner/backend/e2b/E2bBackendTest.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class E2bBackendTest {

    final E2bRestClient rest = mock(E2bRestClient.class);
    final ShimClient shim = mock(ShimClient.class);
    final E2bBackend backend = new E2bBackend(rest, shim, "runner-1");
    final ObjectMapper om = new ObjectMapper();

    SandboxLaunch launch() {
        return new SandboxLaunch("base", List.of("sh", "-c", "true"), Map.of("TOKEN", "s3cret"), List.of(),
                1, 512, 60, Map.of());
    }

    ObjectNode file(String content) {
        return om.createObjectNode().put("ok", true).put("exists", true)
                .put("data", Base64.getEncoder().encodeToString(content.getBytes())).put("size", content.length());
    }

    ObjectNode missing() {
        return om.createObjectNode().put("ok", true).put("exists", false);
    }

    @Test
    void createStartsTheCommandThroughTheShimWithEnv() {
        when(rest.create(any())).thenReturn("sbx-1");
        when(shim.call(any())).thenReturn(om.createObjectNode().put("ok", true));

        assertThat(backend.create(launch())).isEqualTo("sbx-1");
        verify(shim).call(argThat(req -> "start".equals(req.get("op")) && "sbx-1".equals(req.get("sandboxId"))
                && Map.of("TOKEN", "s3cret").equals(req.get("env"))));
    }

    @Test
    void startFailureKillsTheSandboxAndThrowsWithoutSecrets() {
        when(rest.create(any())).thenReturn("sbx-1");
        when(shim.call(any())).thenThrow(new ShimException("start failed: boom"));

        assertThatThrownBy(() -> backend.create(launch()))
                .isInstanceOf(BackendException.class)
                .hasMessageNotContaining("s3cret");
        verify(rest).delete("sbx-1");
    }

    @Test
    void goneWhenE2bReturns404() {
        when(rest.exists("sbx-1")).thenReturn(false);
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.gone());
    }

    @Test
    void exitedWithCodeWhenTheExitFileExists() {
        when(rest.exists("sbx-1")).thenReturn(true);
        when(shim.call(argThat(r -> "/workspace/.run/exit".equals(r.get("path"))))).thenReturn(file("3"));
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.exited(3));
    }

    @Test
    void runningWhenNoExitFile() {
        when(rest.exists("sbx-1")).thenReturn(true);
        when(shim.call(any())).thenReturn(missing());
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.running());
    }

    @Test
    void unknownWhenE2bOrTheShimCannotBeReached() {
        when(rest.exists("sbx-1")).thenThrow(new ResourceAccessException("timeout"));
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.unknown());

        when(rest.exists("sbx-2")).thenReturn(true);
        when(shim.call(any())).thenThrow(new ShimException("timed out"));
        assertThat(backend.status("sbx-2")).isEqualTo(SandboxState.unknown());
    }

    @Test
    void readLogsAdvancesByBytesAndReplacesInvalidUtf8() {
        byte[] bytes = {'o', 'k', (byte) 0xC3};   // truncated multi-byte sequence
        when(rest.exists("sbx-1")).thenReturn(true);
        when(shim.call(any())).thenReturn(om.createObjectNode().put("ok", true).put("exists", true)
                .put("data", Base64.getEncoder().encodeToString(bytes)).put("size", 3));

        var chunk = backend.readLogs("sbx-1", 10, 1024);
        assertThat(chunk.nextOffset()).isEqualTo(13);
        assertThat(chunk.data()).startsWith("ok").hasSize(3);
    }

    @Test
    void readLogsOfAGoneSandboxIsEmpty() {
        when(rest.exists("sbx-1")).thenReturn(false);
        assertThat(backend.readLogs("sbx-1", 5, 1024).data()).isEmpty();
        verifyNoInteractions(shim);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd runner && mvn -q test -Dtest='ShimClientTest,E2bRestClientTest,E2bBackendTest'`
Expected: compilation FAILURE.

- [ ] **Step 4: Implement `ShimException` and `ShimClient`**

`backend/e2b/ShimException.java`:
```java
package com.agentstore.runner.backend.e2b;

/** Never carries the request (it holds run secrets). */
public class ShimException extends RuntimeException {
    public ShimException(String message) { super(message); }
    public ShimException(String message, Throwable cause) { super(message, cause); }
}
```

`backend/e2b/ShimClient.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs the Node shim once per call: JSON request on stdin, JSON response on stdout (spec §2.3). */
public class ShimClient {

    private static final Logger log = LoggerFactory.getLogger(ShimClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final Duration timeout;
    private final String apiKey;

    public ShimClient(List<String> command, Duration timeout, String apiKey) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
        this.apiKey = apiKey;
    }

    public JsonNode call(Map<String, Object> request) {
        String op = String.valueOf(request.get("op"));
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("E2B_API_KEY", apiKey == null ? "" : apiKey);
            process = pb.start();
        } catch (IOException e) {
            throw new ShimException("shim " + op + " could not start: " + e.getMessage());
        }
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        try (OutputStream in = process.getOutputStream()) {
            MAPPER.writeValue(in, request);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new ShimException("shim " + op + " stdin failed: " + e.getClass().getSimpleName());
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ShimException("shim " + op + " timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ShimException("shim " + op + " interrupted");
        }
        String err = new String(stderr.join(), StandardCharsets.UTF_8);
        if (!err.isBlank()) {
            log.warn("shim {} stderr: {}", op, err.length() > 2000 ? err.substring(0, 2000) : err);
        }
        JsonNode response;
        try {
            response = MAPPER.readTree(stdout.join());
        } catch (IOException e) {
            throw new ShimException("shim " + op + " returned non-JSON output (exit " + process.exitValue() + ")");
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            String message = response == null ? "empty response" : response.path("error").path("message").asText("unknown error");
            throw new ShimException("shim " + op + " failed: " + message);
        }
        return response;
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
```

- [ ] **Step 5: Implement `E2bRestClient`**

`backend/e2b/E2bRestClient.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** E2B lifecycle over REST. Env is NOT sent here: it goes to the started process via the shim only. */
public class E2bRestClient {

    /** E2B: allow rules take precedence over deny rules, so deny-all + allowOut = allowlist only. */
    static final String ALL_TRAFFIC = "0.0.0.0/0";

    private final RestClient client;
    private final String apiKey;

    public E2bRestClient(RestClient.Builder builder, String baseUrl, String apiKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public String create(SandboxLaunch launch) {
        Map<String, Object> body = Map.of(
                "templateID", launch.imageRef(),
                "timeout", launch.timeoutSeconds(),
                "metadata", launch.labels(),
                "network", Map.of("allowOut", launch.allowOut(), "denyOut", List.of(ALL_TRAFFIC)));
        Map<?, ?> response = client.post().uri("/sandboxes")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return (String) response.get("sandboxID");
    }

    public boolean exists(String sandboxId) {
        try {
            client.get().uri("/sandboxes/{id}", sandboxId).header("X-API-Key", apiKey).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public void delete(String sandboxId) {
        try {
            client.delete().uri("/sandboxes/{id}", sandboxId).header("X-API-Key", apiKey).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // already gone
        }
    }

    public List<OwnedSandbox> list(String runnerId) {
        List<?> rows = client.get().uri("/sandboxes").header("X-API-Key", apiKey).retrieve().body(List.class);
        List<OwnedSandbox> owned = new ArrayList<>();
        if (rows == null) {
            return owned;
        }
        for (Object row : rows) {
            Map<?, ?> m = (Map<?, ?>) row;
            Object metadata = m.get("metadata");
            if (!(metadata instanceof Map<?, ?> md) || !runnerId.equals(md.get(Labels.RUNNER_ID))) {
                continue;
            }
            Map<String, String> labels = new HashMap<>();
            md.forEach((k, v) -> labels.put(String.valueOf(k), String.valueOf(v)));
            owned.add(new OwnedSandbox((String) m.get("sandboxID"), labels));
        }
        return owned;
    }
}
```

- [ ] **Step 6: Implement `E2bBackend` and its config**

`backend/e2b/E2bBackend.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.LogChunk;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class E2bBackend implements IsolationBackend {

    private static final Logger log = LoggerFactory.getLogger(E2bBackend.class);
    static final String LOG_PATH = "/workspace/.run/log";
    static final String EXIT_PATH = "/workspace/.run/exit";

    private final E2bRestClient rest;
    private final ShimClient shim;
    private final String runnerId;

    public E2bBackend(E2bRestClient rest, ShimClient shim, String runnerId) {
        this.rest = rest;
        this.shim = shim;
        this.runnerId = runnerId;
    }

    @Override
    public String name() {
        return "e2b";
    }

    @Override
    public String create(SandboxLaunch launch) {
        String sandboxId;
        try {
            sandboxId = rest.create(launch);
        } catch (RestClientException e) {
            throw new BackendException("E2B create failed: " + e.getClass().getSimpleName());
        }
        try {
            shim.call(Map.of("op", "start", "sandboxId", sandboxId, "command", launch.command(), "env", launch.env()));
            return sandboxId;
        } catch (ShimException e) {
            try {
                rest.delete(sandboxId);
            } catch (RestClientException deleteFailure) {
                log.warn("Could not delete half-started sandbox {}; its E2B timeout will reclaim it", sandboxId);
            }
            throw new BackendException("Agent start failed in sandbox " + sandboxId + ": " + e.getMessage());
        }
    }

    @Override
    public SandboxState status(String sandboxId) {
        try {
            if (!rest.exists(sandboxId)) {
                return SandboxState.gone();
            }
            JsonNode exit = readFile(sandboxId, EXIT_PATH, 0, 64);
            if (!exit.path("exists").asBoolean(false)) {
                return SandboxState.running();
            }
            String code = new String(Base64.getDecoder().decode(exit.path("data").asText("")), StandardCharsets.UTF_8).trim();
            return SandboxState.exited(Integer.parseInt(code));
        } catch (RestClientException | ShimException | NumberFormatException e) {
            log.warn("Status of sandbox {} unknown: {}", sandboxId, e.getMessage());
            return SandboxState.unknown();
        }
    }

    @Override
    public LogChunk readLogs(String sandboxId, long offset, int maxBytes) {
        try {
            if (!rest.exists(sandboxId)) {
                return LogChunk.empty(offset);
            }
            JsonNode res = readFile(sandboxId, LOG_PATH, offset, maxBytes);
            if (!res.path("exists").asBoolean(false)) {
                return LogChunk.empty(offset);
            }
            byte[] bytes = Base64.getDecoder().decode(res.path("data").asText(""));
            return new LogChunk(new String(bytes, StandardCharsets.UTF_8), offset + bytes.length);
        } catch (RestClientException | ShimException e) {
            throw new BackendException("Reading logs of sandbox " + sandboxId + " failed: " + e.getMessage());
        }
    }

    @Override
    public void kill(String sandboxId) {
        try {
            rest.delete(sandboxId);
        } catch (RestClientException e) {
            throw new BackendException("E2B delete failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public List<OwnedSandbox> listOwned() {
        try {
            return rest.list(runnerId);
        } catch (RestClientException e) {
            throw new BackendException("E2B list failed: " + e.getClass().getSimpleName());
        }
    }

    private JsonNode readFile(String sandboxId, String path, long offset, int maxBytes) {
        return shim.call(Map.of("op", "readFile", "sandboxId", sandboxId, "path", path, "offset", offset,
                "maxBytes", maxBytes));
    }
}
```

`backend/e2b/E2bConfig.java`:
```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.RunnerProperties;
import com.agentstore.runner.backend.IsolationBackend;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "agentstore.runner.backend", havingValue = "e2b")
public class E2bConfig {

    @Bean
    IsolationBackend e2bBackend(RestClient.Builder builder, RunnerProperties props) {
        RunnerProperties.E2b e2b = props.e2b();
        if (e2b == null || !StringUtils.hasText(e2b.apiKey())) {
            throw new IllegalStateException("agentstore.runner.e2b.api-key (E2B_API_KEY) is required when backend=e2b");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) e2b.httpTimeout().toMillis());
        factory.setReadTimeout((int) e2b.httpTimeout().toMillis());
        E2bRestClient rest = new E2bRestClient(builder.requestFactory(factory), e2b.baseUrl(), e2b.apiKey());
        ShimClient shim = new ShimClient(e2b.shimCommand(), e2b.shimTimeout(), e2b.apiKey());
        return new E2bBackend(rest, shim, props.runnerId());
    }
}
```

- [ ] **Step 7: Run all runner tests**

Run: `cd runner && mvn -q test`
Expected: PASS (all tests so far plus these three classes; the E2B-tagged ones don't exist yet).

- [ ] **Step 8: Commit**

```bash
git add runner/src
git commit -m "feat(runner): add E2B backend over REST and the Node shim"
```

---

### Task 7: E2B conformance tests (tagged, real credits)

**Files:**
- Test: `runner/src/test/java/com/agentstore/runner/backend/e2b/E2bBackendConformanceTest.java`

**Interfaces:**
- Consumes: `IsolationBackendConformanceTest` (Task 1), `E2bBackend`, `E2bRestClient`, `ShimClient` (Task 6), `runner/e2b-shim` (Task 5).

**This task spends E2B credit** (roughly cents per full run). Run it only with `E2B_API_KEY` set.

- [ ] **Step 1: Write the test**

```java
package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.IsolationBackendConformanceTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real E2B. Run: E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bBackendConformanceTest */
@Tag("e2b")
class E2bBackendConformanceTest extends IsolationBackendConformanceTest {

    private static IsolationBackend backend;

    @BeforeAll
    static void connect() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        var rest = new E2bRestClient(RestClient.builder(), "https://api.e2b.app", apiKey);
        var shim = new ShimClient(List.of("node", "e2b-shim/shim.mjs"), Duration.ofSeconds(60), apiKey);
        backend = new E2bBackend(rest, shim, RUNNER_ID);
    }

    @Override
    protected IsolationBackend backend() { return backend; }

    @Override
    protected String imageRef() { return System.getenv().getOrDefault("E2B_CONFORMANCE_TEMPLATE", "base"); }

    @Override
    protected boolean enforcesEgress() { return true; }

    @Override
    protected boolean filtersDns() { return false; }   // accepted gap for dev/testing (spec §5)

    @Override
    protected Duration terminalWait() { return Duration.ofSeconds(90); }
}
```

- [ ] **Step 2: Check the tools in the `base` template before running the suite**

Run from `runner/`, with `E2B_API_KEY` set (this uses a few seconds of credit):
```bash
cd e2b-shim && npm ci
node --input-type=module -e "
const { Sandbox } = await import('e2b');
const s = await Sandbox.create('base', { apiKey: process.env.E2B_API_KEY, timeoutMs: 60000 });
for (const t of ['sh','curl','setsid','nohup','getent']) {
  const r = await s.commands.run('command -v ' + t).catch(e => ({ exitCode: 1 }));
  console.log(t, r.exitCode === 0 ? 'ok' : 'MISSING');
}
await s.kill();
"
cd ..
```
Expected: `sh`, `curl`, `setsid` and `nohup` all print `ok`.
- **If `curl` is missing:** change the curl helper in `IsolationBackendConformanceTest.curl()` to `python3 -c "import urllib.request,sys; urllib.request.urlopen(sys.argv[1], timeout=10)" <url>` and re-run this check for `python3`.
- **If `setsid` is missing:** change the launch in `shim.mjs` to `nohup … &` without `setsid`, and update the matching assertion in `shim.test.mjs`.

- [ ] **Step 3: Run the E2B conformance tests**

Run: `cd runner && E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bBackendConformanceTest`
Expected: all tests PASS except `dnsForUnlistedNamesDoesNotResolve`, which is skipped.

**Diagnosis if a test fails:**
- **`reportsTheExitCodeOfTheCommand` stays RUNNING:** the detached process didn't survive the shim exiting, or the wrapper couldn't write `/workspace/.run`. Inspect it with `commands.run('ls -la /workspace/.run; cat /workspace/.run/log')` from a scratch Node script.
- **`allowlistedHostIsReachable` fails:** E2B's `allowOut` may not accept hostnames on your plan. Check the current E2B network docs and adapt `E2bRestClient.create` (for example, resolve the host to IPs). Keep the conformance test unchanged.
- **Any egress-blocking test fails (`unlistedHostIsBlocked`, `rawIpIsBlocked`, `cloudMetadataAddressIsBlocked`):** this is a **security finding**. Stop and report it to the user; don't weaken the test.

- [ ] **Step 4: Remove the obsolete note in the backend's live test**

Nothing to edit yet. `E2bSandboxProviderLiveTest` (which said "the unreachable assertion is added in WS5") is deleted in Task 8, and this suite now carries that assertion.

- [ ] **Step 5: Commit**

```bash
git add runner/src/test
git commit -m "test(runner): run the backend conformance suite against real E2B, including egress"
```

---

### Task 8: Make Spring's sandbox types vendor-neutral and delete `E2bSandboxProvider`

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/execution/SandboxSpec.java`, `SandboxStatus.java`, `FakeSandboxProvider.java`, `RunReaper.java`, `RunController.java`
- Delete: `backend/src/main/java/com/agentstore/platform/execution/E2bSandboxProvider.java`, `backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderTest.java`, `backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderLiveTest.java`
- Modify: `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml`
- Modify tests: `RunControllerTest.java`, `RunVersionPinningTest.java`, `RunReaperTest.java`

**Interfaces:**
- Produces:
  - `record SandboxSpec(String requestId, String imageRef, List<String> command, Map<String,String> envVars, List<String> allowOut, int cpu, int memoryMb, int timeoutSeconds)`
  - `record SandboxStatus(State state, Integer exitCode)` with `enum State { RUNNING, EXITED, GONE, UNKNOWN }`, statics `RUNNING`, `GONE`, `UNKNOWN`, `exited(int)`, and `boolean stopped()` (true for EXITED or GONE)
  - `FakeSandboxProvider.markExited(String id, int exitCode)`
  - `RunController.SANDBOX_CPU = 1`, `SANDBOX_MEMORY_MB = 1024`

- [ ] **Step 1: Update the tests first**

In `RunControllerTest.triggeringARunCreatesASandboxWithManifestAllowlist`, replace
```java
assertThat(lastSpec.templateId()).isEqualTo("collections-template-v1");
```
with
```java
assertThat(lastSpec.imageRef()).isEqualTo("collections-template-v1");
assertThat(lastSpec.requestId()).isEqualTo(saved.getId().toString());
assertThat(lastSpec.command()).containsExactly("sh", "-c", "/workspace/app/agentstore-entrypoint");
assertThat(lastSpec.cpu()).isEqualTo(RunController.SANDBOX_CPU);
assertThat(lastSpec.memoryMb()).isEqualTo(RunController.SANDBOX_MEMORY_MB);
```

In `RunVersionPinningTest`, replace `.templateId()` with `.imageRef()`.

In `RunReaperTest`, add:
```java
@Test
void runningRunWhoseSandboxIsGoneIsFailed() {
    String sandboxId = sandbox();
    Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.plusSeconds(600), null);
    fakeSandboxProvider.kill(sandboxId);

    reaper.reapAt(now);

    assertThat(reload(run).getStatus()).isEqualTo(RunStatus.FAILED);
}

@Test
void failureRecordsTheAgentExitCode() {
    String sandboxId = sandbox();
    Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.plusSeconds(600), null);
    fakeSandboxProvider.markExited(sandboxId, 3);

    reaper.reapAt(now);

    assertThat(reload(run).getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
            .singleElement().satisfies(step -> assertThat(step.getDetail()).contains("\"exitCode\":3"));
}

@Test
void unknownSandboxStatusLeavesTheRunAlone() {
    String sandboxId = sandbox();
    Run run = run(scenario(), RunStatus.RUNNING, sandboxId, now.plusSeconds(600), null);
    fakeSandboxProvider.markUnknown(sandboxId);

    reaper.reapAt(now);

    assertThat(reload(run).getStatus()).isEqualTo(RunStatus.RUNNING);
}
```
Check the accessor for a step's JSON detail in `RunStep.java` (for example `getDetail()` or `getDetailJson()`) and use that name.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RunControllerTest,RunVersionPinningTest,RunReaperTest'`
Expected: compilation FAILURE (`imageRef()`, `requestId()`, `markExited(String,int)` and `markUnknown` don't exist).

- [ ] **Step 3: Implement the types**

`SandboxSpec.java`:
```java
package com.agentstore.platform.execution;

import java.util.List;
import java.util.Map;

/** Vendor-neutral sandbox request. {@code requestId} is the run id and makes creation idempotent in the runner. */
public record SandboxSpec(String requestId, String imageRef, List<String> command, Map<String, String> envVars,
                          List<String> allowOut, int cpu, int memoryMb, int timeoutSeconds) {
    @Override
    public String toString() {
        return "SandboxSpec[requestId=" + requestId + ", imageRef=" + imageRef + ", envKeys=" + envVars.keySet() + "]";
    }
}
```

`SandboxStatus.java`:
```java
package com.agentstore.platform.execution;

/** UNKNOWN means the provider could not be asked; callers must not treat it as stopped. */
public record SandboxStatus(State state, Integer exitCode) {

    public enum State { RUNNING, EXITED, GONE, UNKNOWN }

    public static final SandboxStatus RUNNING = new SandboxStatus(State.RUNNING, null);
    public static final SandboxStatus GONE = new SandboxStatus(State.GONE, null);
    public static final SandboxStatus UNKNOWN = new SandboxStatus(State.UNKNOWN, null);

    public static SandboxStatus exited(int exitCode) {
        return new SandboxStatus(State.EXITED, exitCode);
    }

    public boolean stopped() {
        return state == State.EXITED || state == State.GONE;
    }
}
```

`FakeSandboxProvider.java`: change the profile, the status and the exited tracking.
```java
@Component
@Profile("!runner")
public class FakeSandboxProvider implements SandboxProvider {
    // ... existing fields; replace `exited` Set with:
    private final Map<String, Integer> exited = new ConcurrentHashMap<>();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();

    @Override
    public SandboxStatus status(String sandboxId) {
        if (unknown.contains(sandboxId)) {
            return SandboxStatus.UNKNOWN;
        }
        if (killed.contains(sandboxId)) {
            return SandboxStatus.GONE;
        }
        Integer code = exited.get(sandboxId);
        return code == null ? SandboxStatus.RUNNING : SandboxStatus.exited(code);
    }

    public void markExited(String sandboxId) {
        markExited(sandboxId, 0);
    }

    public void markExited(String sandboxId, int exitCode) {
        exited.put(sandboxId, exitCode);
    }

    public void markUnknown(String sandboxId) {
        unknown.add(sandboxId);
    }
    // specForRun unchanged (still reads envVars().get("AGENT_RUN_ID"))
}
```
Add `import java.util.Map;`.

`RunReaper.java`: replace the last `if` block in `reap`:
```java
        if (snapshotStatus == RunStatus.RUNNING && run.getSandboxId() != null) {
            SandboxStatus sandbox = sandboxProvider.status(run.getSandboxId());
            if (sandbox.stopped()) {
                Map<String, Object> detail = new java.util.HashMap<>();
                detail.put("summary", "Agent stopped without reporting completion");
                if (sandbox.exitCode() != null) {
                    detail.put("exitCode", sandbox.exitCode());
                }
                return runLifecycleService.terminate(run.getId(), snapshotStatus, RunStatus.FAILED,
                        RunStepKind.FAILED, detail);
            }
        }
        return false;
```

`RunController.java`:
- Add constants `static final int SANDBOX_CPU = 1;` and `static final int SANDBOX_MEMORY_MB = 1024;`.
- Add a constructor parameter `@Value("${agentstore.runs.agent-command:/workspace/app/agentstore-entrypoint}") String agentCommand` and store it in a field.
- Replace the `sandboxProvider.create(...)` call with:
```java
            handle = sandboxProvider.create(new SandboxSpec(run.getId().toString(), version.getImageRef(),
                    List.of("sh", "-c", agentCommand), envVars, allowOut, SANDBOX_CPU, SANDBOX_MEMORY_MB,
                    SANDBOX_TIMEOUT_SECONDS));
```
Add a comment above the field: `// WS5a stand-in; WS5b replaces it with the manifest entrypoint.`

- [ ] **Step 4: Delete the E2B provider and its config**

```bash
git rm backend/src/main/java/com/agentstore/platform/execution/E2bSandboxProvider.java \
       backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderTest.java \
       backend/src/test/java/com/agentstore/platform/execution/E2bSandboxProviderLiveTest.java
```
- In `backend/src/main/resources/application.yml`, delete the whole `agentstore.e2b:` block and add `agent-command: ${AGENTSTORE_RUNS_AGENT_COMMAND:/workspace/app/agentstore-entrypoint}` under `agentstore.runs:`.
- In `backend/src/test/resources/application-test.yml`, delete the `e2b:` block.
- Run `grep -rn "E2bSandboxProvider\|agentstore.e2b\|Profile(\"e2b\")\|templateId" backend/src` and update any remaining hit. Comment-only mentions in `RunReaper`/`RunController` should now refer to the runner.

- [ ] **Step 5: Run the whole backend test suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS, with no remaining references to `templateId` and none to the enum-style `SandboxStatus.EXITED` (use `SandboxStatus.exited(0)` or `.stopped()`).

- [ ] **Step 6: Commit**

```bash
git add -A backend
git commit -m "refactor(execution): vendor-neutral SandboxSpec/SandboxStatus and remove E2bSandboxProvider"
```

---

### Task 9: `RunnerSandboxProvider`, capacity errors and the active-runs endpoint

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/execution/{RunnerSandboxProvider,SandboxCapacityException,InternalRunsController}.java`
- Modify: `backend/src/main/java/com/agentstore/platform/execution/{RunController,FakeSandboxProvider}.java`, `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/agentstore/platform/execution/{RunnerSandboxProviderTest,InternalRunsControllerTest}.java`, `RunControllerTest.java`

**Interfaces:**
- Consumes: the runner HTTP contract (Task 4), `SandboxSpec`/`SandboxStatus` (Task 8).
- Produces:
  - `SandboxCapacityException(String code)` with `code()`.
  - `GET /internal/runs/active-ids`, with the `X-Runner-Secret` header, returns `{"runIds":[uuid,…]}` for runs in PENDING, RUNNING or AWAITING_APPROVAL. It returns 401 when the secret is missing or wrong, **and always 401 when `agentstore.runner.secret` is blank**.
  - `FakeSandboxProvider.failNextCreateWithCapacity(String code)`.

- [ ] **Step 1: Write the failing tests**

`RunnerSandboxProviderTest.java`:
```java
package com.agentstore.platform.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RunnerSandboxProviderTest {

    MockRestServiceServer server;
    RunnerSandboxProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RunnerSandboxProvider(builder, "http://runner.test", "shh");
    }

    SandboxSpec spec() {
        return new SandboxSpec("run-1", "img", List.of("sh", "-c", "go"), Map.of("K", "V"), List.of("example.com"),
                1, 1024, 900);
    }

    @Test
    void createPostsTheSpecWithTheSecret() {
        server.expect(requestTo("http://runner.test/sandboxes")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Runner-Secret", "shh"))
                .andExpect(jsonPath("$.requestId").value("run-1"))
                .andExpect(jsonPath("$.imageRef").value("img"))
                .andExpect(jsonPath("$.command[2]").value("go"))
                .andExpect(jsonPath("$.env.K").value("V"))
                .andExpect(jsonPath("$.allowOut[0]").value("example.com"))
                .andExpect(jsonPath("$.timeoutSeconds").value(900))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sandboxId\":\"sbx-1\"}"));

        assertThat(provider.create(spec()).sandboxId()).isEqualTo("sbx-1");
        server.verify();
    }

    @Test
    void create429BecomesSandboxCapacityExceptionWithTheCode() {
        server.expect(requestTo("http://runner.test/sandboxes"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BUDGET_EXHAUSTED\",\"message\":\"m\"}"));

        assertThatThrownBy(() -> provider.create(spec()))
                .isInstanceOfSatisfying(SandboxCapacityException.class,
                        e -> assertThat(e.code()).isEqualTo("BUDGET_EXHAUSTED"));
    }

    @Test
    void statusMapsStateAndExitCode() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1"))
                .andRespond(withSuccess("{\"state\":\"EXITED\",\"exitCode\":3}", MediaType.APPLICATION_JSON));
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.exited(3));
    }

    @Test
    void unreachableRunnerIsUnknown() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1")).andRespond(withServerError());
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.UNKNOWN);
    }

    @Test
    void killDeletes() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1")).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Runner-Secret", "shh"))
                .andRespond(withNoContent());
        provider.kill("sbx-1");
        server.verify();
    }
}
```

`InternalRunsControllerTest.java`:
```java
package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.runner.secret=runner-test-secret")
class InternalRunsControllerTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ExecutionFixtures fixtures;

    String url() { return "http://localhost:" + port + "/internal/runs/active-ids"; }

    HttpEntity<Void> withSecret(String secret) {
        HttpHeaders h = new HttpHeaders();
        if (secret != null) h.set("X-Runner-Secret", secret);
        return new HttpEntity<>(h);
    }

    @Test
    void listsActiveRunsOnly() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        Run running = fixtures.run(s, RunStatus.RUNNING, "sbx-a", "tok-" + UUID.randomUUID());
        Run done = fixtures.run(s, RunStatus.COMPLETED, "sbx-b", "tok-" + UUID.randomUUID());

        var response = rest.exchange(url(), HttpMethod.GET, withSecret("runner-test-secret"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> ids = (List<?>) response.getBody().get("runIds");
        assertThat(ids).contains(running.getId().toString()).doesNotContain(done.getId().toString());
    }

    @Test
    void wrongOrMissingSecretIsUnauthorized() {
        assertThat(rest.exchange(url(), HttpMethod.GET, withSecret("nope"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(url(), HttpMethod.GET, withSecret(null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

`@TestPropertySource` creates a separate Spring context. That's acceptable for one test class.

Add to `RunControllerTest`:
```java
@Test
void noSandboxCapacityFailsTheRunWith503AndReleasesTheReservation() {
    var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
    fakeSandboxProvider.failNextCreateWithCapacity("CAPACITY_EXHAUSTED");

    var response = rest.postForEntity(url("/api/v1/runs"),
            new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("CAPACITY_EXHAUSTED");
    var run = runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(s.buyer().getId()).get(0);
    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
    assertThat(run.getReservedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
}
```

`ResponseStatusException` reasons appear in the body only if `server.error.include-message` is enabled. **Check how `RunControllerTest.sandboxCreationFailureFailsTheRunAndReturnsBadGateway` reads bodies.** If messages aren't included, drop the `contains("CAPACITY_EXHAUSTED")` assertion and instead assert on the run step detail: `runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())` has a FAILED step whose detail contains `"code":"CAPACITY_EXHAUSTED"`. Autowire `RunStepRepository` if the class doesn't already.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest='RunnerSandboxProviderTest,InternalRunsControllerTest,RunControllerTest'`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

`SandboxCapacityException.java`:
```java
package com.agentstore.platform.execution;

/** The runner refused to start a sandbox right now (CAPACITY_EXHAUSTED) or this month (BUDGET_EXHAUSTED). */
public class SandboxCapacityException extends RuntimeException {
    private final String code;

    public SandboxCapacityException(String code) {
        super("Sandbox runner refused: " + code);
        this.code = code;
    }

    public String code() { return code; }
}
```

`RunnerSandboxProvider.java`:
```java
package com.agentstore.platform.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Talks to the internal sandbox-runner service (WS5 spec §3). The runner owns all vendor credentials. */
@Component
@Profile("runner")
public class RunnerSandboxProvider implements SandboxProvider {

    private final RestClient restClient;

    @Autowired
    public RunnerSandboxProvider(RestClient.Builder builder,
                                 @Value("${agentstore.runner.base-url}") String baseUrl,
                                 @Value("${agentstore.runner.secret}") String secret,
                                 @Value("${agentstore.runner.connect-timeout:PT5S}") Duration connectTimeout,
                                 @Value("${agentstore.runner.read-timeout:PT90S}") Duration readTimeout) {
        // Unbounded calls would hang the reaper and every lifecycle call waiting on the run row lock.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Runner-Secret", secret).build();
    }

    /** Test-only: keeps the caller's request factory (e.g. MockRestServiceServer). */
    RunnerSandboxProvider(RestClient.Builder builder, String baseUrl, String secret) {
        this.restClient = builder.baseUrl(baseUrl).defaultHeader("X-Runner-Secret", secret).build();
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", spec.requestId());
        body.put("imageRef", spec.imageRef());
        body.put("command", spec.command());
        body.put("env", spec.envVars());
        body.put("allowOut", spec.allowOut());
        body.put("cpu", spec.cpu());
        body.put("memoryMb", spec.memoryMb());
        body.put("timeoutSeconds", spec.timeoutSeconds());
        try {
            Map<?, ?> response = restClient.post().uri("/sandboxes")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Map.class);
            return new SandboxHandle((String) response.get("sandboxId"));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                Map<?, ?> error = e.getResponseBodyAs(Map.class);
                throw new SandboxCapacityException(error == null ? "CAPACITY_EXHAUSTED" : String.valueOf(error.get("code")));
            }
            throw e;
        }
    }

    @Override
    public void kill(String sandboxId) {
        restClient.delete().uri("/sandboxes/{id}", sandboxId).retrieve().toBodilessEntity();
    }

    @Override
    public SandboxStatus status(String sandboxId) {
        try {
            Map<?, ?> body = restClient.get().uri("/sandboxes/{id}", sandboxId).retrieve().body(Map.class);
            SandboxStatus.State state = SandboxStatus.State.valueOf(String.valueOf(body.get("state")));
            Object code = body.get("exitCode");
            return new SandboxStatus(state, code == null ? null : ((Number) code).intValue());
        } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
            return SandboxStatus.UNKNOWN;
        }
    }
}
```

`InternalRunsController.java`:
```java
package com.agentstore.platform.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Lets the sandbox-runner find orphaned sandboxes on startup (WS5 spec §5). */
@RestController
public class InternalRunsController {

    private static final List<RunStatus> ACTIVE =
            List.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final RunRepository runRepository;
    private final byte[] runnerSecret;

    public InternalRunsController(RunRepository runRepository,
                                  @Value("${agentstore.runner.secret:}") String runnerSecret) {
        this.runRepository = runRepository;
        this.runnerSecret = runnerSecret.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/runs/active-ids")
    public Map<String, List<String>> activeIds(@RequestHeader(value = "X-Runner-Secret", required = false) String secret) {
        byte[] given = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (runnerSecret.length == 0 || !MessageDigest.isEqual(given, runnerSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runner secret");
        }
        return Map.of("runIds", runRepository.findByStatusIn(ACTIVE).stream().map(r -> r.getId().toString()).toList());
    }
}
```

`FakeSandboxProvider`: add a field `private final AtomicReference<String> capacityCode = new AtomicReference<>();`. At the top of `create`:
```java
        String code = capacityCode.getAndSet(null);
        if (code != null) {
            throw new SandboxCapacityException(code);
        }
```
and a method:
```java
    public void failNextCreateWithCapacity(String code) {
        capacityCode.set(code);
    }
```

`RunController.launch`: add a catch **before** the existing `catch (RuntimeException e)`:
```java
        } catch (SandboxCapacityException e) {
            runLifecycleService.terminate(run.getId(), RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "No sandbox capacity right now; try again shortly", "code", e.code()));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e);
```

`backend/src/main/resources/application.yml`: add under `agentstore:`
```yaml
  runner:
    base-url: ${RUNNER_BASE_URL:http://localhost:8090}
    secret: ${RUNNER_SECRET:}
    connect-timeout: PT5S
    read-timeout: PT90S
```
(The 90-second read timeout covers create, which runs the shim. The runner's shim timeout is 60 seconds.)

`application-test.yml` needs no runner keys: the `test` profile uses `FakeSandboxProvider`, and `InternalRunsControllerTest` sets its own secret.

- [ ] **Step 4: Run the whole backend suite**

Run: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(execution): add RunnerSandboxProvider, capacity errors and runner active-runs endpoint"
```

---

### Task 10: Runner README and the manual end-to-end check on E2B

**Files:**
- Create: `runner/README.md`

This task proves the §1 success criterion "a triggered run starts the agent, which reaches Spring and exits". It needs the user's E2B key and `cloudflared`. **Stop and ask the user before running Step 2**, because it spends credit and needs their keys.

- [ ] **Step 1: Write `runner/README.md`**

````markdown
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
| `RUNNER_BACKEND` | `e2b` | `e2b` now; `gvisor` arrives in WS5d. |
| `E2B_API_KEY` | (required for e2b) | Only the runner has it. |
| `SPRING_BASE_URL` | `http://localhost:8080` | Used on startup to find orphaned sandboxes. |
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
````

- [ ] **Step 2: Manual end-to-end check** (ask the user first; it needs their E2B key)

1. Start a disposable Postgres (the shared `localhost:5432` database is stale at V11):
   `docker run -d --rm --name ws5a-pg -p 5433:5432 -e POSTGRES_DB=agentstore -e POSTGRES_USER=agentstore -e POSTGRES_PASSWORD=agentstore postgres:16-alpine`
2. Start the tunnel with `cloudflared tunnel --url http://localhost:8080` and note the `https://….trycloudflare.com` URL.
3. Start the backend (from `backend/`):
   ```bash
   SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/agentstore SPRING_PROFILES_ACTIVE=runner \
   RUNNER_SECRET=dev-runner-secret CALLBACK_BASE_URL=https://<tunnel> \
   AGENTSTORE_RUNS_AGENT_COMMAND='curl -sS -X POST -H "Authorization: Bearer $AGENT_CALLBACK_TOKEN" -H "Content-Type: application/json" -d "{\"kind\":\"COMPLETE\",\"detail\":{\"summary\":\"ws5a smoke\"}}" "$AGENT_CALLBACK_URL/events"' \
   mvn spring-boot:run
   ```
4. Start the runner as described in the README (Step 1).
5. Seed a buyer, a developer agent version and a connection. Use `frontend`'s `npm run seed:e2e` pointed at port 5433, or the existing test fixtures as a guide. Then run `UPDATE agent_version SET image_ref = 'base';` so the run uses E2B's `base` template.
6. Trigger a run: sign in as the seeded buyer admin, then `POST /api/v1/runs {"connectionId": "<id>"}`.
7. **Expected:**
   - The run moves `RUNNING` → `COMPLETED` within about 30 seconds, with a COMPLETE step whose summary is "ws5a smoke".
   - The runner log shows one create and one kill.
   - `GET http://localhost:8090/sandboxes/<sandboxId>` (with the secret) returns `GONE` afterwards.
8. **Negative check:** stop the runner and trigger again. The run must end `FAILED` with 502 and the wallet reservation released; no sandbox should be left running in the E2B dashboard.

Record the outcome (pass or fail, run ID, anything unexpected) in the PR description.

- [ ] **Step 3: Commit**

```bash
git add runner/README.md
git commit -m "docs(runner): document running the sandbox runner and the WS5a end-to-end check"
```

---

## Self-review notes (for the executor)

- **Spec coverage for WS5a:**
  - §2 architecture: Tasks 1, 4 and 6.
  - §2.1 interface: Task 1 (**`buildImage` deferred to WS5b by design**).
  - §2.2 wrapper: Tasks 5 and 6.
  - §2.3 shim: Tasks 5 and 6.
  - §3 runner API: Task 4 (`/builds` is WS5b).
  - Spring-side changes: Tasks 8 and 9.
  - §5 egress: Task 7.
  - §5 secrets: Tasks 4, 5 and 6.
  - §5 budget guard: Task 3.
  - §5 reaper: Tasks 4 and 9.
  - §7 testing items 1–4 and 6: Tasks 1–10 (item 5, the CLI, is WS5c).
- **Deliberate deviations from the spec, to raise with the user:**
  - The concurrency cap returns `CAPACITY_EXHAUSTED`, not `BUDGET_EXHAUSTED`.
  - DNS filtering isn't asserted on E2B.
  - Logs are readable only while the sandbox exists (the MinIO `LogSink` is WS5b).
  - `imageRef` travels inside `SandboxLaunch` instead of as a separate `create` argument.
