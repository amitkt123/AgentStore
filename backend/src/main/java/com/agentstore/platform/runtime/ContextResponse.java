package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ScopeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ContextResponse(UUID runId, RunMode mode, AgentVersionInfo agentVersion, Map<String, Object> input,
                              List<GrantedScope> grantedScopes, Limits limits) {

    public record AgentVersionInfo(UUID id, String versionNumber) {}

    public record GrantedScope(ScopeType type, String resource, boolean requiresApproval) {}

    public record Limits(Instant deadlineAt, BigDecimal costCapRemaining, String currency) {}
}
