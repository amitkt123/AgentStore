package com.agentstore.platform.manifest;

import java.util.List;

/** {@code runtime} and {@code entrypoint} are required for bundles (BundleManifestRules) and null otherwise. */
public record Manifest(String name, String version, List<Scope> scopes, List<String> networkAllowlist,
                        CostEnvelope costEnvelope, List<ApprovalGateDecl> approvalGates,
                        List<String> dataClasses, String runtime, String entrypoint) {
}
