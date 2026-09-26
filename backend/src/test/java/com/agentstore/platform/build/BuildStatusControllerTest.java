package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildStatusControllerTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired BuildFixtures fixtures;
    @Autowired IdentityFixtures identity;
    @Autowired OrgRepository orgs;
    @Autowired com.agentstore.platform.catalog.AgentVersionRepository versions;

    ResponseEntity<String> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    void theOwnerSeesStatusFailureAndALogLink() throws Exception {
        var built = fixtures.queued("e2b");
        built.build().setStatus(BuildStatus.FAILED);
        built.build().setFailureCode("DEP_VULN");
        built.build().setFailureReason("2 high vulnerabilities");
        storage.put(built.build().getLogStorageKey(), "audit output\n".getBytes());
        fixtures.saveBuild(built.build());
        String token = identity.tokenFor(identity.member(built.org(), "owner-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));

        var response = get(token, "/api/v1/agents/" + built.agent().getId() + "/builds/" + built.build().getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("FAILED");
        assertThat(body.get("failureCode").asText()).isEqualTo("DEP_VULN");
        assertThat(body.get("versionId").asText()).isEqualTo(built.version().getId().toString());
        assertThat(body.get("logUrl").asText()).contains(built.build().getId() + ".log");
    }

    @Test
    void theLogLinkIsNullBeforeAnyLogExists() throws Exception {
        var built = fixtures.queued("e2b");
        String token = identity.tokenFor(identity.member(built.org(), "owner-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        JsonNode body = new ObjectMapper().readTree(
                get(token, "/api/v1/agents/" + built.agent().getId() + "/builds/" + built.build().getId()).getBody());
        assertThat(body.get("logUrl").isNull()).isTrue();
    }

    @Test
    void anotherOrgIsForbiddenAndAWrongAgentIsNotFound() {
        var built = fixtures.queued("e2b");
        Org other = orgs.save(new Org(null, "Other " + UUID.randomUUID(), OrgType.DEVELOPER));
        String intruder = identity.tokenFor(identity.member(other, "x-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        assertThat(get(intruder, "/api/v1/agents/" + built.agent().getId() + "/builds/" + built.build().getId())
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String owner = identity.tokenFor(identity.member(built.org(), "o-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        assertThat(get(owner, "/api/v1/agents/" + UUID.randomUUID() + "/builds/" + built.build().getId())
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theVersionDetailShowsTheBuildToItsOwnerButNotToABuyer() throws Exception {
        var built = fixtures.queued("e2b");
        var version = fixtures.reload(built.version());
        version.setStatus(com.agentstore.platform.catalog.VersionStatus.LIVE);
        versions.save(version);
        String path = "/api/v1/agents/" + built.agent().getId() + "/versions/" + built.version().getId();
        String owner = identity.tokenFor(identity.member(built.org(), "own-" + UUID.randomUUID() + "@example.com",
                UserRole.DEVELOPER, "Dev"));
        String buyer = identity.tokenFor(identity.buyerAdmin("buy-" + UUID.randomUUID() + "@example.com"));

        JsonNode ownerView = new ObjectMapper().readTree(get(owner, path).getBody());
        JsonNode buyerView = new ObjectMapper().readTree(get(buyer, path).getBody());

        assertThat(ownerView.get("build").get("buildId").asText()).isEqualTo(built.build().getId().toString());
        assertThat(ownerView.get("build").get("status").asText()).isEqualTo("QUEUED");
        assertThat(buyerView.get("build") == null || buyerView.get("build").isNull()).isTrue();
    }
}
