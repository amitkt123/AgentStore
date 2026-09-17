package com.agentstore.platform.catalog;

import java.util.UUID;

public record AgentVersionResponse(UUID id, String versionNumber, VersionStatus status) {
    public static AgentVersionResponse from(AgentVersion version) {
        return new AgentVersionResponse(version.getId(), version.getVersionNumber(), version.getStatus());
    }
}
