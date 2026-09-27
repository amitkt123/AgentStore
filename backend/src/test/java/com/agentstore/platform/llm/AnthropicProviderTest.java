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

class AnthropicProviderTest {

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

    AnthropicProvider provider(String baseUrl, Duration readTimeout) {
        return new AnthropicProvider(baseUrl, ProviderHttp.create(Duration.ofSeconds(2), readTimeout, mapper), mapper);
    }

    AnthropicProvider provider() {
        return provider(STUB.baseUrl(), Duration.ofSeconds(5));
    }

    static LlmRequest simple() {
        return new LlmRequest("claude-x", null,
                List.of(new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello")))),
                List.of(), 256, null);
    }

    interface Call {
        void run() throws ProviderCallException;
    }

    void expect(Call call, Kind kind) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.kind()).isEqualTo(kind));
    }

    @Test
    void translatesTheRequestToTheMessagesApi() throws Exception {
        STUB.enqueue(Reply.anthropicText("ok", 1, 1));
        LlmRequest request = new LlmRequest("claude-x", "be brief", List.of(
                new LlmRequest.Msg("user", List.of(Map.of("type", "text", "text", "hello"))),
                new LlmRequest.Msg("assistant", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of("q", "x")))),
                new LlmRequest.Msg("user", List.of(
                        Map.of("type", "tool_result", "toolUseId", "t1", "content", "42")))),
                List.of(new LlmRequest.Tool("lookup", "Looks things up", Map.of("type", "object"))), 256, 0.5);

        provider().chat(request, "sk-test");

        var received = STUB.received().get(0);
        JsonNode sent = mapper.readTree(received.body());
        assertThat(received.path()).isEqualTo("/v1/messages");
        assertThat(received.header("x-api-key")).isEqualTo("sk-test");
        assertThat(received.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(sent.get("model").asText()).isEqualTo("claude-x");
        assertThat(sent.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(sent.get("system").asText()).isEqualTo("be brief");
        assertThat(sent.get("temperature").asDouble()).isEqualTo(0.5);
        assertThat(sent.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(sent.at("/messages/0/content/0/text").asText()).isEqualTo("hello");
        assertThat(sent.at("/messages/1/content/0/type").asText()).isEqualTo("tool_use");
        assertThat(sent.at("/messages/1/content/0/input/q").asText()).isEqualTo("x");
        assertThat(sent.at("/messages/2/content/0/type").asText()).isEqualTo("tool_result");
        assertThat(sent.at("/messages/2/content/0/tool_use_id").asText()).isEqualTo("t1");
        assertThat(sent.at("/messages/2/content/0/content").asText()).isEqualTo("42");
        assertThat(sent.at("/tools/0/name").asText()).isEqualTo("lookup");
        assertThat(sent.at("/tools/0/description").asText()).isEqualTo("Looks things up");
        assertThat(sent.at("/tools/0/input_schema/type").asText()).isEqualTo("object");
    }

    @Test
    void omitsOptionalFieldsThatWereNotSent() throws Exception {
        STUB.enqueue(Reply.anthropicText("ok", 1, 1));

        provider().chat(simple(), "sk-test");

        JsonNode sent = mapper.readTree(STUB.received().get(0).body());
        assertThat(sent.has("system")).isFalse();
        assertThat(sent.has("temperature")).isFalse();
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void parsesTextAndToolUseAndSkipsOtherBlockTypes() throws Exception {
        STUB.enqueue(Reply.ok("""
                {"model":"claude-x","content":[
                  {"type":"thinking","thinking":"hmm"},
                  {"type":"text","text":"Hi there"},
                  {"type":"tool_use","id":"tu_1","name":"lookup","input":{"q":"y"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":21,"output_tokens":9}}"""));

        LlmResponse response = provider().chat(simple(), "sk-test");

        assertThat(response.model()).isEqualTo("claude-x");
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).containsEntry("type", "text").containsEntry("text", "Hi there");
        assertThat(response.content().get(1)).containsEntry("type", "tool_use").containsEntry("id", "tu_1")
                .containsEntry("name", "lookup").containsEntry("input", Map.of("q", "y"));
        assertThat(response.stopReason()).isEqualTo("tool_use");
        assertThat(response.inputTokens()).isEqualTo(21);
        assertThat(response.outputTokens()).isEqualTo(9);
    }

    @Test
    void mapsStopReasons() throws Exception {
        for (var pair : Map.of("end_turn", "end_turn", "max_tokens", "max_tokens", "tool_use", "tool_use",
                "stop_sequence", "other", "refusal", "other").entrySet()) {
            STUB.enqueue(Reply.ok("{\"content\":[],\"stop_reason\":\"" + pair.getKey()
                    + "\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            assertThat(provider().chat(simple(), "k").stopReason()).isEqualTo(pair.getValue());
        }
    }

    @Test
    void classifiesFailuresWithoutTheirBodies() {
        STUB.enqueue(Reply.status(429, "{}").withHeader("Retry-After", "3"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
            assertThat(e.retryable()).isTrue();
        });

        STUB.enqueue(Reply.status(529, "{}"));
        expect(() -> provider().chat(simple(), "k"), Kind.SERVER_ERROR);

        STUB.enqueue(Reply.status(400, "{}"));
        assertThatThrownBy(() -> provider().chat(simple(), "k")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR);
            assertThat(e.retryable()).isFalse();
            assertThat(e.countsAsBreakerFailure()).isFalse();
        });

        STUB.enqueue(Reply.ok("this is not json"));
        expect(() -> provider().chat(simple(), "k"), Kind.BAD_RESPONSE);

        STUB.enqueue(Reply.ok("{\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}"));
        expect(() -> provider().chat(simple(), "k"), Kind.BAD_RESPONSE);

        STUB.enqueue(Reply.ok("{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
        expect(() -> provider().chat(simple(), "k"), Kind.BAD_RESPONSE);
    }

    @Test
    void aSlowProviderTimesOut() {
        STUB.enqueue(Reply.anthropicText("late", 1, 1).delayed(1500));

        expect(() -> provider(STUB.baseUrl(), Duration.ofMillis(200)).chat(simple(), "k"), Kind.TIMEOUT);
    }

    @Test
    void aRefusedConnectionIsAnIoError() {
        expect(() -> provider("http://127.0.0.1:1", Duration.ofSeconds(2)).chat(simple(), "k"), Kind.IO);
    }

    @Test
    void exceptionMessagesNeverCarryTheProviderBodyOrTheKey() {
        STUB.enqueue(Reply.status(401, "{\"error\":{\"message\":\"invalid x-api-key sk-secret-999 for prompt hello\"}}"));

        assertThatThrownBy(() -> provider().chat(simple(), "sk-secret-999"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.getMessage()).doesNotContain("sk-secret-999").doesNotContain("invalid")
                            .doesNotContain("hello");
                    assertThat(e.getCause()).isNull();
                });
    }
}
