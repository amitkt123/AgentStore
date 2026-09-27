package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the API returns for a profile. Fake secret values are write-only: only their names are listed. */
public record ProfileView(UUID id, UUID agentId, String name, String description, JsonNode input, GatePolicy gatePolicy,
                          RunLlmMode llmMode, UUID replayRunId, JsonNode llmMock, BigDecimal spendCap,
                          List<String> fakeSecretNames, List<BindingView> bindings, boolean trialEnabled,
                          ProfileSource source, ProfileStatus status, Instant createdAt, Instant updatedAt) {

    public record BindingView(String resource, UUID datasetId, String displayName) {
    }
}
