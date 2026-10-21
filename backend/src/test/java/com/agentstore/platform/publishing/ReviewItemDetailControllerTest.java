package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
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

// AbstractIntegrationTest does not reset DB state, so other test classes (and
// detailIncludesManifestAndNotes(), which leaves its item PENDING) can leave items in the
// queue. Assert on this test's own item rather than the queue size.
class ReviewItemDetailControllerTest extends AbstractIntegrationTest {

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
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        return h;
    }

    @Test
    void queueIncludesAgentNameAndVersionNumber() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());

        var response = rest.exchange(url("/api/v1/review/queue"), HttpMethod.GET, new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<ReviewItemResponse>>() {});

        ReviewItemResponse item = response.getBody().stream()
                .filter(r -> r.agentVersionId().equals(version.getId()))
                .findFirst().orElseThrow();
        assertThat(item.agentName()).isEqualTo("Collections");
        assertThat(item.versionNumber()).isEqualTo("1.0.0");
    }

    @Test
    void detailIncludesManifestAndNotes() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections2", "collections", "d"));
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));
        publishingService.submit(version.getId(), developer.getId());
        ReviewItem pending = reviewItemRepository.findByAgentVersionId(version.getId()).orElseThrow();

        var response = rest.exchange(url("/api/v1/review/" + pending.getId()), HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), ReviewItemDetailResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().manifest().scopes()).isNotEmpty();
        assertThat(response.getBody().status()).isEqualTo(ReviewStatus.PENDING);
    }
}
