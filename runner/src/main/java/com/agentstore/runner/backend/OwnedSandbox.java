package com.agentstore.runner.backend;

import java.util.Map;

public record OwnedSandbox(String sandboxId, Map<String, String> labels) {
    public String requestId() { return labels.get(Labels.REQUEST_ID); }
    public Long deadlineEpochSeconds() {
        String v = labels.get(Labels.DEADLINE);
        return v == null ? null : Long.valueOf(v);
    }
}
