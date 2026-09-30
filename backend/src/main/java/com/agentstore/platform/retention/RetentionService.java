package com.agentstore.platform.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Hourly cleanup of the outbox and notification tables. Finished outbox events go after a week; DEAD deliveries stay
 * 30 days so they can be inspected. Notifications go after 90 days, approval links 30 days after they expire or are
 * used. Delivery rows carry no timestamp of their own, so age is the event's {@code created_at}.
 */
@Service
public class RetentionService {

    /** Mirrors {@code EmailDelivery}'s attempt cap: a row at this count with an error is not retried. */
    static final int EMAIL_MAX_ATTEMPTS = 5;

    public record Summary(int eventsDeleted, int notificationsDeleted, int linksDeleted) {
    }

    public record Health(int deadDeliveries, int failedEmails) {
    }

    private final JdbcTemplate jdbc;
    private final Duration eventRetention;
    private final Duration deadRetention;
    private final Duration notificationRetention;
    private final Duration linkRetention;

    public RetentionService(JdbcTemplate jdbc,
                            @Value("${agentstore.retention.event:P7D}") Duration eventRetention,
                            @Value("${agentstore.retention.dead-delivery:P30D}") Duration deadRetention,
                            @Value("${agentstore.retention.notification:P90D}") Duration notificationRetention,
                            @Value("${agentstore.retention.approval-link:P30D}") Duration linkRetention) {
        this.jdbc = jdbc;
        this.eventRetention = eventRetention;
        this.deadRetention = deadRetention;
        this.notificationRetention = notificationRetention;
        this.linkRetention = linkRetention;
    }

    public Summary purgeOnce() {
        return purgeOnce(Instant.now());
    }

    @Transactional
    public Summary purgeOnce(Instant now) {
        Timestamp eventCutoff = Timestamp.from(now.minus(eventRetention));
        jdbc.update("delete from domain_event_delivery d using domain_event e where e.id = d.event_id "
                + "and d.status = 'DONE' and e.created_at < ?", eventCutoff);
        jdbc.update("delete from domain_event_delivery d using domain_event e where e.id = d.event_id "
                + "and d.status = 'DEAD' and e.created_at < ?", Timestamp.from(now.minus(deadRetention)));
        int events = jdbc.update("delete from domain_event e where e.created_at < ? "
                + "and not exists (select 1 from domain_event_delivery d where d.event_id = e.id)", eventCutoff);

        Timestamp linkCutoff = Timestamp.from(now.minus(linkRetention));
        Timestamp notificationCutoff = Timestamp.from(now.minus(notificationRetention));
        int links = jdbc.update("delete from approval_link where least(expires_at, coalesce(used_at, expires_at)) < ? "
                + "or notification_id in (select id from notification where created_at < ?)",
                linkCutoff, notificationCutoff);
        int notifications = jdbc.update("delete from notification where created_at < ?", notificationCutoff);
        return new Summary(events, notifications, links);
    }

    public Health health() {
        Integer dead = jdbc.queryForObject("select count(*) from domain_event_delivery where status = 'DEAD'",
                Integer.class);
        Integer emails = jdbc.queryForObject("select count(*) from notification where email_sent_at is null "
                + "and email_error is not null and email_attempts >= ?", Integer.class, EMAIL_MAX_ATTEMPTS);
        return new Health(dead == null ? 0 : dead, emails == null ? 0 : emails);
    }
}
