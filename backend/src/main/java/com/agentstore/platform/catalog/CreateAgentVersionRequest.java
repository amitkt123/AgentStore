package com.agentstore.platform.catalog;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentVersionRequest(@NotBlank String versionNumber, @NotBlank String manifestJson,
                                         @NotBlank String imageRef, String changelog) {
}
