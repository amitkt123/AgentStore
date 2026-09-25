package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.ledger.InsufficientFundsException;
import com.agentstore.platform.ledger.WalletReservationService;
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
    static final int SANDBOX_CPU = 1;
    static final int SANDBOX_MEMORY_MB = 1024;

    private final ConnectionRepository connectionRepository;
    private final RunRepository runRepository;
    private final SecretRepository secretRepository;
    private final SecretService secretService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final RunTokenService runTokenService;
    private final String callbackBaseUrl;
    private final WalletReservationService walletReservationService;
    private final RunLifecycleService runLifecycleService;
    // WS5a stand-in; WS5b replaces it with the manifest entrypoint.
    private final String agentCommand;

    public RunController(ConnectionRepository connectionRepository, RunRepository runRepository,
                         SecretRepository secretRepository, SecretService secretService,
                         ManifestParser manifestParser, SandboxProvider sandboxProvider, RunTokenService runTokenService,
                         @Value("${agentstore.callback.base-url}") String callbackBaseUrl,
                         WalletReservationService walletReservationService, RunLifecycleService runLifecycleService,
                         @Value("${agentstore.runs.agent-command:/workspace/app/agentstore-entrypoint}") String agentCommand) {
        this.connectionRepository = connectionRepository;
        this.runRepository = runRepository;
        this.secretRepository = secretRepository;
        this.secretService = secretService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.runTokenService = runTokenService;
        this.callbackBaseUrl = callbackBaseUrl;
        this.walletReservationService = walletReservationService;
        this.runLifecycleService = runLifecycleService;
        this.agentCommand = agentCommand;
    }

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
        var manifest = manifestParser.parse(version.getManifestJson());

        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();
        try {
            walletReservationService.reserve(buyerOrgId, spendCap);
        } catch (InsufficientFundsException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        }

        String rawToken = runTokenService.generate();
        Instant now = Instant.now();
        Run pending = new Run(null, connection, version, RunStatus.PENDING, spendCap, BigDecimal.ZERO, null,
                RunTokenService.hash(rawToken));
        pending.setReservedAmount(spendCap);
        pending.setStartedAt(now);
        pending.setDeadlineAt(now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        Run run;
        try {
            run = runRepository.save(pending);
        } catch (RuntimeException e) {
            walletReservationService.release(buyerOrgId, spendCap);
            throw e;
        }

        return launch(run, connection, version, manifest.networkAllowlist(), rawToken);
    }

    private RunResponse launch(Run run, Connection connection, AgentVersion version, List<String> manifestAllowlist,
                               String rawToken) {
        Map<String, String> envVars;
        List<String> allowOut;
        try {
            envVars = new HashMap<>();
            envVars.put("AGENT_RUN_ID", run.getId().toString());
            envVars.put("AGENT_CALLBACK_URL", callbackBaseUrl + "/internal/agent-callback/runs/" + run.getId());
            envVars.put("AGENT_CALLBACK_TOKEN", rawToken);
            for (Secret secret : secretRepository.findByConnectionId(connection.getId())) {
                envVars.put("AGENT_SECRET_" + secret.getName(), secretService.reveal(connection.getId(), secret.getName()));
            }

            allowOut = new ArrayList<>();
            allowOut.add(URI.create(callbackBaseUrl).getHost());
            allowOut.addAll(manifestAllowlist);
        } catch (RuntimeException e) {
            runLifecycleService.terminate(run.getId(), RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Run could not be launched"));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Run could not be launched", e);
        }

        SandboxHandle handle;
        try {
            handle = sandboxProvider.create(new SandboxSpec(run.getId().toString(), version.getImageRef(),
                    List.of("sh", "-c", agentCommand), envVars, allowOut, SANDBOX_CPU, SANDBOX_MEMORY_MB,
                    SANDBOX_TIMEOUT_SECONDS));
        } catch (RuntimeException e) {
            runLifecycleService.terminate(run.getId(), RunStatus.PENDING, RunStatus.FAILED, RunStepKind.FAILED,
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
