package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.FakeSandboxProvider;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.LlmSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxRunApiTest extends AbstractStorageIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;
    @Autowired RunRepository runs;
    @Autowired FakeSandboxProvider sandbox;
    @Autowired LlmCallRepository llmCalls;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String path, Object body, Org org, UserRole role) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, fx.bearer(org, role)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<List> listCall(String path, Org org, UserRole role) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(null, fx.bearer(org, role)), List.class);
    }

    @Test
    void postingWithAProfileIdStartsARunWithADisplayId() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "smoke");

        var response = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "profileId", profile.getId()), dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("displayId").toString()).matches("S-\\d+");
        assertThat(response.getBody().get("versionLabel")).isEqualTo("1.0.0 draft");
        assertThat(response.getBody().get("profileName")).isEqualTo("smoke");
        assertThat(response.getBody().get("status")).isEqualTo("RUNNING");
    }

    @Test
    void postingWithAnInlineProfileWorks() {
        var dev = fx.developer("valid-tier-a.json");

        var response = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "inlineProfile", Map.of()), dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void neitherOrBothProfileFieldsIs400() {
        var dev = fx.developer("valid-tier-a.json");

        assertThat(call(HttpMethod.POST, "/api/v1/sandbox/runs", Map.of("agentVersionId", dev.version().getId()), dev.org(),
                UserRole.DEVELOPER).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        SandboxProfile profile = fx.profile(dev, "p");
        assertThat(call(HttpMethod.POST, "/api/v1/sandbox/runs", Map.of("agentVersionId", dev.version().getId(),
                "profileId", profile.getId(), "inlineProfile", Map.of()), dev.org(), UserRole.DEVELOPER).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listAndDetailAreOwnerCheckedAndBuyersAreForbiddenAndNeverInBuyerLists() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "profileId", fx.profile(dev, "p").getId()), dev.org(), UserRole.DEVELOPER);
        String runId = (String) created.getBody().get("id");

        var mine = rest.exchange(url("/api/v1/sandbox/runs?agentId=" + dev.agent().getId()), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.DEVELOPER)), List.class);
        assertThat(mine.getBody()).hasSize(1);
        var theirs = rest.exchange(url("/api/v1/sandbox/runs"), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(other.org(), UserRole.DEVELOPER)), List.class);
        assertThat(theirs.getBody()).isEmpty();
        assertThat(call(HttpMethod.GET, "/api/v1/sandbox/runs/" + runId, null, other.org(), UserRole.DEVELOPER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/sandbox/runs/" + runId + "/cancel", null, other.org(), UserRole.DEVELOPER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/sandbox/runs/" + runId, null, dev.org(), UserRole.BUYER_ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var buyerMine = rest.exchange(url("/api/v1/runs/mine"), HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(dev.org(), UserRole.BUYER_ADMIN)), List.class);
        assertThat(buyerMine.getBody()).isEmpty();
    }

    @Test
    void detailShowsStepsCostAndAiCost() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "profileId", fx.profile(dev, "p").getId()), dev.org(), UserRole.DEVELOPER);
        UUID runId = UUID.fromString((String) created.getBody().get("id"));
        jdbc.update("INSERT INTO llm_model (id, provider, display_name, provider_retains_data, input_price_per_mtok, "
                + "output_price_per_mtok, currency, enabled) VALUES ('test-claude','ANTHROPIC','test-claude',FALSE,3,15,'USD',TRUE) "
                + "ON CONFLICT (id) DO NOTHING");
        llmCalls.save(cost(runId, "0.50"));
        llmCalls.save(cost(runId, "0.25"));

        var detail = call(HttpMethod.GET, "/api/v1/sandbox/runs/" + runId, null, dev.org(), UserRole.DEVELOPER);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(String.valueOf(detail.getBody().get("aiCost")))).isEqualByComparingTo("0.75");
        assertThat(new BigDecimal(String.valueOf(detail.getBody().get("costCap")))).isEqualByComparingTo("500.00");
        assertThat((List<?>) detail.getBody().get("steps")).isNotNull();
    }

    LlmCall cost(UUID runId, String amount) {
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, "test-claude", LlmSource.PLATFORM, LlmCallStatus.OK, "h-" + UUID.randomUUID());
        call.setCost(new BigDecimal(amount));
        return call;
    }

    @Test
    void simulatedAndRequestedChargesShowAfterUsageAndTheRunFailsWhenTheCapIsHit() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "capped");
        profile.setSpendCap(new BigDecimal("10.00"));
        profilesRepo.save(profile);
        var created = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "profileId", profile.getId()), dev.org(), UserRole.DEVELOPER);
        UUID runId = UUID.fromString((String) created.getBody().get("id"));
        String token = sandbox.specForRun(runId).envVars().get("AGENTSTORE_RUN_TOKEN");

        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        rest.exchange(url("/runtime/v1/usage"), HttpMethod.POST,
                new HttpEntity<>(Map.of("quantity", 8, "unit", "reconciled_transaction"), headers), Map.class);

        var detail = call(HttpMethod.GET, "/api/v1/sandbox/runs/" + runId, null, dev.org(), UserRole.DEVELOPER);
        assertThat(new BigDecimal(String.valueOf(detail.getBody().get("simulatedCharge")))).isEqualByComparingTo("10.00");
        assertThat(new BigDecimal(String.valueOf(detail.getBody().get("requestedCharge")))).isEqualByComparingTo("16.00");
        assertThat(detail.getBody().get("status")).isEqualTo("FAILED");
    }

    @Autowired SandboxProfileRepository profilesRepo;

    @Test
    void cancelKillsTheSandboxAndRecordsAStepAndIsIdempotentlyRefused() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "profileId", fx.profile(dev, "p").getId()), dev.org(), UserRole.DEVELOPER);
        String runId = (String) created.getBody().get("id");

        var cancelled = call(HttpMethod.POST, "/api/v1/sandbox/runs/" + runId + "/cancel", null, dev.org(), UserRole.DEVELOPER);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        var run = runs.findById(UUID.fromString(runId)).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(sandbox.wasKilled(run.getSandboxId())).isTrue();

        var again = call(HttpMethod.POST, "/api/v1/sandbox/runs/" + runId + "/cancel", null, dev.org(), UserRole.DEVELOPER);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fakeSecretValuesNeverAppearInAnyResponse() {
        var dev = fx.developer("valid-tier-a.json");
        var created = call(HttpMethod.POST, "/api/v1/sandbox/runs",
                Map.of("agentVersionId", dev.version().getId(), "inlineProfile",
                        Map.of("fakeSecrets", Map.of("K", "topsecret-value"))), dev.org(), UserRole.DEVELOPER);
        String runId = (String) created.getBody().get("id");

        var list = listCall("/api/v1/sandbox/runs?agentId=" + dev.agent().getId(), dev.org(), UserRole.DEVELOPER);
        var detail = call(HttpMethod.GET, "/api/v1/sandbox/runs/" + runId, null, dev.org(), UserRole.DEVELOPER);
        var profileList = listCall("/api/v1/agents/" + dev.agent().getId() + "/sandbox-profiles", dev.org(), UserRole.DEVELOPER);

        assertThat(created.getBody().toString()).doesNotContain("topsecret-value");
        assertThat(list.getBody().toString()).doesNotContain("topsecret-value");
        assertThat(detail.getBody().toString()).doesNotContain("topsecret-value");
        assertThat(profileList.getBody().toString()).doesNotContain("topsecret-value");
    }
}
