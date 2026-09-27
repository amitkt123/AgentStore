package com.agentstore.platform.manifest;

import java.util.ArrayList;
import java.util.List;

/** Cross-field {@code llm} rules the JSON schema cannot express. They apply only when the manifest declares {@code llm}. */
public final class LlmManifestRules {

    private LlmManifestRules() {
    }

    public static List<String> check(Manifest manifest, long maxTokensPerRunLimit) {
        List<String> errors = new ArrayList<>();
        LlmDecl llm = manifest.llm();
        if (llm == null) {
            return errors;
        }
        if (llm.maxTokensPerRun() > maxTokensPerRunLimit) {
            errors.add("llm.maxTokensPerRun must be at most " + maxTokensPerRunLimit);
        }
        if (llm.mode() == LlmMode.BYOK_DIRECT && llm.provider() != null
                && !manifest.networkAllowlist().contains(llm.provider().apiHost())) {
            errors.add("llm.mode byok_direct requires " + llm.provider().apiHost() + " in network.allowlist");
        }
        return errors;
    }
}
