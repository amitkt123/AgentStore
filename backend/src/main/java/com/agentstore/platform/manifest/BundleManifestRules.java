package com.agentstore.platform.manifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extra checks for a manifest that arrives inside a bundle. There, runtime and entrypoint are required; they stay
 * optional in the JSON schema because the rest of manifest v2 is WS1's.
 */
public final class BundleManifestRules {

    static final Pattern ENTRYPOINT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]*");

    private BundleManifestRules() {}

    public static List<String> check(Manifest manifest) {
        List<String> errors = new ArrayList<>();
        Optional<AgentRuntime> runtime = AgentRuntime.fromId(manifest.runtime());
        if (manifest.runtime() == null) {
            errors.add("runtime is required (python-3.12 or node-22)");
        } else if (runtime.isEmpty()) {
            errors.add("runtime must be python-3.12 or node-22");
        }
        String entrypoint = manifest.entrypoint();
        if (entrypoint == null || entrypoint.isBlank()) {
            errors.add("entrypoint is required");
        } else if (!ENTRYPOINT.matcher(entrypoint).matches() || entrypoint.contains("//")
                || Arrays.asList(entrypoint.split("/")).contains("..")) {
            errors.add("entrypoint must be a relative path inside the bundle (letters, digits, '_', '-', '.', '/')");
        } else if (runtime.isPresent() && !runtime.get().acceptsEntrypoint(entrypoint)) {
            errors.add("entrypoint " + entrypoint + " does not match runtime " + manifest.runtime());
        }
        return errors;
    }
}
