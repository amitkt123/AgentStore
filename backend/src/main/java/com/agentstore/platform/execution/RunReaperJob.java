package com.agentstore.platform.execution;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RunReaperJob {

    private final RunReaper runReaper;

    public RunReaperJob(RunReaper runReaper) {
        this.runReaper = runReaper;
    }

    @Scheduled(fixedDelayString = "${agentstore.runs.reaper-interval:PT30S}",
            initialDelayString = "${agentstore.runs.reaper-interval:PT30S}")
    @SchedulerLock(name = "runReaper", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        runReaper.reapOnce();
    }
}
