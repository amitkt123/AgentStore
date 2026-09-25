package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.BuildLog;
import com.agentstore.runner.backend.ImageBuild;
import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.LogChunk;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class E2bBackend implements IsolationBackend {

    private static final Logger log = LoggerFactory.getLogger(E2bBackend.class);
    static final String LOG_PATH = "/workspace/.run/log";
    static final String EXIT_PATH = "/workspace/.run/exit";
    static final Duration DEFAULT_BUILD_TIMEOUT = Duration.ofMinutes(14);
    static final int BUILD_CPU = 1;
    static final int BUILD_MEMORY_MB = 1024;
    private static final Set<String> STEP_CODES = Set.of(BuildFailedException.INSTALL_FAILED,
            BuildFailedException.DEP_VULN, BuildFailedException.SMOKE_FAILED);

    private final E2bRestClient rest;
    private final ShimClient shim;
    private final String runnerId;
    private final Duration buildTimeout;

    public E2bBackend(E2bRestClient rest, ShimClient shim, String runnerId) {
        this(rest, shim, runnerId, DEFAULT_BUILD_TIMEOUT);
    }

    public E2bBackend(E2bRestClient rest, ShimClient shim, String runnerId, Duration buildTimeout) {
        this.rest = rest;
        this.shim = shim;
        this.runnerId = runnerId;
        this.buildTimeout = buildTimeout;
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
            String code = e.code() != null && STEP_CODES.contains(e.code()) ? e.code() : BuildFailedException.BUILD_FAILED;
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
