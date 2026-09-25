package com.agentstore.runner.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record CreateSandboxRequest(@NotBlank String requestId, @NotBlank String imageRef,
                                   @NotEmpty List<String> command, Map<String, String> env, List<String> allowOut,
                                   @Min(1) int cpu, @Min(128) int memoryMb, @Min(1) int timeoutSeconds) {
    @Override
    public String toString() {
        return "CreateSandboxRequest[requestId=" + requestId + ", imageRef=" + imageRef + "]";
    }
}
