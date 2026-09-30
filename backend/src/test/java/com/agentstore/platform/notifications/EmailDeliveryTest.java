package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.RecordingEmailSender;
import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDeliveryTest extends AbstractIntegrationTest {

    @Autowired EmailDelivery delivery;
    @Autowired NotificationConsumer consumer;
    @Autowired ExecutionFixtures fixtures;
    @Autowired IdentityFixtures identities;
    @Autowired GateService gateService;
    @Autowired RecordingEmailSender emails;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("delete from approval_link");
        jdbc.update("delete from notification");
    }

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    private GateRequest openGate(ExecutionFixtures.Scenario s) {
        String token = "tok-ed-" + UUID.randomUUID();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post 12 entries", "{}", "[]");
        consumer.handle(gateOpened(gate));
        return gate;
    }

    private DomainEvent gateOpened(GateRequest gate) {
        return jdbc.query("select id, type, org_id, aggregate_id, payload::text as payload, created_at from domain_event "
                        + "where aggregate_id = ? and type = 'GATE_OPENED'", (rs, i) -> {
                    try {
                        return new DomainEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                mapper.readTree(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, gate.getId()).get(0);
    }

    private Map<String, Object> notificationOf(UUID userId) {
        return jdbc.queryForMap("select id, email_sent_at, email_attempts, email_error, email_skipped, "
                + "extract(epoch from (email_due_at - now())) as secs from notification where user_id = ?", userId);
    }

    @Test
    void anApprovalEmailCarriesAWorkingLinkAndIsMarkedSent() {
        var s = scenario();
        User approver = fixtures.approver(s);
        GateRequest gate = openGate(s);

        assertThat(delivery.deliverDue()).isEqualTo(1);

        EmailMessage message = emails.lastTo(approver.getEmail()).orElseThrow();
        assertThat(message.subject()).contains("Post 12 entries");
        String token = RecordingEmailSender.tokenFromLink(message, "approve");
        Map<String, Object> link = jdbc.queryForMap(
                "select gate_id, user_id, used_at, expires_at from approval_link where token_hash = ?",
                TokenHasher.sha256Hex(token));
        assertThat(link.get("gate_id")).isEqualTo(gate.getId());
        assertThat(link.get("user_id")).isEqualTo(approver.getId());
        assertThat(link.get("used_at")).isNull();
        assertThat(((java.sql.Timestamp) link.get("expires_at")).toInstant())
                .isCloseTo(gate.getExpiresAt(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(notificationOf(approver.getId()).get("email_sent_at")).isNotNull();
    }

    @Test
    void aNoApproverNotificationSendsAnEmailWithoutALink() {
        var s = scenario();
        User admin = identities.member(s.buyer(), "admin-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_ADMIN, "Finance lead").getUser();
        openGate(s);

        delivery.deliverDue();

        EmailMessage message = emails.lastTo(admin.getEmail()).orElseThrow();
        assertThat(message.text()).doesNotContain("/approve/").contains("approver");
        assertThat(jdbc.queryForObject("select count(*) from approval_link", Integer.class)).isZero();
    }

    @Test
    void aGateResolvedBeforeSendingIsSkippedNotEmailed() {
        var s = scenario();
        User approver = fixtures.approver(s);
        GateRequest gate = openGate(s);
        gateService.decide(gate.getRun().getId(), gate.getId(), true, approver.getId(), null, DecisionChannel.WEB);

        delivery.deliverDue();

        assertThat(emails.lastTo(approver.getEmail())).isEmpty();
        assertThat(notificationOf(approver.getId())).containsEntry("email_skipped", true);
        assertThat(jdbc.queryForObject("select count(*) from approval_link", Integer.class)).isZero();
    }

    @Test
    void aFailedSendIsRetriedLaterWithAFreshLink() {
        var s = scenario();
        User approver = fixtures.approver(s);
        openGate(s);
        emails.failNextSendTo(approver.getEmail());

        delivery.deliverDue();

        Map<String, Object> failed = notificationOf(approver.getId());
        assertThat(failed.get("email_sent_at")).isNull();
        assertThat(failed).containsEntry("email_attempts", 1);
        assertThat(failed.get("email_error").toString()).contains("Simulated");
        assertThat(((Number) failed.get("secs")).doubleValue()).isBetween(30.0, 90.0);
        assertThat(delivery.deliverDue()).as("not due yet").isZero();

        jdbc.update("update notification set email_due_at = now() where user_id = ?", approver.getId());
        delivery.deliverDue();

        Map<String, Object> retried = notificationOf(approver.getId());
        assertThat(retried.get("email_sent_at")).isNotNull();
        assertThat(retried).containsEntry("email_attempts", 2);
        assertThat(emails.lastTo(approver.getEmail())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from approval_link", Integer.class)).isEqualTo(1);
    }

    @Test
    void afterFiveAttemptsARowIsLeftAlone() {
        var s = scenario();
        User approver = fixtures.approver(s);
        openGate(s);
        jdbc.update("update notification set email_attempts = 5, email_due_at = now() where user_id = ?", approver.getId());

        assertThat(delivery.deliverDue()).isZero();
        assertThat(emails.lastTo(approver.getEmail())).isEmpty();
    }

    @Test
    void aSentEmailIsNeverSentAgain() {
        var s = scenario();
        fixtures.approver(s);
        openGate(s);

        assertThat(delivery.deliverDue()).isEqualTo(1);
        assertThat(delivery.deliverDue()).isZero();
    }

    @Test
    void aRowNotYetDueIsNotSent() {
        var s = scenario();
        User approver = fixtures.approver(s);
        openGate(s);
        jdbc.update("update notification set email_due_at = ? where user_id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofHours(1))), approver.getId());

        assertThat(delivery.deliverDue()).isZero();
        assertThat(emails.lastTo(approver.getEmail())).isEmpty();
    }
}
