package com.agentstore.platform.llm;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LlmRecordingRetentionJob {

    private final LlmRecordingRetentionService retention;

    public LlmRecordingRetentionJob(LlmRecordingRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${agentstore.llm.retention-interval:PT1H}",
            initialDelayString = "${agentstore.llm.retention-interval:PT1H}")
    @SchedulerLock(name = "llmRecordingRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        retention.purgeOnce();
    }
}
