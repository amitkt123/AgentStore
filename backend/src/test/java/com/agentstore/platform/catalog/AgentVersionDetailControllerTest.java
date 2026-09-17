package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionDetailControllerTest extends AbstractIntegrationTest {

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

    HttpHeaders headers(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    @Test
    void anyBuyerCanReadALiveVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", manifest, "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        version = agentVersionRepository.save(version);

        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId()),
                HttpMethod.GET, new HttpEntity<>(headers(buyer.getId(), UserRole.BUYER_ADMIN)),
                AgentVersionDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().manifest().name()).isEqualTo("Bank Reconciliation Agent");
        assertThat(response.getBody().status()).isEqualTo(VersionStatus.LIVE);
    }

    @Test
    void buyerCannotReadADraftVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Unfinished", "x", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion draft = agentVersionRepository.save(new AgentVersion(null, agent, "0.1.0", manifest,
                "template-x", VersionStatus.DRAFT, null, "cl"));

        Org buyer = orgRepository.save(new Org(null, "BuyerCo2", OrgType.BUYER));
        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + draft.getId()),
                HttpMethod.GET, new HttpEntity<>(headers(buyer.getId(), UserRole.BUYER_ADMIN)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void owningDeveloperCanReadTheirOwnDraft() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo3", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "In Progress", "x", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion draft = agentVersionRepository.save(new AgentVersion(null, agent, "0.1.0", manifest,
                "template-x", VersionStatus.DRAFT, null, "cl"));

        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + draft.getId()),
                HttpMethod.GET, new HttpEntity<>(headers(developer.getId(), UserRole.DEVELOPER)),
                AgentVersionDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
