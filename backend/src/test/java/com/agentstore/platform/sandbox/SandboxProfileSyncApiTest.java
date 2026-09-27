package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.DataFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxProfileSyncApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired DataFixtures dataFx;
    @Autowired SandboxProfileRepository profiles;
    @Autowired com.agentstore.platform.data.DatasetRepository datasets;
    @Autowired com.agentstore.platform.data.DatasetBindingRepository bindingRepository;

    String url(SandboxFixtures.Dev dev) {
        return "http://localhost:" + port + "/api/v1/agents/" + dev.agent().getId() + "/sandbox-profiles/sync";
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<List> sync(SandboxFixtures.Dev dev, List<Map<String, Object>> entries) {
        return rest.exchange(url(dev), HttpMethod.POST, new HttpEntity<>(entries, fx.bearer(dev.org(), com.agentstore.platform.identity.UserRole.DEVELOPER)), List.class);
    }

    /** For a request expected to be rejected (4xx): a rejection's body is an error object, not a result array. */
    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> syncExpectingRejection(SandboxFixtures.Dev dev, List<Map<String, Object>> entries) {
        return rest.exchange(url(dev), HttpMethod.POST, new HttpEntity<>(entries, fx.bearer(dev.org(), com.agentstore.platform.identity.UserRole.DEVELOPER)), Map.class);
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, Object> entry(String name, String hash) {
        return new java.util.LinkedHashMap<>(Map.of("name", name, "bindings", List.of(), "contentHash", hash));
    }

    @Test
    void aNewNameIsCreatedWithSourceRepo() {
        var dev = fx.developer("valid-tier-a.json");

        var response = sync(dev, List.of(entry("happy-path", hash("v1"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
        assertThat(result.get("action")).isEqualTo("created");
        SandboxProfile stored = profiles.findByAgentIdAndName(dev.agent().getId(), "happy-path").orElseThrow();
        assertThat(stored.getSource()).isEqualTo(ProfileSource.REPO);
        assertThat(stored.getRepoSourceHash()).isEqualTo(hash("v1"));
    }

    @Test
    void anUnchangedHashOnAnExistingRepoProfileIsANoOp() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));

        var response = sync(dev, List.of(entry("p", hash("v1"))));

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("skipped");
    }

    @Test
    void aChangedHashOnAnExistingRepoProfileIsUpdated() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));

        var response = sync(dev, List.of(entry("p", hash("v2"))));

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("updated");
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow().getRepoSourceHash()).isEqualTo(hash("v2"));
    }

    @Test
    void aPortalSourcedProfileIsOverwrittenAndReclaimedAsRepo() {
        var dev = fx.developer("valid-tier-a.json");
        profiles.save(new SandboxProfile(dev.agent().getId(), "portal-made"));

        var response = sync(dev, List.of(entry("portal-made", hash("v1"))));

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("updated");
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "portal-made").orElseThrow().getSource()).isEqualTo(ProfileSource.REPO);
    }

    @Test
    void anUnresolvableFixtureIsAWarningNotAFailureAndTheBindingIsSkipped() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> withBinding = entry("p", hash("v1"));
        withBinding.put("bindings", List.of(Map.of("resource", "bank_statement", "fixture", "does-not-exist")));

        var response = sync(dev, List.of(withBinding));

        Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
        assertThat(result.get("action")).isEqualTo("created");
        assertThat((List<String>) result.get("unresolvedFixtures")).containsExactly("does-not-exist");
    }

    @Test
    void aResolvableFixtureIsBound() {
        var dev = fx.developer("valid-tier-a.json");
        var fixture = dataFx.upload(dev.org(), "sample-invoices.csv", "name,amount\nA,1\n");
        // dataFx.upload defaults to BUYER_DATA; persist it as FIXTURE so the sync's lookup can find it.
        fixture.setPurpose(com.agentstore.platform.data.DatasetPurpose.FIXTURE);
        datasets.saveAndFlush(fixture);
        Map<String, Object> withBinding = entry("p", hash("v1"));
        withBinding.put("bindings", List.of(Map.of("resource", "bank_statement", "fixture", "sample-invoices.csv")));

        var response = sync(dev, List.of(withBinding));

        Map<String, Object> result = (Map<String, Object>) response.getBody().get(0);
        assertThat((List<String>) result.get("unresolvedFixtures")).isEmpty();
    }

    @Test
    void reSyncingAnUnchangedFileTwiceIsANoOpBothTimes() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("stable"))));

        var second = sync(dev, List.of(entry("p", hash("stable"))));
        var third = sync(dev, List.of(entry("p", hash("stable"))));

        assertThat(((Map<String, Object>) second.getBody().get(0)).get("action")).isEqualTo("skipped");
        assertThat(((Map<String, Object>) third.getBody().get(0)).get("action")).isEqualTo("skipped");
    }

    @Test
    void aProfileDeletedSinceTheLastSyncIsRecreatedNotSkipped() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));
        profiles.delete(profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow());

        var response = sync(dev, List.of(entry("p", hash("v1"))));   // same hash as before deletion

        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("created");
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "p")).isPresent();
    }

    @Test
    void anEntryWithNoBindingsKeyAtAllDoesNotCrash() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> withoutBindingsKey = new java.util.LinkedHashMap<>(Map.of("name", "p", "contentHash", hash("v1")));

        var response = sync(dev, List.of(withoutBindingsKey));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<String, Object>) response.getBody().get(0)).get("action")).isEqualTo("created");
    }

    @Test
    void aBlankNameIsRejectedWithA400NotAServerError() {
        var dev = fx.developer("valid-tier-a.json");

        var response = syncExpectingRejection(dev, List.of(entry("", hash("v1"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNonPositiveSpendCapIsRejectedWithA400() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> withBadCap = entry("p", hash("v1"));
        withBadCap.put("spendCap", -1);

        var response = syncExpectingRejection(dev, List.of(withBadCap));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void llmModeReplayIsRejectedBecauseSyncHasNoReplayRunId() {
        var dev = fx.developer("valid-tier-a.json");
        Map<String, Object> withReplay = entry("p", hash("v1"));
        withReplay.put("llmMode", "REPLAY");

        var response = syncExpectingRejection(dev, List.of(withReplay));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void removingABindingFromTheYamlDeletesItOnTheNextSync() {
        var dev = fx.developer("valid-tier-a.json");
        var fixture = dataFx.upload(dev.org(), "sample-invoices.csv", "name,amount\nA,1\n");
        fixture.setPurpose(com.agentstore.platform.data.DatasetPurpose.FIXTURE);
        datasets.saveAndFlush(fixture);
        Map<String, Object> withBinding = entry("p", hash("v1"));
        withBinding.put("bindings", List.of(Map.of("resource", "bank_statement", "fixture", "sample-invoices.csv")));
        sync(dev, List.of(withBinding));
        UUID profileId = profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow().getId();
        assertThat(bindingRepository.findByOwnerTypeAndOwnerId(com.agentstore.platform.data.BindingOwnerType.SANDBOX_PROFILE, profileId)).hasSize(1);

        sync(dev, List.of(entry("p", hash("v2"))));   // same name, no bindings this time

        assertThat(bindingRepository.findByOwnerTypeAndOwnerId(com.agentstore.platform.data.BindingOwnerType.SANDBOX_PROFILE, profileId)).isEmpty();
    }

    @Test
    void syncNeverOverwritesLlmMockOrReplayRunIdSinceItCannotExpressThem() {
        var dev = fx.developer("valid-tier-a.json");
        sync(dev, List.of(entry("p", hash("v1"))));
        SandboxProfile profile = profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow();
        profile.setLlmMockJson("{\"foo\":1}");
        profiles.saveAndFlush(profile);

        sync(dev, List.of(entry("p", hash("v2"))));   // content changed, so this profile is overwritten

        // JSONB round-trips through a canonical text form (Postgres may reformat whitespace), so compare loosely.
        assertThat(profiles.findByAgentIdAndName(dev.agent().getId(), "p").orElseThrow().getLlmMockJson())
                .isEqualToIgnoringWhitespace("{\"foo\":1}");
    }

}
