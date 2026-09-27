package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunReport;
import com.agentstore.platform.execution.RunReportRepository;
import com.agentstore.platform.execution.RunReportResult;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ReadinessControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    SandboxFixtures fx;
    @Autowired
    RunReportRepository reports;
    @Autowired
    OrgRepository orgRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ReadinessResponse readiness(com.agentstore.platform.catalog.Agent agent, AgentVersion version, Org caller) {
        var response = rest.exchange(
                url("/api/v1/agents/" + agent.getId() + "/versions/" + version.getId() + "/readiness"),
                HttpMethod.GET, new HttpEntity<>(fx.bearer(caller, UserRole.DEVELOPER)), ReadinessResponse.class);
        return response.getBody();
    }

    @Test
    void anUnbuiltDraftVersionIsNotReady() {
        var dev = fx.developer("valid-runtime-agent.json");

        ReadinessResponse body = readiness(dev.agent(), dev.version(), dev.org());

        assertThat(body.ready()).isFalse();
        assertThat(body.blockers()).extracting(ReadinessResponse.Blocker::code).containsExactly("BUILD_NOT_READY");
    }

    @Test
    void aBuiltVersionWithNoSandboxRunsIsReady() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.ready()).isTrue();
        assertThat(body.blockers()).isEmpty();
    }

    @Test
    void aBuiltVersionWithACleanLatestReportIsReady() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.COMPLETED);
        reports.save(new RunReport(run.getId(), RunReportResult.CLEAN, 0, "{}"));

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.ready()).isTrue();
    }

    @Test
    void issuesInTheLatestReportBecomeOneBlockerPerViolation() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.COMPLETED);
        reports.save(new RunReport(run.getId(), RunReportResult.ISSUES, 2, "{}"));

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.ready()).isFalse();
        assertThat(body.blockers()).hasSize(2)
                .allMatch(b -> b.code().equals("UNDECLARED_ACCESS") && b.runId().equals(run.getId()));
    }

    @Test
    void overCostLimitInTheLatestReportBlocksReadiness() {
        var dev = fx.developer("valid-runtime-agent.json");
        var version = fx.versionNeedingRebuild(dev.agent(), "1.0.1", "valid-runtime-agent.json", "e2b");
        Run run = fx.sandboxRun(dev, version, null, RunStatus.FAILED);
        reports.save(new RunReport(run.getId(), RunReportResult.OVER_COST_LIMIT, 0, "{}"));

        ReadinessResponse body = readiness(dev.agent(), version, dev.org());

        assertThat(body.blockers()).extracting(ReadinessResponse.Blocker::code).containsExactly("COST_ENVELOPE_EXCEEDED");
    }

    @Test
    void aNonOwningDeveloperCannotSeeReadiness() {
        var dev = fx.developer("valid-runtime-agent.json");
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));

        var response = rest.exchange(
                url("/api/v1/agents/" + dev.agent().getId() + "/versions/" + dev.version().getId() + "/readiness"),
                HttpMethod.GET, new HttpEntity<>(fx.bearer(intruder, UserRole.DEVELOPER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
