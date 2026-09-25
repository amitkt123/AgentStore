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
    public String buildImage(ImageBuild build, BuildLog log) {
        // Replaced in WS5b Task 3 by the shim-driven image build.
        throw new BuildFailedException(BuildFailedException.BUILD_FAILED, "Image builds are not wired for this backend yet");
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
