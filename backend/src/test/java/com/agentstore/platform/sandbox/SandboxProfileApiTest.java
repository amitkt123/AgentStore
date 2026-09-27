package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.DataFixtures;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxProfileApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired SandboxProfileService service;
    @Autowired SandboxProfileRepository profiles;
    @Autowired DatasetBindingRepository bindings;
    @Autowired DataFixtures dataFx;

    String base(SandboxFixtures.Dev dev) {
        return "http://localhost:" + port + "/api/v1/agents/" + dev.agent().getId() + "/sandbox-profiles";
    }

    ResponseEntity<Map> call(HttpMethod method, String url, Object body, Org org, UserRole role) {
        return rest.exchange(url, method, new HttpEntity<>(body, fx.bearer(org, role)), Map.class);
    }

    static Map<String, Object> body(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("input", Map.of("month", "2026-01"));
        body.put("gatePolicy", Map.of("default", "AUTO_APPROVE", "actions", Map.of("post_journal_entries", "MANUAL")));
        body.put("spendCap", 40);
        body.put("fakeSecrets", Map.of("API_KEY", "s3cret-value"));
        return body;
    }

    @Test
    void aProfileIsCreatedAndItsSecretsAreNeverEchoed() {
        var dev = fx.developer("valid-tier-a.json");

        var created = call(HttpMethod.POST, base(dev), body("happy"), dev.org(), UserRole.DEVELOPER);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("name")).isEqualTo("happy");
        assertThat(created.getBody().get("fakeSecretNames")).isEqualTo(List.of("API_KEY"));
        assertThat(created.getBody().toString()).doesNotContain("s3cret-value");
        var fetched = call(HttpMethod.GET, base(dev) + "/" + created.getBody().get("id"), null, dev.org(), UserRole.DEVELOPER);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().toString()).doesNotContain("s3cret-value");
        assertThat(((Map<?, ?>) fetched.getBody().get("gatePolicy")).get("default")).isEqualTo("AUTO_APPROVE");
    }

    @Test
    void aDuplicateNameIs409() {
        var dev = fx.developer("valid-tier-a.json");
        call(HttpMethod.POST, base(dev), body("same"), dev.org(), UserRole.DEVELOPER);

        var again = call(HttpMethod.POST, base(dev), body("same"), dev.org(), UserRole.DEVELOPER);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("PROFILE_NAME_TAKEN");
    }

    @Test
    void aReplaySourceThatDoesNotExistIs403NotAMisleadingNameConflict() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> withRandomSource = body("r1");
        withRandomSource.put("llmMode", "REPLAY");
        withRandomSource.put("replayRunId", UUID.randomUUID());

        var response = call(HttpMethod.POST, base(dev), withRandomSource, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_SOURCE_INVALID");
        assertThat(profiles.findByAgentIdAndInlineFalseOrderByNameAsc(dev.agent().getId())).isEmpty();
    }

    @Test
    void aReplaySourceOwnedByAnotherOrgIs403() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        var foreignRun = fx.sandboxRun(other, fx.profile(other, "p"), com.agentstore.platform.execution.RunStatus.RUNNING);
        Map<String, Object> withForeignSource = body("r2");
        withForeignSource.put("llmMode", "REPLAY");
        withForeignSource.put("replayRunId", foreignRun.getId());

        var response = call(HttpMethod.POST, base(dev), withForeignSource, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_SOURCE_INVALID");
    }

    @Test
    void aReplaySourceOfTheSameOrgWithNoRecordingIs422() {
        var dev = fx.developer("valid-tier-a.json");
        var ownRun = fx.sandboxRun(dev, fx.profile(dev, "source"), com.agentstore.platform.execution.RunStatus.RUNNING);
        Map<String, Object> withBareSource = body("r3");
        withBareSource.put("llmMode", "REPLAY");
        withBareSource.put("replayRunId", ownRun.getId());

        var response = call(HttpMethod.POST, base(dev), withBareSource, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("REPLAY_SOURCE_INVALID");
    }

    @Test
    void invalidProfilesAre400() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> replayWithoutSource = body("r");
        replayWithoutSource.put("llmMode", "REPLAY");
        Map<String, Object> badSecret = body("b");
        badSecret.put("fakeSecrets", Map.of("bad name", "x"));
        Map<String, Object> negativeCap = body("n");
        negativeCap.put("spendCap", -1);
        Map<String, Object> badPolicy = body("p");
        badPolicy.put("gatePolicy", Map.of("default", "MANUAL", "actions", Map.of("x", "NOPE")));

        for (Map<String, Object> invalid : List.of(replayWithoutSource, badSecret, negativeCap, badPolicy)) {
            var response = call(HttpMethod.POST, base(dev), invalid, dev.org(), UserRole.DEVELOPER);
            assertThat(response.getStatusCode()).as(invalid.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(profiles.findByAgentIdAndInlineFalseOrderByNameAsc(dev.agent().getId())).isEmpty();
    }

    @Test
    void listingExcludesInlineProfiles() {
        var dev = fx.developer("valid-tier-a.json");
        call(HttpMethod.POST, base(dev), body("saved"), dev.org(), UserRole.DEVELOPER);
        service.createInline(dev.org().getId(), dev.agent().getId(),
                new ProfileInput(null, null, null, null, null, null, null, null, null));

        var list = rest.exchange(base(dev), HttpMethod.GET, new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), List.class);

        assertThat(list.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) list.getBody().get(0)).get("name")).isEqualTo("saved");
        assertThat(profiles.findAll()).anyMatch(p -> p.isInline() && p.getName().startsWith("inline-"));
    }

    @Test
    void anotherDeveloperGets404AndABuyerGets403() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("mine"), dev.org(), UserRole.DEVELOPER);
        String url = base(dev) + "/" + created.getBody().get("id");

        assertThat(call(HttpMethod.GET, url, null, other.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, url, body("mine"), other.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.DELETE, url, null, other.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, base(dev), body("x"), other.org(), UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, url, null, dev.org(), UserRole.BUYER_ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateKeepsSecretsWhenOmittedAndClearsThemWhenEmpty() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("u"), dev.org(), UserRole.DEVELOPER);
        String url = base(dev) + "/" + created.getBody().get("id");
        Map<String, Object> withoutSecrets = body("u");
        withoutSecrets.remove("fakeSecrets");
        withoutSecrets.put("spendCap", 55);

        var kept = call(HttpMethod.PUT, url, withoutSecrets, dev.org(), UserRole.DEVELOPER);
        assertThat(kept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kept.getBody().get("fakeSecretNames")).isEqualTo(List.of("API_KEY"));
        assertThat(new java.math.BigDecimal(String.valueOf(kept.getBody().get("spendCap")))).isEqualByComparingTo("55");

        Map<String, Object> cleared = body("u");
        cleared.put("fakeSecrets", Map.of());
        var gone = call(HttpMethod.PUT, url, cleared, dev.org(), UserRole.DEVELOPER);
        assertThat(gone.getBody().get("fakeSecretNames")).isEqualTo(List.of());
    }

    @Test
    void deletingAProfileRemovesItsBindings() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("d"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var dataset = dataFx.upload(dev.org(), "a.csv", DataFixtures.CSV);
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", dataset.getId(), "{}", null));

        var deleted = call(HttpMethod.DELETE, base(dev) + "/" + profileId, null, dev.org(), UserRole.DEVELOPER);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(profiles.findById(profileId)).isEmpty();
        assertThat(bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profileId)).isEmpty();
    }
}
