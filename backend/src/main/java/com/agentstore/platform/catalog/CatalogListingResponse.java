package com.agentstore.platform.catalog;

import java.util.UUID;

public record CatalogListingResponse(UUID agentId, String agentName, String category,
                                      UUID versionId, String versionNumber) {
    static CatalogListingResponse from(AgentVersion version) {
        return new CatalogListingResponse(version.getAgent().getId(), version.getAgent().getName(),
                version.getAgent().getCategory(), version.getId(), version.getVersionNumber());
    }
}
