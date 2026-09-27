package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The body of a saved or an inline profile. Every field is optional except a saved profile's {@code name}.
 * {@code bindings} (resource to fixture dataset id) is honoured only for an inline profile; a saved profile binds
 * through {@code PUT /sandbox-profiles/{id}/bindings/{resource}}. On update, a null {@code fakeSecrets} keeps what is
 * stored and an empty map clears it.
 */
public record ProfileInput(String name, JsonNode input, GatePolicy gatePolicy, RunLlmMode llmMode, UUID replayRunId,
                           JsonNode llmMock, BigDecimal spendCap, Map<String, String> fakeSecrets,
                           Map<String, UUID> bindings) {
}
