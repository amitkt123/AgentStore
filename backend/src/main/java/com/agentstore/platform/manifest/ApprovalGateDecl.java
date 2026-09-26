package com.agentstore.platform.manifest;

public record ApprovalGateDecl(String trigger, String description, int timeoutMinutes) {
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;

    public ApprovalGateDecl(String trigger, String description) {
        this(trigger, description, DEFAULT_TIMEOUT_MINUTES);
    }
}
