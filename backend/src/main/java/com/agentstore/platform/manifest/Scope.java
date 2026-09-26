package com.agentstore.platform.manifest;

public record Scope(ScopeType type, String resource, String description, boolean requiresApproval) {
    public Scope(ScopeType type, String resource, String description) {
        this(type, resource, description, false);
    }
}
