package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.OwnedSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RunnerReaper {

    private static final Logger log = LoggerFactory.getLogger(RunnerReaper.class);

    private final IsolationBackend backend;
    private final SandboxService sandboxService;
    private final BudgetGuard budget;
    private final SpringRunsClient spring;
    private final Clock clock;

    public RunnerReaper(IsolationBackend backend, SandboxService sandboxService, BudgetGuard budget,
                        SpringRunsClient spring, Clock clock) {
        this.backend = backend;
        this.sandboxService = sandboxService;
        this.budget = budget;
        this.spring = spring;
        this.clock = clock;
    }

    /** Kills owned sandboxes past their deadline label, then closes budget entries for sandboxes that are gone. */
    public int sweep() {
        long now = clock.instant().getEpochSecond();
        List<OwnedSandbox> owned = backend.listOwned();
        int killed = 0;
        for (OwnedSandbox o : owned) {
            Long deadline = o.deadlineEpochSeconds();
            if (deadline != null && deadline < now) {
                killQuietly(o.sandboxId());
                killed++;
            }
        }
        Set<String> live = backend.listOwned().stream().map(OwnedSandbox::sandboxId).collect(Collectors.toSet());
        budget.reconcile(live);
        return killed;
    }

    /**
     * Kills owned sandboxes whose run Spring no longer considers active. Runs at startup and on every
     * scheduled sweep, so it also catches sandboxes Spring never learned about (a create that outlived
     * Spring's read timeout, a kill that failed while the runner was down).
     *
     * <p>Lists sandboxes BEFORE asking Spring. A run that becomes active between the two calls is then
     * already in Spring's answer. The other order would kill the sandbox of a run created in between.
     */
    public int reconcileWithSpring() {
        List<OwnedSandbox> owned = backend.listOwned();
        if (owned.isEmpty()) {
            return 0;
        }
        Optional<Set<String>> active = spring.activeRunIds();
        if (active.isEmpty()) {
            log.warn("Spring unreachable; skipping orphan reconcile");
            return 0;
        }
        int killed = 0;
        for (OwnedSandbox o : owned) {
            if (o.requestId() != null && !active.get().contains(o.requestId())) {
                killQuietly(o.sandboxId());
                killed++;
            }
        }
        return killed;
    }

    private void killQuietly(String sandboxId) {
        try {
            sandboxService.kill(sandboxId);
        } catch (RuntimeException e) {
            log.warn("Reaper failed to kill sandbox {}: {}", sandboxId, e.getMessage());
        }
    }
}
