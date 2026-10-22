package com.agentstore.platform.payouts;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resubmits withdrawals left REQUESTED by a provider timeout, outage or low provider balance. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class WithdrawalSubmitJob {

    private final WithdrawalService withdrawals;

    public WithdrawalSubmitJob(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    @SchedulerLock(name = "payoutSubmit", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        withdrawals.submitDue();
    }
}
