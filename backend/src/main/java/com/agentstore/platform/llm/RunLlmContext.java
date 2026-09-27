package com.agentstore.platform.llm;

import com.agentstore.platform.execution.RunLlmMode;

import java.util.UUID;

/** What the gateway needs from the run row and its agent: whose key, and how the calls are served. */
public record RunLlmContext(UUID developerOrgId, RunLlmMode llmMode, UUID replayRunId, String llmMockJson) {
}
