package com.agentstore.platform.execution;

import com.agentstore.platform.build.RebuildService;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.ledger.InsufficientFundsException;
import com.agentstore.platform.ledger.WalletReservationService;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    /** Matches the sandbox runner's default max-lifetime (PT10M), so a run hits its own TIMED_OUT first. */
    static final int SANDBOX_TIMEOUT_SECONDS = 600;
    static final int SANDBOX_CPU = 1;
    static final int SANDBOX_MEMORY_MB = 1024;

    private final ConnectionRepository connectionRepository;
    private final RunRepository runRepository;
    private final ManifestParser manifestParser;
    private final RunTokenService runTokenService;
    private final WalletReservationService walletReservationService;
    private final RunLifecycleService runLifecycleService;
    private final RunLauncher runLauncher;
    private final RebuildService rebuildService;
    private final PendingBuildRuns pendingBuildRuns;

    public RunController(ConnectionRepository connectionRepository, RunRepository runRepository,
                         ManifestParser manifestParser, RunTokenService runTokenService,
                         WalletReservationService walletReservationService, RunLifecycleService runLifecycleService,
                         RunLauncher runLauncher, RebuildService rebuildService, PendingBuildRuns pendingBuildRuns) {
        this.connectionRepository = connectionRepository;
        this.runRepository = runRepository;
        this.manifestParser = manifestParser;
        this.runTokenService = runTokenService;
        this.walletReservationService = walletReservationService;
        this.runLifecycleService = runLifecycleService;
        this.runLauncher = runLauncher;
        this.rebuildService = rebuildService;
        this.pendingBuildRuns = pendingBuildRuns;
    }

    /** How long a run may wait for its agent to be rebuilt before the reaper times it out. */
    static final Duration PENDING_BUILD_TIMEOUT = Duration.ofMinutes(25);

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
        manifestParser.parse(version.getManifestJson());   // fail fast on a corrupt manifest, before reserving

        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();
        try {
            walletReservationService.reserve(buyerOrgId, spendCap);
        } catch (InsufficientFundsException e) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
        }

        boolean waitForRebuild = rebuildService.needsRebuild(version);
        Instant now = Instant.now();
        // The launcher issues the real callback token; this placeholder hash only satisfies NOT NULL until then.
        Run pending = new Run(null, connection, version, waitForRebuild ? RunStatus.PENDING_BUILD : RunStatus.PENDING,
                spendCap, BigDecimal.ZERO, null, RunTokenService.hash(runTokenService.generate()));
        pending.setReservedAmount(spendCap);
        pending.setStartedAt(now);
        pending.setDeadlineAt(waitForRebuild ? now.plus(PENDING_BUILD_TIMEOUT) : now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        Run run;
        try {
            if (waitForRebuild) {
                rebuildService.queueRebuild(version);
            }
            run = runRepository.save(pending);
        } catch (RuntimeException e) {
            walletReservationService.release(buyerOrgId, spendCap);
            throw e;
        }
        if (waitForRebuild) {
            // The build we joined may have finished before this run existed, so the listener never saw it.
            pendingBuildRuns.settle(version.getId(), null);
            return RunResponse.from(runRepository.findById(run.getId()).orElseThrow());
        }
        runLauncher.launch(run.getId());
        return RunResponse.from(runRepository.findById(run.getId()).orElseThrow());
    }

    static String capacitySummary(String code) {
        return "BUDGET_EXHAUSTED".equals(code)
                ? "Monthly sandbox budget reached"
                : "No sandbox capacity right now; try again shortly";
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
