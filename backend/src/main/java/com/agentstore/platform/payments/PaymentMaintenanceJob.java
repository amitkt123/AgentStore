package com.agentstore.platform.payments;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Hourly: OPEN bills past due become OVERDUE, orders left CREATED for over 24 hours become EXPIRED, and last
 * month's bills are issued (idempotent, so this catches up when the monthly {@link BillingCycleJob} run failed).
 * Each step runs on its own, so one failing does not stop the others.
 */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentMaintenanceJob.class);

    private final BillingCycleService billing;
    private final TopUpService topUps;

    public PaymentMaintenanceJob(BillingCycleService billing, TopUpService topUps) {
        this.billing = billing;
        this.topUps = topUps;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @SchedulerLock(name = "paymentMaintenance", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        step("mark overdue bills", billing::markOverdueBills);
        step("expire stale orders", topUps::expireStaleOrders);
        step("issue last month's bills", () -> billing.issueBills(YearMonth.now(ZoneOffset.UTC).minusMonths(1)));
    }

    private static void step(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("Payment maintenance step '{}' failed", name, e);
        }
    }
}
