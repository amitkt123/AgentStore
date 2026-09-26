package com.agentstore.platform.build;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FinalizeBundleRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) {}
