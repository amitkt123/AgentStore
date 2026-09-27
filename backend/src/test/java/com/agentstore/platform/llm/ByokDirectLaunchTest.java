package com.agentstore.platform.llm;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.FakeSandboxProvider;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLauncher;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStep;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.execution.SecretService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByokDirectLaunchTest extends AbstractIntegrationTest {

    static final String DEV_KEY = "sk-dev-direct-key-0001";

    @Autowired LlmFixtures fx;
    @Autowired ExecutionFixtures executionFixtures;
    @Autowired RunLauncher launcher;
    @Autowired FakeSandboxProvider sandbox;
    @Autowired ByokKeyService keys;
    @Autowired SecretService secrets;
    @Autowired RunRepository runs;
    @Autowired RunStepRepository steps;

    ExecutionFixtures.Scenario scenario(String manifest) {
        return executionFixtures.liveWithManifest(manifest, List.of("bank_statement"), new BigDecimal("500.00"),
                new BigDecimal("1000.00"));
    }

    /** A PENDING run, the way the trigger leaves it just before the launcher starts the sandbox. */
    Run pendingRun(ExecutionFixtures.Scenario scenario) {
        return executionFixtures.run(scenario, RunStatus.PENDING, null, "tok-" + UUID.randomUUID());
    }

    Org developerOf(ExecutionFixtures.Scenario scenario) {
        return scenario.version().getAgent().getDeveloperOrg();
    }

    @Test
    void aByokDirectRunGetsTheDevelopersKeyInItsEnvironment() {
        var scenario = scenario("valid-llm-byok-direct.json");
        fx.verifyDeveloperOrg(developerOf(scenario));
        keys.put(developerOf(scenario).getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        Run run = pendingRun(scenario);

        launcher.launch(run.getId());

        var env = sandbox.specForRun(run.getId()).envVars();
        assertThat(env).containsEntry("AGENT_SECRET_ANTHROPIC_API_KEY", DEV_KEY);
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void aBuyerSecretWithTheSameNameCannotReplaceTheDevelopersKey() {
        var scenario = scenario("valid-llm-byok-direct.json");
        fx.verifyDeveloperOrg(developerOf(scenario));
        keys.put(developerOf(scenario).getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        secrets.store(scenario.connection().getId(), "ANTHROPIC_API_KEY", "buyer-supplied-value");
        Run run = pendingRun(scenario);

        launcher.launch(run.getId());

        assertThat(sandbox.specForRun(run.getId()).envVars()).containsEntry("AGENT_SECRET_ANTHROPIC_API_KEY", DEV_KEY);
    }

    @Test
    void withoutAStoredKeyTheLaunchFailsWithLlmKeyMissingAndNoSandboxIsCreated() {
        var scenario = scenario("valid-llm-byok-direct.json");
        Run run = pendingRun(scenario);

        assertThatThrownBy(() -> launcher.launch(run.getId())).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
            assertThat(e.getReason()).isEqualTo("LLM_KEY_MISSING");
        });

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        RunStep failed = steps.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                .filter(s -> s.getKind() == RunStepKind.FAILED).findFirst().orElseThrow();
        assertThat(failed.getDetail()).contains("LLM_KEY_MISSING");
        assertThatThrownBy(() -> sandbox.specForRun(run.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void platformAndProxyRunsGetNoLlmKeyInTheirEnvironment() {
        var platform = scenario("valid-llm-platform.json");
        var proxy = scenario("valid-llm-byok-proxy.json");
        fx.verifyDeveloperOrg(developerOf(proxy));
        keys.put(developerOf(proxy).getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        Run platformRun = pendingRun(platform);
        Run proxyRun = pendingRun(proxy);

        launcher.launch(platformRun.getId());
        launcher.launch(proxyRun.getId());

        for (Run run : List.of(platformRun, proxyRun)) {
            var env = sandbox.specForRun(run.getId()).envVars();
            assertThat(env.keySet()).noneMatch(k -> k.startsWith("AGENT_SECRET_ANTHROPIC") || k.startsWith("AGENT_SECRET_OPENAI"));
            assertThat(env.values()).doesNotContain(DEV_KEY);
        }
    }
}
