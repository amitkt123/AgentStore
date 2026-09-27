package com.agentstore.platform.manifest;

import java.util.List;
import java.util.Optional;

/**
 * {@code runtime} and {@code entrypoint} are required for bundles (BundleManifestRules) and null otherwise.
 * {@code resources} is null when the manifest has no {@code resources} key (untyped resources), never an empty
 * stand-in; {@code llm} is null when the manifest has no {@code llm} key (its agents cannot use the gateway).
 */
public record Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                        CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                        List<String> dataClasses, String runtime, String entrypoint, List<ResourceDecl> resources,
                        LlmDecl llm) {

    public Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                    CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                    List<String> dataClasses, String runtime, String entrypoint) {
        this(name, version, scopes, networkAllowlist, costEnvelope, approvalGates, dataClasses, runtime, entrypoint,
                null, null);
    }

    public Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                    CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                    List<String> dataClasses, String runtime, String entrypoint, List<ResourceDecl> resources) {
        this(name, version, scopes, networkAllowlist, costEnvelope, approvalGates, dataClasses, runtime, entrypoint,
                resources, null);
    }

    public boolean declaresResources() {
        return resources != null;
    }

    public Optional<ResourceDecl> resource(String resourceName) {
        return resources == null ? Optional.empty()
                : resources.stream().filter(r -> r.name().equals(resourceName)).findFirst();
    }
}
