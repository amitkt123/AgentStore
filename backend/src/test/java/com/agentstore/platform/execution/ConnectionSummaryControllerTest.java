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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSummaryControllerTest extends AbstractIntegrationTest {

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
    ConnectionRepository connectionRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void listsOnlyTheCallersOwnConnectionsWithAgentName() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        Org myBuyer = orgRepository.save(new Org(null, "MyBuyerCo", OrgType.BUYER));
        Org otherBuyer = orgRepository.save(new Org(null, "OtherBuyerCo", OrgType.BUYER));

        connectionRepository.save(new Connection(null, myBuyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));
        connectionRepository.save(new Connection(null, otherBuyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), myBuyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/connections/mine"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<ConnectionSummaryResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("Bank Reconciliation");
    }
}
