package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConsumerTest extends AbstractIntegrationTest {

    @Autowired NotificationConsumer consumer;
    @Autowired NotificationPreferences preferences;
    @Autowired ExecutionFixtures fixtures;
    @Autowired IdentityFixtures identities;
    @Autowired GateService gateService;
    @Autowired RunLifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    private Run runningRun(ExecutionFixtures.Scenario s) {
        String token = "tok-nc-" + UUID.randomUUID();
        return fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
    }

    /** The outbox event the real flow published for this aggregate, as a consumer would receive it. */
    private DomainEvent eventOf(UUID aggregateId, String type) {
        return jdbc.query("select id, type, org_id, aggregate_id, payload::text as payload, created_at from domain_event "
                        + "where aggregate_id = ? and type = ?", (rs, i) -> {
                    try {
                        return new DomainEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                mapper.readTree(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, aggregateId, type).get(0);
    }

    private List<Map<String, Object>> rows(UUID userId) {
        return jdbc.queryForList("select category, title, gate_id, run_id, email_due_at, email_no_link, resolved_at "
                + "from notification where user_id = ?", userId);
    }

    private static Instant due(Map<String, Object> row) {
        Timestamp t = (Timestamp) row.get("email_due_at");
        return t == null ? null : t.toInstant();
    }

    @Test
    void gateOpenedNotifiesEachSnapshotApproverWithAnEmailDueNow() {
        var s = scenario();
        UUID a = fixtures.approver(s).getId();
        UUID b = fixtures.approver(s).getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post 12 entries", "{}", "[]");

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        for (UUID user : List.of(a, b)) {
            assertThat(rows(user)).singleElement().satisfies(row -> {
                assertThat(row.get("category")).isEqualTo("APPROVAL_NEEDED");
                assertThat(row.get("title").toString()).contains("Post 12 entries");
                assertThat(row.get("gate_id")).isEqualTo(gate.getId());
                assertThat(row.get("email_no_link")).isEqualTo(false);
                assertThat(due(row)).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
            });
        }
    }

    @Test
    void gateOpenedInAnOrgWithNoApproversNotifiesAdminsWithoutALink() {
        var s = scenario();
        UUID admin = identities.member(s.buyer(), "admin-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_ADMIN, "Finance lead").getUser().getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        assertThat(gate.eligibleApproverIds()).isNull();

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        assertThat(rows(admin)).singleElement().satisfies(row -> {
            assertThat(row.get("email_no_link")).isEqualTo(true);
            assertThat(due(row)).isNotNull();
        });
    }

    @Test
    void aRedeliveredEventCreatesNoDuplicateRows() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        DomainEvent event = eventOf(gate.getId(), "GATE_OPENED");

        consumer.handle(event);
        consumer.handle(event);

        assertThat(rows(approver)).hasSize(1);
    }

    @Test
    void anApproverWhoTurnedEmailOffStillGetsTheWebRowButNoEmailIsDue() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        preferences.save(approver, Map.of(NotificationCategory.APPROVAL_NEEDED,
                new NotificationPreferences.Channels(true, false)), null);
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        assertThat(rows(approver)).singleElement().satisfies(row -> assertThat(due(row)).isNull());
    }

    @Test
    void aFinishedRunNotifiesItsStarterOnTheWebOnlyByDefault() {
        var s = scenario();
        UUID starter = UUID.randomUUID();
        Run run = runningRun(s);
        jdbc.update("update run set started_by_user_id = ? where id = ?", starter, run.getId());
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        assertThat(rows(starter)).singleElement().satisfies(row -> {
            assertThat(row.get("category")).isEqualTo("RUN_FINISHED");
            assertThat(due(row)).isNull();
        });
    }

    @Test
    void aFailedRunIsAStoppedEarlyNotificationWithAnEmailDue() {
        var s = scenario();
        UUID starter = UUID.randomUUID();
        Run run = runningRun(s);
        jdbc.update("update run set started_by_user_id = ? where id = ?", starter, run.getId());
        lifecycle.fail(run.getId(), Map.of("error", "boom"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        assertThat(rows(starter)).singleElement().satisfies(row -> {
            assertThat(row.get("category")).isEqualTo("RUN_STOPPED_EARLY");
            assertThat(due(row)).isNotNull();
        });
    }

    @Test
    void aRunWithNoStarterNotifiesNobody() {
        var s = scenario();
        Run run = runningRun(s);
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        assertThat(jdbc.queryForObject("select count(*) from notification where run_id = ?", Integer.class, run.getId()))
                .isZero();
    }

    @Test
    void duringQuietHoursARunFinishedEmailIsHeldButAnApprovalEmailIsDueNow() {
        var s = scenario();
        UUID person = fixtures.approver(s).getId();
        LocalTime nowUtc = LocalTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.MINUTES);
        preferences.save(person, Map.of(NotificationCategory.RUN_STOPPED_EARLY,
                new NotificationPreferences.Channels(true, true)), new NotificationPreferences.QuietWindow(
                true, nowUtc.minusHours(1), nowUtc.plusHours(1), ZoneId.of("UTC")));
        Run run = runningRun(s);
        jdbc.update("update run set started_by_user_id = ? where id = ?", person, run.getId());
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));
        gateService.decide(run.getId(), gate.getId(), true, person, null, DecisionChannel.WEB);
        lifecycle.fail(run.getId(), Map.of("error", "boom"));

        consumer.handle(eventOf(run.getId(), "RUN_FINISHED"));

        Map<String, Object> approval = rows(person).stream()
                .filter(r -> r.get("category").equals("APPROVAL_NEEDED")).findFirst().orElseThrow();
        Map<String, Object> finished = rows(person).stream()
                .filter(r -> r.get("category").equals("RUN_STOPPED_EARLY")).findFirst().orElseThrow();
        assertThat(due(approval)).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
        assertThat(Duration.between(Instant.now(), due(finished)))
                .isBetween(Duration.ofMinutes(50), Duration.ofMinutes(70));
    }

    @Test
    void gateDecidedAndGateExpiredResolveTheApprovalNotifications() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        Run decidedRun = runningRun(s);
        GateRequest decided = gateService.open(decidedRun.getId(), "a", 5, "s", "{}", "[]");
        consumer.handle(eventOf(decided.getId(), "GATE_OPENED"));
        gateService.decide(decidedRun.getId(), decided.getId(), true, approver, null, DecisionChannel.WEB);

        consumer.handle(eventOf(decided.getId(), "GATE_DECIDED"));

        assertThat(rows(approver)).singleElement().satisfies(row -> assertThat(row.get("resolved_at")).isNotNull());

        var s2 = scenario();
        UUID approver2 = fixtures.approver(s2).getId();
        Run expiredRun = runningRun(s2);
        GateRequest expired = gateService.open(expiredRun.getId(), "a", 5, "s", "{}", "[]");
        consumer.handle(eventOf(expired.getId(), "GATE_OPENED"));
        lifecycle.fail(expiredRun.getId(), Map.of("error", "boom"));   // ends the run, expiring the pending gate

        consumer.handle(eventOf(expired.getId(), "GATE_EXPIRED"));

        assertThat(rows(approver2)).singleElement().satisfies(row -> assertThat(row.get("resolved_at")).isNotNull());
    }

    @Test
    void aGateOpenedEventDeliveredAfterTheGateWasDecidedCreatesAnAlreadyResolvedRow() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        Run run = runningRun(s);
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");
        gateService.decide(run.getId(), gate.getId(), true, approver, null, DecisionChannel.WEB);
        consumer.handle(eventOf(gate.getId(), "GATE_DECIDED"));

        consumer.handle(eventOf(gate.getId(), "GATE_OPENED"));

        assertThat(rows(approver)).singleElement().satisfies(row -> assertThat(row.get("resolved_at")).isNotNull());
    }
}
