package com.agentstore.runner.sandbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.runner.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class ReaperJob {

    private final RunnerReaper reaper;

    public ReaperJob(RunnerReaper reaper) {
        this.reaper = reaper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reaper.reconcileWithSpring();
        reaper.sweep();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void sweep() {
        reaper.sweep();
    }
}
