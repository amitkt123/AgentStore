package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPlatformCallTest extends AbstractLlmIntegrationTest {

    @Test
    void aPlatformCallIsForwardedMeteredAndTraced() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.anthropicText("hello", 100, 40));

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 500));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = response.getBody();
        assertThat(body.get("model")).isEqualTo("test-claude");
        assertThat(body.get("stopReason")).isEqualTo("end_turn");
        assertThat(body.get("usage")).isEqualTo(Map.of("inputTokens", 100, "outputTokens", 40));
        assertThat(((List<?>) body.get("content")).get(0)).isEqualTo(Map.of("type", "text", "text", "hello"));

        assertThat(STUB.received()).hasSize(1);
        var received = STUB.received().get(0);
        assertThat(received.path()).isEqualTo("/v1/messages");
        assertThat(received.header("x-api-key")).isEqualTo(PLATFORM_ANTHROPIC_KEY);
        JsonNode sent = json.readTree(received.body());
        assertThat(sent.get("model").asText()).isEqualTo("test-claude");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(500);

        var calls = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId());
        assertThat(calls).hasSize(1);
        LlmCall call = calls.get(0);
        assertThat(call.getStatus()).isEqualTo(LlmCallStatus.OK);
        assertThat(call.getSource()).isEqualTo(LlmSource.PLATFORM);
        assertThat(call.getModelId()).isEqualTo("test-claude");
        assertThat(call.getInputTokens()).isEqualTo(100);
        assertThat(call.getOutputTokens()).isEqualTo(40);
        assertThat(call.getCost()).isEqualByComparingTo("0.0009");   // 100/1e6*3 + 40/1e6*15
        assertThat(call.getRequestHash()).hasSize(64);
        assertThat(call.getRequestKey()).as("production runs never store prompts").isNull();
        assertThat(call.getErrorCode()).isNull();
        assertThat(fx.tokens(agent.runId())).containsExactly(140, 0);

        var steps = llmSteps(agent.runId());
        assertThat(steps).hasSize(1);
        JsonNode step = steps.get(0);
        assertThat(step.get("block").asText()).isEqualTo("llm");
        assertThat(step.get("calls").asInt()).isEqualTo(1);
        assertThat(step.get("tokens").asInt()).isEqualTo(140);
        assertThat(step.get("inputTokens").asInt()).isEqualTo(100);
        assertThat(step.get("outputTokens").asInt()).isEqualTo(40);
        assertThat(step.get("model").asText()).isEqualTo("test-claude");
        assertThat(step.get("source").asText()).isEqualTo("PLATFORM");
        assertThat(step.get("status").asText()).isEqualTo("OK");
    }

    @Test
    void anOpenAiModelGoesToTheOpenAiAdapterWithThePlatformOpenAiKey() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.openAiText("hey", 10, 5));

        var response = chat(agent.token(), request(LlmFixtures.GPT, "hi", 200));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var received = STUB.received().get(0);
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.header("authorization")).isEqualTo("Bearer " + PLATFORM_OPENAI_KEY);
        assertThat(json.readTree(received.body()).get("store").asBoolean()).isFalse();
        var call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getCost()).isEqualByComparingTo("0.0001");   // 75 per million, rounded half up to 4 places
        assertThat(fx.tokens(agent.runId())).containsExactly(15, 0);
    }

    @Test
    void aModelOutsideTheManifestIsRejectedWithoutMetering() throws Exception {
        var agent = fx.runningAgent("valid-llm-small-budget.json");   // lists only test-claude

        var response = chat(agent.token(), request(LlmFixtures.GPT, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void aDisabledModelIsRejectedEvenWhenTheManifestListsIt() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");   // lists test-disabled

        var response = chat(agent.token(), request(LlmFixtures.DISABLED, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void aModelTheOperatorDisablesLaterStopsWorkingWithoutResubmitting() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        STUB.enqueue(Reply.anthropicText("ok", 1, 1));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100)).getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE llm_model SET enabled = FALSE WHERE id = ?", LlmFixtures.CLAUDE);

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
    }

    @Test
    void anAgentWithoutAnLlmBlockGets400() throws Exception {
        var agent = fx.runningAgent("valid-runtime-agent.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("LLM_NOT_DECLARED");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void aByokDirectAgentMustCallTheProviderItself() throws Exception {
        var agent = fx.runningAgent("valid-llm-byok-direct.json");

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("USE_DIRECT_PROVIDER");
        assertNothingWasMetered(agent.runId());
    }

    @Test
    void invalidRequestsWriteNoRowAndReserveNothing() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");

        var missingMaxTokens = chat(agent.token(), Map.of("model", "test-claude",
                "messages", List.of(Map.of("role", "user", "content", "hi"))));
        var unknownKey = chat(agent.token(), Map.of("model", "test-claude", "maxTokens", 100, "stream", true,
                "messages", List.of(Map.of("role", "user", "content", "hi"))));

        for (var response : List.of(missingMaxTokens, unknownKey)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("INVALID_REQUEST");
        }
        assertNothingWasMetered(agent.runId());
    }

    private void assertNothingWasMetered(java.util.UUID runId) throws Exception {
        assertThat(STUB.received()).isEmpty();
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(runId)).isEmpty();
        assertThat(llmSteps(runId)).isEmpty();
        assertThat(fx.tokens(runId)).containsExactly(0, 0);
    }
}
