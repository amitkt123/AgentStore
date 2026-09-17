package com.agentstore.platform.catalog;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentRequest(@NotBlank String name, @NotBlank String category, String description) {
}
