package com.agentstore.platform.notifications;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.RecordingEmailSender;
import com.agentstore.platform.common.TokenHasher;
import com.agentstore.platform.events.DomainEvent;
import com.agentstore.platform.execution.DecidedVia;
import com.agentstore.platform.execution.DecisionChannel;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.GateRequest;
import com.agentstore.platform.execution.GateRequestRepository;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.GateStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunReaper;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalLinkTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ExecutionFixtures fixtures;
    @Autowired GateService gateService;
    @Autowired GateRequestRepository gates;
    @Autowired NotificationConsumer consumer;
    @Autowired EmailDelivery delivery;
    @Autowired RecordingEmailSender emails;
    @Autowired RunReaper reaper;
    @Autowired OrgMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.update("delete from approval_link");
        jdbc.update("delete from notification");
    }

    private record Setup(ExecutionFixtures.Scenario scenario, User approver, Run run, GateRequest gate, String token) {
    }

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    /** Opens a gate, runs the consumer and the email job, and returns the token from the delivered email. */
    private Setup setup(ExecutionFixtures.Scenario s, User approver, int timeoutMinutes) {
        String raw = "tok-al-" + UUID.randomUUID();
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-" + raw, raw);
        return open(s, approver, run, timeoutMinutes);
    }

    private Setup open(ExecutionFixtures.Scenario s, User approver, Run run, int timeoutMinutes) {
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", timeoutMinutes, "Post 12 entries",
                "{}", "[]");
        consumer.handle(event(gate.getId()));
        delivery.deliverDue();
        EmailMessage mail = emails.lastTo(approver.getEmail()).orElseThrow();
        return new Setup(s, approver, run, gate, RecordingEmailSender.tokenFromLink(mail, "approve"));
    }

    private DomainEvent event(UUID gateId) {
        return jdbc.query("select id, type, org_id, aggregate_id, payload::text as payload, created_at from domain_event "
                        + "where aggregate_id = ? and type = 'GATE_OPENED'", (rs, i) -> {
                    try {
                        return new DomainEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                                rs.getObject("org_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                                mapper.readTree(rs.getString("payload")), rs.getTimestamp("created_at").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, gateId).get(0);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<JsonNode> get(String token) {
        return rest.getForEntity(url("/api/v1/approvals/" + token), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String token, String decision) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "approval-link-test");
        return rest.exchange(url("/api/v1/approvals/" + token + "/decision"), HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", decision, "note", "from link"), headers), JsonNode.class);
    }

    private GateStatus statusOf(GateRequest gate) {
        return gates.findById(gate.getId()).orElseThrow().getStatus();
    }

    private int decidedEvents(GateRequest gate) {
        return jdbc.queryForObject("select count(*) from domain_event where aggregate_id = ? and type = 'GATE_DECIDED'",
                Integer.class, gate.getId());
    }

    @Test
    void getShowsTheCardAndLeavesTheGatePending() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        var response = get(t.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("summary").asText()).isEqualTo("Post 12 entries");
        assertThat(response.getBody().get("action").asText()).isEqualTo("post_journal_entries");
        assertThat(response.getBody().has("runDisplayId")).isTrue();
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.PENDING);
        assertThat(jdbc.queryForObject("select used_at from approval_link where token_hash = ?", java.sql.Timestamp.class,
                TokenHasher.sha256Hex(t.token()))).isNull();
    }

    @Test
    void postApprovesAndRecordsTheChannelAndWhoDecided() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        var response = post(t.token(), "APPROVE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("decision").asText()).isEqualTo("APPROVED");
        GateRequest decided = gates.findById(t.gate().getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(GateStatus.APPROVED);
        assertThat(decided.getDecidedVia()).isEqualTo(DecidedVia.EMAIL_LINK);
        assertThat(decided.getDecidedByUserId()).isEqualTo(t.approver().getId());
        Map<String, Object> link = jdbc.queryForMap(
                "select used_at, decided_ip, decided_user_agent from approval_link where token_hash = ?",
                TokenHasher.sha256Hex(t.token()));
        assertThat(link.get("used_at")).isNotNull();
        assertThat(link.get("decided_ip")).isNotNull();
        assertThat(link.get("decided_user_agent")).isEqualTo("approval-link-test");
    }

    @Test
    void postCanReject() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        var response = post(t.token(), "REJECT");

        assertThat(response.getBody().get("decision").asText()).isEqualTo("REJECTED");
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.REJECTED);
    }

    @Test
    void aSecondPostIs410AndTheGateIsDecidedOnce() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);

        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(decidedEvents(t.gate())).isEqualTo(1);
        assertThat(get(t.token()).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void aLinkUsedAfterTheGateWasApprovedInTheWebAppIs410() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);
        gateService.decide(t.run().getId(), t.gate().getId(), true, t.approver().getId(), null, DecisionChannel.WEB);

        assertThat(get(t.token()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(post(t.token(), "REJECT").getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.APPROVED);
    }

    @Test
    void aLinkUsedAfterTheReaperExpiredTheGateIs410() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 1);

        reaper.reapAt(Instant.now().plus(Duration.ofMinutes(40)));

        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.EXPIRED);
    }

    @Test
    void anOldLinkNeverDecidesASecondGateOnTheSameRun() {
        var s = scenario();
        User approver = fixtures.approver(s);
        Setup first = setup(s, approver, 5);
        gateService.decide(first.run().getId(), first.gate().getId(), true, approver.getId(), null, DecisionChannel.WEB);
        Setup second = open(s, approver, first.run(), 5);

        assertThat(post(first.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);

        assertThat(statusOf(second.gate())).isEqualTo(GateStatus.PENDING);
        assertThat(post(second.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anApproverRemovedAfterTheEmailWasSentGets403AndTheLinkStaysUnused() {
        var s = scenario();
        User removed = fixtures.approver(s);
        fixtures.approver(s);
        Setup t = setup(s, removed, 5);
        memberships.deleteById(new OrgMembershipId(removed.getId(), s.buyer().getId()));

        var response = post(t.token(), "APPROVE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_AN_APPROVER");
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.PENDING);
        assertThat(jdbc.queryForObject("select used_at from approval_link where token_hash = ?", java.sql.Timestamp.class,
                TokenHasher.sha256Hex(t.token()))).isNull();
    }

    @Test
    void anUnknownTokenIs404() {
        assertThat(get("not-a-real-token").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("not-a-real-token", "APPROVE").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anExpiredLinkIs410() {
        var s = scenario();
        Setup t = setup(s, fixtures.approver(s), 5);
        jdbc.update("update approval_link set expires_at = now() - interval '1 minute' where token_hash = ?",
                TokenHasher.sha256Hex(t.token()));

        assertThat(get(t.token()).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(post(t.token(), "APPROVE").getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(statusOf(t.gate())).isEqualTo(GateStatus.PENDING);
    }
}
