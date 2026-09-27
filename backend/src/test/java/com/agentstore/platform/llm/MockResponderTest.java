package com.agentstore.platform.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockResponderTest {

    static final String HASH = "a".repeat(64);
    static final LlmRequest REQUEST = new LlmRequest("m", null,
            List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hi")))), List.of(), 100, null);

    final MockResponder responder = new MockResponder(new ObjectMapper());

    static String text(LlmResponse response) {
        return (String) response.content().get(0).get("text");
    }

    @Test
    void anExactHashWinsOverTheDefault() {
        String json = """
                {"responses":{"%s":{"content":[{"type":"text","text":"exact"}],"stopReason":"max_tokens",
                   "usage":{"inputTokens":7,"outputTokens":3}}},
                 "default":{"content":[{"type":"text","text":"default"}]}}""".formatted(HASH);

        LlmResponse response = responder.respond(json, REQUEST, HASH);

        assertThat(text(response)).isEqualTo("exact");
        assertThat(response.stopReason()).isEqualTo("max_tokens");
        assertThat(response.inputTokens()).isEqualTo(7);
        assertThat(response.outputTokens()).isEqualTo(3);
        assertThat(response.model()).isEqualTo("m");
    }

    @Test
    void theDefaultAnswersEverythingElse() {
        String json = """
                {"responses":{"%s":{"content":[{"type":"text","text":"exact"}]}},
                 "default":{"content":[{"type":"text","text":"default"}],"usage":{"inputTokens":1,"outputTokens":2}}}"""
                .formatted("b".repeat(64));

        LlmResponse response = responder.respond(json, REQUEST, HASH);

        assertThat(text(response)).isEqualTo("default");
        assertThat(response.inputTokens()).isEqualTo(1);
        assertThat(response.outputTokens()).isEqualTo(2);
    }

    @Test
    void aMockRunNeverFailsForWantOfAnEntry() {
        for (String json : new String[]{null, "", "  ", "{}", "not json", "[]", "{\"responses\":{}}",
                "{\"responses\":\"nope\",\"default\":7}"}) {
            LlmResponse response = responder.respond(json, REQUEST, HASH);

            assertThat(text(response)).as(String.valueOf(json)).isEqualTo("[mock response]");
            assertThat(response.stopReason()).isEqualTo("end_turn");
            assertThat(response.inputTokens()).isZero();
            assertThat(response.model()).isEqualTo("m");
        }
    }

    @Test
    void aMalformedEntryIsTreatedAsAbsent() {
        String json = """
                {"responses":{"%s":{"content":"not an array"}},
                 "default":{"content":[{"type":"text","text":"default"}]}}""".formatted(HASH);

        assertThat(text(responder.respond(json, REQUEST, HASH))).isEqualTo("default");
        assertThat(text(responder.respond("{\"default\":{\"content\":[{\"type\":\"image\"}]}}", REQUEST, HASH)))
                .isEqualTo("[mock response]");
    }

    @Test
    void aCannedToolUseBlockIsServed() {
        String json = """
                {"default":{"content":[{"type":"tool_use","id":"t1","name":"lookup","input":{"q":"x"}}],
                            "stopReason":"tool_use"}}""";

        LlmResponse response = responder.respond(json, REQUEST, HASH);

        assertThat(response.content().get(0)).containsEntry("type", "tool_use").containsEntry("name", "lookup");
        assertThat(response.stopReason()).isEqualTo("tool_use");
    }
}
