package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
