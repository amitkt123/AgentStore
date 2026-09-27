package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunReport;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunReportResult;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a code-review finding on WS6b: readiness only emitted UNDECLARED_ACCESS when
 * run_report.result was exactly ISSUES, so a run that had violations but then timed out (or otherwise failed) —
 * result TIMED_OUT/FAILED, issue_count > 0 — reported ready with no blocker for the violations.
 */
class ReadinessSurfacesIssuesOnAnyResultTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    SandboxFixtures fx;
    @Autowired
    RunReportRepository reports;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void aTimedOutRunWithIssuesStillBlocksReadiness() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.TIMED_OUT);
        reports.save(new RunReport(run.getId(), RunReportResult.TIMED_OUT, 2, "{}"));

        var response = rest.exchange(
                url("/api/v1/agents/" + dev.agent().getId() + "/versions/" + version.getId() + "/readiness"),
                HttpMethod.GET, new HttpEntity<>(fx.bearer(dev.org(), UserRole.DEVELOPER)), ReadinessResponse.class);

        assertThat(response.getBody().ready()).isFalse();
        assertThat(response.getBody().blockers()).hasSize(2)
                .allMatch(b -> b.code().equals("UNDECLARED_ACCESS") && b.runId().equals(run.getId()));
    }
}
