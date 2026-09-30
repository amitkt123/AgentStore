package com.agentstore.platform.notifications;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class EmailDeliveryJob {

    private final EmailDelivery delivery;

    public EmailDeliveryJob(EmailDelivery delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${agentstore.notifications.email-interval:PT30S}",
            initialDelayString = "${agentstore.notifications.email-interval:PT30S}")
    @SchedulerLock(name = "emailDelivery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void run() {
        delivery.deliverDue();
    }
}
