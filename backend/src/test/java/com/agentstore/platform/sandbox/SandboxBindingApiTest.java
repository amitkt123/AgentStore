package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.data.DatasetService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxBindingApiTest extends AbstractStorageIntegrationTest {

    static final String GOOD_CSV = "date,narration,amount\n2026-01-05,Salary,50000.00\n";
    static final String BAD_CSV = "date,amount\n2026-01-05,10\n";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired SandboxProfileService profileService;
    @Autowired DatasetService datasetService;
    @Autowired DatasetBindingRepository bindings;
    @Autowired DatasetRepository datasets;

    Dataset fixture(SandboxFixtures.Dev dev, String csv) {
        return datasetService.upload(dev.org().getId(), UUID.randomUUID(), "f.csv", new ByteArrayInputStream(csv.getBytes()),
                "f", null, DatasetPurpose.FIXTURE, null);
    }

    String bindUrl(UUID profileId, String resource) {
        return "http://localhost:" + port + "/api/v1/sandbox-profiles/" + profileId + "/bindings/" + resource;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String url, Object body, SandboxFixtures.Dev dev) {
        return rest.exchange(url, method, new HttpEntity<>(body, fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);
    }

    @Test
    void bindingAMatchingFixtureSucceedsAndShowsUpOnTheProfile() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Dataset dataset = fixture(dev, GOOD_CSV);

        var response = call(HttpMethod.PUT, bindUrl(profile.getId(), "bank_statement"), Map.of("datasetId", dataset.getId()), dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var view = profileService.get(dev.org().getId(), dev.agent().getId(), profile.getId());
        assertThat(view.bindings()).extracting(ProfileView.BindingView::resource).containsExactly("bank_statement");
    }

    @Test
    void aMismatchedFixtureIs422() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Dataset dataset = fixture(dev, BAD_CSV);

        var response = call(HttpMethod.PUT, bindUrl(profile.getId(), "bank_statement"), Map.of("datasetId", dataset.getId()), dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("DATASET_CHECK_FAILED");
    }

    @Test
    void bindingToAWriteResourceIs422RESOURCE_NOT_BINDABLE() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Dataset dataset = fixture(dev, GOOD_CSV);

        var response = call(HttpMethod.PUT, bindUrl(profile.getId(), "journal_entries"), Map.of("datasetId", dataset.getId()), dev);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code")).isEqualTo("RESOURCE_NOT_BINDABLE");
    }

    @Test
    void aBuyerDataDatasetOrAnotherOrgsFixtureIs404() {
        var dev = fx.developer("valid-data-agent.json");
        var other = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Dataset buyerData = datasetService.upload(dev.org().getId(), UUID.randomUUID(), "b.csv",
                new ByteArrayInputStream(GOOD_CSV.getBytes()), "b", null, DatasetPurpose.BUYER_DATA, null);
        Dataset foreignFixture = fixture(other, GOOD_CSV);

        assertThat(call(HttpMethod.PUT, bindUrl(profile.getId(), "bank_statement"), Map.of("datasetId", buyerData.getId()), dev)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.PUT, bindUrl(profile.getId(), "bank_statement"), Map.of("datasetId", foreignFixture.getId()), dev)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherDevelopersProfileIs404() {
        var dev = fx.developer("valid-data-agent.json");
        var other = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Dataset dataset = fixture(other, GOOD_CSV);

        assertThat(call(HttpMethod.PUT, bindUrl(profile.getId(), "bank_statement"), Map.of("datasetId", dataset.getId()), other)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unbindRemovesTheBindingButNeverSchedulesTheFixtureForPurge() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Dataset dataset = fixture(dev, GOOD_CSV);
        call(HttpMethod.PUT, bindUrl(profile.getId(), "bank_statement"), Map.of("datasetId", dataset.getId()), dev);

        var response = rest.exchange(bindUrl(profile.getId(), "bank_statement"), HttpMethod.DELETE,
                new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profile.getId())).isEmpty();
        assertThat(datasets.findById(dataset.getId()).orElseThrow().getPurgeAfter()).isNull();   // the buyer purge clock never starts
    }
}
