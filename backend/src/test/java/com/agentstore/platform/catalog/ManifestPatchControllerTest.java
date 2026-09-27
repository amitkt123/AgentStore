package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestPatchRequest;
import com.agentstore.platform.manifest.ManifestPatchResponse;
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

class ManifestPatchControllerTest extends AbstractIntegrationTest {

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
    ManifestParser manifestParser;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authHeaders(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.DEVELOPER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String manifest(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    private AgentVersion draftVersion(Agent agent, String number) throws Exception {
        return agentVersionRepository.save(new AgentVersion(null, agent, number,
                manifest("valid-runtime-agent.json"), null, VersionStatus.DRAFT, Tier.TIER_A, "cl"));
    }

    private String patchUrl(Agent agent, AgentVersion version) {
        return url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/manifest/patches");
    }

    private ResponseEntity<ManifestPatchResponse> patch(Agent agent, AgentVersion version, UUID orgId, ManifestPatchRequest body) {
        return rest.postForEntity(patchUrl(agent, version), new HttpEntity<>(body, authHeaders(orgId)), ManifestPatchResponse.class);
    }

    private ResponseEntity<String> patchRaw(Agent agent, AgentVersion version, UUID orgId, ManifestPatchRequest body) {
        return rest.postForEntity(patchUrl(agent, version), new HttpEntity<>(body, authHeaders(orgId)), String.class);
    }

    @Test
    void addReadScopeAppendsANewReadScopeAndBumpsTheRevision() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patch(agent, version, org.getId(),
                new ManifestPatchRequest("add_read_scope", "vendor_master", "Read vendor master", null, null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().manifestRevision()).isEqualTo(2);
        assertThat(response.getBody().diff().addedScopes()).contains("read:vendor_master");
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).scopes()).anyMatch(s -> s.resource().equals("vendor_master"));
    }

    @Test
    void addingTheSameReadScopeTwiceConflicts() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_read_scope", "bank_statement", "dup", null, null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addResourceAppendsATypedResourceDeclaration() throws Exception {
        // valid-data-agent.json is used here (not the shared valid-runtime-agent.json fixture) because
        // ResourceManifestRules requires every declared scope to have a matching resources[] entry once the
        // manifest declares any resources at all; valid-runtime-agent.json's scopes are untyped and would fail
        // that pre-existing semantic check as soon as this patch adds a resources[] array.
        Org org = orgRepository.save(new Org(null, "DevCo3", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0",
                manifest("valid-data-agent.json"), null, VersionStatus.DRAFT, Tier.TIER_A, "cl"));

        var response = patch(agent, version, org.getId(),
                new ManifestPatchRequest("add_resource", null, null, null, "vendor_master", "csv", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).resource("vendor_master")).isPresent();
    }

    @Test
    void addAllowlistHostIsIdempotent() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo4", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");
        var body = new ManifestPatchRequest("add_allowlist_host", null, null, null, null, null, null, "api.vendor.com", null);

        patch(agent, version, org.getId(), body);
        var second = patch(agent, version, org.getId(), body);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).networkAllowlist()).containsExactly("api.vendor.com");
    }

    @Test
    void removeScopeDropsAnExistingScope() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo5", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patch(agent, version, org.getId(),
                new ManifestPatchRequest("remove_scope", "invoices", null, null, null, null, null, null, "READ"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var saved = agentVersionRepository.findById(version.getId()).orElseThrow();
        assertThat(manifestParser.parse(saved.getManifestJson()).scopes()).noneMatch(s -> s.resource().equals("invoices"));
    }

    @Test
    void removingAScopeThatIsNotDeclaredIs404() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo6", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("remove_scope", "nope", null, null, null, null, null, null, "READ"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aLiveVersionCannotBePatched() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo7", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0",
                manifest("valid-runtime-agent.json"), "img", VersionStatus.LIVE, Tier.TIER_A, "cl"));

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_allowlist_host", null, null, null, null, null, null, "x.com", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void diffComparesAgainstTheCurrentLiveVersionNotThePriorDraftState() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo8", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion live = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0",
                manifest("valid-tier-a.json"), "img", VersionStatus.LIVE, Tier.TIER_A, "cl"));
        live.setApprovedAt(Instant.now());
        agentVersionRepository.save(live);
        AgentVersion draft = draftVersion(agent, "1.1.0");

        var response = patch(agent, draft, org.getId(),
                new ManifestPatchRequest("add_read_scope", "vendor_master", "d", null, null, null, null, null, null));

        assertThat(response.getBody().diff().addedScopes())
                .containsExactlyInAnyOrder("read:invoices", "write:journal_entries", "read:vendor_master");
        assertThat(response.getBody().diff().removedScopes()).isEmpty();
    }

    @Test
    void anInvalidPatchResultIsRejected() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo9", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_resource", null, null, null, "bad", "not-a-format", null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingRequiredFieldIsRejectedNotAServerError() throws Exception {
        Org org = orgRepository.save(new Org(null, "DevCo10", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, org, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, org.getId(),
                new ManifestPatchRequest("add_read_scope", null, null, null, null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNonOwningDeveloperCannotPatch() throws Exception {
        Org owner = orgRepository.save(new Org(null, "Owner", OrgType.DEVELOPER));
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, owner, "Agent", "x", "y"));
        AgentVersion version = draftVersion(agent, "1.0.0");

        var response = patchRaw(agent, version, intruder.getId(),
                new ManifestPatchRequest("add_allowlist_host", null, null, null, null, null, null, "x.com", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
