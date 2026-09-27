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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI {@code /v1/chat/completions}, always with {@code store: false}. */
@Component
public class OpenAiProvider implements LlmProvider {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final String baseUrl;
    private final ProviderHttp http;
    private final ObjectMapper mapper;

    @Autowired
    public OpenAiProvider(LlmProperties properties, ObjectMapper mapper) {
        this(properties.baseUrl(LlmVendor.OPENAI),
                ProviderHttp.create(properties.connectTimeout(), properties.readTimeout(), mapper), mapper);
    }

    OpenAiProvider(String baseUrl, ProviderHttp http, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public LlmVendor vendor() {
        return LlmVendor.OPENAI;
    }

    @Override
    public LlmResponse chat(LlmRequest request, String apiKey) throws ProviderCallException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("max_completion_tokens", request.maxTokens());
        body.put("store", false);
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        ArrayNode messages = body.putArray("messages");
        if (request.system() != null) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        for (LlmRequest.Msg message : request.messages()) {
            addMessage(messages, message);
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmRequest.Tool tool : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("type", "function");
                ObjectNode function = node.putObject("function");
                function.put("name", tool.name());
                if (tool.description() != null) {
                    function.put("description", tool.description());
                }
                function.set("parameters", mapper.valueToTree(tool.inputSchema()));
            }
        }
        JsonNode root = http.post(baseUrl + "/v1/chat/completions", Map.of("authorization", "Bearer " + apiKey), body);
        return parse(root, request.model());
    }

    private void addMessage(ArrayNode messages, LlmRequest.Msg message) {
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolUses = new ArrayList<>();
        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (Map<String, Object> block : message.content()) {
            switch ((String) block.get("type")) {
                case "text" -> text.append((String) block.get("text"));
                case "tool_use" -> toolUses.add(block);
                case "tool_result" -> toolResults.add(block);
                default -> throw new IllegalStateException("the normalizer let an unknown block type through");
            }
        }
        if (message.role().equals("assistant")) {
            ObjectNode node = messages.addObject();
            node.put("role", "assistant");
            if (text.isEmpty() && !toolUses.isEmpty()) {
                node.putNull("content");
            } else {
                node.put("content", text.toString());
            }
            if (!toolUses.isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (Map<String, Object> use : toolUses) {
                    ObjectNode call = calls.addObject();
                    call.put("id", (String) use.get("id"));
                    call.put("type", "function");
                    ObjectNode function = call.putObject("function");
                    function.put("name", (String) use.get("name"));
                    JsonNode input = mapper.valueToTree(use.get("input"));
                    function.put("arguments", input.toString());
                }
            }
        } else {
            for (Map<String, Object> result : toolResults) {
                messages.addObject().put("role", "tool").put("tool_call_id", (String) result.get("toolUseId"))
                        .put("content", (String) result.get("content"));
            }
            if (!text.isEmpty() || toolResults.isEmpty()) {
                messages.addObject().put("role", "user").put("content", text.toString());
            }
        }
    }

    private LlmResponse parse(JsonNode root, String requestedModel) throws ProviderCallException {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode usage = root.path("usage");
        if (!message.isObject() || !usage.path("prompt_tokens").isNumber() || !usage.path("completion_tokens").isNumber()) {
            throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (message.path("content").isTextual() && !message.get("content").asText().isEmpty()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", message.get("content").asText());
            content.add(text);
        }
        for (JsonNode call : message.path("tool_calls")) {
            Map<String, Object> use = new LinkedHashMap<>();
            use.put("type", "tool_use");
            use.put("id", call.path("id").asText());
            use.put("name", call.path("function").path("name").asText());
            try {
                JsonNode arguments = mapper.readTree(call.path("function").path("arguments").asText("{}"));
                if (!arguments.isObject()) {
                    throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
                }
                use.put("input", mapper.convertValue(arguments, MAP));
            } catch (IOException | IllegalArgumentException e) {
                throw new ProviderCallException(Kind.BAD_RESPONSE, 200, null);
            }
            content.add(use);
        }
        String model = root.path("model").asText(requestedModel);
        return new LlmResponse(model.isBlank() ? requestedModel : model, content,
                stopReason(choice.path("finish_reason").asText()), usage.get("prompt_tokens").asInt(),
                usage.get("completion_tokens").asInt());
    }

    private static String stopReason(String finishReason) {
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            default -> "other";
        };
    }
}
