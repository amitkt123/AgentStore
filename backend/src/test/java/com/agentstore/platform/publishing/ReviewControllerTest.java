package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewControllerTest extends AbstractIntegrationTest {

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
    ReviewItemRepository reviewItemRepository;
    @Autowired
    PublishingService publishingService;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void approvingAReviewItemPublishesTheVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());
        ReviewItem pending = reviewItemRepository.findByAgentVersionId(version.getId()).orElseThrow();

        var request = new HttpEntity<>(new ReviewDecisionRequest("looks safe"), adminHeaders());
        var response = rest.postForEntity(url("/api/v1/review/" + pending.getId() + "/approve"), request, ReviewItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ReviewStatus.APPROVED);
        AgentVersion updated = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VersionStatus.LIVE);
        assertThat(updated.getApprovedAt()).isNotNull();
    }

    @Test
    void rejectingAReviewItemRejectsTheVersion() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections2", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());
        ReviewItem pending = reviewItemRepository.findByAgentVersionId(version.getId()).orElseThrow();

        var request = new HttpEntity<>(new ReviewDecisionRequest("scope too broad"), adminHeaders());
        var response = rest.postForEntity(url("/api/v1/review/" + pending.getId() + "/reject"), request, ReviewItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(ReviewStatus.REJECTED);
        AgentVersion updated = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VersionStatus.REJECTED);
    }
}
