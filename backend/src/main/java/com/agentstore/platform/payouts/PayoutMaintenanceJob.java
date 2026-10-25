package com.agentstore.platform.payouts;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Polls pending payout account validations and stuck withdrawals every 5 minutes. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PayoutMaintenanceJob {

    private final PayoutMaintenanceService maintenance;

    public PayoutMaintenanceJob(PayoutMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @SchedulerLock(name = "payoutMaintenance", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void run() {
        maintenance.run(Instant.now());
    }
}
