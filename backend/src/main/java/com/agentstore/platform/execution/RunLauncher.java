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
        if (run.getStatus() != RunStatus.PENDING) {
            return;   // reaped or failed since it was promoted: nothing to start
        }
        AgentVersion version = run.getAgentVersion();
        Connection connection = run.getConnection();
        Map<String, String> envVars;
        List<String> allowOut;
        List<String> command;
        try {
            if (version.getImageRef() == null || version.getImageRef().isBlank()) {
                throw new IllegalStateException("Agent version has no built image");
            }
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
