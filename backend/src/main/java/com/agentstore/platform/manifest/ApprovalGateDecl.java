package com.agentstore.platform.manifest;

import java.util.List;

public record ApprovalGateDecl(String trigger, String description, int timeoutMinutes, Preview preview) {
    public static final int DEFAULT_TIMEOUT_MINUTES = 30;

    /** Optional: which columns a gate preview shows and which numeric column it totals. */
    public record Preview(List<String> columns, String totalField) {
    }

    public ApprovalGateDecl(String trigger, String description, int timeoutMinutes) {
        this(trigger, description, timeoutMinutes, null);
    }

    public ApprovalGateDecl(String trigger, String description) {
        this(trigger, description, DEFAULT_TIMEOUT_MINUTES, null);
    }
}
