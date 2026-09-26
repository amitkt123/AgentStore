package com.agentstore.platform.data;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class DataRetentionJob {

    private final DataRetentionService retention;

    public DataRetentionJob(DataRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${agentstore.data.retention-interval:PT1H}",
            initialDelayString = "${agentstore.data.retention-interval:PT1H}")
    @SchedulerLock(name = "dataRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        retention.purgeOnce();
    }
}
