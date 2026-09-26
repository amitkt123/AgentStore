package com.agentstore.platform.build;

import java.time.Instant;
import java.util.UUID;

public record BuildResponse(UUID id, UUID versionId, BuildStatus status, String backend, String imageRef,
                            String failureCode, String failureReason, String logUrl, Instant queuedAt,
                            Instant startedAt, Instant finishedAt) {

    static BuildResponse from(TemplateBuild b, String logUrl) {
        return new BuildResponse(b.getId(), b.getAgentVersion().getId(), b.getStatus(), b.getBackend(), b.getImageRef(),
                b.getFailureCode(), b.getFailureReason(), logUrl, b.getQueuedAt(), b.getStartedAt(), b.getFinishedAt());
    }
}
