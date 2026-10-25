package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a code-review finding on WS6b: run_step.block was VARCHAR(40), but manifest resource/gate
 * names and an undeclared violation's target (attacker-controlled) have no such length cap, so a long name
 * previously threw DataIntegrityViolationException on flush instead of being recorded.
 */
class RunStepBlockLengthTest extends AbstractIntegrationTest {

    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunStepRepository stepRepository;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunRepository runRepository;
    @Autowired
    GateService gateService;

    Run productionRun() {
        var scenario = fixtures.liveTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"));
        return fixtures.run(scenario, RunStatus.RUNNING, null, "tok-" + java.util.UUID.randomUUID());
    }

    @Test
    void aLongUndeclaredViolationTargetIsRecordedNotRejected() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);
        String longTarget = "a".repeat(60);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", longTarget));

        var steps = stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId());
        assertThat(steps).singleElement().satisfies(s -> {
            assertThat(s.getKind()).isEqualTo(RunStepKind.SCOPE_VIOLATION);
            assertThat(s.getBlock()).isEqualTo("read:" + longTarget);
        });
    }

    @Test
    void aLongBlockNameIsCopiedToReachedBlock() {
        Run run = productionRun();
        String target = "a".repeat(200);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", target));

        Run after = runRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getReachedBlock()).isEqualTo("read:" + target);
        assertThat(after.getViolationCount()).isEqualTo(1);
    }

    @Test
    void threeLongViolationsStillStopAProductionRun() {
        Run run = productionRun();
        Map<String, Object> detail = Map.of("summary", "x", "attempted", "read", "target", "a".repeat(200));

        assertThat(lifecycle.recordViolation(run.getId(), detail)).isFalse();
        assertThat(lifecycle.recordViolation(run.getId(), detail)).isFalse();
        assertThat(lifecycle.recordViolation(run.getId(), detail)).isTrue();

        assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void aGateWithAHundredCharTriggerOpens() {
        Run run = productionRun();
        String action = "g".repeat(100);

        gateService.open(run.getId(), action, 30, "s", "{}", "[]");

        // GateService records the block on the GATE_REQUESTED step; it does not touch run.reached_block.
        assertThat(stepRepository.findByRunIdOrderByCreatedAtAsc(run.getId()))
                .anySatisfy(s -> assertThat(s.getBlock()).isEqualTo("gate:" + action));
    }
}
