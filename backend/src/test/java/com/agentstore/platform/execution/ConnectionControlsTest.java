package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionControlsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    IdentityFixtures identities;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    GateService gateService;
    @Autowired
    GateRequestRepository gates;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders bearer(UUID orgId, UUID userId, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(userId, orgId, role));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"));
    }

    UUID approver(ExecutionFixtures.Scenario s, String email) {
        return identities.member(orgById(s), email, UserRole.BUYER_MEMBER, "Approver").getUser().getId();
    }

    Org orgById(ExecutionFixtures.Scenario s) {
        return orgRepository.findById(s.buyer().getId()).orElseThrow();
    }

    ResponseEntity<String> patch(ExecutionFixtures.Scenario s, UUID orgId, Object body) {
        return rest.exchange(url("/api/v1/connections/" + s.connection().getId()), HttpMethod.PATCH,
                new HttpEntity<>(body, bearer(orgId, UUID.randomUUID(), UserRole.BUYER_ADMIN)), String.class);
    }

    ResponseEntity<String> decide(Run run, GateRequest gate, UUID orgId, UUID userId) {
        return rest.exchange(url("/api/v1/runs/" + run.getId() + "/gates/" + gate.getId() + "/decision"),
                HttpMethod.POST, new HttpEntity<>(Map.of("decision", "APPROVE"),
                        bearer(orgId, userId, UserRole.BUYER_MEMBER)), String.class);
    }

    @Test
    void patchSetsTheLimitAndApproversAndTheListShowsThem() {
        var s = scenario();
        UUID a1 = approver(s, "ap1@example.com");

        var response = patch(s, s.buyer().getId(), Map.of("monthlyLimit", "250.00", "approverUserIds", List.of(a1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var mine = rest.exchange(url("/api/v1/connections/mine"), HttpMethod.GET,
                new HttpEntity<>(bearer(s.buyer().getId(), UUID.randomUUID(), UserRole.BUYER_ADMIN)),
                ConnectionSummaryResponse[].class).getBody();
        assertThat(mine).hasSize(1);
        assertThat(mine[0].monthlyLimit()).isEqualByComparingTo("250.00");
        assertThat(mine[0].approverUserIds()).containsExactly(a1);
        assertThat(mine[0].monthToDate()).isEqualByComparingTo("0");
    }

    @Test
    void patchCanClearTheLimitAndTheApprovers() {
        var s = scenario();
        UUID a1 = approver(s, "ap1@example.com");
        patch(s, s.buyer().getId(), Map.of("monthlyLimit", "250.00", "approverUserIds", List.of(a1)));

        var response = patch(s, s.buyer().getId(), Map.of("clearMonthlyLimit", true, "approverUserIds", List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Connection saved = connectionRepository.findById(s.connection().getId()).orElseThrow();
        assertThat(saved.getMonthlyLimit()).isNull();
        assertThat(saved.getApproverUserIds()).isEmpty();
    }

    @Test
    void anotherOrgCannotPatch() {
        var s = scenario();
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));

        assertThat(patch(s, intruder.getId(), Map.of("monthlyLimit", "1.00")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approversMustBeBuyerMembersOfTheOrg() {
        var s = scenario();
        UUID admin = identities.member(orgById(s), "adm@example.com", UserRole.BUYER_ADMIN, "Admin").getUser().getId();
        UUID outsider = identities.buyerAdmin("out@example.com").getUser().getId();

        assertThat(patch(s, s.buyer().getId(), Map.of("approverUserIds", List.of(admin))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patch(s, s.buyer().getId(), Map.of("approverUserIds", List.of(outsider))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyTheListedApproverCanDecideAndEditingTheListDoesNotChangeAnOpenGate() {
        var s = scenario();
        UUID listed = approver(s, "listed@example.com");
        UUID other = approver(s, "other@example.com");
        Connection connection = s.connection();
        connection.setApproverUserIds(Set.of(listed));
        connectionRepository.save(connection);
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-ctl", "tok-ctl");
        GateRequest gate = gateService.open(run.getId(), "post_journal_entries", 5, "Post entries", "{}", "[]");

        patch(s, s.buyer().getId(), Map.of("approverUserIds", List.of(other)));   // after the gate opened

        assertThat(decide(run, gate, s.buyer().getId(), other).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(run, gate, s.buyer().getId(), listed).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theGatesViewTellsEachViewerWhetherTheyMayDecide() {
        var s = scenario();
        UUID listed = approver(s, "listed@example.com");
        UUID admin = identities.member(orgById(s), "admin@example.com", UserRole.BUYER_ADMIN, "Finance lead").getUser().getId();
        Connection connection = s.connection();
        connection.setApproverUserIds(Set.of(listed));
        connectionRepository.save(connection);
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-vcd", "tok-vcd");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post entries", "{}", "[]");

        assertThat(viewerCanDecide(run, s.buyer().getId(), listed, UserRole.BUYER_MEMBER)).isTrue();
        assertThat(viewerCanDecide(run, s.buyer().getId(), admin, UserRole.BUYER_ADMIN)).isFalse();
        assertThat(viewerCanDecide(run, s.buyer().getId(), listed, UserRole.ADMIN)).isFalse();
    }

    boolean viewerCanDecide(Run run, UUID orgId, UUID userId, UserRole role) {
        var body = rest.exchange(url("/api/v1/runs/" + run.getId() + "/gates"), HttpMethod.GET,
                new HttpEntity<>(bearer(orgId, userId, role)), com.fasterxml.jackson.databind.JsonNode.class).getBody();
        return body.get(0).get("viewerCanDecide").asBoolean();
    }

    @Test
    void createAcceptsTheLimitAndApprovers() {
        var s = scenario();
        UUID a1 = approver(s, "ap1@example.com");
        var request = new CreateConnectionRequest(s.version().getAgent().getId(), List.of("bank_statement"),
                new BigDecimal("50.00"), new BigDecimal("300.00"), List.of(a1));

        var response = rest.postForEntity(url("/api/v1/connections"), new HttpEntity<>(request,
                bearer(s.buyer().getId(), UUID.randomUUID(), UserRole.BUYER_ADMIN)), ConnectionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Connection saved = connectionRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getMonthlyLimit()).isEqualByComparingTo("300.00");
        assertThat(saved.getApproverUserIds()).containsExactly(a1);
    }
}
