package com.agentstore.platform.llm;

import com.agentstore.platform.runtime.RuntimeApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and canonicalises a chat request. Everything after this class works on {@link LlmRequest}: unknown keys
 * and block types never reach a provider. The hash covers the fields the spec lists (model, messages, tools,
 * maxTokens, temperature) plus {@code system}, so a mock or replay can tell two requests that differ only in their
 * system prompt apart (sorted keys, string content already turned into a text block, empty tools omitted). The token
 * estimate is taken from the same canonical bytes, so a big system prompt is not under-reserved.
 */
@Component
public class LlmRequestNormalizer {

    public record Normalized(LlmRequest request, String hash, long estimatedInputTokens) {
    }

    private static final Set<String> TOP_LEVEL = Set.of("model", "system", "messages", "tools", "maxTokens", "temperature");
    private static final Set<String> MESSAGE_KEYS = Set.of("role", "content");
    private static final Set<String> TEXT_KEYS = Set.of("type", "text");
    private static final Set<String> TOOL_USE_KEYS = Set.of("type", "id", "name", "input");
    private static final Set<String> TOOL_RESULT_KEYS = Set.of("type", "toolUseId", "content");
    private static final Set<String> TOOL_KEYS = Set.of("name", "description", "inputSchema");
    private static final ObjectMapper CANONICAL =
            JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final int maxOutputTokens;

    public LlmRequestNormalizer(@Value("${agentstore.llm.max-output-tokens:32000}") int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Normalized normalize(Map<String, Object> raw) {
        if (raw == null) {
            throw invalid("A request body is required");
        }
        onlyKeys(raw, TOP_LEVEL, "the request");
        String model = requiredString(raw, "model", "the request");
        String system = raw.get("system") == null ? null : text(raw.get("system"), "system");
        int maxTokens = maxTokens(raw.get("maxTokens"));
        Double temperature = temperature(raw.get("temperature"));
        List<LlmRequest.Msg> messages = messages(raw.get("messages"));
        List<LlmRequest.Tool> tools = tools(raw.get("tools"));
        LlmRequest request = new LlmRequest(model, system, messages, tools, maxTokens, temperature);
        byte[] canonical = canonicalBytes(request);
        return new Normalized(request, sha256Hex(canonical), ceilDiv3(canonical.length));
    }

    static long ceilDiv3(long n) {
        return (n + 2) / 3;
    }

    private int maxTokens(Object value) {
        boolean whole = value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
        if (!whole) {
            throw invalid("maxTokens is required and must be a whole number");
        }
        long n = ((Number) value).longValue();
        if (n < 1 || n > maxOutputTokens) {
            throw invalid("maxTokens must be between 1 and " + maxOutputTokens);
        }
        return (int) n;
    }

    private static Double temperature(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number n)) {
            throw invalid("temperature must be a number");
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) {
            throw invalid("temperature must be a finite number of at least 0");
        }
        return d;
    }

    private static List<LlmRequest.Msg> messages(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw invalid("messages must be a non-empty array");
        }
        List<LlmRequest.Msg> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> message = object(item, "a message");
            onlyKeys(message, MESSAGE_KEYS, "a message");
            String role = requiredString(message, "role", "a message");
            if (!role.equals("user") && !role.equals("assistant")) {
                throw invalid("message.role must be 'user' or 'assistant'");
            }
            out.add(new LlmRequest.Msg(role, blocks(message.get("content"))));
        }
        return out;
    }

    private static List<Map<String, Object>> blocks(Object content) {
        if (content instanceof String s) {
            return List.of(textBlock(s));
        }
        if (!(content instanceof List<?> list) || list.isEmpty()) {
            throw invalid("message.content must be a string or a non-empty array of blocks");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> block = object(item, "a content block");
            String type = requiredString(block, "type", "a content block");
            switch (type) {
                case "text" -> {
                    onlyKeys(block, TEXT_KEYS, "a text block");
                    out.add(textBlock(text(block.get("text"), "text")));
                }
                case "tool_use" -> {
                    onlyKeys(block, TOOL_USE_KEYS, "a tool_use block");
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", requiredString(block, "id", "a tool_use block"));
                    use.put("name", requiredString(block, "name", "a tool_use block"));
                    use.put("input", object(block.get("input"), "tool_use.input"));
                    out.add(use);
                }
                case "tool_result" -> {
                    onlyKeys(block, TOOL_RESULT_KEYS, "a tool_result block");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("type", "tool_result");
                    result.put("toolUseId", requiredString(block, "toolUseId", "a tool_result block"));
                    result.put("content", text(block.get("content"), "tool_result.content"));
                    out.add(result);
                }
                default -> throw invalid("Unsupported content block type '" + shorten(type) + "'");
            }
        }
        return out;
    }

    private static List<LlmRequest.Tool> tools(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw invalid("tools must be an array");
        }
        List<LlmRequest.Tool> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> tool = object(item, "a tool");
            onlyKeys(tool, TOOL_KEYS, "a tool");
            String description = tool.get("description") == null ? null : text(tool.get("description"), "tool.description");
            out.add(new LlmRequest.Tool(requiredString(tool, "name", "a tool"), description,
                    object(tool.get("inputSchema"), "tool.inputSchema")));
        }
        return out;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(what + " must be an object");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw invalid(what + " has a non-string key");
            }
        }
        return (Map<String, Object>) map;
    }

    private static void onlyKeys(Map<String, Object> map, Set<String> allowed, String what) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("Unknown field '" + shorten(key) + "' in " + what);
            }
        }
    }

    private static String requiredString(Map<String, Object> map, String key, String what) {
        Object value = map.get(key);
        if (value == null) {
            throw invalid(key + " is required in " + what);
        }
        if (!(value instanceof String s) || s.isBlank()) {
            throw invalid(key + " must be a non-blank string in " + what);
        }
        return s;
    }

    private static String text(Object value, String what) {
        if (!(value instanceof String s)) {
            throw invalid(what + " must be a string");
        }
        return s;
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static RuntimeApiException invalid(String message) {
        return new RuntimeApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    /** The one place that decides what is hashed. Sorted keys come from the mapper. */
    private static Map<String, Object> canonical(LlmRequest r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", r.model());
        out.put("maxTokens", r.maxTokens());
        List<Map<String, Object>> turns = new ArrayList<>();
        for (LlmRequest.Msg message : r.messages()) {
            turns.add(Map.of("role", message.role(), "content", message.content()));
        }
        out.put("messages", turns);
        if (!r.tools().isEmpty()) {
            List<Map<String, Object>> declared = new ArrayList<>();
            for (LlmRequest.Tool tool : r.tools()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", tool.name());
                if (tool.description() != null) {
                    entry.put("description", tool.description());
                }
                entry.put("inputSchema", tool.inputSchema());
                declared.add(entry);
            }
            out.put("tools", declared);
        }
        if (r.temperature() != null) {
            out.put("temperature", r.temperature());
        }
        if (r.system() != null) {
            out.put("system", r.system());
        }
        return out;
    }

    private static byte[] canonicalBytes(LlmRequest request) {
        try {
            return CANONICAL.writeValueAsBytes(canonical(request));
        } catch (JsonProcessingException e) {
            throw invalid("The request cannot be serialised");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
