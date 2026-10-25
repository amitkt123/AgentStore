package com.agentstore.platform.execution;

import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.RunLlmCostService;
import com.agentstore.platform.ledger.UsageEvent;
import com.agentstore.platform.ledger.UsageEventRepository;
import com.agentstore.platform.ledger.WalletReservationService;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every run state transition, each under a row lock on the run, so callbacks, buyer decisions, trigger and the
 * reaper cannot overwrite each other. {@link #finish} is the only way a run becomes terminal.
 */
@Service
public class RunLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycleService.class);

    /** A PRODUCTION run that keeps calling outside its granted scopes is stopped at this many violations. */
    static final int MAX_PRODUCTION_VIOLATIONS = 3;
    static final BigDecimal MAX_QUANTITY = new BigDecimal("9999999999");
    static final int MAX_QUANTITY_FRACTION = 4;
    private static final Set<RunStepKind> CLOSING_KINDS = EnumSet.of(RunStepKind.COMPLETE, RunStepKind.FAILED,
            RunStepKind.TIMEOUT, RunStepKind.COST_CAP_REACHED, RunStepKind.CANCELLED);
    private static final Set<RunStepKind> AGENT_REPORTABLE = EnumSet.of(
            RunStepKind.READ, RunStepKind.WRITE, RunStepKind.GATE, RunStepKind.COST,
            RunStepKind.COMPLETE, RunStepKind.FAILED);

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final UsageEventRepository usageEventRepository;
    private final LedgerService ledgerService;
    private final RunLlmCostService runLlmCostService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final WalletReservationService walletReservationService;
    private final GateRequestRepository gateRequestRepository;
    private final ApplicationEventPublisher events;
    private final DomainEventPublisher domainEvents;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunLifecycleService(RunRepository runRepository, RunStepRepository runStepRepository,
                               UsageEventRepository usageEventRepository, LedgerService ledgerService,
                               ManifestParser manifestParser, SandboxProvider sandboxProvider,
                               WalletReservationService walletReservationService,
                               GateRequestRepository gateRequestRepository, ApplicationEventPublisher events,
                               DomainEventPublisher domainEvents, RunLlmCostService runLlmCostService) {
        this.gateRequestRepository = gateRequestRepository;
        this.events = events;
        this.domainEvents = domainEvents;
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.usageEventRepository = usageEventRepository;
        this.ledgerService = ledgerService;
        this.runLlmCostService = runLlmCostService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
        this.walletReservationService = walletReservationService;
    }

    @Transactional
    public void markStarted(UUID runId, String sandboxId) {
        Run run = lock(runId);
        run.setSandboxId(sandboxId);
        if (run.getStatus().isTerminal()) {
            // The agent finished (or the run was stopped) before we recorded its sandbox; don't leave it running.
            killQuietly(sandboxId);
        } else if (run.getStatus() == RunStatus.PENDING) {
            run.setStatus(RunStatus.RUNNING);
        }
        runRepository.save(run);
    }

    /**
     * PENDING_BUILD → PENDING once the rebuild succeeded; the run's time limit restarts. False when the run has
     * moved on meanwhile (reaped, failed), in which case nothing changes.
     */
    @Transactional
    public boolean promotePendingBuild(UUID runId, Instant deadline) {
        Run run = lock(runId);
        if (run.getStatus() != RunStatus.PENDING_BUILD) {
            return false;
        }
        run.setStatus(RunStatus.PENDING);
        run.setDeadlineAt(deadline);
        // The wait for a rebuild is not billed run time (a sandbox run's run-seconds quota excludes PENDING_BUILD only
        // while it stays in that status; once promoted, its elapsed time would otherwise include the whole wait).
        run.setStartedAt(Instant.now());
        runRepository.save(run);
        return true;
    }

    @Transactional
    public void recordEvent(UUID runId, RunStepKind kind, Map<String, Object> detail) {
        if (!AGENT_REPORTABLE.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Step kind is platform-only: " + kind);
        }
        Map<String, Object> safeDetail = detail == null ? Map.of() : detail;
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
        run.setLastHeartbeatAt(Instant.now());

        switch (kind) {
            case GATE -> {
                step(run, kind, safeDetail);
                run.setStatus(RunStatus.AWAITING_APPROVAL);
                run.setAwaitingSince(Instant.now());
                runRepository.save(run);
            }
            case COST -> recordCost(run, safeDetail);
            case COMPLETE -> {
                step(run, kind, safeDetail);
                finish(run, RunStatus.COMPLETED, TerminalReason.FINISHED);
            }
            case FAILED -> {
                step(run, kind, safeDetail);
                finish(run, RunStatus.FAILED, TerminalReason.AGENT_FAILED);
            }
            default -> {
                step(run, kind, safeDetail);
                runRepository.save(run);
            }
        }
    }

    @Transactional
    public Run approveGate(UUID runId) {
        Run run = lock(runId);
        requireAwaitingApproval(run);
        run.setStatus(RunStatus.RUNNING);
        run.setAwaitingSince(null);
        return runRepository.save(run);
    }

    @Transactional
    public Run rejectGate(UUID runId) {
        Run run = lock(runId);
        requireAwaitingApproval(run);
        finish(run, RunStatus.CANCELLED, TerminalReason.GATE_REJECTED);
        return run;
    }

    /** Moves a run to a terminal status. Returns false (and changes nothing) if it was already terminal. */
    @Transactional
    public boolean terminate(UUID runId, RunStatus terminalStatus, TerminalReason reason, RunStepKind stepKind,
                             Map<String, Object> detail) {
        return terminate(runId, null, terminalStatus, reason, stepKind, detail);
    }

    /**
     * Moves a run to a terminal status, but only if its current (locked) status is still {@code expectedStatus}.
     * Returns false (and changes nothing) otherwise. For callers acting on a snapshot that may be stale by the
     * time they get the row lock (the reaper, working off a list it read before the lock) this avoids clobbering
     * a state change — e.g. a buyer's approval — that happened after the snapshot was taken. Passing
     * {@code expectedStatus} as null falls back to the old check: refuse only if already terminal.
     */
    @Transactional
    public boolean terminate(UUID runId, RunStatus expectedStatus, RunStatus terminalStatus, TerminalReason reason,
                             RunStepKind stepKind, Map<String, Object> detail) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal status: " + terminalStatus);
        }
        Run run = lock(runId);
        boolean blocked = expectedStatus != null ? run.getStatus() != expectedStatus : run.getStatus().isTerminal();
        if (blocked) {
            return false;
        }
        if (stepKind != null) {
            step(run, stepKind, detail == null ? Map.of() : detail);
        }
        finish(run, terminalStatus, reason);
        return true;
    }

    /** Records a platform-observed step (DATA_READ, LLM_CALL, …) for a run that is still open. */
    @Transactional
    public void recordStep(UUID runId, RunStepKind kind, RunStepSource source, Map<String, Object> detail) {
        recordStep(runId, kind, source, detail, null);
    }

    /** As above, stamping when the runtime call that caused the step began (null when unknown). */
    @Transactional
    public void recordStep(UUID runId, RunStepKind kind, RunStepSource source, Map<String, Object> detail,
                           Instant startedAt) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        step(run, kind, source, detail == null ? Map.of() : detail, startedAt);
        runRepository.save(run);
    }

    /** Stores what the agent logged, one AGENT_LOG step per entry, clearly marked as agent-reported. */
    @Transactional
    public void recordAgentLogs(UUID runId, List<Map<String, Object>> entries) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        for (Map<String, Object> entry : entries) {
            step(run, RunStepKind.AGENT_LOG, RunStepSource.AGENT, entry);
        }
        runRepository.save(run);
    }

    /** Stores blocked-connection reports, one NETWORK_BLOCKED step per event; agent-sourced because the proxy is untrusted. */
    @Transactional
    public void recordNetworkEvents(UUID runId, List<Map<String, Object>> events) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        for (Map<String, Object> event : events) {
            step(run, RunStepKind.NETWORK_BLOCKED, RunStepSource.AGENT, event);
        }
        runRepository.save(run);
    }

    @Transactional
    public void heartbeat(UUID runId) {
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        runRepository.save(run);
    }

    /**
     * Records a call outside the run's granted scopes. Returns true when this violation stopped the run
     * (PRODUCTION runs stop at {@link #MAX_PRODUCTION_VIOLATIONS}; other modes only report).
     */
    @Transactional
    public boolean recordViolation(UUID runId, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        step(run, RunStepKind.SCOPE_VIOLATION, RunStepSource.PLATFORM, detail);
        run.setViolationCount(run.getViolationCount() + 1);
        boolean stop = run.getMode() == RunMode.PRODUCTION && run.getViolationCount() >= MAX_PRODUCTION_VIOLATIONS;
        if (stop) {
            step(run, RunStepKind.FAILED, RunStepSource.PLATFORM, Map.of(
                    "summary", "Run stopped after repeated calls outside its permissions", "code", "SCOPE_VIOLATIONS"));
            finish(run, RunStatus.FAILED, TerminalReason.SCOPE_VIOLATIONS);
        } else {
            runRepository.save(run);
        }
        return stop;
    }

    /** A usage report from /runtime/v1/usage: same charging, cap clamping and reservation release as the legacy COST event. */
    @Transactional
    public UsageOutcome recordUsage(UUID runId, BigDecimal quantity, Map<String, Object> detail) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
        }
        Run run = lockOpen(runId);
        run.setLastHeartbeatAt(Instant.now());
        return charge(run, quantity, detail == null ? Map.of() : detail, RunStepKind.USAGE, RunStepSource.PLATFORM);
    }

    @Transactional
    public void complete(UUID runId, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A gate is awaiting approval; the run cannot complete yet");
        }
        step(run, RunStepKind.COMPLETE, RunStepSource.PLATFORM, detail == null ? Map.of() : detail);
        finish(run, RunStatus.COMPLETED, TerminalReason.FINISHED);
    }

    @Transactional
    public void fail(UUID runId, Map<String, Object> detail) {
        Run run = lockOpen(runId);
        step(run, RunStepKind.FAILED, RunStepSource.PLATFORM, detail == null ? Map.of() : detail);
        finish(run, RunStatus.FAILED, TerminalReason.AGENT_FAILED);
    }

    private Run lockOpen(UUID runId) {
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new RunTerminatedException(run.getStatus());
        }
        return run;
    }

    private void recordCost(Run run, Map<String, Object> detail) {
        charge(run, parseQuantity(detail.get("quantity")), detail, RunStepKind.COST, RunStepSource.AGENT);
    }

    private UsageOutcome charge(Run run, BigDecimal quantity, Map<String, Object> detail, RunStepKind stepKind,
                                RunStepSource source) {
        var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        BigDecimal unitCost = manifest.costEnvelope().unitPrice();
        BigDecimal requested = quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        boolean sandbox = run.getConnection() == null;   // no buyer: nothing is ledgered, only simulated
        BigDecimal spent = sandbox ? run.getSimulatedCharged() : run.getTotalCharged();
        BigDecimal remaining = run.getCostCap().subtract(spent).max(BigDecimal.ZERO);
        BigDecimal charged = requested.min(remaining);

        Map<String, Object> stepDetail = new LinkedHashMap<>(detail);
        stepDetail.put("amount", charged);
        step(run, stepKind, source, stepDetail);
        if (sandbox) {
            run.setSimulatedCharged(run.getSimulatedCharged().add(charged));
            run.setRequestedCharge(run.getRequestedCharge().add(requested));
        } else if (charged.signum() > 0) {
            UUID buyerOrgId = run.getConnection().getBuyerOrg().getId();
            UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(), buyerOrgId,
                    quantity, unitCost, charged));
            ledgerService.chargeUsageEvent(usageEvent.getId());
            run.setTotalCharged(run.getTotalCharged().add(charged));
            BigDecimal consumed = charged.min(run.getReservedAmount());
            walletReservationService.release(buyerOrgId, consumed);
            run.setReservedAmount(run.getReservedAmount().subtract(consumed));
        }

        boolean capReached = requested.compareTo(remaining) > 0;
        if (capReached) {
            step(run, RunStepKind.COST_CAP_REACHED, Map.of(
                    "summary", "Spend limit reached; run stopped",
                    "requested", requested, "charged", charged, "costCap", run.getCostCap()));
            finish(run, RunStatus.FAILED, TerminalReason.COST_CAP_REACHED);
        } else {
            runRepository.save(run);
        }
        BigDecimal total = sandbox ? run.getSimulatedCharged() : run.getTotalCharged();
        return new UsageOutcome(charged, total, run.getCostCap().subtract(total).max(BigDecimal.ZERO), capReached);
    }

    private BigDecimal parseQuantity(Object raw) {
        try {
            BigDecimal quantity = new BigDecimal(String.valueOf(raw));
            if (quantity.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be positive");
            }
            // scale() and compareTo are O(1) even for a huge exponent, so bound before any arithmetic
            if (quantity.scale() > MAX_QUANTITY_FRACTION || quantity.compareTo(MAX_QUANTITY) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "quantity must be at most 9999999999 with up to 4 decimal places");
            }
            return quantity;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be a number");
        }
    }

    private void finish(Run run, RunStatus status, TerminalReason reason) {
        if (run.getConnection() != null && run.getReservedAmount().signum() > 0) {
            walletReservationService.release(run.getConnection().getBuyerOrg().getId(), run.getReservedAmount());
            run.setReservedAmount(BigDecimal.ZERO);
        }
        run.setStatus(status);
        run.setTerminalReason(reason);
        run.setCompletedAt(Instant.now());
        run.setAwaitingSince(null);
        runRepository.save(run);
        if (run.getConnection() != null) {
            // After the reservation release above: the wallet row is locked before any ledger balance row.
            runLlmCostService.post(run);
        }
        // A run that ends for any reason (failure, spend cap, violations, legacy events) must not leave its gate
        // PENDING: nobody can decide it any more. Decisions close their own gate before reaching here.
        gateRequestRepository.findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING).ifPresent(gate -> {
            gate.setStatus(GateStatus.EXPIRED);
            gate.setDecisionNote("The run ended before a decision was made");
            gate.setDecidedAt(Instant.now());
            gateRequestRepository.save(gate);
            events.publishEvent(new GateOutcomeEvent(run.getId(), gate.getId(), GateStatus.EXPIRED, gate.affected(),
                    gate.getCreatedAt()));
            domainEvents.publish("GATE_EXPIRED", buyerOrgId(run), gate.getId(),
                    Map.of("runId", run.getId().toString(), "gateId", gate.getId().toString()));
        });
        events.publishEvent(new RunFinishedEvent(run.getId()));
        Map<String, Object> finished = new java.util.HashMap<>();
        finished.put("runId", run.getId().toString());
        finished.put("status", status.name());
        finished.put("terminalReason", reason == null ? null : reason.name());
        domainEvents.publish("RUN_FINISHED", buyerOrgId(run), run.getId(), finished);
        if (run.getSandboxId() != null) {
            killQuietly(run.getSandboxId());
        }
    }

    private static UUID buyerOrgId(Run run) {
        return run.getConnection() == null ? null : run.getConnection().getBuyerOrg().getId();
    }

    private void killQuietly(String sandboxId) {
        try {
            sandboxProvider.kill(sandboxId);
        } catch (RuntimeException e) {
            // The provider's own timeout reclaims it; the run's terminal state must not roll back over this.
            log.warn("Failed to kill sandbox {}", sandboxId, e);
        }
    }

    private void requireAwaitingApproval(Run run) {
        if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is not awaiting approval: " + run.getStatus());
        }
    }

    private Run lock(UUID runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    private void step(Run run, RunStepKind kind, Map<String, Object> detail) {
        step(run, kind, kind.defaultSource(), detail);
    }

    private void step(Run run, RunStepKind kind, RunStepSource source, Map<String, Object> detail) {
        step(run, kind, source, detail, null);
    }

    private void step(Run run, RunStepKind kind, RunStepSource source, Map<String, Object> detail, Instant startedAt) {
        try {
            RunStep step = new RunStep(null, run, kind, objectMapper.writeValueAsString(detail), source);
            step.setStartedAt(startedAt);
            step.setBlock(RunStepBlocks.of(kind, detail));
            runStepRepository.save(step);
            if (step.getBlock() != null) {
                run.setReachedBlock(step.getBlock());
            }
            if (CLOSING_KINDS.contains(kind) && detail.get("summary") != null) {
                run.setSummary(String.valueOf(detail.get("summary")));
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
