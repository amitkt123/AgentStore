package com.agentstore.platform.payments;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/** At 02:00 UTC every day, reconciles yesterday's provider records with ours. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @SchedulerLock(name = "paymentReconciliation", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        try {
            int found = reconciliation.reconcile(yesterday);
            log.info("Payment reconciliation for {} found {} issues", yesterday, found);
        } catch (RuntimeException e) {
            log.error("Payment reconciliation for {} failed", yesterday, e);
        }
    }
}
