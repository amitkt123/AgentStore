package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.UsageEventRepository;
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

class AgentCallbackCompletionTest extends AbstractIntegrationTest {

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
    UsageEventRepository usageEventRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpEntity<Map<String, Object>> eventRequest(String kind, Map<String, Object> detail) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-complete");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(Map.of("kind", kind, "detail", detail), headers);
    }

    @Test
    void costEventEmitsUsageEventAndCompleteEndsTheRun() throws Exception {
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
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("500.00"),
                BigDecimal.ZERO, "fake-sandbox-complete", RunTokenService.hash("tok-complete")));

        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("COST", Map.of("quantity", 5)), Void.class);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                eventRequest("COMPLETE", Map.of()), Void.class);

        var usageEvents = usageEventRepository.findByRunId(run.getId());
        assertThat(usageEvents).hasSize(1);
        assertThat(usageEvents.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(usageEvents.get(0).getOrgId()).isEqualTo(buyer.getId());

        Run completed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(fakeSandboxProvider.wasKilled("fake-sandbox-complete")).isTrue();
    }
}
