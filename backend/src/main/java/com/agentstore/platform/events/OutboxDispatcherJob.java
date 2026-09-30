package com.agentstore.platform.events;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcherJob {

    private final OutboxDispatcher dispatcher;

    public OutboxDispatcherJob(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${agentstore.outbox.dispatch-interval:PT5S}",
            initialDelayString = "${agentstore.outbox.dispatch-interval:PT5S}")
    @SchedulerLock(name = "outboxDispatcher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        dispatcher.dispatchOnce();
    }
}
