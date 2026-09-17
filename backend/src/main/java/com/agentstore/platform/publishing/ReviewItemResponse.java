package com.agentstore.platform.publishing;

import java.util.UUID;

public record ReviewItemResponse(UUID id, UUID agentVersionId, ReviewStatus status) {
    static ReviewItemResponse from(ReviewItem item) {
        return new ReviewItemResponse(item.getId(), item.getAgentVersion().getId(), item.getStatus());
    }
}
