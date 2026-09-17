package com.agentstore.platform.publishing;

import java.util.UUID;

public record ReviewItemResponse(UUID id, UUID agentVersionId, String agentName, String versionNumber, ReviewStatus status) {
    static ReviewItemResponse from(ReviewItem item) {
        return new ReviewItemResponse(item.getId(), item.getAgentVersion().getId(),
                item.getAgentVersion().getAgent().getName(), item.getAgentVersion().getVersionNumber(), item.getStatus());
    }
}
