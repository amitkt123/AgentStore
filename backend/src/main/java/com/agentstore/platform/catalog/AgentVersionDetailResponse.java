package com.agentstore.platform.catalog;

import com.agentstore.platform.manifest.Manifest;

import java.util.UUID;

public record AgentVersionDetailResponse(UUID id, UUID agentId, String agentName, String versionNumber,
                                         VersionStatus status, Manifest manifest) {
    static AgentVersionDetailResponse from(AgentVersion version, Manifest manifest) {
        return new AgentVersionDetailResponse(version.getId(), version.getAgent().getId(),
                version.getAgent().getName(), version.getVersionNumber(), version.getStatus(), manifest);
    }
}
