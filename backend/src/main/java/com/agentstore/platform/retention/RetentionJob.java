package com.agentstore.platform.retention;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Purges old outbox and notification rows, then reports deliveries that will never be retried. */
@Component
@ConditionalOnProperty(name = "agentstore.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RetentionService retention;
    private final AtomicInteger deadDeliveries = new AtomicInteger();
    private final AtomicInteger failedEmails = new AtomicInteger();

    public RetentionJob(RetentionService retention, MeterRegistry meters) {
        this.retention = retention;
        Gauge.builder("agentstore.outbox.dead_deliveries", deadDeliveries, AtomicInteger::get).register(meters);
        Gauge.builder("agentstore.notifications.failed_emails", failedEmails, AtomicInteger::get).register(meters);
    }

    @Scheduled(fixedDelayString = "${agentstore.retention.interval:PT1H}",
            initialDelayString = "${agentstore.retention.interval:PT1H}")
    @SchedulerLock(name = "retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        RetentionService.Summary summary = retention.purgeOnce();
        log.info("Retention purged {} events, {} notifications, {} approval links",
                summary.eventsDeleted(), summary.notificationsDeleted(), summary.linksDeleted());
        RetentionService.Health health = retention.health();
        deadDeliveries.set(health.deadDeliveries());
        failedEmails.set(health.failedEmails());
        if (health.deadDeliveries() > 0 || health.failedEmails() > 0) {
            log.error("{} outbox deliveries are DEAD and {} approval/notification emails used all attempts; "
                    + "neither is retried", health.deadDeliveries(), health.failedEmails());
        }
    }
}
