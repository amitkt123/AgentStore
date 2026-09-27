package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** The provider timeout (504) is covered where the clock can be controlled: AnthropicProviderTest and ProviderClientTest. */
class GatewayErrorContractTest extends AbstractLlmIntegrationTest {

    @Test
    void aRateLimitedCallIsRetriedOnceAndThenSucceeds() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.status(429, "{}").withHeader("Retry-After", "0"), Reply.anthropicText("ok", 10, 5));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(STUB.received()).hasSize(2);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).hasSize(1);
        assertThat(fx.tokens(agent.runId())).containsExactly(15, 0);
    }

    @Test
    void rateLimitedTwiceIsServiceUnavailable() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.status(429, "{}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
        assertThat(STUB.received()).hasSize(2);
        assertFailedRow(agent.runId(), "LLM_PROVIDER_UNAVAILABLE");
    }

    @Test
    void aServerErrorTwiceIsBadGateway() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.status(500, "{\"error\":\"secret-provider-detail\"}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_ERROR");
        assertThat(response.getBody().get("status")).isEqualTo(502);
        assertThat(response.getBody().toString()).doesNotContain("secret-provider-detail");
        assertThat(STUB.received()).hasSize(2);
        assertFailedRow(agent.runId(), "LLM_PROVIDER_ERROR");
    }

    @Test
    void aProviderRejectingTheKeyIsBadGatewayAndIsNotRetried() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.status(401, "{\"error\":\"invalid api key\"}"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_ERROR");
        assertThat(STUB.received()).hasSize(1);
        assertFailedRow(agent.runId(), "LLM_PROVIDER_ERROR");
    }

    @Test
    void anUnparseableReplyIsBadGateway() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.always(Reply.ok("<html>not json</html>"));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_ERROR");
        assertFailedRow(agent.runId(), "LLM_PROVIDER_ERROR");
    }

    @Test
    void anOpenCircuitFailsFastWithoutCallingTheProviderAndReleasesTheReservation() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        providerClient.breaker(LlmVendor.ANTHROPIC).transitionToOpenState();

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
        assertThat(STUB.received()).isEmpty();
        assertFailedRow(agent.runId(), "LLM_PROVIDER_UNAVAILABLE");

        // the OpenAI breaker is separate
        STUB.enqueue(Reply.openAiText("ok", 1, 1));
        assertThat(chat(agent.token(), request(LlmFixtures.GPT, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void assertFailedRow(java.util.UUID runId, String errorCode) throws Exception {
        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(runId);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(calls.get(0).getErrorCode()).isEqualTo(errorCode);
        assertThat(fx.tokens(runId)).containsExactly(0, 0);
        var steps = llmSteps(runId);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("status").asText()).isEqualTo("ERROR");
    }
}
