package com.agentstore.platform.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-field rules the JSON schema cannot express. They apply only when the manifest declares {@code resources};
 * a manifest without them stays valid and untyped.
 */
public final class ResourceManifestRules {

    private ResourceManifestRules() {
    }

    public static List<String> check(Manifest manifest) {
        List<String> errors = new ArrayList<>();
        if (manifest.resources() == null) {
            return errors;
        }
        Map<String, ResourceDecl> byName = new LinkedHashMap<>();
        for (ResourceDecl resource : manifest.resources()) {
            if (byName.put(resource.name(), resource) != null) {
                errors.add("Resource name is declared twice: " + resource.name());
            }
        }
        for (Scope scope : manifest.scopes()) {
            ResourceDecl resource = byName.get(scope.resource());
            if (resource == null) {
                errors.add("Scope " + scope.type() + " '" + scope.resource()
                        + "' names a resource that is not declared in resources");
            } else if (resource.format() == DataFormat.PDF && scope.type() == ScopeType.WRITE) {
                errors.add("Resource '" + resource.name() + "' is a pdf and can only be READ");
            }
        }
        return errors;
    }
}
