package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMockTest extends AbstractLlmIntegrationTest {

    @Autowired LlmRequestNormalizer normalizer;

    String hashOf(String text) {
        return normalizer.normalize(request(LlmFixtures.CLAUDE, text, 100)).hash();
    }

    static String textOf(org.springframework.http.ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) ((List<?>) response.getBody().get("content")).get(0)).get("text");
    }

    LlmFixtures.LlmAgent mockAgent(String manifest, String mockJson) {
        var agent = fx.runningAgent(manifest);
        fx.configureRun(agent.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, mockJson);
        return agent;
    }

    @Test
    void anExactHashHitIsServedFreeAndTraced() throws Exception {
        String json = """
                {"responses":{"%s":{"content":[{"type":"text","text":"canned!"}],"usage":{"inputTokens":7,"outputTokens":3}}},
                 "default":{"content":[{"type":"text","text":"default!"}]}}""".formatted(hashOf("hi"));
        var agent = mockAgent("valid-llm-platform.json", json);

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textOf(response)).isEqualTo("canned!");
        assertThat(response.getBody().get("usage")).isEqualTo(Map.of("inputTokens", 7, "outputTokens", 3));
        assertThat(STUB.received()).as("a mock run never reaches a provider").isEmpty();
        var call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getSource()).isEqualTo(LlmSource.MOCK);
        assertThat(call.getStatus()).isEqualTo(LlmCallStatus.OK);
        assertThat(call.getInputTokens()).isEqualTo(7);
        assertThat(call.getOutputTokens()).isEqualTo(3);
        assertThat(call.getCost()).isEqualByComparingTo("0");
        assertThat(call.getRequestKey()).as("mock calls are never recorded").isNull();
        assertThat(fx.tokens(agent.runId())).as("never reserves or uses budget").containsExactly(0, 0);
        var step = llmSteps(agent.runId()).get(0);
        assertThat(step.get("source").asText()).isEqualTo("MOCK");
        assertThat(step.get("status").asText()).isEqualTo("OK");
    }

    @Test
    void otherRequestsGetTheDefaultAndThenTheGenericStub() throws Exception {
        String withDefault = "{\"default\":{\"content\":[{\"type\":\"text\",\"text\":\"default!\"}]}}";
        var a = mockAgent("valid-llm-platform.json", withDefault);
        var b = mockAgent("valid-llm-platform.json", null);
        var c = mockAgent("valid-llm-platform.json", "{\"responses\":{}}");

        assertThat(textOf(chat(a.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("default!");
        assertThat(textOf(chat(b.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("[mock response]");
        assertThat(textOf(chat(c.token(), request(LlmFixtures.CLAUDE, "anything", 100)))).isEqualTo("[mock response]");
    }

    @Test
    void aMockCallIgnoresTheTokenBudgetButNotTheModelAllowlistOrRequestValidation() throws Exception {
        var agent = mockAgent("valid-llm-small-budget.json", null);   // 1,000 tokens per run

        var big = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 20_000));
        var wrongModel = chat(agent.token(), request(LlmFixtures.GPT, "hi", 100));
        var invalid = chat(agent.token(), Map.of("model", "test-claude",
                "messages", List.of(Map.of("role", "user", "content", "hi"))));

        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wrongModel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrongModel.getBody().get("code")).isEqualTo("MODEL_NOT_ALLOWED");
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("code")).isEqualTo("INVALID_REQUEST");
        assertThat(fx.tokens(agent.runId())).containsExactly(0, 0);
        assertThat(llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId())).hasSize(1);
    }

    @Test
    void aMockRunNeedsNoKeyEvenForByokProxy() throws Exception {
        var agent = mockAgent("valid-llm-byok-proxy.json", null);   // no developer key stored

        var response = chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textOf(response)).isEqualTo("[mock response]");
    }
}
