package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogListingControllerTest extends AbstractIntegrationTest {

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
    void listsOnlyLiveVersions() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent liveAgent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        Agent draftAgent = agentRepository.save(new Agent(null, developer, "Unfinished", "x", "d"));

        AgentVersion live = new AgentVersion(null, liveAgent, "1.0.0", "{\"costEnvelope\":{\"unitPrice\":2.00,\"unitLabel\":\"reconciled_transaction\",\"currency\":\"INR\"}}",
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        live.setApprovedAt(Instant.now());
        agentVersionRepository.save(live);

        agentVersionRepository.save(new AgentVersion(null, draftAgent, "0.1.0", "{}", "template-x",
                VersionStatus.DRAFT, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/catalog"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<CatalogListingResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .filteredOn(entry -> entry.agentId().equals(liveAgent.getId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.agentName()).isEqualTo("Bank Reconciliation"));
        assertThat(response.getBody())
                .noneMatch(entry -> entry.agentId().equals(draftAgent.getId()));
    }
}
