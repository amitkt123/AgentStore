package com.agentstore.platform.manifest;

import java.util.Optional;

/** How an agent reaches an LLM (manifest {@code llm.mode}). */
public enum LlmMode {
    PLATFORM("platform"), BYOK_PROXY("byok_proxy"), BYOK_DIRECT("byok_direct");

    private final String wire;

    LlmMode(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean isByok() {
        return this != PLATFORM;
    }

    public static Optional<LlmMode> fromWire(String wire) {
        for (LlmMode mode : values()) {
            if (mode.wire.equals(wire)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
