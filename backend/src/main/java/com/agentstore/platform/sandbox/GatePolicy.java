package com.agentstore.platform.sandbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * How a sandbox profile answers approval gates: a default and per-action overrides. Stored as the profile's
 * {@code gate_policy} JSON. A production run never consults one.
 */
public record GatePolicy(@JsonProperty("default") GateMode defaultMode, Map<String, GateMode> actions) {

    public enum GateMode { MANUAL, AUTO_APPROVE, AUTO_REJECT }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GatePolicy {
        defaultMode = defaultMode == null ? GateMode.MANUAL : defaultMode;
        actions = actions == null ? Map.of() : Map.copyOf(actions);
    }

    public static GatePolicy manual() {
        return new GatePolicy(GateMode.MANUAL, Map.of());
    }

    public GateMode modeFor(String action) {
        return actions.getOrDefault(action, defaultMode);
    }

    public static GatePolicy parse(String json) {
        if (json == null || json.isBlank()) {
            return manual();
        }
        try {
            return MAPPER.readValue(json, GatePolicy.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid gate policy: " + e.getOriginalMessage(), e);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
