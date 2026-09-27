package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunStepBlocksTest {

    @Test
    void dataReadBlockIncludesTheResource() {
        assertThat(RunStepBlocks.of(RunStepKind.DATA_READ, Map.of("resource", "bank_statement")))
                .isEqualTo("read:bank_statement");
    }

    @Test
    void dataWriteBlockIncludesTheResource() {
        assertThat(RunStepBlocks.of(RunStepKind.DATA_WRITE, Map.of("resource", "journal_entries")))
                .isEqualTo("write:journal_entries");
    }

    @Test
    void writeStagedBlockIsADraft() {
        assertThat(RunStepBlocks.of(RunStepKind.WRITE_STAGED, Map.of("resource", "journal_entries")))
                .isEqualTo("draft:journal_entries");
    }

    @Test
    void gateRequestedBlockIncludesTheAction() {
        assertThat(RunStepBlocks.of(RunStepKind.GATE_REQUESTED, Map.of("action", "post_journal_entries")))
                .isEqualTo("gate:post_journal_entries");
    }

    @Test
    void gateDecidedBlockIncludesTheAction() {
        assertThat(RunStepBlocks.of(RunStepKind.GATE_DECIDED, Map.of("action", "post_journal_entries")))
                .isEqualTo("gate:post_journal_entries");
    }

    @Test
    void llmCallBlockIsAlwaysLlm() {
        assertThat(RunStepBlocks.of(RunStepKind.LLM_CALL, Map.of("tokens", 100))).isEqualTo("llm");
    }

    @Test
    void usageBlockIsAlwaysCharge() {
        assertThat(RunStepBlocks.of(RunStepKind.USAGE, Map.of("quantity", "2"))).isEqualTo("charge");
    }

    @Test
    void scopeViolationBlockMirrorsTheAttemptedAccess() {
        assertThat(RunStepBlocks.of(RunStepKind.SCOPE_VIOLATION, Map.of("attempted", "read", "target", "vendor_master")))
                .isEqualTo("read:vendor_master");
        assertThat(RunStepBlocks.of(RunStepKind.SCOPE_VIOLATION, Map.of("attempted", "gate", "target", "post_journal_entries")))
                .isEqualTo("gate:post_journal_entries");
    }

    @Test
    void agentLogTimeoutCostCapReachedAndCancelledHaveNoBlock() {
        assertThat(RunStepBlocks.of(RunStepKind.AGENT_LOG, Map.of())).isNull();
        assertThat(RunStepBlocks.of(RunStepKind.TIMEOUT, Map.of())).isNull();
        assertThat(RunStepBlocks.of(RunStepKind.COST_CAP_REACHED, Map.of())).isNull();
        assertThat(RunStepBlocks.of(RunStepKind.CANCELLED, Map.of())).isNull();
    }
}
