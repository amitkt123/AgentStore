package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a code-review finding on WS6b: RunReportService zeroed issueCount for any result other than
 * ISSUES, so a run that had violations but then timed out (or otherwise failed) reported issueCount = 0 — losing
 * the violation record readiness needs to surface UNDECLARED_ACCESS for.
 */
class RunReportIssueCountOnFailureTest extends AbstractIntegrationTest {

    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunReportRepository reports;

    @Test
    void aTimedOutRunWithPriorViolationsKeepsTheirCount() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", "payroll"));
        lifecycle.terminate(run.getId(), RunStatus.RUNNING, RunStatus.TIMED_OUT, RunStepKind.TIMEOUT,
                Map.of("summary", "no heartbeat"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.TIMED_OUT);
        assertThat(report.getIssueCount()).isEqualTo(1);
    }
}
