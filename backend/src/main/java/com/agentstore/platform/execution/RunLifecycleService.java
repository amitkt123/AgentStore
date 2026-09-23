package com.agentstore.platform.execution;

import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.UsageEvent;
import com.agentstore.platform.ledger.UsageEventRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
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
    private static final Set<RunStepKind> AGENT_REPORTABLE = EnumSet.of(
            RunStepKind.READ, RunStepKind.WRITE, RunStepKind.GATE, RunStepKind.COST,
            RunStepKind.COMPLETE, RunStepKind.FAILED);

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final UsageEventRepository usageEventRepository;
    private final LedgerService ledgerService;
    private final ManifestParser manifestParser;
    private final SandboxProvider sandboxProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RunLifecycleService(RunRepository runRepository, RunStepRepository runStepRepository,
                               UsageEventRepository usageEventRepository, LedgerService ledgerService,
                               ManifestParser manifestParser, SandboxProvider sandboxProvider) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.usageEventRepository = usageEventRepository;
        this.ledgerService = ledgerService;
        this.manifestParser = manifestParser;
        this.sandboxProvider = sandboxProvider;
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

    @Transactional
    public void recordEvent(UUID runId, RunStepKind kind, Map<String, Object> detail) {
        if (!AGENT_REPORTABLE.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Step kind is platform-only: " + kind);
        }
        Map<String, Object> safeDetail = detail == null ? Map.of() : detail;
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run not accepting events: " + run.getStatus());
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
                finish(run, RunStatus.COMPLETED);
            }
            case FAILED -> {
                step(run, kind, safeDetail);
                finish(run, RunStatus.FAILED);
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
        finish(run, RunStatus.CANCELLED);
        return run;
    }

    /** Moves a run to a terminal status. Returns false (and changes nothing) if it was already terminal. */
    @Transactional
    public boolean terminate(UUID runId, RunStatus terminalStatus, RunStepKind stepKind, Map<String, Object> detail) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("Not a terminal status: " + terminalStatus);
        }
        Run run = lock(runId);
        if (run.getStatus().isTerminal()) {
            return false;
        }
        if (stepKind != null) {
            step(run, stepKind, detail == null ? Map.of() : detail);
        }
        finish(run, terminalStatus);
        return true;
    }

    private void recordCost(Run run, Map<String, Object> detail) {
        step(run, RunStepKind.COST, detail);
        var manifest = manifestParser.parse(run.getAgentVersion().getManifestJson());
        BigDecimal quantity = new BigDecimal(String.valueOf(detail.getOrDefault("quantity", "0")));
        BigDecimal unitCost = manifest.costEnvelope().unitPrice();
        BigDecimal amount = quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

        UsageEvent usageEvent = usageEventRepository.save(new UsageEvent(null, run.getId(),
                run.getConnection().getBuyerOrg().getId(), quantity, unitCost, amount));
        ledgerService.chargeUsageEvent(usageEvent.getId());

        run.setTotalCharged(run.getTotalCharged().add(amount));
        if (run.getTotalCharged().compareTo(run.getCostCap()) > 0) {
            finish(run, RunStatus.FAILED);
        } else {
            runRepository.save(run);
        }
    }

    private void finish(Run run, RunStatus status) {
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        run.setAwaitingSince(null);
        runRepository.save(run);
        if (run.getSandboxId() != null) {
            killQuietly(run.getSandboxId());
        }
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
        try {
            runStepRepository.save(new RunStep(null, run, kind, objectMapper.writeValueAsString(detail)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid detail payload");
        }
    }
}
