package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.runtime.legacy-callback.enabled=false")
class LegacyCallbackFlagOffTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLauncher launcher;
    @Autowired
    FakeSandboxProvider sandbox;

    @Test
    void withTheFlagOffTheLauncherOnlyInjectsTheRuntimeEnvironment() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.PENDING, null, "placeholder");

        launcher.launch(run.getId());

        var env = sandbox.specForRun(run.getId()).envVars();
        assertThat(env).containsKeys("AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN", "AGENTSTORE_RUN_ID", "AGENTSTORE_MODE");
        assertThat(env).doesNotContainKeys("AGENT_RUN_ID", "AGENT_CALLBACK_URL", "AGENT_CALLBACK_TOKEN");
    }

    @Test
    void withTheFlagOffTheLegacyEndpointIsGone() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-flag-off", "tok-flag-off");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("tok-flag-off");
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = rest.postForEntity("http://localhost:" + port + "/internal/agent-callback/runs/" + run.getId() + "/events",
                new HttpEntity<>(Map.of("kind", "READ", "detail", Map.of()), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
