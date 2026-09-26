package com.agentstore.platform.runtime;

import java.util.Map;

/** What WS3 (the LLM gateway) implements. */
public interface LlmBackend {
    Map<String, Object> chat(RunPrincipal principal, Map<String, Object> request);
}
