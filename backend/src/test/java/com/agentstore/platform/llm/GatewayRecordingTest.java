package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRecordingTest extends AbstractLlmIntegrationTest {

    @Test
    void aSandboxCallIsRecordedForFourteenDays() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, null, null, null);
        STUB.enqueue(Reply.anthropicText("recorded answer", 12, 8));

        chat(agent.token(), request(LlmFixtures.CLAUDE, "recorded question", 100));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        String base = "llm/" + agent.runId() + "/" + call.getId() + "/";
        assertThat(call.getRequestKey()).isEqualTo(base + "request.json");
        assertThat(call.getResponseKey()).isEqualTo(base + "response.json");
        assertThat(call.getRecordingExpiresAt()).isBetween(Instant.now().plus(Duration.ofDays(14)).minusSeconds(120),
                Instant.now().plus(Duration.ofDays(14)).plusSeconds(120));
        assertThat(call.getRecordingPurgedAt()).isNull();

        String requestBlob = new String(storage.open(call.getRequestKey()).readAllBytes(), StandardCharsets.UTF_8);
        String responseBlob = new String(storage.open(call.getResponseKey()).readAllBytes(), StandardCharsets.UTF_8);
        JsonNode sent = json.readTree(requestBlob);
        assertThat(sent.get("model").asText()).isEqualTo("test-claude");
        assertThat(sent.at("/messages/0/content/0/text").asText()).isEqualTo("recorded question");
        JsonNode answered = json.readTree(responseBlob);
        assertThat(answered.at("/content/0/text").asText()).isEqualTo("recorded answer");
        assertThat(answered.at("/usage/inputTokens").asInt()).isEqualTo(12);
        assertThat(requestBlob + responseBlob).as("a key never lands in a recording").doesNotContain(PLATFORM_ANTHROPIC_KEY);
    }

    @Test
    void productionTrialAndReviewCallsNeverStorePrompts() throws Exception {
        for (RunMode mode : new RunMode[]{RunMode.PRODUCTION, RunMode.TRIAL, RunMode.REVIEW}) {
            var agent = fx.runningAgent("valid-llm-platform.json");
            fx.configureRun(agent.runId(), mode, null, null, null);
            STUB.enqueue(Reply.anthropicText("secret answer", 1, 1));

            chat(agent.token(), request(LlmFixtures.CLAUDE, "secret question", 100));

            LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
            assertThat(call.getRequestKey()).as(mode.name()).isNull();
            assertThat(call.getResponseKey()).isNull();
            assertThat(call.getRecordingExpiresAt()).isNull();
            assertThat(storage.size("llm/" + agent.runId() + "/" + call.getId() + "/request.json")).isEmpty();
            assertThat(storage.size("llm/" + agent.runId() + "/" + call.getId() + "/response.json")).isEmpty();
        }
    }

    @Test
    void mockCallsAreNeverRecorded() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, RunLlmMode.MOCK, null, null);

        chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getSource()).isEqualTo(LlmSource.MOCK);
        assertThat(call.getRequestKey()).isNull();
        assertThat(call.getRecordingExpiresAt()).isNull();
    }

    @Test
    void aFailedSandboxCallLeavesNoRecording() throws Exception {
        var agent = fx.runningAgent("valid-llm-platform.json");
        fx.configureRun(agent.runId(), RunMode.SANDBOX, null, null, null);
        STUB.always(Reply.status(500, "{}"));

        chat(agent.token(), request(LlmFixtures.CLAUDE, "hi", 100));

        LlmCall call = llmCalls.findByRunIdOrderByCreatedAtAsc(agent.runId()).get(0);
        assertThat(call.getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(call.getRequestKey()).isNull();
    }
}
