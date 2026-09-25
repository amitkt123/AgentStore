package com.agentstore.runner.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateBuildRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String buildId,
                                 @NotBlank String runtime,
                                 @NotBlank String bundleUrl,
                                 @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String bundleSha256,
                                 @NotBlank String entrypoint,
                                 @NotBlank String logUploadUrl) {
    /** Pre-signed URLs carry signatures: never print them. */
    @Override
    public String toString() {
        return "CreateBuildRequest[buildId=" + buildId + ", runtime=" + runtime + ", entrypoint=" + entrypoint + "]";
    }
}
