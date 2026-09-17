package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogControllerTest extends AbstractIntegrationTest {

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

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void developerCreatesAnAgentListing() {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        String token = jwtService.issue(java.util.UUID.randomUUID(), developerOrg.getId(), UserRole.DEVELOPER);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new CreateAgentRequest("Bank Reconciliation", "reconciliation", "Matches bank rows"), headers);

        var response = rest.postForEntity(url("/api/v1/agents"), request, AgentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Bank Reconciliation");
        assertThat(agentRepository.findById(response.getBody().id())).isPresent();
        assertThat(agentRepository.findById(response.getBody().id()).get().getDeveloperOrg().getId())
                .isEqualTo(developerOrg.getId());
    }

    @Test
    void buyerCannotCreateAnAgentListing() {
        Org buyerOrg = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        String token = jwtService.issue(java.util.UUID.randomUUID(), buyerOrg.getId(), UserRole.BUYER_ADMIN);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(new CreateAgentRequest("Nope", "x", "y"), headers);

        var response = rest.postForEntity(url("/api/v1/agents"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
