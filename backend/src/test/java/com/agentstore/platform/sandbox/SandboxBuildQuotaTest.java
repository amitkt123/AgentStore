package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.AgentBundleRepository;
import com.agentstore.platform.build.TemplateBuildRepository;
import com.agentstore.platform.build.TestBundles;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The daily sandbox build quota, driven through the same bundle push/finalize path as {@code BundleControllerTest}. */
@TestPropertySource(properties = "agentstore.sandbox.quota.unverified.builds-per-day=2")
class SandboxBuildQuotaTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired AgentBundleRepository bundles;
    @Autowired TemplateBuildRepository builds;
    @Autowired SandboxQuotaService quota;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    record Dev(String token, com.agentstore.platform.catalog.Agent agent, UUID userId) {}

    Dev developer() {
        var org = orgs.save(new com.agentstore.platform.identity.Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        var agent = agents.save(new com.agentstore.platform.catalog.Agent(null, org, "Recon", "finance", "d"));
        return new Dev(identity.tokenFor(m), agent, m.getUser().getId());
    }

    ResponseEntity<String> post(Dev dev, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dev.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    JsonNode read(ResponseEntity<String> response) throws Exception {
        return json.readTree(response.getBody());
    }

    ResponseEntity<String> push(Dev dev, byte[] zip) throws Exception {
        var upload = read(post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/uploads",
                Map.of("sha256", TestBundles.sha256(zip), "sizeBytes", zip.length)));
        HttpResponse<Void> put = http.send(HttpRequest.newBuilder(URI.create(upload.get("uploadUrl").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);
        return post(dev, "/api/v1/agents/" + dev.agent().getId() + "/bundles/" + upload.get("bundleId").asText() + "/finalize",
                Map.of("sha256", TestBundles.sha256(zip)));
    }

    @Test
    void directCheckRejectsTheThirdBuildOfTheDayForAnUnverifiedOrg() {
        Dev dev = developer();

        quota.checkBuild(dev.agent().getDeveloperOrg().getId());
        // simulate two builds already queued today, straight in the repository (cheaper than three real pushes)
        queueBuild(dev, "1.0.0");
        queueBuild(dev, "2.0.0");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> quota.checkBuild(dev.agent().getDeveloperOrg().getId()))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).extra()).containsEntry("limit", "buildsToday"));
    }

    /** A build queued for this agent's version, straight in the repository. */
    private com.agentstore.platform.build.TemplateBuild queueBuild(Dev dev, String versionNumber) {
        var version = versions.save(new com.agentstore.platform.catalog.AgentVersion(null, dev.agent(), versionNumber,
                SandboxFixtures.readManifest("valid-bundle-python.json"), null, com.agentstore.platform.catalog.VersionStatus.DRAFT,
                com.agentstore.platform.catalog.Tier.TIER_A, "c"));
        var bundleId = UUID.randomUUID();
        var bundle = bundles.save(new com.agentstore.platform.build.AgentBundle(bundleId, dev.agent(), dev.userId(),
                "bundles/" + bundleId + ".zip", "d".repeat(64), 10, com.agentstore.platform.build.BundleStatus.UPLOADED));
        return builds.save(new com.agentstore.platform.build.TemplateBuild(version, bundle, "e2b", "python-3.12", "agent/main.py"));
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void aBuildQueuedYesterdayDoesNotCount() {
        Dev dev = developer();
        var yesterday = queueBuild(dev, "1.0.0");
        jdbc.update("UPDATE template_build SET queued_at = queued_at - interval '1 day' WHERE id = ?", yesterday.getId());
        queueBuild(dev, "2.0.0");

        quota.checkBuild(dev.agent().getDeveloperOrg().getId());   // only 1 counts today; limit is 2: does not throw
    }

    @Test
    void finalizingThroughTheRealEndpointIsRejectedOnceTheDailyLimitIsReached() throws Exception {
        Dev dev = developer();
        assertThat(push(dev, TestBundles.python("1.0.0")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(push(dev, TestBundles.python("2.0.0")).getStatusCode()).isEqualTo(HttpStatus.OK);
        long buildsBefore = builds.count();

        var third = push(dev, TestBundles.python("3.0.0"));

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody()).contains("SANDBOX_QUOTA_EXCEEDED");
        assertThat(builds.count()).isEqualTo(buildsBefore);   // no new build queued from the rejected finalize
        assertThat(versions.findByAgentIdAndVersionNumber(dev.agent().getId(), "3.0.0")).isEmpty();
    }
}
