package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayByokProxyTest extends AbstractLlmIntegrationTest {

    @Autowired ByokKeyService keys;

    @Test
    void theProviderSeesTheDevelopersKeyAndTheCallCostsNothing() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        keys.put(agent.developerOrg().getId(), LlmVendor.ANTHROPIC, "sk-dev-own-key-0001");
        STUB.enqueue(Reply.anthropicText("hello", 100, 40));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 500));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(0).header("x-api-key")).isEqualTo("sk-dev-own-key-0001")
                .isNotEqualTo(PLATFORM_ANTHROPIC_KEY);
        var call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getSource()).isEqualTo(LlmSource.BYOK_PROXY);
        assertThat(call.getInputTokens()).isEqualTo(100);
        assertThat(call.getOutputTokens()).isEqualTo(40);
        assertThat(call.getCost()).as("the developer's provider bill, not ours").isEqualByComparingTo("0");
        assertThat(fx.tokens(agent.runId())).containsExactly(140, 0);
        assertThat(llmSteps(agent.runId()).get(0).get("source").asText()).isEqualTo("BYOK_PROXY");
    }

    @Test
    void withoutAStoredKeyTheCallIsABadRequestAndNeverFallsBackToThePlatformKey() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_KEY_MISSING");
        assertThat(STUB.received()).isEmpty();
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).isEmpty();
        assertThat(llmSteps(agent.runId())).isEmpty();
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
    }

    @Test
    void deletingTheKeyStopsLaterCallsAndReplacingItChangesWhatTheProviderSees() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(agent.developerOrg());
        var orgId = agent.developerOrg().getId();
        keys.put(orgId, LlmVendor.ANTHROPIC, "sk-dev-first-key-0001");
        STUB.always(Reply.anthropicText("ok", 1, 1));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);

        keys.put(orgId, LlmVendor.ANTHROPIC, "sk-dev-second-key-0002");
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received().get(1).header("x-api-key")).isEqualTo("sk-dev-second-key-0002");

        keys.delete(orgId, LlmVendor.ANTHROPIC);
        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_KEY_MISSING");
    }
}
