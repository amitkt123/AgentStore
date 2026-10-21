package com.agentstore.platform.payments;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resubmits refund parts left PENDING by a provider timeout or outage. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RefundRetryJob {

    private final RefundService refunds;

    public RefundRetryJob(RefundService refunds) {
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    @SchedulerLock(name = "refundRetry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        refunds.submitPending();
    }
}
