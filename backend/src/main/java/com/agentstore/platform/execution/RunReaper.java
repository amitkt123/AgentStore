package com.agentstore.platform.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Moves runs that can no longer make progress to a terminal state (spec WS0 B3). */
@Component
public class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);
    private static final List<RunStatus> ACTIVE =
            List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;
    private final GateService gateService;
    private final GateRequestRepository gateRequestRepository;
    private final SandboxProvider sandboxProvider;
    private final Duration gateTimeout;

    public RunReaper(RunRepository runRepository, RunLifecycleService runLifecycleService, GateService gateService,
                     GateRequestRepository gateRequestRepository, SandboxProvider sandboxProvider,
                     @Value("${agentstore.runs.gate-timeout:PT30M}") Duration gateTimeout) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
        this.gateService = gateService;
        this.gateRequestRepository = gateRequestRepository;
        this.sandboxProvider = sandboxProvider;
        this.gateTimeout = gateTimeout;
    }

    public int reapOnce() {
        return reapAt(Instant.now());
    }

    public int reapAt(Instant now) {
        int reaped = 0;
        for (Run run : runRepository.findByStatusIn(ACTIVE)) {
            try {
                if (reap(run, now)) {
                    reaped++;
                }
            } catch (RuntimeException e) {
                log.error("Reaper failed for run {}", run.getId(), e);
            }
        }
        return reaped;
    }

    /**
     * {@code run} is a snapshot read outside any lock; it may be stale by the time the terminate call below
     * acquires the row lock (a buyer decision or an agent callback can land in between). Every terminate call
     * passes the snapshot status as {@code expectedStatus} so a stale decision is a no-op rather than clobbering
     * whatever actually happened to the run since it was read.
     */
    private boolean reap(Run run, Instant now) {
        RunStatus snapshotStatus = run.getStatus();
        boolean deadlinePassed = run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(now);
        if (snapshotStatus == RunStatus.AWAITING_APPROVAL) {
            // A run with a gate row expires when that gate does (the manifest's timeoutMinutes); a legacy run
            // without one falls back to the global gate timeout.
            Instant gateExpiresAt = gateRequestRepository
                    .findFirstByRunIdAndStatusOrderByCreatedAtAsc(run.getId(), GateStatus.PENDING)
                    .map(GateRequest::getExpiresAt)
                    .orElseGet(() -> run.getAwaitingSince() == null ? null : run.getAwaitingSince().plus(gateTimeout));
            boolean gateExpired = gateExpiresAt != null && gateExpiresAt.isBefore(now);
            if (!gateExpired && !deadlinePassed) {
                return false;
            }
            return gateService.expire(run.getId(), snapshotStatus,
                    gateExpired ? TerminalReason.GATE_EXPIRED : TerminalReason.TIMED_OUT, Map.of("summary", "No approval decision in time; run stopped"));
        }
        if (deadlinePassed) {
            return runLifecycleService.terminate(run.getId(), snapshotStatus, RunStatus.TIMED_OUT, TerminalReason.TIMED_OUT, RunStepKind.TIMEOUT,
                    Map.of("summary", "Run exceeded its time limit"));
        }
        if (snapshotStatus == RunStatus.RUNNING && run.getSandboxId() != null) {
            SandboxStatus sandbox = sandboxProvider.status(run.getSandboxId());
            if (sandbox.stopped()) {
                Map<String, Object> detail = new java.util.HashMap<>();
                detail.put("summary", "Agent stopped without reporting completion");
                if (sandbox.exitCode() != null) {
                    detail.put("exitCode", sandbox.exitCode());
                }
                return runLifecycleService.terminate(run.getId(), snapshotStatus, RunStatus.FAILED,
                        TerminalReason.SANDBOX_LOST, RunStepKind.FAILED, detail);
            }
        }
        return false;
    }
}
