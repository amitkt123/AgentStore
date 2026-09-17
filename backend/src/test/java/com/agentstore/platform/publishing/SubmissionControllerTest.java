package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionControllerTest extends AbstractIntegrationTest {

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
    void submittingDraftMovesItToInReview() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), developer.getId(), UserRole.DEVELOPER));

        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/submit"),
                HttpMethod.POST, new HttpEntity<>(headers), AgentVersionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(VersionStatus.IN_REVIEW);
    }

    @Test
    void cannotResubmitAVersionAlreadyInReview() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Agent", "x", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.IN_REVIEW, null, "cl"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), developer.getId(), UserRole.DEVELOPER));

        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/submit"),
                HttpMethod.POST, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
