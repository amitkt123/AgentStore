package com.agentstore.platform.llm;

import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.agentstore.platform.runtime.RuntimeFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContextLlmLimitsTest extends AbstractLlmIntegrationTest {

    @SuppressWarnings("rawtypes")
    Map<?, ?> limits(String token) {
        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/runtime/v1/context", HttpMethod.GET,
                new HttpEntity<>(RuntimeFixtures.bearer(token)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) response.getBody().get("limits");
    }

    @Test
    void aPlatformAgentSeesItsModelsAndRemainingBudgetShrinkAsItSpends() {
        var agent = fx.runningAgent("valid-llm-platform.json");

        Map<?, ?> before = (Map<?, ?>) limits(agent.token()).get("llm");
        assertThat(before.get("mode")).isEqualTo("platform");
        assertThat(new java.util.ArrayList<Object>((List<?>) before.get("models"))).containsExactly("test-claude", "test-gpt", "test-disabled");
        assertThat(before.get("tokensRemaining")).isEqualTo(100_000);

        STUB.enqueue(Reply.anthropicText("hello", 100, 40));
        assertThat(chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(((Map<?, ?>) limits(agent.token()).get("llm")).get("tokensRemaining")).isEqualTo(99_860);
    }

    @Test
    void inFlightReservationsAreAlreadyTakenOff() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 1000, 500);

        assertThat(((Map<?, ?>) limits(agent.token()).get("llm")).get("tokensRemaining")).isEqualTo(98_500);
    }

    @Test
    void theRemainingBudgetNeverGoesNegativeEvenAfterAnOvershoot() {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.setTokens(agent.runId(), 100_400, 0);   // actual usage may exceed the estimate by one call

        assertThat(((Map<?, ?>) limits(agent.token()).get("llm")).get("tokensRemaining")).isEqualTo(0);
    }

    @Test
    void aByokDirectAgentSeesItsModeAndTheFullBudget() {
        var agent = fx.runningAgent("valid-llm-byok-direct.json");

        Map<?, ?> llm = (Map<?, ?>) limits(agent.token()).get("llm");

        assertThat(llm.get("mode")).isEqualTo("byok_direct");
        assertThat(llm.get("tokensRemaining")).isEqualTo(100_000);
    }

    @Test
    void anAgentWithoutAnLlmBlockHasNoLlmLimits() {
        var agent = fx.runningAgent("valid-runtime-agent.json");

        Map<?, ?> limits = limits(agent.token());

        assertThat(limits.containsKey("llm")).isFalse();
        assertThat(limits.get("currency")).isEqualTo("INR");
    }
}
