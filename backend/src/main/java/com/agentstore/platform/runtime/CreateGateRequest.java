package com.agentstore.platform.runtime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateGateRequest(@NotBlank @Size(max = 100) String action, @NotBlank @Size(max = 2000) String summary,
                                @NotNull Map<String, Object> payload, List<String> affectsResources) {
}
