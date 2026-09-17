package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MyAgentsControllerTest extends AbstractIntegrationTest {

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

    @Test
    void listsOnlyTheCallersOwnAgentsAcrossAllStatuses() {
        Org mine = orgRepository.save(new Org(null, "MyDevCo", OrgType.DEVELOPER));
        Org other = orgRepository.save(new Org(null, "OtherDevCo", OrgType.DEVELOPER));
        Agent myAgent = agentRepository.save(new Agent(null, mine, "My Agent", "reconciliation", "d"));
        agentRepository.save(new Agent(null, other, "Other Agent", "x", "d"));

        agentVersionRepository.save(new AgentVersion(null, myAgent, "0.1.0", "{}", "t", VersionStatus.DRAFT, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), mine.getId(), UserRole.DEVELOPER));

        var response = rest.exchange(url("/api/v1/agents/mine"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<MyAgentsResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).agentName()).isEqualTo("My Agent");
        assertThat(response.getBody().get(0).versions()).hasSize(1);
        assertThat(response.getBody().get(0).versions().get(0).status()).isEqualTo(VersionStatus.DRAFT);
    }
}
