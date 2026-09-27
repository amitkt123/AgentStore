package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.AbstractLlmIntegrationTest;
import com.agentstore.platform.llm.LlmCall;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.ByokKeyService;
import com.agentstore.platform.llm.LlmFixtures;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly sandbox LLM allowance (spec §5): enforced in {@code GatewayLlmBackend} for platform-key calls of a
 * sandbox run whose {@code llm_mode} is null (real). Model {@code test-claude} costs 15/Mtok output (from
 * {@link LlmFixtures#ensureModels}); {@code maxTokens=1000} estimates 0.0150.
 */
@TestPropertySource(properties = "agentstore.sandbox.quota.unverified.llm-spend=0.02")
class SandboxLlmQuotaTest extends AbstractLlmIntegrationTest {

    @Autowired ByokKeyService keys;

    /** Turns a RUNNING PRODUCTION run into a connection-less sandbox run of the given org, real LLM mode by default. */
    void makeSandbox(LlmFixtures.LlmAgent agent, RunLlmMode llmMode) {
        jdbc.update("UPDATE run SET connection_id = NULL, initiated_by_org_id = ?, mode = 'SANDBOX', llm_mode = ? WHERE id = ?",
                agent.developerOrg().getId(), llmMode == null ? null : llmMode.name(), agent.runId());
    }

    @Test
    void aSecondCallThatWouldCrossTheMonthlyAllowanceIsRejectedBeforeReachingTheProvider() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        makeSandbox(agent, null);
        STUB.enqueue(Reply.anthropicText("ok", 10, 5));

        var first = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));   // estimate 0.0015, well under the limit
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received()).hasSize(1);

        // Estimate 32,000 (the configured max) * 15/1e6 = 0.48: however small the first call's real cost, this alone
        // exceeds the 0.02 limit.
        var second = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 32_000));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(second.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        assertThat(STUB.received()).as("the rejected call never reached the provider").hasSize(1);
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).extracting(LlmCall::getStatus).containsExactly(LlmCallStatus.OK, LlmCallStatus.BUDGET_REJECTED);
        assertThat(calls.get(1).getErrorCode()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void aMockModeSandboxRunIsNeverRefusedHoweverSmallTheLimit() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        makeSandbox(agent, RunLlmMode.MOCK);

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received()).isEmpty();
    }

    @Test
    void aReplayModeSandboxRunIsNeverRefusedHoweverSmallTheLimit() throws Exception {
        var source = fx.runningAgent("valid-llm-platform.json");
        makeSandbox(source, null);
        STUB.enqueue(Reply.anthropicText("recorded", 10, 5));
        chat(source.token(), request(LlmFixtures.CLAUDE, "same", 100));

        var replay = fx.runningAgent("valid-llm-platform.json");
        makeSandbox(replay, RunLlmMode.REPLAY);
        jdbc.update("UPDATE run SET replay_run_id = ? WHERE id = ?", source.runId(), replay.runId());

        var response = chat(replay.token(), request(LlmFixtures.CLAUDE, "same", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aProductionRunIsNeverAffectedByTheSandboxQuota() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");   // stays PRODUCTION, with its connection
        STUB.enqueue(Reply.anthropicText("ok", 10, 5), Reply.anthropicText("ok2", 10, 5));

        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aByokProxySandboxRunIsNotCheckedBecauseItCostsNothing() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        keys.put(agent.developerOrg().getId(), LlmVendor.ANTHROPIC, "sk-dev-own-key-quota-0001");
        makeSandbox(agent, null);
        STUB.enqueue(Reply.anthropicText("ok", 10, 5), Reply.anthropicText("ok2", 10, 5));

        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aVerifiedOrgGetsTheLargerLimitAndTheSameCallPasses() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        makeSandbox(agent, null);
        STUB.enqueue(Reply.anthropicText("ok", 10, 5), Reply.anthropicText("ok2", 10, 5));

        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 1000)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
