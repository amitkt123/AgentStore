package com.agentstore.platform.manifest;

import java.util.List;

public record ManifestPatchResponse(int manifestRevision, Diff diff) {

    public record Diff(List<String> addedScopes, List<String> removedScopes, List<String> addedHosts) {
    }
}
