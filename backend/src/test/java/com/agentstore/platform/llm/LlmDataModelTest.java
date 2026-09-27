package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmDataModelTest extends AbstractIntegrationTest {

    static final String HASH = "a".repeat(64);

    @Autowired LlmFixtures fx;
    @Autowired LlmModelRepository models;
    @Autowired LlmCallRepository calls;
    @Autowired ByokCredentialRepository credentials;
    @Autowired RunRepository runs;
    @Autowired OrgRepository orgs;

    @Test
    void theMigrationSeedsTheParentSpecModelsDisabledWithZeroPrices() {
        for (String id : List.of("claude-sonnet-5", "claude-haiku-4-5-20251001")) {
            LlmModel model = models.findById(id).orElseThrow();
            assertThat(model.getProvider()).isEqualTo(LlmVendor.ANTHROPIC);
            assertThat(model.isEnabled()).isFalse();
            assertThat(model.getInputPricePerMtok()).isEqualByComparingTo("0");
            assertThat(model.getOutputPricePerMtok()).isEqualByComparingTo("0");
        }
    }

    @Test
    void aProductionRunCannotCarryAnLlmModeButASandboxRunCan() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThatThrownBy(() -> fx.configureRun(agent.runId(), RunMode.PRODUCTION, RunLlmMode.MOCK, null, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);

        fx.configureRun(agent.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, "{\"default\":{}}");
        Run run = runs.findById(agent.runId()).orElseThrow();
        assertThat(run.getLlmMode()).isEqualTo(RunLlmMode.MOCK);
        assertThat(run.getLlmMockJson()).contains("default");
    }

    @Test
    void aFullEntitySaveNeverWritesTheTokenCounters() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 5, 7);
        Run stale = runs.findById(agent.runId()).orElseThrow();      // holds 5 and 7
        assertThat(stale.getLlmTokensUsed()).isEqualTo(5);
        assertThat(stale.getLlmTokensReserved()).isEqualTo(7);

        fx.setTokens(agent.runId(), 9, 3);                            // another transaction moves the counters
        stale.setViolationCount(1);
        runs.saveAndFlush(stale);

        assertThat(fx.tokens(agent.runId())).containsExactly(9, 3);
    }

    @Test
    void newRunsStartWithZeroCountersAndNoLlmMode() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
        Run run = runs.findById(agent.runId()).orElseThrow();
        assertThat(run.getLlmMode()).isNull();
        assertThat(run.getReplayRunId()).isNull();
        assertThat(run.getLlmMockJson()).isNull();
    }

    @Test
    void aReplayOrdinalIsUniquePerRunAndHashButNullOrdinalsAreNot() {
        fx.ensureModels();
        var agent = fx.runningAgent("valid-llm-platform.json");

        calls.saveAndFlush(call(agent.runId(), 1));
        assertThatThrownBy(() -> calls.saveAndFlush(call(agent.runId(), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        calls.saveAndFlush(call(agent.runId(), 2));
        calls.saveAndFlush(call(agent.runId(), null));
        calls.saveAndFlush(call(agent.runId(), null));

        assertThat(calls.findByRunIdOrderByCreatedAtAsc(agent.runId())).hasSize(4);
    }

    @Test
    void aCallRoundTripsItsColumns() {
        fx.ensureModels();
        var agent = fx.runningAgent("valid-llm-platform.json");
        LlmCall call = call(agent.runId(), null);
        call.setInputTokens(100);
        call.setOutputTokens(40);
        call.setCost(new java.math.BigDecimal("0.0009"));
        call.setLatencyMs(12);
        call.setErrorCode(null);
        calls.saveAndFlush(call);

        LlmCall loaded = calls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(loaded.getInputTokens()).isEqualTo(100);
        assertThat(loaded.getOutputTokens()).isEqualTo(40);
        assertThat(loaded.getCost()).isEqualByComparingTo("0.0009");
        assertThat(loaded.getSource()).isEqualTo(LlmSource.PLATFORM);
        assertThat(loaded.getStatus()).isEqualTo(LlmCallStatus.OK);
        assertThat(loaded.getRequestHash()).isEqualTo(HASH);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void aByokCredentialIsUniquePerOrgAndProvider() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));

        credentials.saveAndFlush(credential(org, LlmVendor.ANTHROPIC));
        credentials.saveAndFlush(credential(org, LlmVendor.OPENAI));
        assertThatThrownBy(() -> credentials.saveAndFlush(credential(org, LlmVendor.ANTHROPIC)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(credentials.findByDeveloperOrgIdOrderByProviderAsc(org.getId())).hasSize(2);
        assertThat(credentials.findByDeveloperOrgIdAndProvider(org.getId(), LlmVendor.OPENAI)).isPresent();
    }

    @Test
    void anOrgIsUnverifiedUntilSomethingSetsTheStamp() {
        Org org = orgs.save(new Org(null, "Dev " + UUID.randomUUID(), OrgType.DEVELOPER));
        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNull();

        fx.verifyDeveloperOrg(org);

        assertThat(orgs.findById(org.getId()).orElseThrow().getDeveloperVerifiedAt()).isNotNull();
    }

    private static LlmCall call(UUID runId, Integer ordinal) {
        LlmCall call = new LlmCall(UUID.randomUUID(), runId, LlmFixtures.CLAUDE, LlmSource.PLATFORM, LlmCallStatus.OK, HASH);
        call.setReplayOrdinal(ordinal);
        return call;
    }

    private static ByokCredential credential(Org org, LlmVendor vendor) {
        ByokCredential credential = new ByokCredential(org.getId(), vendor);
        credential.setEncryptedKey(new byte[]{1, 2, 3});
        credential.setNonce(new byte[]{4, 5, 6});
        credential.setLast4("1234");
        return credential;
    }
}
