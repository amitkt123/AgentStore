package com.agentstore.platform.execution;

import java.util.List;
import java.util.Map;

/** Vendor-neutral sandbox request. {@code requestId} is the run id and makes creation idempotent in the runner. */
public record SandboxSpec(String requestId, String imageRef, List<String> command, Map<String, String> envVars,
                          List<String> allowOut, int cpu, int memoryMb, int timeoutSeconds) {
    @Override
    public String toString() {
        return "SandboxSpec[requestId=" + requestId + ", imageRef=" + imageRef + ", envKeys=" + envVars.keySet() + "]";
    }
}
