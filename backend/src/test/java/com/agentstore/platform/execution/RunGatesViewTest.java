package com.agentstore.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class RunGatesViewTest extends AbstractIntegrationTest {

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

    private HttpEntity<Void> buyer(UUID orgId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_APPROVER));
        return new HttpEntity<>(h);
    }

    @Test
    void gateViewShowsCostPreviewAndPayload() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-gv", "tok-gv");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post 3 entries", "{\"n\":3}", "[]",
                new BigDecimal("10"), "{\"rows\":[],\"totalRows\":0}");

        var response = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + run.getDisplayId() + "/gates",
                HttpMethod.GET, buyer(s.buyer().getId()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var gate = response.getBody().get(0);
        assertThat(gate.get("status").asText()).isEqualTo("PENDING");
        assertThat(gate.get("costIfApproved").decimalValue()).isPositive();
        assertThat(gate.get("preview").get("totalRows").asInt()).isZero();
        assertThat(gate.get("payload").get("n").asInt()).isEqualTo(3);
    }

    @Test
    void flowGateBlockCarriesExpiryAndCost() {
        var s = fixtures.liveWithManifest("valid-runtime-agent.json", java.util.List.of("bank_statement", "journal_entries"),
                new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-gf", "tok-gf");
        gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]", new BigDecimal("10"), null);

        var flow = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + run.getId() + "/flow",
                HttpMethod.GET, buyer(s.buyer().getId()), JsonNode.class).getBody();

        var block = java.util.stream.StreamSupport.stream(flow.get("blocks").spliterator(), false)
                .filter(b -> b.get("block").asText().equals("gate:post_journal_entries")).findFirst().orElseThrow();
        assertThat(block.get("state").asText()).isEqualTo("WAITING");
        assertThat(block.get("metrics").has("expiresAt")).isTrue();
        assertThat(block.get("metrics").has("costIfApproved")).isTrue();
    }

    @Test
    void anotherOrgIsRefusedAndAnUnknownReferenceIs404() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-go", "tok-go");
        String base = "http://localhost:" + port + "/api/v1/runs/";
        assertThat(rest.exchange(base + run.getId() + "/gates", HttpMethod.GET, buyer(UUID.randomUUID()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(base + "R-99999999/gates", HttpMethod.GET, buyer(s.buyer().getId()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void gatesAreListedNewestFirst() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-gn", "tok-gn");
        var first = gateService.open(run.getId(), "post_journal_entries", 5, "first", "{}", "[]");
        gateService.decide(run.getId(), null, true, UUID.randomUUID(), "ok", DecisionChannel.WEB);
        var second = gateService.open(run.getId(), "post_journal_entries", 5, "second", "{}", "[]");

        JsonNode list = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + run.getId() + "/gates",
                HttpMethod.GET, buyer(s.buyer().getId()), JsonNode.class).getBody();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("gateId").asText()).isEqualTo(second.getId().toString());
        assertThat(list.get(1).get("gateId").asText()).isEqualTo(first.getId().toString());
    }
}
