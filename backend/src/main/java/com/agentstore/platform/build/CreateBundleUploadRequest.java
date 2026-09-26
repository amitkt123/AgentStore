package com.agentstore.platform.build;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateBundleUploadRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256,
                                        @Positive long sizeBytes) {}
