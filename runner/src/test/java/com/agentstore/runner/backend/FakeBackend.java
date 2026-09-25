package com.agentstore.runner.backend;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    /** Test hook: an app dir containing this file fails the build with the failure code written in it. */
    public static final String FAIL_MARKER = ".fake-build-fail";
    /** Test hook: an app dir containing this file has its content appended to the build log. */
    public static final String LOG_MARKER = ".fake-build-log";

    public final AtomicInteger buildCalls = new AtomicInteger();
    public volatile Duration buildDelay = Duration.ZERO;

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
