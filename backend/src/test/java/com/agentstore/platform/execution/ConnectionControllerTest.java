package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionControllerTest extends AbstractIntegrationTest {

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

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Agent liveBankReconAgent(Org developer) throws Exception {
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        return agent;
    }

    @Test
    void buyerGrantsADeclaredScope() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme SMB", OrgType.BUYER));
        Agent agent = liveBankReconAgent(developer);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(
                new CreateConnectionRequest(agent.getId(), List.of("bank_statement"), new BigDecimal("500.00")), headers);

        var response = rest.postForEntity(url("/api/v1/connections"), request, ConnectionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ConnectionStatus.ACTIVE);
        assertThat(response.getBody().grantedScopes()).containsExactly("bank_statement");
    }

    @Test
    void buyerCannotGrantAnUndeclaredScope() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme SMB 2", OrgType.BUYER));
        Agent agent = liveBankReconAgent(developer);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(
                new CreateConnectionRequest(agent.getId(), List.of("gst_portal_login"), new BigDecimal("500.00")), headers);

        var response = rest.postForEntity(url("/api/v1/connections"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-500.00", "0"})
    void buyerCannotCreateAConnectionWithANonPositiveSpendCap(String spendCap) throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo3", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme SMB 3", OrgType.BUYER));
        Agent agent = liveBankReconAgent(developer);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(
                new CreateConnectionRequest(agent.getId(), List.of("bank_statement"), new BigDecimal(spendCap)), headers);

        var response = rest.postForEntity(url("/api/v1/connections"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
