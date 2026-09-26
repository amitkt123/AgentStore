package com.agentstore.platform.data;

import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;

/** Resolves a resource name against an agent version's manifest for the buyer-side check and bind. */
final class ResourceLookup {

    private ResourceLookup() {
    }

    /** The declaration to check against, or null when the manifest leaves the resource untyped. */
    static ResourceDecl requireReadable(Manifest manifest, String resource) {
        boolean readable = manifest.scopes().stream()
                .anyMatch(s -> s.type() == ScopeType.READ && s.resource().equals(resource));
        if (!readable) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_NOT_BINDABLE",
                    "'" + resource + "' is not a resource this agent reads, so no file can be bound to it");
        }
        return manifest.resource(resource).orElse(null);
    }
}
