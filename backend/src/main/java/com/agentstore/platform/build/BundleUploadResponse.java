package com.agentstore.platform.build;

import java.time.Instant;
import java.util.UUID;

public record BundleUploadResponse(UUID bundleId, String uploadUrl, Instant expiresAt) {
    /** The upload URL carries a signature: never print it. */
    @Override
    public String toString() {
        return "BundleUploadResponse[bundleId=" + bundleId + ", expiresAt=" + expiresAt + "]";
    }
}
