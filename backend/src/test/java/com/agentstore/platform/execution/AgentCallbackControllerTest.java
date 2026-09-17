package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

class AgentCallbackControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
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
    RunStepRepository runStepRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Run newRunningRun(BigDecimal costCap, String sandboxId) throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        Run run = new Run(null, connection, version, RunStatus.RUNNING, costCap, BigDecimal.ZERO,
                sandboxId, "tok-123");
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    HttpEntity<Map<String, Object>> eventRequest(String token, String kind, Map<String, Object> detail) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("kind", kind, "detail", detail), headers);
    }

    @Test
    void costEventUnderCapAccumulatesWithoutKillingRun() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"), "fake-sandbox-under-cap");

        var response = rest.postForEntity(
                url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(new BigDecimal("6.00"));
        assertThat(updated.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-under-cap")).isFalse();
        assertThat(runStepRepository.findByRunIdOrderByCreatedAtAsc(run.getId())).hasSize(1);
    }

    @Test
    void costEventExceedingCapKillsSandboxAndFailsRun() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"), "fake-sandbox-over-cap");

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("tok-123", "COST", Map.of("quantity", 3)), Void.class);

        Run updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalCharged()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(updated.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-over-cap")).isTrue();
    }

    @Test
    void wrongCallbackTokenIsRejected() throws Exception {
        Run run = newRunningRun(new BigDecimal("10.00"), "fake-sandbox-wrong-token");

        var response = rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("wrong-token", "READ", Map.of()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
