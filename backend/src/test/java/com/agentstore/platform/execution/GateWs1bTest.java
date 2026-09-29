package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class GateWs1bTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    GateService gateService;
    @Autowired
    GateRequestRepository gates;
    @Autowired
    UserRepository users;
    @Autowired
    OrgMembershipRepository memberships;
    @Autowired
    ManifestParser manifestParser;

    private User member(Org org, String email, UserRole role) {
        User user = users.save(new User(null, email, null, "Approver"));
        OrgMembership m = new OrgMembership();
        m.setId(new OrgMembershipId(user.getId(), org.getId()));
        m.setUser(user);
        m.setOrg(org);
        m.setRole(role);
        m.setCreatedAt(Instant.now());
        memberships.save(m);
        return user;
    }

    private HttpHeaders bearer(UUID orgId, UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(userId, orgId, UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpStatus decide(Run run, GateRequest gate, HttpHeaders headers) {
        return HttpStatus.valueOf(rest.exchange(
                "http://localhost:" + port + "/api/v1/runs/" + run.getId() + "/gates/" + gate.getId() + "/decision",
                HttpMethod.POST, new HttpEntity<>(java.util.Map.of("decision", "APPROVE"), headers), String.class)
                .getStatusCode().value());
    }

    @Test
    void snapshotsTheOrgsApproversAndRefusesEveryoneElse() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        User eligible = member(scenario.buyer(), "a1@example.com", UserRole.BUYER_APPROVER);
        member(scenario.buyer(), "admin1@example.com", UserRole.BUYER_ADMIN);   // admins cannot approve today
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g1", "tok-g1");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");

        assertThat(gate.eligibleApproverIds()).containsExactly(eligible.getId());
        assertThat(decide(run, gate, bearer(scenario.buyer().getId(), UUID.randomUUID()))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(run, gate, bearer(scenario.buyer().getId(), eligible.getId()))).isEqualTo(HttpStatus.OK);
        assertThat(gates.findById(gate.getId()).orElseThrow().getDecidedVia()).isEqualTo(DecidedVia.WEB);
    }

    @Test
    void anOrgWithNoApproversIsUnrestrictedInsteadOfDeadlocked() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g2", "tok-g2");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        assertThat(gate.eligibleApproverIds()).isNull();
        assertThat(decide(run, gate, bearer(scenario.buyer().getId(), UUID.randomUUID()))).isEqualTo(HttpStatus.OK);
    }

    @Test
    void roleChangeAfterOpeningDoesNotAlterTheSnapshot() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        User eligible = member(scenario.buyer(), "a2@example.com", UserRole.BUYER_APPROVER);
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g3", "tok-g3");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
        User late = member(scenario.buyer(), "a3@example.com", UserRole.BUYER_APPROVER);
        assertThat(gates.findById(gate.getId()).orElseThrow().eligibleApproverIds())
                .containsExactly(eligible.getId()).doesNotContain(late.getId());
    }

    @Test
    void costIfApprovedIsCappedByRemainingBudgetAndNullWithoutAnEstimate() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        BigDecimal unitPrice = manifestParser.parse(scenario.version().getManifestJson()).costEnvelope().unitPrice();
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-g4", "tok-g4");

        GateRequest withEstimate = gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]",
                new BigDecimal("10"), null);
        assertThat(GateCosts.costIfApproved(run, withEstimate, manifestParser))
                .isEqualByComparingTo(unitPrice.multiply(new BigDecimal("10")).min(new BigDecimal("500.00")));

        GateRequest huge = new GateRequest(run, "x", "s", "{}", "[]", Instant.now().plusSeconds(60));
        huge.setEstimatedUnits(new BigDecimal("9999999999"));
        assertThat(GateCosts.costIfApproved(run, huge, manifestParser)).isEqualByComparingTo("500.00");

        GateRequest none = new GateRequest(run, "x", "s", "{}", "[]", Instant.now().plusSeconds(60));
        assertThat(GateCosts.costIfApproved(run, none, manifestParser)).isNull();
    }
}
