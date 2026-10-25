package com.agentstore.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** WS1b acceptance: gate with an estimate and a platform preview, approver enforcement, stop reason and run list. */
class Ws1bEndToEndTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    JwtService jwtService;
    @Autowired
    UserRepository users;
    @Autowired
    OrgMembershipRepository memberships;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private <T> org.springframework.http.ResponseEntity<T> call(HttpMethod method, String path, HttpHeaders headers,
                                                               Object body, Class<T> type) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), type);
    }

    private HttpHeaders buyer(UUID orgId, UUID userId, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(userId, orgId, role));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Map<String, Object> entry(String line, int amount) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("bank_line", line);
        e.put("amount", amount);
        return e;
    }

    @Test
    void aGateShowsCostAndPreviewOnlyEligibleApproversDecideAndTheRunEndsFinished() {
        var agent = fx.runningAgent();
        UUID orgId = agent.scenario().buyer().getId();
        String displayId = agent.run().getDisplayId();
        User eligible = users.save(new User(null, "eligible-" + UUID.randomUUID() + "@example.com", null, "Erin Eligible"));
        OrgMembership membership = new OrgMembership();
        membership.setId(new OrgMembershipId(eligible.getId(), orgId));
        membership.setUser(eligible);
        membership.setOrg(agent.scenario().buyer());
        membership.setRole(UserRole.BUYER_MEMBER);
        membership.setCreatedAt(Instant.now());
        memberships.save(membership);
        HttpHeaders agentHeaders = RuntimeFixtures.bearer(agent.token());

        // the agent stages two rows and asks for approval with an estimate
        assertThat(call(HttpMethod.POST, "/runtime/v1/resources/journal_entries/records", agentHeaders,
                Map.of("records", List.of(entry("Salary", 50000), entry("Rent", -12000))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        var gate = call(HttpMethod.POST, "/runtime/v1/gates", agentHeaders, Map.of("action", "post_journal_entries",
                "summary", "Post 2 entries", "payload", Map.of("rows", 2), "affectsResources", List.of("journal_entries"),
                "estimatedUnits", 2), Map.class);
        assertThat(gate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String gateId = (String) gate.getBody().get("gateId");

        // the buyer sees cost and a platform-built preview, by display id
        HttpHeaders approver = buyer(orgId, UUID.randomUUID(), UserRole.BUYER_MEMBER);
        JsonNode gates = call(HttpMethod.GET, "/api/v1/runs/" + displayId + "/gates", approver, null, JsonNode.class).getBody();
        assertThat(gates.get(0).get("costIfApproved").decimalValue()).isPositive();
        assertThat(gates.get(0).get("preview").get("totalRows").asInt()).isEqualTo(2);
        assertThat(gates.get(0).get("preview").get("rows")).hasSize(2);

        // a run waiting on a gate is listed under needs_you
        JsonNode waiting = call(HttpMethod.GET, "/api/v1/runs?group=needs_you", approver, null, JsonNode.class).getBody();
        assertThat(waiting.get("items").get(0).get("displayId").asText()).isEqualTo(displayId);
        assertThat(waiting.get("counts").get("needs_you").asInt()).isEqualTo(1);

        // an approver who is not on the gate's list is refused; the listed one is accepted
        String decisionPath = "/api/v1/runs/" + displayId + "/gates/" + gateId + "/decision";
        assertThat(call(HttpMethod.POST, decisionPath, approver, Map.of("decision", "APPROVE"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, decisionPath, buyer(orgId, eligible.getId(), UserRole.BUYER_MEMBER),
                Map.of("decision", "APPROVE"), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the agent finishes; the run ends FINISHED and moves to the finished group
        assertThat(call(HttpMethod.POST, "/runtime/v1/complete", agentHeaders, Map.of("summary", "Posted 2 entries"),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = call(HttpMethod.GET, "/api/v1/runs/" + displayId, approver, null, JsonNode.class).getBody();
        assertThat(detail.get("terminalReason").asText()).isEqualTo("FINISHED");
        JsonNode finished = call(HttpMethod.GET, "/api/v1/runs?group=finished", approver, null, JsonNode.class).getBody();
        assertThat(finished.get("items").get(0).get("terminalReason").asText()).isEqualTo("FINISHED");
        assertThat(finished.get("items").get(0).get("decidedBy").get("name").asText()).isEqualTo("Erin Eligible");
        assertThat(finished.get("counts").get("finished").asInt()).isEqualTo(1);
    }
}
