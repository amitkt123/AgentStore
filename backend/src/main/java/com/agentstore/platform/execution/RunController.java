package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.ledger.WalletRepository;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    static final int SANDBOX_TIMEOUT_SECONDS = 900;

    private final ConnectionRepository connectionRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final String callbackBaseUrl;
    private final WalletRepository walletRepository;
    private final RunLifecycleService runLifecycleService;

    public RunController(ConnectionRepository connectionRepository, AgentVersionRepository agentVersionRepository,
                         RunRepository runRepository, SecretRepository secretRepository, SecretService secretService,
                         ManifestParser manifestParser, SandboxProvider sandboxProvider, RunTokenService runTokenService,
                         @Value("${agentstore.callback.base-url}") String callbackBaseUrl,
                         WalletRepository walletRepository, RunLifecycleService runLifecycleService) {
        this.connectionRepository = connectionRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.callbackBaseUrl = callbackBaseUrl;
        this.walletRepository = walletRepository;
        this.runLifecycleService = runLifecycleService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection not active");
        }
        var wallet = walletRepository.findByOrgId(connection.getBuyerOrg().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "No wallet for org"));
        if (wallet.getBalance().compareTo(connection.getSpendCap()) < 0) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Insufficient wallet balance for spend cap");
        }
        AgentVersion version = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(connection.getAgent().getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));
        var manifest = manifestParser.parse(version.getManifestJson());

        String rawToken = runTokenService.generate();
        Instant now = Instant.now();
        Run pending = new Run(null, connection, version, RunStatus.PENDING, connection.getSpendCap(),
                BigDecimal.ZERO, null, RunTokenService.hash(rawToken));
        pending.setStartedAt(now);
        pending.setDeadlineAt(now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        Run run = runRepository.save(pending);

        return launch(run, connection, version, manifest.networkAllowlist(), rawToken);
    }

    private RunResponse launch(Run run, Connection connection, AgentVersion version, List<String> manifestAllowlist,
                               String rawToken) {
        Map<String, String> envVars = new HashMap<>();
        envVars.put("AGENT_RUN_ID", run.getId().toString());
        envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + run.getId());
        envVars.put("AGENT_CALLBACK_TOKEN", rawToken);
        for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
            envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
        }

        List<String> allowOut = new ArrayList<>();
        allowOut.add(URI.create(callbackBaseUrl).getHost());
        allowOut.addAll(manifestAllowlist);

        SandboxHandle handle;
        try {
            handle = sandboxProvider.create(new SandboxSpec(version.getImageRef(), SANDBOX_TIMEOUT_SECONDS, envVars,
                    allowOut));
        } catch (RuntimeException e) {
            runLifecycleService.terminate(run.getId(), RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Sandbox could not be started"));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sandbox could not be started", e);
        }
        runLifecycleService.markStarted(run.getId(), handle.sandboxId());
        return RunResponse.from(runRepository.findById(run.getId()).orElseThrow());
    }

    @PostMapping("/{runId}/approve")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse approve(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(runLifecycleService.approveGate(runId));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse reject(@PathVariable UUID runId, @RequestBody ApproveRunRequest request) {
        requireOwnedByCaller(runId);
        return RunResponse.from(runLifecycleService.rejectGate(runId));
    }

    private void requireOwnedByCaller(UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!run.getConnection().getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
    }
}
