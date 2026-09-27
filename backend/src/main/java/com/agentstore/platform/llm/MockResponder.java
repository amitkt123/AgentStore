package com.agentstore.platform.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Canned answers for MOCK runs: {@code {"responses": {"<hash>": {…}}, "default": {…}}}. Lookup is the exact request
 * hash, then {@code default}, then a fixed stub, so a mock run never fails for want of an entry. A malformed entry
 * counts as absent.
 */
@Component
public class MockResponder {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public MockResponder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmResponse respond(String mockJson, LlmRequest request, String hash) {
        Map<String, Object> root = parse(mockJson);
        if (root.get("responses") instanceof Map<?, ?> byHash) {
            LlmResponse exact = canned(byHash.get(hash), request.model());
            if (exact != null) {
                return exact;
            }
        }
        LlmResponse fallback = canned(root.get("default"), request.model());
        return fallback != null ? fallback : genericStub(request.model());
    }

    static LlmResponse genericStub(String model) {
        return new LlmResponse(model, List.of(Map.of("type", "text", "text", "[mock response]")), "end_turn", 0, 0);
    }

    @SuppressWarnings("unchecked")
    private static LlmResponse canned(Object entry, String model) {
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            return LlmResponse.fromMap((Map<String, Object>) map, model);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
