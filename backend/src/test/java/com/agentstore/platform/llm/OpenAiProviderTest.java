package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiProviderTest {

    static final StubProviderServer STUB = StubProviderServer.start();
    final ObjectMapper mapper = new ObjectMapper();

    @AfterAll
    static void stop() {
        STUB.stop();
    }

    @BeforeEach
    void reset() {
        STUB.reset();
    }

    OpenAiProvider provider() {
        return new OpenAiProvider(STUB.baseUrl(), ProviderHttp.create(Duration.ofSeconds(2), Duration.ofSeconds(5), mapper), mapper);
    }

    static LlmRequest simple() {
        return new LlmRequest("gpt-x", null,
                List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello")))),
                List.of(), 128, null);
    }

    @Test
    void translatesTheRequestToChatCompletions() throws Exception {
        STUB.enqueue(Reply.openAiText("ok", 1, 1));
        LlmRequest request = new LlmRequest("gpt-x", "be brief", List.of(
                new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello"))),
                new LlmRequest.Msg("assistant", List.of(
                        Map.of("type", "text", "text", "Let me check."),
                        Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of("q", "x")))),
                new LlmRequest.Msg("user", List.of(
                        Map.of("type", "tool_result", "toolUseId", "t1", "content", "42"),
                        Map.of("type", "text", "text", "thanks")))),
                List.of(new LlmRequest.Tool("lookup", "Looks things up", Map.of("type", "object"))), 128, 0.2);

        provider().chat(request, "sk-openai");

        var received = STUB.received().get(0);
        JsonNode sent = mapper.readTree(received.body());
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.header("authorization")).isEqualTo("Bearer sk-openai");
        assertThat(sent.get("model").asText()).isEqualTo("gpt-x");
        assertThat(sent.get("max_completion_tokens").asInt()).isEqualTo(128);
        assertThat(sent.get("store").asBoolean()).isFalse();
        assertThat(sent.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(sent.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(sent.at("/messages/0/content").asText()).isEqualTo("be brief");
        assertThat(sent.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/1/content").asText()).isEqualTo("hello");
        assertThat(sent.at("/messages/2/role").asText()).isEqualTo("assistant");
        assertThat(sent.at("/messages/2/content").asText()).isEqualTo("Let me check.");
        assertThat(sent.at("/messages/2/tool_calls/0/id").asText()).isEqualTo("t1");
        assertThat(sent.at("/messages/2/tool_calls/0/type").asText()).isEqualTo("function");
        assertThat(sent.at("/messages/2/tool_calls/0/function/name").asText()).isEqualTo("lookup");
        assertThat(mapper.readTree(sent.at("/messages/2/tool_calls/0/function/arguments").asText()).get("q").asText())
                .isEqualTo("x");
        assertThat(sent.at("/messages/3/role").asText()).isEqualTo("tool");
        assertThat(sent.at("/messages/3/tool_call_id").asText()).isEqualTo("t1");
        assertThat(sent.at("/messages/3/content").asText()).isEqualTo("42");
        assertThat(sent.at("/messages/4/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/4/content").asText()).isEqualTo("thanks");
        assertThat(sent.at("/tools/0/type").asText()).isEqualTo("function");
        assertThat(sent.at("/tools/0/function/name").asText()).isEqualTo("lookup");
        assertThat(sent.at("/tools/0/function/description").asText()).isEqualTo("Looks things up");
        assertThat(sent.at("/tools/0/function/parameters/type").asText()).isEqualTo("object");
    }

    @Test
    void anAssistantTurnWithOnlyToolCallsSendsNullContent() throws Exception {
        STUB.enqueue(Reply.openAiText("ok", 1, 1));
        LlmRequest request = new LlmRequest("gpt-x", null, List.of(
                new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello"))),
                new LlmRequest.Msg("assistant", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of())))),
                List.of(), 128, null);

        provider().chat(request, "k");

        JsonNode sent = mapper.readTree(STUB.received().get(0).body());
        assertThat(sent.at("/messages/1/content").isNull()).isTrue();
        assertThat(sent.at("/messages/1/tool_calls/0/function/arguments").asText()).isEqualTo("{}");
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void parsesTextAndToolCalls() throws Exception {
        STUB.enqueue(Reply.ok("""
                {"model":"gpt-x","choices":[{"index":0,"message":{"role":"assistant","content":"Working on it",
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\\"q\\":\\"y\\"}"}}]},
                  "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":30,"completion_tokens":12}}"""));

        LlmResponse response = provider().chat(simple(), "k");

        assertThat(response.model()).isEqualTo("gpt-x");
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).containsEntry("type", "text").containsEntry("text", "Working on it");
        assertThat(response.content().get(1)).containsEntry("type", "tool_use").containsEntry("id", "call_1")
                .containsEntry("name", "lookup").containsEntry("input", Map.of("q", "y"));
        assertThat(response.stopReason()).isEqualTo("tool_use");
        assertThat(response.inputTokens()).isEqualTo(30);
        assertThat(response.outputTokens()).isEqualTo(12);
    }

    @Test
    void mapsFinishReasons() throws Exception {
        for (var pair : Map.of("stop", "end_turn", "length", "max_tokens", "tool_calls", "tool_use",
                "content_filter", "other").entrySet()) {
            STUB.enqueue(Reply.ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\""
                    + pair.getKey() + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
            assertThat(provider().chat(simple(), "k").stopReason()).isEqualTo(pair.getValue());
        }
    }

    @Test
    void classifiesFailuresAndUnparseableReplies() {
        STUB.enqueue(Reply.status(429, "{}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED));

        STUB.enqueue(Reply.status(503, "{}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR));

        STUB.enqueue(Reply.status(401, "{\"error\":\"bad key sk-secret-1\"}"));
        assertThatThrownBy(() -> provider().chat(simple(), "sk-secret-1")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> {
                    assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR);
                    assertThat(e.getMessage()).doesNotContain("sk-secret-1");
                });

        STUB.enqueue(Reply.ok("{\"choices\":[]}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));

        STUB.enqueue(Reply.ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"c\",\"function\":{\"name\":\"n\",\"arguments\":\"not json\"}}]},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
    }
}
