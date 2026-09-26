package com.agentstore.runner.backend;

import java.util.Arrays;

/** Runtimes a bundle's manifest can declare (spec §4.1 "Manifest"). */
public enum AgentRuntime {
    PYTHON_3_12("python-3.12", "python:3.12-slim"),
    NODE_22("node-22", "node:22-slim");

    private final String id;
    private final String baseImage;

    AgentRuntime(String id, String baseImage) {
        this.id = id;
        this.baseImage = baseImage;
    }

    public String id() { return id; }

    public String baseImage() { return baseImage; }

    public static AgentRuntime fromId(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown runtime: " + id));
    }
}
