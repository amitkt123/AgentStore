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
            List.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final RunRepository runRepository;
    private final RunLifecycleService runLifecycleService;
    private final SandboxProvider sandboxProvider;
    private final Duration gateTimeout;

    public RunReaper(RunRepository runRepository, RunLifecycleService runLifecycleService,
                     SandboxProvider sandboxProvider,
                     @Value("${agentstore.runs.gate-timeout:PT30M}") Duration gateTimeout) {
        this.runRepository = runRepository;
        this.runLifecycleService = runLifecycleService;
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

    private boolean reap(Run run, Instant now) {
        boolean deadlinePassed = run.getDeadlineAt() != null && run.getDeadlineAt().isBefore(now);
        if (run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            boolean gateExpired = run.getAwaitingSince() != null
                    && run.getAwaitingSince().plus(gateTimeout).isBefore(now);
            if (!gateExpired && !deadlinePassed) {
                return false;
            }
            return runLifecycleService.terminate(run.getId(), RunStatus.EXPIRED, RunStepKind.TIMEOUT,
                    Map.of("summary", "No approval decision in time; run stopped"));
        }
        if (deadlinePassed) {
            return runLifecycleService.terminate(run.getId(), RunStatus.TIMED_OUT, RunStepKind.TIMEOUT,
                    Map.of("summary", "Run exceeded its time limit"));
        }
        if (run.getStatus() == RunStatus.RUNNING && run.getSandboxId() != null
                && sandboxProvider.status(run.getSandboxId()) == SandboxStatus.EXITED) {
            return runLifecycleService.terminate(run.getId(), RunStatus.FAILED, RunStepKind.FAILED,
                    Map.of("summary", "Agent stopped without reporting completion"));
        }
        return false;
    }
}
