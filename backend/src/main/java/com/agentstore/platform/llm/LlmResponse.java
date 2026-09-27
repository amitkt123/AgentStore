package com.agentstore.platform.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The normalized chat response: text and tool_use blocks, a stop reason and the provider's own token counts. */
public record LlmResponse(String model, List<Map<String, Object>> content, String stopReason, int inputTokens,
                          int outputTokens) {

    private static final Set<String> STOP_REASONS = Set.of("end_turn", "max_tokens", "tool_use", "other");

    public Map<String, Object> toMap() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", inputTokens);
        usage.put("outputTokens", outputTokens);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("content", content);
        out.put("stopReason", stopReason);
        out.put("usage", usage);
        return out;
    }

    /**
     * Reads a response that was recorded or canned by hand. Only {@code content} is required; a missing model,
     * stop reason or usage falls back to the request's model, {@code end_turn} and zero tokens.
     */
    @SuppressWarnings("unchecked")
    public static LlmResponse fromMap(Map<String, Object> map, String fallbackModel) {
        if (!(map.get("content") instanceof List<?> list)) {
            throw new IllegalArgumentException("content must be an array");
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> block)) {
                throw new IllegalArgumentException("a content block must be an object");
            }
            Object type = block.get("type");
            boolean text = "text".equals(type) && block.get("text") instanceof String;
            boolean toolUse = "tool_use".equals(type) && block.get("id") instanceof String
                    && block.get("name") instanceof String && block.get("input") instanceof Map;
            if (!text && !toolUse) {
                throw new IllegalArgumentException("unsupported content block");
            }
            blocks.add((Map<String, Object>) block);
        }
        String model = map.get("model") instanceof String s && !s.isBlank() ? s : fallbackModel;
        String stop = map.get("stopReason") instanceof String s && STOP_REASONS.contains(s) ? s : "end_turn";
        int in = 0;
        int out = 0;
        if (map.get("usage") instanceof Map<?, ?> usage) {
            in = count(usage.get("inputTokens"));
            out = count(usage.get("outputTokens"));
        }
        return new LlmResponse(model, List.copyOf(blocks), stop, in, out);
    }

    private static int count(Object value) {
        return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }
}
