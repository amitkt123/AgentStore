package com.agentstore.runner.build;

import com.agentstore.runner.backend.AgentRuntime;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.ImageBuild;
import com.agentstore.runner.backend.IsolationBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);

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
            try {
                dir = Files.createTempDirectory(Files.createDirectories(workDir), "build-");
                imageRef = build(entry, dir);
            } catch (BuildFailedException e) {
                code = e.code();
                reason = LogRedactor.redact(String.valueOf(e.getMessage()));
            } catch (Throwable e) {
                // Anything else (BackendException, an unexpected RuntimeException, even an Error from a
                // misbehaving backend) must still end the build, never leave it stuck in BUILDING.
                code = BuildFailedException.BUILD_FAILED;
                reason = LogRedactor.redact("Build failed: " + describe(e));
            }
        } finally {
            watchdog.cancel(false);
            flusher.cancel(false);
            Thread.interrupted();   // clear a watchdog interrupt that landed as the work finished
        }
        if (imageRef == null && entry.timedOut) {
            code = BuildFailedException.TIMEOUT;
            reason = "The build took longer than " + timeout;
        }
        String finalImageRef = imageRef;
        String finalCode = code;
        String finalReason = reason;
        try {
            entry.log.append(finalImageRef != null ? "Build succeeded: " + finalImageRef
                    : "Build failed: " + finalCode + ": " + finalReason);
            flush(entry);
        } catch (RuntimeException e) {
            log.warn("Could not publish the final build log for {}: {}", entry.cmd.buildId(), describe(e));
        }
        try {
            deleteRecursively(dir);
        } catch (RuntimeException e) {
            log.warn("Could not remove the build directory for {}: {}", entry.cmd.buildId(), describe(e));
        } finally {
            // Always published, however the two blocks above went: a poller must never see a build stuck
            // in BUILDING because a log flush or a directory cleanup misbehaved.
            entry.imageRef = finalImageRef;
            entry.failureCode = finalCode;
            entry.failureReason = finalReason;
            entry.finishedAt = clock.instant();
            entry.status = finalImageRef != null ? Status.SUCCEEDED : Status.FAILED;
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
        } catch (IOException | UncheckedIOException ignored) {
            // best effort: Files.walk's stream can throw UncheckedIOException lazily during iteration
        }
    }
}
