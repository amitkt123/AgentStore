package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final ConnectionRepository connectionRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final String callbackBaseUrl;

    public RunController(ConnectionRepository connectionRepository, AgentVersionRepository agentVersionRepository,
                          RunRepository runRepository, SecretRepository secretRepository, SecretService secretService,
                          ManifestParser manifestParser, SandboxProvider sandboxProvider, RunTokenService runTokenService,
                          @Value("${agentstore.callback.base-url}") String callbackBaseUrl) {
        this.connectionRepository = connectionRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.callbackBaseUrl = callbackBaseUrl;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        AgentVersion version = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(connection.getAgent().getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));
        var manifest = manifestParser.parse(version.getManifestJson());

        Run run = runRepository.save(new Run(null, connection, version, RunStatus.PENDING, connection.getSpendCap(),
                BigDecimal.ZERO, null, runTokenService.generate()));

        Map<String, String> envVars = new HashMap<>();
        envVars.put("AGENT_RUN_ID", run.getId().toString());
        envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + run.getId());
        envVars.put("AGENT_CALLBACK_TOKEN", run.getCallbackToken());
        for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
            envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
        }

        List<String> allowOut = new ArrayList<>();
        allowOut.add(URI.create(callbackBaseUrl).getHost());
        allowOut.addAll(manifest.networkAllowlist());

        SandboxHandle handle = sandboxProvider.create(new SandboxSpec(version.getImageRef(), 900, envVars, allowOut));

        run.setSandboxId(handle.sandboxId());
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run = runRepository.save(run);

        return RunResponse.from(run);
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse approve(@PathVariable java.util.UUID runId, @RequestBody ApproveRunRequest request) {
        Run run = ownedAwaitingApprovalRun(runId);
        run.setStatus(RunStatus.RUNNING);
        return RunResponse.from(runRepository.save(run));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse reject(@PathVariable java.util.UUID runId, @RequestBody ApproveRunRequest request) {
        Run run = ownedAwaitingApprovalRun(runId);
        run.setStatus(RunStatus.CANCELLED);
        run.setCompletedAt(Instant.now());
        sandboxProvider.kill(run.getSandboxId());
        return RunResponse.from(runRepository.save(run));
    }

    private Run ownedAwaitingApprovalRun(java.util.UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        var claims = (com.agentstore.platform.identity.JwtService.Claims)
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!run.getConnection().getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is not awaiting approval: " + run.getStatus());
        }
        return run;
    }
}
