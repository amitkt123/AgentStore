package com.agentstore.platform.execution;

import java.util.Map;

/** One manifest-declared or undeclared block in a run's flow (spec §4). {@code flag} is "UNDECLARED" or null. */
public record FlowBlock(String block, BlockState state, Map<String, Object> metrics, String gateId, String flag) {

    static FlowBlock declared(String block, BlockState state, Map<String, Object> metrics, String gateId) {
        return new FlowBlock(block, state, metrics, gateId, null);
    }

    static FlowBlock undeclared(String block, BlockState state, Map<String, Object> metrics) {
        return new FlowBlock(block, state, metrics, null, "UNDECLARED");
    }
}
