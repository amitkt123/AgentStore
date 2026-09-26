package com.agentstore.platform.catalog;

import jakarta.validation.constraints.NotBlank;

/** No image: a version's image comes only from a successful build of a pushed bundle (WS5b/WS5c). */
public record CreateAgentVersionRequest(@NotBlank String versionNumber, @NotBlank String manifestJson, String changelog) {
}
