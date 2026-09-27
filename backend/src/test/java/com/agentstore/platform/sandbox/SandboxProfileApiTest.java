package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.DataFixtures;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetService;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    @Autowired DatasetService datasetService;
    @Autowired com.agentstore.platform.data.SandboxBindingService sandboxBindingService;

    /** A FIXTURE-purpose upload (unlike {@code dataFx.upload}, which is always BUYER_DATA) so the PII scan actually runs. */
    Dataset fixture(Org org, String filename, String csv) {
        return datasetService.upload(org.getId(), UUID.randomUUID(), filename,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), filename, null, DatasetPurpose.FIXTURE, null);
    }

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
                new ProfileInput(null, null, null, null, null, null, null, null, null, null, null, null));

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

    @Test
    void trialCanBeEnabledWithAttestationWhenBoundFilesAreClean() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t1"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var clean = fixture(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", clean.getId(), "{}", null));
        Map<String, Object> withTrial = body("t1");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("trialEnabled")).isEqualTo(true);
    }

    @Test
    void trialIsRejectedWithPiiFoundWhenABoundFileIsDirty() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t2"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var dirty = fixture(dev.org(), "dirty.csv", "name,email\nA,person@example.com\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", dirty.getId(), "{}", null));
        Map<String, Object> withTrial = body("t2");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("PII_FOUND");
    }

    @Test
    void trialIsRejectedWithA409WhenABoundFileWasNeverScanned() {
        // dataFx.upload always uploads BUYER_DATA (never scanned); binding it to a profile simulates a null scan.
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t3"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var unscanned = dataFx.upload(dev.org(), "u.csv", "name,amount\nA,1\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", unscanned.getId(), "{}", null));
        Map<String, Object> withTrial = body("t3");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("PII_FOUND");
    }

    @Test
    void trialWithoutAttestIs400() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t4"), dev.org(), UserRole.DEVELOPER);
        Map<String, Object> withTrial = body("t4");
        withTrial.put("trialEnabled", true);

        var response = call(HttpMethod.PUT, base(dev) + "/" + created.getBody().get("id"), withTrial, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("ATTESTATION_REQUIRED");
    }

    @Test
    void settingTrialFalseNeverRequiresAttestAndAlwaysSucceeds() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t5"), dev.org(), UserRole.DEVELOPER);
        Map<String, Object> withoutAttest = body("t5");
        withoutAttest.put("trialEnabled", false);

        var response = call(HttpMethod.PUT, base(dev) + "/" + created.getBody().get("id"), withoutAttest, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("trialEnabled")).isEqualTo(false);
    }

    @Test
    void omittingTrialEnabledOnAnUnrelatedEditLeavesAnExistingTrialUntouched() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t6"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var clean = fixture(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", clean.getId(), "{}", null));
        Map<String, Object> withTrial = body("t6");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);
        call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        Map<String, Object> unrelatedEdit = body("t6");
        unrelatedEdit.put("spendCap", 77);   // no "trialEnabled" key at all

        var response = call(HttpMethod.PUT, base(dev) + "/" + profileId, unrelatedEdit, dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("trialEnabled")).isEqualTo(true);
    }

    @Test
    void bindingChangeClearsAnExistingTrialAttestation() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t7"), dev.org(), UserRole.DEVELOPER);
        UUID profileId = UUID.fromString((String) created.getBody().get("id"));
        var clean = fixture(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profileId, "bank_statement", clean.getId(), "{}", null));
        Map<String, Object> withTrial = body("t7");
        withTrial.put("trialEnabled", true);
        withTrial.put("attest", true);
        call(HttpMethod.PUT, base(dev) + "/" + profileId, withTrial, dev.org(), UserRole.DEVELOPER);

        sandboxBindingService.unbind(dev.org().getId(), profileId, "bank_statement");

        var fetched = call(HttpMethod.GET, base(dev) + "/" + profileId, null, dev.org(), UserRole.DEVELOPER);
        assertThat(fetched.getBody().get("trialEnabled")).isEqualTo(false);
    }

    @Test
    void aPortalPutAlwaysReclaimsSourcePortal() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, base(dev), body("t8"), dev.org(), UserRole.DEVELOPER);

        var response = call(HttpMethod.PUT, base(dev) + "/" + created.getBody().get("id"), body("t8"), dev.org(), UserRole.DEVELOPER);

        assertThat(response.getBody().get("source")).isEqualTo("PORTAL");
    }
}
