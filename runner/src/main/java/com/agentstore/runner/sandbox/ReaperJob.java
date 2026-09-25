package com.agentstore.runner.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.runner.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class ReaperJob {

    private static final Logger log = LoggerFactory.getLogger(ReaperJob.class);

    private final RunnerReaper reaper;

    public ReaperJob(RunnerReaper reaper) {
        this.reaper = reaper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runBoth();
    }

    /** Every sweep also reconciles with Spring, so orphans Spring never learned about are found. */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void sweep() {
        runBoth();
    }

    private void runBoth() {
        try {
            reaper.reconcileWithSpring();
        } catch (RuntimeException e) {
            log.warn("Orphan reconcile failed: {}", e.getClass().getSimpleName());
        }
        try {
            reaper.sweep();
        } catch (RuntimeException e) {
            log.warn("Deadline sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
