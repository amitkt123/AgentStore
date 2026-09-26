package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The run an authenticated runtime call belongs to, with what it may touch. */
public record RunPrincipal(UUID runId, UUID orgId, RunMode mode, Manifest manifest, Set<String> grantedScopes) {

    /**
     * A scope is granted when the manifest declares it and the buyer's connection lists its resource in
     * {@code grantedScopes} (buyers grant resource names, not READ/WRITE pairs).
     */
    public static RunPrincipal forRun(Run run, ManifestParser parser) {
        Manifest manifest = parser.parse(run.getAgentVersion().getManifestJson());
        Set<String> buyerGranted = new HashSet<>(run.getConnection().getGrantedScopes());
        Set<String> granted = manifest.scopes().stream()
                .filter(s -> buyerGranted.contains(s.resource()))
                .map(s -> scopeKey(s.type(), s.resource()))
                .collect(Collectors.toSet());
        return new RunPrincipal(run.getId(), run.getConnection().getBuyerOrg().getId(), run.getMode(), manifest, granted);
    }

    public static RunPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (RunPrincipal) authentication.getPrincipal();
    }

    public static String scopeKey(ScopeType type, String resource) {
        return type + ":" + resource;
    }

    public boolean granted(ScopeType type, String resource) {
        return grantedScopes.contains(scopeKey(type, resource));
    }
}
