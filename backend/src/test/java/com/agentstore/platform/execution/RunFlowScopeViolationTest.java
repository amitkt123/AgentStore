package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a code-review finding on WS6b: a SCOPE_VIOLATION on a resource that IS declared in the
 * manifest (but not granted by the buyer) groups into that same declared block (same "read:<resource>" key) and
 * must show FAILED, not DONE — a denied access must never look like a success in /flow.
 */
class RunFlowScopeViolationTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunFlowService flowService;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunRepository runRepository;

    @Test
    void aViolationOnADeclaredButUngrantedScopeFailsThatDeclaredBlock() {
        var scenario = fixtures.liveWithManifest("valid-runtime-agent.json", List.of("bank_statement", "journal_entries"),
                new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "rt-" + UUID.randomUUID());
        run = runRepository.save(run);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", "invoices"));
        run = runRepository.findById(run.getId()).orElseThrow();

        FlowView flow = flowService.compute(run);

        assertThat(flow.blocks()).filteredOn(b -> b.block().equals("read:invoices"))
                .singleElement().satisfies(b -> {
                    assertThat(b.state()).isEqualTo(BlockState.FAILED);
                    assertThat(b.flag()).isNull();
                });
    }
}
