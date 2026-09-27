package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ScopeType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContextResponse(UUID runId, RunMode mode, AgentVersionInfo agentVersion, Map<String, Object> input,
                              List<GrantedScope> grantedScopes, Limits limits) {

    public record AgentVersionInfo(UUID id, String versionNumber) {}

    public record GrantedScope(ScopeType type, String resource, boolean requiresApproval) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Limits(Instant deadlineAt, BigDecimal costCapRemaining, String currency, Llm llm) {}

    /** Present when the manifest declares an llm block: how the agent reaches an LLM and how much token budget is left. */
    public record Llm(String mode, List<String> models, long tokensRemaining) {}
}
