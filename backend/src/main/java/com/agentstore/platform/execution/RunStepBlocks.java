package com.agentstore.platform.execution;

import java.util.Map;

/**
 * The manifest-facing shape of a run_step, derived from its kind and the fields its own recording call site already
 * puts in {@code detail} (spec §3). {@code COST_CAP_REACHED}'s block is null on purpose — see
 * {@code RunFlowService}, which still surfaces it as a FAILED {@code charge} block by checking for the step's
 * existence directly, not through this mapping.
 */
final class RunStepBlocks {

    private RunStepBlocks() {
    }

    static String of(RunStepKind kind, Map<String, Object> detail) {
        return switch (kind) {
            case DATA_READ -> "read:" + detail.get("resource");
            case DATA_WRITE -> "write:" + detail.get("resource");
            case WRITE_STAGED -> "draft:" + detail.get("resource");
            case GATE_REQUESTED, GATE_DECIDED -> "gate:" + detail.get("action");
            case LLM_CALL -> "llm";
            case USAGE -> "charge";
            case SCOPE_VIOLATION -> detail.get("attempted") + ":" + detail.get("target");
            default -> null;
        };
    }
}
