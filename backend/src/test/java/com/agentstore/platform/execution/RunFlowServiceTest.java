package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RunFlowServiceTest extends AbstractIntegrationTest {

    @Autowired
    RunFlowService flowService;
    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;

    @Test
    void aFreshRunHasEveryDeclaredBlockIdle() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).extracting(FlowBlock::block, FlowBlock::state).containsExactlyInAnyOrder(
                tuple("read:bank_statement", BlockState.IDLE),
                tuple("read:invoices", BlockState.IDLE),
                tuple("draft:journal_entries", BlockState.IDLE),
                tuple("gate:post_journal_entries", BlockState.IDLE),
                tuple("charge", BlockState.IDLE));
        assertThat(flow.reachedBlock()).isNull();
    }

    @Test
    void aDecidedGateIsDoneAndAnOpenGateIsWaitingWithItsGateId() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordStep(run.getId(), RunStepKind.GATE_REQUESTED, RunStepSource.PLATFORM,
                Map.of("gateId", "11111111-1111-1111-1111-111111111111", "action", "post_journal_entries"));

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("gate:post_journal_entries"))
                .singleElement().satisfies(b -> {
                    assertThat(b.state()).isEqualTo(BlockState.WAITING);
                    assertThat(b.gateId()).isEqualTo("11111111-1111-1111-1111-111111111111");
                });
        assertThat(flow.reachedBlock()).isEqualTo("gate:post_journal_entries");
    }

    @Test
    void anUndeclaredReadAppearsAsAFailedFlaggedExtra() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", "vendor_master"));

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("read:vendor_master"))
                .singleElement().satisfies(b -> {
                    assertThat(b.state()).isEqualTo(BlockState.FAILED);
                    assertThat(b.flag()).isEqualTo("UNDECLARED");
                });
    }

    @Test
    void unreachedBlocksAreSkippedOnceTheRunIsTerminal() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.complete(run.getId(), Map.of("summary", "done"));
        run = runRepository.findById(run.getId()).orElseThrow();

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("draft:journal_entries"))
                .singleElement().extracting(FlowBlock::state).isEqualTo(BlockState.SKIPPED);
    }

    @Test
    void aCostCapReachedStepFailsTheChargeBlockEvenThoughItsOwnBlockColumnIsNull() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordUsage(run.getId(), new BigDecimal("1000"), Map.of("quantity", "1000", "unit", "reconciled_transaction"));
        run = runRepository.findById(run.getId()).orElseThrow();

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("charge"))
                .singleElement().extracting(FlowBlock::state).isEqualTo(BlockState.FAILED);
    }
}
