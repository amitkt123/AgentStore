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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateAgentVersionTest extends AbstractIntegrationTest {

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

    HttpHeaders authHeaders(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.DEVELOPER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void createsDraftVersionWithValidManifest() throws Exception {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developerOrg, "Bank Reconciliation", "reconciliation", "desc"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));

        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "bank-recon-template-v1", "first cut"),
                authHeaders(developerOrg.getId()));

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"), request, AgentVersionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(VersionStatus.DRAFT);
        var saved = agentVersionRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getImageRef()).isEqualTo("bank-recon-template-v1");
    }

    @Test
    void rejectsInvalidManifest() throws Exception {
        Org developerOrg = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developerOrg, "Broken", "x", "y"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/invalid-missing-cost-envelope.json"));

        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "template", "cl"),
                authHeaders(developerOrg.getId()));

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsVersionCreationByNonOwningDeveloper() throws Exception {
        Org owner = orgRepository.save(new Org(null, "Owner", OrgType.DEVELOPER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, owner, "Bank Reconciliation", "reconciliation", "desc"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));

        var request = new HttpEntity<>(new CreateAgentVersionRequest("1.0.0", manifest, "template", "cl"),
                authHeaders(intruder.getId()));

        var response = rest.postForEntity(url("/api/v1/agents/" + agent.getId() + "/versions"), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
