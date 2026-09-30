package com.agentstore.platform.retention;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionServiceTest extends AbstractIntegrationTest {

    @Autowired RetentionService retention;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @BeforeEach
    void clean() {
        jdbc.update("delete from approval_link");
        jdbc.update("delete from notification");
        jdbc.update("delete from domain_event_delivery");
        jdbc.update("delete from domain_event");
    }

    private UUID event(Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into domain_event (id, type, aggregate_id, payload, created_at) values (?, 'T', ?, '{}'::jsonb, ?)",
                id, UUID.randomUUID(), java.sql.Timestamp.from(now.minus(age)));
        return id;
    }

    private void delivery(UUID eventId, String consumer, String status) {
        jdbc.update("insert into domain_event_delivery (id, event_id, consumer, status) values (?, ?, ?, ?)",
                UUID.randomUUID(), eventId, consumer, status);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void oldDoneEventsAreDeletedButRecentAndPendingOnesStay() {
        UUID oldDone = event(Duration.ofDays(8));
        delivery(oldDone, "a", "DONE");
        UUID recentDone = event(Duration.ofDays(1));
        delivery(recentDone, "a", "DONE");
        UUID oldPending = event(Duration.ofDays(8));
        delivery(oldPending, "a", "PENDING");
        UUID oldMixed = event(Duration.ofDays(8));
        delivery(oldMixed, "a", "DONE");
        delivery(oldMixed, "b", "PENDING");

        RetentionService.Summary summary = retention.purgeOnce(now);

        assertThat(summary.eventsDeleted()).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from domain_event", UUID.class))
                .containsExactlyInAnyOrder(recentDone, oldPending, oldMixed);
        assertThat(count("domain_event_delivery")).isEqualTo(4 - 1);
    }

    @Test
    void deadDeliveriesAreKeptForThirtyDays() {
        UUID deadEight = event(Duration.ofDays(8));
        delivery(deadEight, "a", "DEAD");
        UUID deadThirtyOne = event(Duration.ofDays(31));
        delivery(deadThirtyOne, "a", "DEAD");

        retention.purgeOnce(now);

        assertThat(jdbc.queryForList("select id from domain_event", UUID.class)).containsExactly(deadEight);
        assertThat(count("domain_event_delivery")).isEqualTo(1);
    }

    @Test
    void oldNotificationsAndTheirLinksAreDeleted() {
        UUID oldN = notification(Duration.ofDays(91));
        UUID newN = notification(Duration.ofDays(10));
        link(oldN, Duration.ofDays(91), null);
        link(newN, Duration.ofDays(10), null);

        RetentionService.Summary summary = retention.purgeOnce(now);

        assertThat(summary.notificationsDeleted()).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from notification", UUID.class)).containsExactly(newN);
        assertThat(jdbc.queryForList("select notification_id from approval_link", UUID.class)).containsExactly(newN);
    }

    @Test
    void linksAreDeletedThirtyDaysAfterTheyExpireOrAreUsed() {
        UUID n = notification(Duration.ofDays(5));
        UUID expiredLongAgo = link(n, Duration.ofDays(40), null);      // expires_at = created + 1d, ~39d ago
        UUID usedLongAgo = link(n, Duration.ofDays(2), Duration.ofDays(31));
        UUID expiredRecently = link(n, Duration.ofDays(5), null);
        UUID live = link(n, Duration.ofHours(1), null);

        RetentionService.Summary summary = retention.purgeOnce(now);

        assertThat(summary.linksDeleted()).isEqualTo(2);
        assertThat(jdbc.queryForList("select id from approval_link", UUID.class))
                .containsExactlyInAnyOrder(expiredRecently, live);
        assertThat(expiredLongAgo).isNotEqualTo(usedLongAgo);
    }

    @Test
    void countsDeadDeliveriesAndEmailsThatUsedAllAttempts() {
        UUID e = event(Duration.ofDays(1));
        delivery(e, "a", "DEAD");
        delivery(e, "b", "DONE");
        UUID n = notification(Duration.ofDays(1));
        jdbc.update("update notification set email_attempts = 5, email_error = 'boom' where id = ?", n);
        UUID ok = notification(Duration.ofDays(1));
        jdbc.update("update notification set email_attempts = 5, email_sent_at = now() where id = ?", ok);

        RetentionService.Health health = retention.health();

        assertThat(health.deadDeliveries()).isEqualTo(1);
        assertThat(health.failedEmails()).isEqualTo(1);
    }

    private UUID notification(Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notification (id, user_id, org_id, category, title, body, source_event_id, created_at) "
                        + "values (?, ?, ?, 'X', 't', 'b', ?, ?)",
                id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), java.sql.Timestamp.from(now.minus(age)));
        return id;
    }

    /** Link created {@code age} ago, expiring one day after creation; optionally used {@code usedAge} ago. */
    private UUID link(UUID notificationId, Duration age, Duration usedAge) {
        UUID id = UUID.randomUUID();
        Instant created = now.minus(age);
        jdbc.update("insert into approval_link (id, notification_id, user_id, gate_id, token_hash, expires_at, used_at, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, notificationId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(),
                java.sql.Timestamp.from(created.plus(Duration.ofDays(1))),
                usedAge == null ? null : java.sql.Timestamp.from(now.minus(usedAge)),
                java.sql.Timestamp.from(created));
        return id;
    }
}
