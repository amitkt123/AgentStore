package com.agentstore.platform.llm;

import com.agentstore.platform.llm.ProviderCallException.Kind;
import com.agentstore.platform.manifest.LlmVendor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic {@code /v1/messages}. */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final String baseUrl;
    private final ProviderHttp http;
    private final ObjectMapper mapper;

    @Autowired
    public AnthropicProvider(LlmProperties properties, ObjectMapper mapper) {
        this(properties.baseUrl(LlmVendor.ANTHROPIC),
                ProviderHttp.create(properties.connectTimeout(), properties.readTimeout(), mapper), mapper);
    }

    AnthropicProvider(String baseUrl, ProviderHttp http, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.ANTHROPIC;
    }

    @Override
    public LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        if (request.system() != null) {
            body.put("system", request.system());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        ArrayNode messages = body.putArray("messages");
        for (LlmRequest.Msg message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            ArrayNode content = node.putArray("content");
            for (Map<String, Object> block : message.content()) {
                content.add(toBlock(block));
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmRequest.Tool tool : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("name", tool.name());
                if (tool.description() != null) {
                    node.put("description", tool.description());
                }
                node.set("input_schema", mapper.valueToTree(tool.inputSchema()));
            }
        }
        JsonNode root = http.post(baseUrl + "/v1/messages",
                Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"), body);
        return parse(root, request.model());
    }

    private JsonNode toBlock(Map<String, Object> block) {
        ObjectNode node = mapper.createObjectNode();
        switch ((String) block.get("type")) {
            case "text" -> {
                node.put("type", "text");
                node.put("text", (String) block.get("text"));
            }
            case "tool_use" -> {
                node.put("type", "tool_use");
                node.put("id", (String) block.get("id"));
                node.put("name", (String) block.get("name"));
                node.set("input", mapper.valueToTree(block.get("input")));
            }
            case "tool_result" -> {
                node.put("type", "tool_result");
                node.put("tool_use_id", (String) block.get("toolUseId"));
                node.put("content", (String) block.get("content"));
            }
            default -> throw new IllegalStateException("the normalizer let an unknown block type through");
        }
        return node;
    }

    private LlmResponse parse(JsonNode root, String requestedModel) throws ProviderCallException {
        JsonNode usage = root.path("usage");
        if (!root.path("content").isArray() || !usage.path("input_tokens").isNumber()
                || !usage.path("output_tokens").isNumber()) {
            throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (JsonNode block : root.get("content")) {
            switch (block.path("type").asText()) {
                case "text" -> {
                    Map<String, Object> text = new LinkedHashMap<>();
                    text.put("type", "text");
                    text.put("text", block.path("text").asText(""));
                    content.add(text);
                }
                case "tool_use" -> {
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", block.path("id").asText());
                    use.put("name", block.path("name").asText());
                    try {
                        use.put("input", block.hasNonNull("input") ? mapper.convertValue(block.get("input"), MAP)
                                : new LinkedHashMap<String, Object>());
                    } catch (IllegalArgumentException e) {
                        throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
                    }
                    content.add(use);
                }
                default -> {
                    // thinking and other block types are not part of the normalized shape
                }
            }
        }
        String model = root.path("model").asText(requestedModel);
        return new LlmResponse(model.isBlank() ? requestedModel : model, content,
                stopReason(root.path("stop_reason").asText()), usage.get("input_tokens").asInt(),
                usage.get("output_tokens").asInt());
    }

    private static String stopReason(String providerReason) {
        return switch (providerReason) {
            case "end_turn" -> "end_turn";
            case "max_tokens" -> "max_tokens";
            case "tool_use" -> "tool_use";
            default -> "other";
        };
    }
}
