package com.agentstore.platform.execution;

import com.agentstore.platform.build.RebuildService;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.data.BindingResolver;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.ledger.InsufficientFundsException;
import com.agentstore.platform.ledger.WalletReservationService;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    /** Matches the sandbox runner's default max-lifetime (PT10M), so a run hits its own TIMED_OUT first. */
    public static final int SANDBOX_TIMEOUT_SECONDS = 600;
    static final int SANDBOX_CPU = 1;
    static final int SANDBOX_MEMORY_MB = 1024;

    private final ConnectionRepository connectionRepository;
    private final RunRepository runRepository;
    private final ManifestParser manifestParser;
    private final RunTokenService runTokenService;
    private final WalletReservationService walletReservationService;
    private final GateService gateService;
    private final RunLauncher runLauncher;
    private final RebuildService rebuildService;
    private final PendingBuildRuns pendingBuildRuns;
    private final BindingResolver bindingResolver;
    private final RunLookup runLookup;
    private final ConnectionLimits connectionLimits;
    private final TransactionTemplate transactions;

    public RunController(ConnectionRepository connectionRepository, RunRepository runRepository,
                         ManifestParser manifestParser, RunTokenService runTokenService,
                         WalletReservationService walletReservationService, GateService gateService,
                         RunLauncher runLauncher, RebuildService rebuildService, PendingBuildRuns pendingBuildRuns,
                         BindingResolver bindingResolver, RunLookup runLookup, ConnectionLimits connectionLimits,
                         PlatformTransactionManager transactionManager) {
        this.connectionRepository = connectionRepository;
        this.runRepository = runRepository;
        this.manifestParser = manifestParser;
        this.runTokenService = runTokenService;
        this.walletReservationService = walletReservationService;
        this.gateService = gateService;
        this.runLauncher = runLauncher;
        this.rebuildService = rebuildService;
        this.pendingBuildRuns = pendingBuildRuns;
        this.bindingResolver = bindingResolver;
        this.runLookup = runLookup;
        this.connectionLimits = connectionLimits;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** How long a run may wait for its agent to be rebuilt before the reaper times it out. */
    public static final Duration PENDING_BUILD_TIMEOUT = Duration.ofMinutes(25);

    @PostMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse trigger(@Valid @RequestBody TriggerRunRequest request) {
        Connection connection = connectionRepository.findById(request.connectionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!connection.getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connection not owned by caller's org");
        }
        return start(connection);
    }

    /** Starts a new run on the run's own connection, at the connection's current pinned version and bindings. */
    @PostMapping("/{runId}/rerun")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public RunResponse rerun(@PathVariable("runId") String runRef) {
        UUID runId = runLookup.idOf(runRef);
        requireOwnedByCaller(runId);
        Run original = runRepository.findById(runId).orElseThrow();
        return start(original.getConnection());
    }

    private RunResponse start(Connection connection) {
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
        Manifest manifest = manifestParser.parse(version.getManifestJson());   // fail fast on a corrupt manifest, before reserving
        List<BindingResolver.ResolvedPin> pins = bindingResolver.resolve(connection, manifest, RunMode.PRODUCTION);   // MISSING_BINDING before any money is reserved

        UUID buyerOrgId = connection.getBuyerOrg().getId();
        BigDecimal spendCap = connection.getSpendCap();

        boolean waitForRebuild = rebuildService.needsRebuild(version);
        Instant now = Instant.now();
        // The launcher issues the real callback token; this placeholder hash only satisfies NOT NULL until then.
        Run pending = new Run(null, connection, version, waitForRebuild ? RunStatus.PENDING_BUILD : RunStatus.PENDING,
                spendCap, BigDecimal.ZERO, null, RunTokenService.hash(runTokenService.generate()));
        pending.setReservedAmount(spendCap);
        pending.setStartedAt(now);
        pending.setDeadlineAt(waitForRebuild ? now.plus(PENDING_BUILD_TIMEOUT) : now.plusSeconds(SANDBOX_TIMEOUT_SECONDS));
        if (waitForRebuild) {
            rebuildService.queueRebuild(version);
        }
        // One transaction: the monthly limit is checked under the connection's row lock, then the wallet is held and the
        // run saved, so concurrent triggers see each other's runs and any failure rolls the reservation back.
        Run run = transactions.execute(status -> {
            connectionLimits.enforce(connection.getId(), spendCap);
            try {
                walletReservationService.reserve(buyerOrgId, spendCap);
            } catch (InsufficientFundsException e) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
            }
            return bindingResolver.saveWithPins(pending, pins);
        });
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
    public RunResponse approve(@PathVariable("runId") String runRef, @RequestBody ApproveRunRequest request) {
        UUID runId = runLookup.idOf(runRef);
        requireOwnedByCaller(runId);
        return RunResponse.from(gateService.decide(runId, null, true, callerUserId(), request.notes(),
                DecisionChannel.WEB));
    }

    @PostMapping("/{runId}/reject")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse reject(@PathVariable("runId") String runRef, @RequestBody ApproveRunRequest request) {
        UUID runId = runLookup.idOf(runRef);
        requireOwnedByCaller(runId);
        return RunResponse.from(gateService.decide(runId, null, false, callerUserId(), request.notes(),
                DecisionChannel.WEB));
    }

    @PostMapping("/{runId}/gates/{gateId}/decision")
    @PreAuthorize("hasRole('BUYER_APPROVER')")
    public RunResponse decide(@PathVariable("runId") String runRef, @PathVariable UUID gateId,
                              @Valid @RequestBody GateDecisionRequest request) {
        UUID runId = runLookup.idOf(runRef);
        requireOwnedByCaller(runId);
        return RunResponse.from(gateService.decide(runId, gateId, request.decision() == GateDecision.APPROVE,
                callerUserId(), request.note(),
                DecisionChannel.WEB));
    }

    private UUID callerUserId() {
        return ((JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).userId();
    }

    private void requireOwnedByCaller(UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        if (run.getConnection() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");   // a sandbox run is not a buyer's
        }
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!run.getConnection().getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
    }
}
