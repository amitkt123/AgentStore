package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired DeveloperApiKeyRepository keys;
    @Autowired OrgMembershipRepository memberships;

    record Dev(Org org, OrgMembership membership, String jwt, String key) {}

    <T> ResponseEntity<T> call(HttpMethod method, String path, String bearer, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    Dev developer() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        OrgMembership m = identity.member(org, "dev-" + UUID.randomUUID() + "@example.com", UserRole.DEVELOPER, "Dev");
        String jwt = identity.tokenFor(m);
        var created = call(HttpMethod.POST, "/api/v1/developer/api-keys", jwt, Map.of("name", "ci"), Map.class);
        return new Dev(org, m, jwt, (String) created.getBody().get("key"));
    }

    HttpStatusCode status(HttpMethod method, String path, String bearer, Object body) {
        return call(method, path, bearer, body, String.class).getStatusCode();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyListsOnlyItsOwnOrgsAgents() {
        Dev mine = developer();
        Dev theirs = developer();
        agents.save(new Agent(null, mine.org(), "Mine", "finance", "d"));
        agents.save(new Agent(null, theirs.org(), "Theirs", "finance", "d"));

        var response = call(HttpMethod.GET, "/api/v1/agents/mine", mine.key(), null, List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) response.getBody().get(0)).get("agentName")).isEqualTo("Mine");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aKeyCreatesAnAgentAndAnImagelessDraftVersionInItsOwnOrg() throws Exception {
        Dev dev = developer();
        var agent = call(HttpMethod.POST, "/api/v1/agents", dev.key(),
                Map.of("name", "Recon", "category", "finance", "description", "d"), Map.class);
        assertThat(agent.getStatusCode()).isEqualTo(HttpStatus.OK);
        String agentId = (String) agent.getBody().get("id");
        assertThat(agents.findById(UUID.fromString(agentId)).orElseThrow().getDeveloperOrg().getId())
                .isEqualTo(dev.org().getId());

        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        var version = call(HttpMethod.POST, "/api/v1/agents/" + agentId + "/versions", dev.key(),
                Map.of("versionNumber", "1.0.0", "manifestJson", manifest, "changelog", "c"), Map.class);

        assertThat(version.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.findById(UUID.fromString((String) version.getBody().get("id"))).orElseThrow().getImageRef())
                .isNull();
    }

    @Test
    void aKeyCannotTouchAnotherOrgsAgent() throws Exception {
        Dev mine = developer();
        Dev theirs = developer();
        Agent theirAgent = agents.save(new Agent(null, theirs.org(), "Theirs", "finance", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));

        assertThat(status(HttpMethod.POST, "/api/v1/agents/" + theirAgent.getId() + "/versions", mine.key(),
                Map.of("versionNumber", "1.0.0", "manifestJson", manifest, "changelog", "c")))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aKeyIsForbiddenEverywhereOutsideTheAllowlistWhileAJwtIsNot() {
        Dev dev = developer();
        UUID orgId = dev.org().getId();

        // The same endpoints work with the developer's JWT, so the 403 is the key scope and not a role check.
        assertThat(status(HttpMethod.GET, "/api/v1/orgs/" + orgId + "/members", dev.jwt(), null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/developer/api-keys", dev.jwt(), null)).isEqualTo(HttpStatus.OK);
        assertThat(status(HttpMethod.GET, "/api/v1/me", dev.jwt(), null)).isEqualTo(HttpStatus.OK);

        assertThat(status(HttpMethod.GET, "/api/v1/orgs/" + orgId + "/members", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/orgs/" + orgId + "/wallet", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/me", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.GET, "/api/v1/review/queue", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aKeyCannotManageKeys() {
        Dev dev = developer();
        assertThat(status(HttpMethod.GET, "/api/v1/developer/api-keys", dev.key(), null)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(HttpMethod.POST, "/api/v1/developer/api-keys", dev.key(), Map.of("name", "sneaky")))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aKeyCannotSubmitForReview() {
        Dev dev = developer();
        Agent agent = agents.save(new Agent(null, dev.org(), "Recon", "finance", "d"));

        assertThat(status(HttpMethod.POST,
                "/api/v1/agents/" + agent.getId() + "/versions/" + UUID.randomUUID() + "/submit", dev.key(), null))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownAndRevokedKeysAreUnauthorized() {
        Dev dev = developer();
        String unknown = "ask_live_" + "0".repeat(43);
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", unknown, null)).isEqualTo(HttpStatus.UNAUTHORIZED);

        var row = keys.findByKeyHash(ApiKeys.hash(dev.key())).orElseThrow();
        row.setRevokedAt(Instant.now());
        keys.save(row);
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aKeyStopsWorkingWhenItsCreatorLeavesTheOrg() {
        Dev dev = developer();
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null)).isEqualTo(HttpStatus.OK);

        memberships.deleteById(dev.membership().getId());

        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void lastUsedIsRecordedAndThrottled() {
        Dev dev = developer();
        String hash = ApiKeys.hash(dev.key());
        assertThat(keys.findByKeyHash(hash).orElseThrow().getLastUsedAt()).isNull();

        status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null);
        Instant first = keys.findByKeyHash(hash).orElseThrow().getLastUsedAt();
        assertThat(first).isNotNull();

        status(HttpMethod.GET, "/api/v1/agents/mine", dev.key(), null);
        assertThat(keys.findByKeyHash(hash).orElseThrow().getLastUsedAt()).isEqualTo(first);
    }

    @Test
    void aJwtStillWorksAlongsideTheKeyFilter() {
        Dev dev = developer();
        assertThat(status(HttpMethod.GET, "/api/v1/agents/mine", dev.jwt(), null)).isEqualTo(HttpStatus.OK);
    }
}
