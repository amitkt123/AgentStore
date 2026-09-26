package com.agentstore.platform.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Runtimes a bundle can declare. Must match the runner's AgentRuntime ids (spec §4.2). */
public enum AgentRuntime {
    PYTHON_3_12("python-3.12", "python", Set.of(".py")),
    NODE_22("node-22", "node", Set.of(".js", ".mjs", ".cjs"));

    private final String id;
    private final String interpreter;
    private final Set<String> extensions;

    AgentRuntime(String id, String interpreter, Set<String> extensions) {
        this.id = id;
        this.interpreter = interpreter;
        this.extensions = extensions;
    }

    public String id() { return id; }

    public static Optional<AgentRuntime> fromId(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
    }

    public boolean acceptsEntrypoint(String entrypoint) {
        return extensions.stream().anyMatch(entrypoint::endsWith);
    }

    /**
     * The sandbox command: run from the app root, as the build's smoke test did. The entrypoint has already passed
     * BundleManifestRules (no quotes or spaces), so single-quoting it is safe.
     */
    public List<String> runCommand(String entrypoint) {
        return List.of("sh", "-c", "cd /workspace/app && exec " + interpreter + " '" + entrypoint + "'");
    }
}
