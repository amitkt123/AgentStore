package com.agentstore.runner.sandbox;

import java.util.List;
import java.util.Map;

public record CreateSandboxCommand(String requestId, String imageRef, List<String> command, Map<String, String> env,
                                   List<String> allowOut, int cpu, int memoryMb, int timeoutSeconds) {
    @Override
    public String toString() {
        return "CreateSandboxCommand[requestId=" + requestId + ", imageRef=" + imageRef + ", envKeys="
                + (env == null ? "[]" : env.keySet()) + "]";
    }
}
