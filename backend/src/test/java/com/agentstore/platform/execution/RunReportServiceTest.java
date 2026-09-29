package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunReportServiceTest extends AbstractIntegrationTest {

    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    RuntimeFixtures runtimeFixtures;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    RunReportRepository reports;

    @Test
    void aCleanSandboxRunGetsACleanReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.CLEAN);
        assertThat(report.getIssueCount()).isZero();
        assertThat(report.getReportJson()).contains("\"declaredVsActual\"");
    }

    @Test
    void blockedNetworkAttemptsAreAggregatedPerHostInTheReport() throws Exception {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordNetworkEvents(run.getId(), java.util.List.of(
                Map.of("host", "evil.example", "count", 3), Map.of("host", "evil.example", "count", 2),
                Map.of("host", "other.example", "count", 1)));
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        var network = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(reports.findById(run.getId()).orElseThrow().getReportJson()).get("network");
        assertThat(network.get("blocked")).hasSize(2);
        assertThat(network.get("blocked").toString()).contains("{\"host\":\"evil.example\",\"count\":5}")
                .contains("{\"host\":\"other.example\",\"count\":1}");
    }

    @Test
    void violationsProduceAnIssuesReportWithTheViolationCount() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordViolation(run.getId(), Map.of("summary", "x", "attempted", "read", "target", "payroll"));
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.ISSUES);
        assertThat(report.getIssueCount()).isEqualTo(1);
    }

    @Test
    void hittingTheCostCapProducesAnOverCostLimitReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.recordUsage(run.getId(), new BigDecimal("1000"), Map.of("quantity", "1000", "unit", "reconciled_transaction"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.OVER_COST_LIMIT);
    }

    @Test
    void aTimedOutRunProducesATimedOutReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.terminate(run.getId(), RunStatus.RUNNING, RunStatus.TIMED_OUT, TerminalReason.TIMED_OUT, RunStepKind.TIMEOUT,
                Map.of("summary", "no heartbeat"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.TIMED_OUT);
    }

    @Test
    void anOtherwiseFailedRunProducesAFailedReport() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        lifecycle.fail(run.getId(), Map.of("summary", "crashed"));

        RunReport report = reports.findById(run.getId()).orElseThrow();
        assertThat(report.getResult()).isEqualTo(RunReportResult.FAILED);
    }

    @Test
    void aProductionRunGetsNoReport() {
        var agent = runtimeFixtures.runningAgent(new BigDecimal("500.00"));

        lifecycle.complete(agent.run().getId(), Map.of("summary", "done"));

        assertThat(reports.findById(agent.run().getId())).isEmpty();
    }
}
