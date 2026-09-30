package com.agentstore.platform.notifications;

import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.common.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * Sends the emails that notifications owe. A row is claimed in a short transaction (attempt counted, lease pushed 5
 * minutes out), then the mail is sent with no transaction open. So a slow mail server never holds a lock, a crash
 * simply lets the lease lapse, and a failure is recorded and retried later. Delivery is at least once.
 */
@Component
public class EmailDelivery {

    private static final Logger log = LoggerFactory.getLogger(EmailDelivery.class);

    static final int MAX_ATTEMPTS = 5;
    static final int BATCH = 20;
    static final Duration LEASE = Duration.ofMinutes(5);
    private static final long RETRY_STEP_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public EmailDelivery(JdbcTemplate jdbc, TransactionTemplate tx, EmailSender emailSender,
                         @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
    }

    private record Claimed(UUID id, int attempts, boolean skipped, EmailMessage message) {
    }

    /** Sends up to {@link #BATCH} due emails; returns how many rows were handled (sent, failed or skipped). */
    public int deliverDue() {
        int handled = 0;
        while (handled < BATCH) {
            Claimed claimed = tx.execute(status -> claim());
            if (claimed == null) {
                break;
            }
            handled++;
            if (!claimed.skipped()) {
                send(claimed);
            }
        }
        return handled;
    }

    private Claimed claim() {
        var rows = jdbc.queryForList(
                "select n.id, n.user_id, n.category, n.title, n.body, n.gate_id, n.email_attempts, n.email_no_link, u.email "
                        + "from notification n join app_user u on u.id = n.user_id "
                        + "where n.email_due_at <= now() and n.email_sent_at is null and not n.email_skipped "
                        + "and n.email_attempts < ? order by n.email_due_at limit 1 for update of n skip locked",
                MAX_ATTEMPTS);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.get(0);
        UUID id = (UUID) row.get("id");
        UUID userId = (UUID) row.get("user_id");
        UUID gateId = (UUID) row.get("gate_id");
        boolean approval = "APPROVAL_NEEDED".equals(row.get("category"));
        boolean noLink = (Boolean) row.get("email_no_link");
        int attempts = (Integer) row.get("email_attempts") + 1;
        String text = row.get("body").toString();

        if (approval && gateId != null) {
            var gates = jdbc.queryForList("select status, expires_at from gate_request where id = ?", gateId);
            if (gates.isEmpty() || !"PENDING".equals(gates.get(0).get("status"))) {
                jdbc.update("update notification set email_skipped = true where id = ?", id);
                return new Claimed(id, attempts, true, null);
            }
            if (noLink) {
                text += "\n\nThis workspace has no approver set up. Add an approver, or decide in the web app: " + webBaseUrl;
            } else {
                jdbc.update("delete from approval_link where notification_id = ? and used_at is null", id);
                String token = TokenHasher.newToken();
                jdbc.update("insert into approval_link (id, notification_id, user_id, gate_id, token_hash, expires_at) "
                                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, userId, gateId,
                        TokenHasher.sha256Hex(token), gates.get(0).get("expires_at"));
                text += "\n\nReview and decide: " + webBaseUrl + "/approve/" + token
                        + "\n\nThis link works once and expires when the request does.";
            }
        } else {
            text += "\n\nOpen your notifications: " + webBaseUrl + "/notifications";
        }
        jdbc.update("update notification set email_attempts = ?, email_due_at = now() + make_interval(secs => ?) "
                + "where id = ?", attempts, (double) LEASE.toSeconds(), id);
        return new Claimed(id, attempts, false, new EmailMessage(row.get("email").toString(),
                row.get("title").toString(), text));
    }

    private void send(Claimed claimed) {
        try {
            emailSender.send(claimed.message());
            tx.executeWithoutResult(status -> jdbc.update(
                    "update notification set email_sent_at = now(), email_error = null where id = ?", claimed.id()));
        } catch (RuntimeException e) {
            log.warn("Notification email {} failed (attempt {})", claimed.id(), claimed.attempts(), e);
            String error = String.valueOf(e);
            double seconds = (double) (RETRY_STEP_SECONDS * claimed.attempts());
            tx.executeWithoutResult(status -> jdbc.update(
                    "update notification set email_error = ?, email_due_at = now() + make_interval(secs => ?) where id = ?",
                    error.length() > 2000 ? error.substring(0, 2000) : error, seconds, claimed.id()));
        }
    }
}
