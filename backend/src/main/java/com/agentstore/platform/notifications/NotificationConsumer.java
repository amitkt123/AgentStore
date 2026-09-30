package com.agentstore.platform.notifications;

import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.events.DomainEventConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns gate and run events into notification rows. It only writes rows: email goes out later from
 * {@link EmailDelivery}. Redelivery is harmless because a row is unique per (person, source event).
 */
@Component
public class NotificationConsumer implements DomainEventConsumer {

    private static final Set<String> TYPES = Set.of("GATE_OPENED", "RUN_FINISHED", "GATE_DECIDED", "GATE_EXPIRED");

    private final JdbcTemplate jdbc;
    private final NotificationPreferences preferences;

    public NotificationConsumer(JdbcTemplate jdbc, NotificationPreferences preferences) {
        this.jdbc = jdbc;
        this.preferences = preferences;
    }

    @Override
    public String name() {
        return "notifications";
    }

    @Override
    public boolean handles(String type) {
        return TYPES.contains(type);
    }

    @Override
    public void handle(DomainEvent event) {
        switch (event.type()) {
            case "GATE_OPENED" -> gateOpened(event);
            case "RUN_FINISHED" -> runFinished(event);
            default -> resolveGate(event);
        }
    }

    private void gateOpened(DomainEvent event) {
        if (event.orgId() == null) {
            return;   // a sandbox gate has no buyer org to notify
        }
        JsonNode payload = event.payload();
        UUID runId = UUID.fromString(payload.get("runId").asText());
        UUID gateId = UUID.fromString(payload.get("gateId").asText());
        String summary = payload.get("summary").asText();
        String action = payload.get("action").asText();
        String displayId = jdbc.queryForObject("select display_id from run where id = ?", String.class, runId);
        String title = "Approval needed: " + summary;
        String body = "Run " + displayId + " is waiting for your approval to " + action + ".";

        JsonNode eligible = payload.get("eligibleApproverIds");
        List<UUID> recipients = new ArrayList<>();
        boolean noLink = eligible == null || eligible.isNull();
        if (noLink) {
            recipients.addAll(jdbc.queryForList("select user_id from org_membership where org_id = ? and role = 'BUYER_ADMIN'",
                    UUID.class, event.orgId()));
        } else {
            eligible.forEach(id -> recipients.add(UUID.fromString(id.asText())));
        }
        Instant now = Instant.now();
        for (UUID user : recipients) {
            insert(user, event.orgId(), NotificationCategory.APPROVAL_NEEDED, title, body, runId, gateId, event.id(),
                    preferences.emailDueAt(user, NotificationCategory.APPROVAL_NEEDED, now), noLink);
        }
        // A late-delivered GATE_OPENED may find the gate already decided; its GATE_DECIDED found no rows to resolve.
        jdbc.update("update notification set resolved_at = now() where gate_id = ? and category = 'APPROVAL_NEEDED' "
                + "and resolved_at is null and exists (select 1 from gate_request where id = ? and status <> 'PENDING')",
                gateId, gateId);
    }

    private void runFinished(DomainEvent event) {
        if (event.orgId() == null) {
            return;
        }
        UUID runId = UUID.fromString(event.payload().get("runId").asText());
        var row = jdbc.queryForMap("select r.started_by_user_id, r.display_id, a.name as agent_name "
                + "from run r join agent_version av on av.id = r.agent_version_id join agent a on a.id = av.agent_id "
                + "where r.id = ?", runId);
        UUID starter = (UUID) row.get("started_by_user_id");
        if (starter == null) {
            return;
        }
        String status = event.payload().get("status").asText();
        NotificationCategory category = "COMPLETED".equals(status)
                ? NotificationCategory.RUN_FINISHED : NotificationCategory.RUN_STOPPED_EARLY;
        var channels = preferences.channels(starter, category);
        if (!channels.web() && !channels.email()) {
            return;
        }
        String title = category == NotificationCategory.RUN_FINISHED
                ? row.get("agent_name") + " finished" : row.get("agent_name") + " stopped early";
        String body = "Run " + row.get("display_id") + " ended with status " + status + ".";
        insert(starter, event.orgId(), category, title, body, runId, null, event.id(),
                preferences.emailDueAt(starter, category, Instant.now()), false);
    }

    private void resolveGate(DomainEvent event) {
        UUID gateId = UUID.fromString(event.payload().get("gateId").asText());
        jdbc.update("update notification set resolved_at = now() "
                + "where gate_id = ? and category = 'APPROVAL_NEEDED' and resolved_at is null", gateId);
    }

    private void insert(UUID userId, UUID orgId, NotificationCategory category, String title, String body, UUID runId,
                        UUID gateId, UUID eventId, Instant emailDueAt, boolean noLink) {
        jdbc.update("insert into notification (id, user_id, org_id, category, title, body, run_id, gate_id, "
                        + "source_event_id, email_due_at, email_no_link) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (user_id, source_event_id) do nothing",
                UUID.randomUUID(), userId, orgId, category.name(), title, body, runId, gateId, eventId,
                NotificationPreferences.timestamp(emailDueAt), noLink);
    }
}
