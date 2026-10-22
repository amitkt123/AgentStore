package com.agentstore.platform.payments;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/** On the 1st of each month (UTC), bills postpaid orgs for the month just ended. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BillingCycleJob {

    private final BillingCycleService billing;

    public BillingCycleJob(BillingCycleService billing) {
        this.billing = billing;
    }

    @Scheduled(cron = "0 30 0 1 * *", zone = "UTC")
    @SchedulerLock(name = "billingCycle", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        billing.issueBills(YearMonth.now(ZoneOffset.UTC).minusMonths(1));
    }
}
