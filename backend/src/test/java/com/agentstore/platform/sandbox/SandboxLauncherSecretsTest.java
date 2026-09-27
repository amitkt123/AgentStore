package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.FakeSandboxProvider;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLauncher;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepRepository;
import com.agentstore.platform.llm.ByokKeyService;
import com.agentstore.platform.manifest.LlmVendor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxLauncherSecretsTest extends AbstractIntegrationTest {

    static final String DEV_KEY = "sk-dev-direct-key-0002";

    @Autowired SandboxFixtures fx;
    @Autowired SandboxProfileRepository profiles;
    @Autowired FakeSecrets fakeSecrets;
    @Autowired RunLauncher launcher;
    @Autowired FakeSandboxProvider sandbox;
    @Autowired RunRepository runs;
    @Autowired RunStepRepository steps;
    @Autowired ByokKeyService keys;

    Run pendingRun(SandboxFixtures.Dev dev, Map<String, String> secrets) {
        SandboxProfile profile = fx.profile(dev, "p-" + System.nanoTime());
        fakeSecrets.store(profile, secrets);
        profiles.save(profile);
        return fx.sandboxRun(dev, profile, RunStatus.PENDING);
    }

    @Test
    void aSandboxRunGetsTheProfilesFakeSecretsInItsEnvironmentAndNowhereElse() {
        var dev = fx.developer("valid-tier-a.json");
        Run run = pendingRun(dev, Map.of("ACME_TOKEN", "fake-123"));

        launcher.launch(run.getId());

        var spec = sandbox.specForRun(run.getId());
        assertThat(spec.envVars()).containsEntry("AGENT_SECRET_ACME_TOKEN", "fake-123")
                .containsEntry("AGENTSTORE_MODE", "SANDBOX");
        assertThat(spec.toString()).doesNotContain("fake-123");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(steps.findByRunIdOrderByCreatedAtAsc(run.getId())).noneMatch(s -> s.getDetail().contains("fake-123"));
    }

    @Test
    void aByokDirectSandboxRunGetsTheDevelopersRealKey() {
        var dev = fx.developer("valid-llm-byok-direct.json");
        fx.verify(dev.org());
        keys.put(dev.org().getId(), LlmVendor.ANTHROPIC, DEV_KEY);
        Run run = pendingRun(dev, Map.of());

        launcher.launch(run.getId());

        assertThat(sandbox.specForRun(run.getId()).envVars()).containsEntry("AGENT_SECRET_ANTHROPIC_API_KEY", DEV_KEY);
    }

    @Test
    void aFakeSecretOfTheSameNameReplacesTheRealKeyAndNoRealKeyIsNeeded() {
        var dev = fx.developer("valid-llm-byok-direct.json");   // no real key stored for this org
        Run run = pendingRun(dev, Map.of("ANTHROPIC_API_KEY", "fake-key"));

        launcher.launch(run.getId());

        assertThat(sandbox.specForRun(run.getId()).envVars()).containsEntry("AGENT_SECRET_ANTHROPIC_API_KEY", "fake-key");
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
    }
}
