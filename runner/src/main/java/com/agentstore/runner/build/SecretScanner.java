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

    /**
     * gitleaks auto-loads these from the directory it scans, so a bundle could otherwise switch off its own scan.
     * The scan is platform policy: these are removed from the app directory before gitleaks ever runs.
     */
    private static final List<String> SCAN_POLICY_OVERRIDE_FILES = List.of(".gitleaks.toml", ".gitleaksignore");

    private final List<String> command;
    private final Duration timeout;

    public SecretScanner(List<String> command, Duration timeout) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    public void scan(Path appDir, BuildLog log) {
        removeScanPolicyOverrides(appDir, log);
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
            // gitleaks' own fatal errors also exit 1, but leave no parseable, non-empty findings array: that's
            // BUILD_FAILED, not SECRET_FOUND, otherwise a broken scanner would look like a clean secret finding.
            List<String> findings = describe(report, appDir);
            if (findings.isEmpty()) {
                throw new BuildFailedException(BuildFailedException.BUILD_FAILED,
                        "Secret scan exited reporting findings, but its report could not be read");
            }
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

    private static void removeScanPolicyOverrides(Path appDir, BuildLog log) {
        List<String> removed = new ArrayList<>();
        for (String name : SCAN_POLICY_OVERRIDE_FILES) {
            try {
                if (Files.deleteIfExists(appDir.resolve(name))) {
                    removed.add(name);
                }
            } catch (IOException e) {
                throw new BackendException("Could not remove the bundle's " + name, e);
            }
        }
        if (!removed.isEmpty()) {
            log.append("Secret scan: ignored the bundle's own " + String.join(" and ", removed)
                    + " (scan policy is platform-controlled)");
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
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "Secret scan timed out after " + timeout);
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BackendException("Secret scan interrupted");
        }
    }

    /**
     * "relative/file:line (rule)" per finding; never the matched secret. Returns an empty list if the report
     * doesn't parse, or isn't a non-empty JSON array — the caller treats that as BUILD_FAILED, not SECRET_FOUND,
     * since gitleaks' own fatal errors also exit 1 but leave no usable report.
     */
    static List<String> describe(Path report, Path appDir) {
        JsonNode root;
        try {
            root = MAPPER.readTree(report.toFile());
        } catch (IOException e) {
            return List.of();
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            return List.of();
        }
        Path base = appDir.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (JsonNode finding : root) {
            String file = finding.path("File").asText("?");
            Path path = Path.of(file).normalize();
            if (path.isAbsolute() && path.startsWith(base)) {
                file = base.relativize(path).toString();
            }
            out.add(file + ":" + finding.path("StartLine").asInt(0) + " (" + finding.path("RuleID").asText("secret") + ")");
        }
        return out;
    }
}
