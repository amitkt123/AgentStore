package com.agentstore.platform.build;

import java.util.UUID;

public record FinalizeBundleResponse(UUID bundleId, UUID versionId, String versionNumber, UUID buildId,
                                     BuildStatus buildStatus) {}
