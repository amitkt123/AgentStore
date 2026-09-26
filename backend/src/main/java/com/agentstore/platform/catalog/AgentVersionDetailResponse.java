package com.agentstore.platform.catalog;

import com.agentstore.platform.build.BuildSummary;
import com.agentstore.platform.manifest.Manifest;

import java.util.UUID;

/** {@code build} is null unless the caller is the owning developer or an admin. */
public record AgentVersionDetailResponse(UUID id, UUID agentId, String agentName, String versionNumber,
                                         VersionStatus status, Manifest manifest, BuildSummary build) {
    static AgentVersionDetailResponse from(AgentVersion version, Manifest manifest, BuildSummary build) {
        return new AgentVersionDetailResponse(version.getId(), version.getAgent().getId(),
                version.getAgent().getName(), version.getVersionNumber(), version.getStatus(), manifest, build);
    }
}
