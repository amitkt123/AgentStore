package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.publishing.PublishingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByokSubmitTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PublishingService publishingService;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired LlmFixtures fx;
    @Autowired IdentityFixtures identity;

    Org developer(boolean verified) {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        if (verified) {
            fx.verifyDeveloperOrg(org);
        }
        return org;
    }

    AgentVersion draft(Org developer, String manifestFile) throws Exception {
        Agent agent = agents.save(new Agent(null, developer, "Agent " + UUID.randomUUID(), "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/" + manifestFile));
        return versions.save(new AgentVersion(null, agent, "1.0.0", manifest, "template-v1", VersionStatus.DRAFT, null, "cl"));
    }

    @Test
    void anUnverifiedOrgCannotSubmitAByokManifest() throws Exception {
        for (String manifest : new String[]{"valid-llm-byok-proxy.json", "valid-llm-byok-direct.json"}) {
            Org org = developer(false);
            AgentVersion version = draft(org, manifest);

            assertThatThrownBy(() -> publishingService.submit(version.getId(), org.getId()))
                    .isInstanceOfSatisfying(LlmApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.code()).isEqualTo("BYOK_NOT_VERIFIED");
                    });
            assertThat(versions.findById(version.getId()).orElseThrow().getStatus()).isEqualTo(VersionStatus.DRAFT);
        }
    }

    @Test
    void aVerifiedOrgsByokVersionIsTierBAndWaitsForReview() throws Exception {
        for (String manifest : new String[]{"valid-llm-byok-proxy.json", "valid-llm-byok-direct.json"}) {
            Org org = developer(true);
            AgentVersion version = draft(org, manifest);

            AgentVersion result = publishingService.submit(version.getId(), org.getId());

            assertThat(result.getTier()).isEqualTo(Tier.TIER_B);
            assertThat(result.getStatus()).isEqualTo(VersionStatus.IN_REVIEW);
            assertThat(result.getApprovedAt()).isNull();
        }
    }

    @Test
    void aPlatformLlmManifestNeedsNoVerificationAndStaysTierA() throws Exception {
        Org org = developer(false);
        AgentVersion version = draft(org, "valid-llm-platform.json");

        AgentVersion result = publishingService.submit(version.getId(), org.getId());

        assertThat(result.getTier()).isEqualTo(Tier.TIER_A);
        assertThat(result.getStatus()).isEqualTo(VersionStatus.LIVE);
    }

    @Test
    void theSubmitEndpointAnswers403WithTheCode() throws Exception {
        Org org = developer(false);
        AgentVersion version = draft(org, "valid-llm-byok-proxy.json");
        OrgMembership membership = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(identity.tokenFor(membership));

        var response = rest.exchange("http://localhost:" + port + "/api/v1/agents/" + version.getAgent().getId()
                + "/versions/" + version.getId() + "/submit", HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("BYOK_NOT_VERIFIED");
    }
}
