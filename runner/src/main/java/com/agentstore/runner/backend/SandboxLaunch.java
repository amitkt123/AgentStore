package com.agentstore.runner.backend;

import java.util.List;
import java.util.Map;

public record SandboxLaunch(String imageRef, List<String> command, Map<String, String> env, List<String> allowOut,
                            int cpu, int memoryMb, int timeoutSeconds, Map<String, String> labels) {

    public SandboxLaunch {
        command = List.copyOf(command);
        env = Map.copyOf(env);
        allowOut = List.copyOf(allowOut);
        labels = Map.copyOf(labels);
    }

    /** Never print env values. */
    @Override
    public String toString() {
        return "SandboxLaunch[imageRef=" + imageRef + ", command=" + command + ", envKeys=" + env.keySet()
                + ", allowOut=" + allowOut + ", timeoutSeconds=" + timeoutSeconds + ", labels=" + labels + "]";
    }
}
