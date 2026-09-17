package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunSummaryControllerTest extends AbstractIntegrationTest {

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

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void filtersToTheCallersOrgAndOptionalStatus() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", "{}", "t",
                VersionStatus.LIVE, Tier.TIER_A, "cl"));
        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));

        Run run1 = new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("500.00"),
                BigDecimal.ZERO, "sandbox-1", "token-1");
        run1.setStartedAt(Instant.parse("2026-09-17T10:00:00Z"));
        runRepository.save(run1);

        Run run2 = new Run(null, connection, version, RunStatus.AWAITING_APPROVAL, new BigDecimal("500.00"),
                new BigDecimal("10.00"), "sandbox-2", "token-2");
        run2.setStartedAt(Instant.parse("2026-09-17T11:00:00Z"));
        UUID run2Id = runRepository.save(run2).getId();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var all = rest.exchange(url("/api/v1/runs/mine"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<RunSummaryResponse>>() {});
        assertThat(all.getBody()).hasSize(2);
        assertThat(all.getBody().get(0).id()).isEqualTo(run2Id);

        var waiting = rest.exchange(url("/api/v1/runs/mine?status=AWAITING_APPROVAL"), HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<RunSummaryResponse>>() {});
        assertThat(waiting.getBody()).hasSize(1);
        assertThat(waiting.getBody().get(0).status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }
}
