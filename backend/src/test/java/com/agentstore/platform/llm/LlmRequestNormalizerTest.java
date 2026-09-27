package com.agentstore.platform.llm;

import com.agentstore.platform.runtime.RuntimeApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRequestNormalizerTest {

    final LlmRequestNormalizer normalizer = new LlmRequestNormalizer(32_000);

    static Map<String, Object> request(Object content) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", "m");
        r.put("messages", List.of(Map.of("role", "user", "content", content)));
        r.put("maxTokens", 100);
        return r;
    }

    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        if (value == null) {
            copy.remove(key);
        } else {
            copy.put(key, value);
        }
        return copy;
    }

    void assertInvalid(Map<String, Object> request) {
        assertThatThrownBy(() -> normalizer.normalize(request)).isInstanceOfSatisfying(RuntimeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("INVALID_REQUEST");
        });
    }

    @Test
    void aStringContentBecomesASingleTextBlock() {
        var normalized = normalizer.normalize(request("hi"));

        assertThat(normalized.request().messages().get(0).content())
                .containsExactly(Map.of("type", "text", "text", "hi"));
        assertThat(normalized.request().tools()).isEmpty();
        assertThat(normalized.request().system()).isNull();
        assertThat(normalized.request().temperature()).isNull();
        assertThat(normalized.request().maxTokens()).isEqualTo(100);
    }

    @Test
    void theHashIgnoresKeyOrderAndStringVersusBlockContent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("model", "m");
        a.put("maxTokens", 100);
        a.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("messages", List.of(Map.of("content", List.of(Map.of("text", "hi", "type", "text")), "role", "user")));
        b.put("maxTokens", 100);
        b.put("model", "m");

        assertThat(normalizer.normalize(a).hash()).isEqualTo(normalizer.normalize(b).hash()).hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void theHashTreatsEmptyToolsAsAbsentAndAnIntegerTemperatureAsADouble() {
        Map<String, Object> base = request("hi");

        assertThat(normalizer.normalize(with(base, "tools", List.of())).hash())
                .isEqualTo(normalizer.normalize(base).hash());
        assertThat(normalizer.normalize(with(base, "temperature", 1)).hash())
                .isEqualTo(normalizer.normalize(with(base, "temperature", 1.0)).hash());
    }

    @Test
    void theHashChangesWhenAnyHashedFieldChanges() {
        Map<String, Object> base = request("hi");
        Map<String, Object> tool = Map.of("name", "lookup", "inputSchema", Map.of("type", "object"));
        List<String> hashes = new ArrayList<>(List.of(
                normalizer.normalize(base).hash(),
                normalizer.normalize(with(base, "model", "other")).hash(),
                normalizer.normalize(request("hello")).hash(),
                normalizer.normalize(with(base, "maxTokens", 101)).hash(),
                normalizer.normalize(with(base, "system", "be brief")).hash(),
                normalizer.normalize(with(base, "temperature", 0.5)).hash(),
                normalizer.normalize(with(base, "tools", List.of(tool))).hash(),
                normalizer.normalize(with(base, "messages",
                        List.of(Map.of("role", "assistant", "content", "hi")))).hash()));

        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void theEstimateCountsBytesNotCharactersAndIncludesTheSystemPrompt() {
        long ascii = normalizer.normalize(request("a".repeat(30))).estimatedInputTokens();
        long euro = normalizer.normalize(request("€".repeat(30))).estimatedInputTokens();
        long withSystem = normalizer.normalize(with(request("a".repeat(30)), "system", "s".repeat(300)))
                .estimatedInputTokens();

        assertThat(euro).isGreaterThan(ascii);
        assertThat(withSystem).isGreaterThanOrEqualTo(ascii + 100);
        assertThat(LlmRequestNormalizer.ceilDiv3(0)).isZero();
        assertThat(LlmRequestNormalizer.ceilDiv3(1)).isEqualTo(1);
        assertThat(LlmRequestNormalizer.ceilDiv3(3)).isEqualTo(1);
        assertThat(LlmRequestNormalizer.ceilDiv3(4)).isEqualTo(2);
    }

    @Test
    void maxTokensMustBeAPositiveWholeNumberWithinTheCap() {
        Map<String, Object> base = request("hi");
        for (Object bad : new Object[]{null, 0, -1, 32_001, "100", 10.5, 1000.0, true, List.of(100)}) {
            assertInvalid(with(base, "maxTokens", bad));
        }
        assertThat(normalizer.normalize(with(base, "maxTokens", 1)).request().maxTokens()).isEqualTo(1);
        assertThat(normalizer.normalize(with(base, "maxTokens", 32_000)).request().maxTokens()).isEqualTo(32_000);
        assertThat(normalizer.normalize(with(base, "maxTokens", 100L)).request().maxTokens()).isEqualTo(100);
    }

    @Test
    void modelAndTemperatureAreChecked() {
        Map<String, Object> base = request("hi");
        for (Object bad : new Object[]{null, "", "  ", 5, List.of("m")}) {
            assertInvalid(with(base, "model", bad));
        }
        for (Object bad : new Object[]{-1, -0.1, "hot", true, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertInvalid(with(base, "temperature", bad));
        }
        assertThat(normalizer.normalize(with(base, "temperature", 0)).request().temperature()).isEqualTo(0.0);
    }

    @Test
    void unknownKeysAreRejectedNotForwarded() {
        Map<String, Object> base = request("hi");
        assertInvalid(with(base, "stream", true));
        assertInvalid(with(base, "top_p", 0.9));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "user", "content", "hi", "name", "bob"))));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "text", "text", "hi", "cache", true))))));
        assertInvalid(with(base, "tools", List.of(Map.of("name", "t", "inputSchema", Map.of(), "strict", true))));
    }

    @Test
    void messagesMustBeANonEmptyListOfUserOrAssistantTurns() {
        Map<String, Object> base = request("hi");
        assertInvalid(with(base, "messages", null));
        assertInvalid(with(base, "messages", List.of()));
        assertInvalid(with(base, "messages", "hi"));
        assertInvalid(with(base, "messages", List.of("hi")));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "system", "content", "hi"))));
        assertInvalid(with(base, "messages", List.of(Map.of("content", "hi"))));
        assertInvalid(with(base, "messages", List.of(Map.of("role", "user"))));
        assertInvalid(request(List.of()));
        assertInvalid(request(42));
        assertInvalid(request(List.of(Map.of("text", "no type"))));
        assertInvalid(request(List.of(Map.of("type", "image", "source", "x"))));
    }

    @Test
    void toolBlocksAreValidated() {
        assertInvalid(request(List.of(Map.of("type", "tool_result", "toolUseId", "t1"))));
        assertInvalid(request(List.of(Map.of("type", "tool_result", "content", "42"))));
        assertInvalid(request(List.of(Map.of("type", "tool_use", "name", "lookup", "input", Map.of()))));
        assertInvalid(request(List.of(Map.of("type", "tool_use", "id", "t1", "input", Map.of()))));
        assertInvalid(request(List.of(Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", "oops"))));

        var ok = normalizer.normalize(with(request(List.of(
                Map.of("type", "tool_use", "id", "t1", "name", "lookup", "input", Map.of("q", "x")),
                Map.of("type", "tool_result", "toolUseId", "t1", "content", "42"))), "tools",
                List.of(Map.of("name", "lookup", "description", "Looks things up",
                        "inputSchema", Map.of("type", "object")))));
        assertThat(ok.request().tools()).hasSize(1);
        assertThat(ok.request().tools().get(0).description()).isEqualTo("Looks things up");
        assertThat(ok.request().messages().get(0).content()).hasSize(2);
    }

    @Test
    void toolsMustBeWellFormed() {
        Map<String, Object> base = request("hi");
        assertInvalid(with(base, "tools", "lookup"));
        assertInvalid(with(base, "tools", List.of("lookup")));
        assertInvalid(with(base, "tools", List.of(Map.of("name", "lookup"))));
        assertInvalid(with(base, "tools", List.of(Map.of("name", " ", "inputSchema", Map.of()))));
        assertInvalid(with(base, "tools", List.of(Map.of("name", "t", "inputSchema", "object"))));
    }

    @Test
    void aNullRequestBodyIsInvalid() {
        assertThatThrownBy(() -> normalizer.normalize(null)).isInstanceOf(RuntimeApiException.class);
    }

    @Test
    void errorMessagesDoNotEchoLongInput() {
        Map<String, Object> hostile = with(request("hi"), "x".repeat(500), 1);

        assertThatThrownBy(() -> normalizer.normalize(hostile)).isInstanceOfSatisfying(RuntimeApiException.class,
                e -> assertThat(e.getMessage().length()).isLessThan(200));
    }
}
