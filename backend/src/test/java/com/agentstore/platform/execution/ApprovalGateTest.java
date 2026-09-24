package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    record Fixture(Org buyer, Run run) {}

    Fixture runAwaitingApproval() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_B, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("invoice", "whatsapp_message"), new BigDecimal("200.00"), ConnectionStatus.ACTIVE));
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("200.00"),
                BigDecimal.ZERO, "fake-sandbox-gate", RunTokenService.hash("tok-gate")));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-gate");
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                new HttpEntity<>(Map.of("kind", "GATE", "detail", Map.of("trigger", "MESSAGE_CUSTOMER")), headers), Void.class);

        return new Fixture(buyer, runRepository.findById(run.getId()).orElseThrow());
    }

    HttpHeaders approverHeaders(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_APPROVER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    GateStatusResponse pollGateStatus(UUID runId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-gate");
        return rest.exchange(url("/internal/agent-callback/runs/" + runId + "/gate-status"), HttpMethod.GET,
                new HttpEntity<>(headers), GateStatusResponse.class).getBody();
    }

    @Test
    void gateEventPausesRunForApproval() throws Exception {
        Fixture fixture = runAwaitingApproval();
        assertThat(fixture.run().getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(pollGateStatus(fixture.run().getId()).decision()).isEqualTo("PENDING");
    }

    @Test
    void approvingResumesTheRun() throws Exception {
        Fixture fixture = runAwaitingApproval();

        var response = rest.exchange(url("/api/v1/runs/" + fixture.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("looks fine"), approverHeaders(fixture.buyer().getId())), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(pollGateStatus(fixture.run().getId()).decision()).isEqualTo("APPROVED");
    }

    @Test
    void rejectingCancelsAndKillsTheRun() throws Exception {
        Fixture fixture = runAwaitingApproval();

        var response = rest.exchange(url("/api/v1/runs/" + fixture.run().getId() + "/reject"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("too risky"), approverHeaders(fixture.buyer().getId())), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-gate")).isTrue();
        assertThat(pollGateStatus(fixture.run().getId()).decision()).isEqualTo("REJECTED");
    }

    @Test
    void approverFromAnotherOrgIsForbidden() throws Exception {
        Fixture fixture = runAwaitingApproval();
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));

        var response = rest.exchange(url("/api/v1/runs/" + fixture.run().getId() + "/approve"), HttpMethod.POST,
                new HttpEntity<>(new ApproveRunRequest("n/a"), approverHeaders(intruder.getId())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
