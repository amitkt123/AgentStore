package com.agentstore.platform.build;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BuildJob {

    private final BuildPoller poller;
    private final BuildDispatcher dispatcher;

    public BuildJob(BuildPoller poller, BuildDispatcher dispatcher) {
        this.poller = poller;
        this.dispatcher = dispatcher;
    }

    /** Poll first so a finished build frees the single slot before dispatch looks for the next one. */
    @Scheduled(fixedDelayString = "${agentstore.builds.poll-interval:PT5S}",
            initialDelayString = "${agentstore.builds.poll-interval:PT5S}")
    @SchedulerLock(name = "buildPipeline", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        poller.pollOnce();
        dispatcher.dispatchOnce();
    }
}
