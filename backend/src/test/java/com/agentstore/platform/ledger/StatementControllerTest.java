package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.execution.*;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementControllerTest extends AbstractIntegrationTest {

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
    LedgerEntryRepository ledgerEntryRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ownerSeesStatementLinesWithAgentName() throws Exception {
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
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.COMPLETED, new BigDecimal("500.00"),
                new BigDecimal("10.00"), "sbx", RunTokenService.hash("tok")));
        ledgerEntryRepository.save(new LedgerEntry(null, UUID.randomUUID(), run.getId(), buyer.getId(),
                new BigDecimal("10.00"), LedgerEntryType.CHARGE));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/statement"), HttpMethod.GET,
                new HttpEntity<>(headers), new ParameterizedTypeReference<List<StatementLineResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("Bank Reconciliation");
        assertThat(response.getBody().get(0).amount()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void cannotReadAnotherOrgsStatement() throws Exception {
        Org buyer = orgRepository.save(new Org(null, "Acme2", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/statement"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
