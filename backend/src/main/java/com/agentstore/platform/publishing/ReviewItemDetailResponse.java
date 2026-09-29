package com.agentstore.platform.publishing;

import com.agentstore.platform.manifest.Manifest;

import java.time.Instant;
import java.util.UUID;

public record ReviewItemDetailResponse(UUID id, String displayId, UUID agentVersionId, String agentName, String versionNumber,
                                        ReviewStatus status, Manifest manifest, String notes,
                                        Instant createdAt, Instant decidedAt) {
    static ReviewItemDetailResponse from(ReviewItem item, Manifest manifest) {
        return new ReviewItemDetailResponse(item.getId(), item.getDisplayId(), item.getAgentVersion().getId(),
                item.getAgentVersion().getAgent().getName(), item.getAgentVersion().getVersionNumber(),
                item.getStatus(), manifest, item.getNotes(), item.getCreatedAt(), item.getDecidedAt());
    }
}
