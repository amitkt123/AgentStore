package com.agentstore.platform.manifest;

import java.util.Optional;

/** The LLM providers the platform can call. {@code apiHost} is what {@code byok_direct} must allow-list. */
public enum LlmVendor {
    ANTHROPIC("anthropic", "api.anthropic.com"), OPENAI("openai", "api.openai.com");

    private final String wire;
    private final String apiHost;

    LlmVendor(String wire, String apiHost) {
        this.wire = wire;
        this.apiHost = apiHost;
    }

    public String wire() {
        return wire;
    }

    public String apiHost() {
        return apiHost;
    }

    public static Optional<LlmVendor> fromWire(String wire) {
        for (LlmVendor vendor : values()) {
            if (vendor.wire.equals(wire)) {
                return Optional.of(vendor);
            }
        }
        return Optional.empty();
    }
}
