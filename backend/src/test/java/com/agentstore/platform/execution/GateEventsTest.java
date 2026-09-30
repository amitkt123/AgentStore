package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GateEventsTest extends AbstractIntegrationTest {

    @Autowired ExecutionFixtures fixtures;
    @Autowired GateService gateService;
    @Autowired RunLifecycleService lifecycle;
    @Autowired RunReaper reaper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private record Emitted(String type, UUID orgId, JsonNode payload) {
    }

    private List<Emitted> events(UUID aggregateId) {
        return jdbc.query("select type, org_id, payload::text as payload from domain_event where aggregate_id = ? "
                        + "order by created_at, type", (rs, i) -> {
                    try {
                        return new Emitted(rs.getString("type"), rs.getObject("org_id", UUID.class),
                                mapper.readTree(rs.getString("payload")));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, aggregateId);
    }

    private Emitted only(UUID aggregateId, String type) {
        return events(aggregateId).stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
    }

    @Test
    void openingAGatePublishesGateOpenedInTheSameTransaction() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID approver = fixtures.approver(s).getId();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-1", "tok-ge-1");

        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post 12 entries", "{}", "[]");

        Emitted opened = only(gate.getId(), "GATE_OPENED");
        assertThat(opened.orgId()).isEqualTo(s.buyer().getId());
        assertThat(opened.payload().get("runId").asText()).isEqualTo(run.getId().toString());
        assertThat(opened.payload().get("gateId").asText()).isEqualTo(gate.getId().toString());
        assertThat(opened.payload().get("action").asText()).isEqualTo("post_journal_entries");
        assertThat(opened.payload().get("summary").asText()).isEqualTo("Post 12 entries");
        assertThat(Instant.parse(opened.payload().get("expiresAt").asText())).isAfter(Instant.now());
        assertThat(opened.payload().get("eligibleApproverIds")).hasSize(1);
        assertThat(opened.payload().get("eligibleApproverIds").get(0).asText()).isEqualTo(approver.toString());
    }

    @Test
    void anUnrestrictedGateEmitsANullApproverList() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-2", "tok-ge-2");

        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        assertThat(only(gate.getId(), "GATE_OPENED").payload().get("eligibleApproverIds").isNull()).isTrue();
    }

    @Test
    void decidingPublishesGateDecidedWithTheChannel() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID approver = fixtures.approver(s).getId();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-3", "tok-ge-3");
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        gateService.decide(run.getId(), gate.getId(), true, approver, "ok", DecisionChannel.EMAIL_LINK);

        Emitted decided = only(gate.getId(), "GATE_DECIDED");
        assertThat(decided.payload().get("decision").asText()).isEqualTo("APPROVED");
        assertThat(decided.payload().get("decidedBy").asText()).isEqualTo(approver.toString());
        assertThat(decided.payload().get("channel").asText()).isEqualTo("EMAIL_LINK");
        assertThat(decided.payload().get("runId").asText()).isEqualTo(run.getId().toString());
        assertThat(events(run.getId())).extracting(Emitted::type).doesNotContain("RUN_FINISHED");
    }

    @Test
    void rejectingPublishesGateDecidedAndRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        UUID approver = fixtures.approver(s).getId();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-4", "tok-ge-4");
        GateRequest gate = gateService.open(run.getId(), "a", 5, "s", "{}", "[]");

        gateService.decide(run.getId(), gate.getId(), false, approver, "no", DecisionChannel.WEB);

        assertThat(only(gate.getId(), "GATE_DECIDED").payload().get("decision").asText()).isEqualTo("REJECTED");
        Emitted finished = only(run.getId(), "RUN_FINISHED");
        assertThat(finished.payload().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(finished.payload().get("terminalReason").asText()).isEqualTo("GATE_REJECTED");
        assertThat(events(gate.getId())).extracting(Emitted::type).doesNotContain("GATE_EXPIRED");
    }

    @Test
    void theReaperExpiringAGatePublishesGateExpiredAndRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-5", "tok-ge-5");
        GateRequest gate = gateService.open(run.getId(), "a", 1, "s", "{}", "[]");

        reaper.reapAt(Instant.now().plus(Duration.ofMinutes(40)));

        Emitted expired = only(gate.getId(), "GATE_EXPIRED");
        assertThat(expired.payload().get("runId").asText()).isEqualTo(run.getId().toString());
        assertThat(expired.payload().get("gateId").asText()).isEqualTo(gate.getId().toString());
        assertThat(only(run.getId(), "RUN_FINISHED").payload().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(events(gate.getId()).stream().filter(e -> e.type().equals("GATE_EXPIRED"))).hasSize(1);
    }

    @Test
    void aRunFinishingNormallyPublishesRunFinished() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-6", "tok-ge-6");

        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        Emitted finished = only(run.getId(), "RUN_FINISHED");
        assertThat(finished.orgId()).isEqualTo(s.buyer().getId());
        assertThat(finished.payload().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.payload().get("terminalReason").asText()).isEqualTo("FINISHED");
    }

    @Test
    void aRunThatFailsToFinishLeavesNoEvent() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ge-7", "tok-ge-7");
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        // A second completion is refused (run already terminal); it must not add another event.
        try {
            lifecycle.complete(run.getId(), Map.of("summary", "again"));
        } catch (RunTerminatedException expected) {
            // expected
        }

        assertThat(events(run.getId()).stream().filter(e -> e.type().equals("RUN_FINISHED"))).hasSize(1);
    }
}
