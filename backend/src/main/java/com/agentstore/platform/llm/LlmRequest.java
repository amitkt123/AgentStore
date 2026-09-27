package com.agentstore.platform.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The normalized chat request every adapter, the hash and the recorder work from. String message content has already
 * become a single text block; {@code tools} is empty, never null, when the agent sent none.
 */
public record LlmRequest(String model, String system, List<Msg> messages, List<Tool> tools, int maxTokens,
                         Double temperature) {

    public record Msg(String role, List<Map<String, Object>> content) {
    }

    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** The request in the shape agents send it (what a recording stores). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        if (system != null) {
            out.put("system", system);
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        for (Msg message : messages) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("role", message.role());
            turn.put("content", message.content());
            turns.add(turn);
        }
        out.put("messages", turns);
        if (!tools.isEmpty()) {
            List<Map<String, Object>> declared = new ArrayList<>();
            for (Tool tool : tools) {
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
        out.put("maxTokens", maxTokens);
        if (temperature != null) {
            out.put("temperature", temperature);
        }
        return out;
    }
}
