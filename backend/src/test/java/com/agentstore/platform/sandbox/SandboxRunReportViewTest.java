package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxRunReportViewTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    SandboxFixtures fx;
    @Autowired
    RunLifecycleService lifecycle;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aFinishedSandboxRunExposesItsBehaviourReport() {
        var dev = fx.developer("valid-runtime-agent.json");
        Run run = fx.sandboxRun(dev, null, RunStatus.RUNNING);
        lifecycle.complete(run.getId(), Map.of("summary", "done"));

        var response = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) response.getBody().get("report")).get("result")).isEqualTo("CLEAN");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aRunningSandboxRunHasNoReportYet() {
        var dev = fx.developer("valid-runtime-agent.json");
        Run run = fx.sandboxRun(dev, null, RunStatus.RUNNING);

        var response = rest.exchange(url("/api/v1/sandbox/runs/" + run.getId()), HttpMethod.GET,
                new HttpEntity<>(fx.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);

        assertThat(response.getBody().get("report")).isNull();
    }
}
