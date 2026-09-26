package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyEnvVarsTest extends AbstractIntegrationTest {

    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    RunLauncher launcher;
    @Autowired
    FakeSandboxProvider sandbox;

    @Test
    void aLaunchedRunGetsBothTheRuntimeAndTheLegacyEnvironment() {
        var scenario = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run run = fixtures.run(scenario, RunStatus.PENDING, null, "placeholder");

        launcher.launch(run.getId());

        var env = sandbox.specForRun(run.getId()).envVars();
        assertThat(env).containsEntry("AGENTSTORE_RUN_ID", run.getId().toString())
                .containsEntry("AGENTSTORE_MODE", "PRODUCTION")
                .containsKeys("AGENTSTORE_API_URL", "AGENTSTORE_RUN_TOKEN")
                .containsEntry("AGENT_RUN_ID", run.getId().toString())
                .containsKeys("AGENT_CALLBACK_URL", "AGENT_CALLBACK_TOKEN");
        assertThat(env.get("AGENTSTORE_RUN_TOKEN")).isEqualTo(env.get("AGENT_CALLBACK_TOKEN"));   // one token, two names
        assertThat(env.get("AGENT_CALLBACK_URL")).startsWith(env.get("AGENTSTORE_API_URL") + "/internal/agent-callback/runs/");
    }
}
