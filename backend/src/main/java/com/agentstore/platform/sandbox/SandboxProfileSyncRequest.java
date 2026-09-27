package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * One {@code fixtures/profiles/<name>.yaml} file, as the CLI sends it after {@code agentstore push} finalizes.
 * {@code trialEnabled} is deliberately not a field here — attestation names a specific person and a repo sync has
 * none; a REPO-sourced profile is always created with {@code trial_enabled = false} (see {@link SandboxProfileSyncService}).
 */
public record SandboxProfileSyncRequest(String name, String description, JsonNode input, GatePolicy gatePolicy,
                                        RunLlmMode llmMode, BigDecimal spendCap, List<BindingEntry> bindings,
                                        String contentHash) {

    /** {@code fixture} is a dataset's {@code displayName} among the org's developer fixtures, resolved server-side. */
    public record BindingEntry(String resource, String fixture) {
    }
}
