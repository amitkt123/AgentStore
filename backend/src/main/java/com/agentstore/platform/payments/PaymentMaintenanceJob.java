package com.agentstore.platform.payments;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: OPEN bills past due become OVERDUE, and orders left CREATED for over 24 hours become EXPIRED. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentMaintenanceJob {

    private final BillingCycleService billing;
    private final TopUpService topUps;

    public PaymentMaintenanceJob(BillingCycleService billing, TopUpService topUps) {
        this.billing = billing;
        this.topUps = topUps;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @SchedulerLock(name = "paymentMaintenance", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        billing.markOverdueBills();
        topUps.expireStaleOrders();
    }
}
