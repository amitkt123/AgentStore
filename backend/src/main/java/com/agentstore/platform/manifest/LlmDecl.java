package com.agentstore.platform.manifest;

import java.util.List;

/** The manifest's optional {@code llm} block. {@code provider} is null unless the mode is a {@code byok_*} one. */
public record LlmDecl(LlmMode mode, List<String> models, long maxTokensPerRun, LlmVendor provider) {
}
