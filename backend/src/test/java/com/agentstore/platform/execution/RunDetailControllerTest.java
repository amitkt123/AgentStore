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

class RunDetailControllerTest extends AbstractIntegrationTest {

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
    void ownerCanReadTraceButOtherOrgCannot() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        agentVersionRepository.save(version);
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent,
                List.of("bank_statement"), new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        Run run = runRepository.save(new Run(null, connection, version, RunStatus.RUNNING, new BigDecimal("500.00"),
                new BigDecimal("10.00"), "fake-sandbox-trace", "tok-trace"));

        HttpHeaders callbackHeaders = new HttpHeaders();
        callbackHeaders.setBearerAuth("tok-trace");
        callbackHeaders.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity(url("/internal/agent-callback/runs/" + run.getId() + "/events"),
                new HttpEntity<>(Map.of("kind", "READ", "detail", Map.of("resource", "bank_statement")), callbackHeaders), Void.class);

        HttpHeaders ownerHeaders = new HttpHeaders();
        ownerHeaders.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));
        var ownerResponse = rest.exchange(url("/api/v1/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(ownerHeaders), RunDetailResponse.class);

        assertThat(ownerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerResponse.getBody().steps()).hasSize(1);
        assertThat(ownerResponse.getBody().steps().get(0).kind()).isEqualTo(RunStepKind.READ);

        HttpHeaders intruderHeaders = new HttpHeaders();
        intruderHeaders.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));
        var intruderResponse = rest.exchange(url("/api/v1/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(intruderHeaders), String.class);

        assertThat(intruderResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
