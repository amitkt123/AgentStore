package com.agentstore.platform.sandbox;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.llm.AbstractLlmIntegrationTest;
import com.agentstore.platform.llm.LlmFixtures;
import com.agentstore.platform.llm.StubProviderServer.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The replay-ownership check WS3 left to WS6 (spec §3.1 step 5). */
class SandboxReplayOwnershipTest extends AbstractLlmIntegrationTest {

    @Autowired SandboxFixtures sandboxFx;
    @Autowired SandboxRunService service;
    @Autowired SandboxProfileRepository profiles;
    @Autowired com.agentstore.platform.execution.RunRepository runs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** A SANDBOX run of the given developer/agent, with one recorded OK call for "same". */
    LlmFixtures.LlmAgent sandboxSourceWithRecording(SandboxFixtures.Dev dev, com.agentstore.platform.catalog.AgentVersion version) {
        var scenario = new com.agentstore.platform.execution.ExecutionFixtures.Scenario(dev.org(), version, null);
        String token = "rt-" + UUID.randomUUID();
        var run = new com.agentstore.platform.execution.Run(null, null, version, RunStatus.RUNNING,
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, "sbx-" + UUID.randomUUID(),
                com.agentstore.platform.execution.RunTokenService.hash(token));
        run.setMode(RunMode.SANDBOX);
        run.setInitiatedByOrgId(dev.org().getId());
        run.setDeadlineAt(java.time.Instant.now().plusSeconds(600));
        run = runs.save(run);
        STUB.enqueue(Reply.anthropicText("recorded", 10, 5));
        chat(token, request(LlmFixtures.CLAUDE, "same", 100));
        // Terminal, like a real source run would end up: it must not also occupy the org's concurrency slot.
        jdbc.update("UPDATE run SET status = 'COMPLETED', completed_at = now() WHERE id = ?", run.getId());
        return new LlmFixtures.LlmAgent(new com.agentstore.platform.execution.ExecutionFixtures.Scenario(dev.org(), version, null), run, token);
    }

    @Test
    void aRunReplayingAValidSourceStartsFine() {
        var dev = sandboxFx.developer("valid-llm-platform.json");
        var source = sandboxSourceWithRecording(dev, dev.version());
        SandboxProfile profile = sandboxFx.profile(dev, "replay");
        profile.setLlmMode(RunLlmMode.REPLAY);
        profile.setReplayRunId(source.runId());
        profiles.save(profile);

        var run = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);

        assertThat(run.getLlmMode()).isEqualTo(RunLlmMode.REPLAY);
        assertThat(run.getReplayRunId()).isEqualTo(source.runId());
    }

    SandboxProfile replayProfile(SandboxFixtures.Dev dev, UUID sourceRunId) {
        SandboxProfile profile = sandboxFx.profile(dev, "replay-" + UUID.randomUUID());
        profile.setLlmMode(RunLlmMode.REPLAY);
        profile.setReplayRunId(sourceRunId);
        return profiles.save(profile);
    }

    @Test
    void anotherOrgsSourceIsForbidden() {
        var dev = sandboxFx.developer("valid-llm-platform.json");
        var other = sandboxFx.developer("valid-llm-platform.json");
        var source = sandboxSourceWithRecording(other, other.version());
        SandboxProfile profile = replayProfile(dev, source.runId());

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(403));
    }

    @Test
    void anotherAgentsSourceIsForbidden() {
        var dev = sandboxFx.developer("valid-llm-platform.json");
        var otherAgent = sandboxFx.agentIn(dev.org(), "valid-llm-platform.json");
        var source = sandboxSourceWithRecording(dev, otherAgent.version());
        SandboxProfile profile = replayProfile(dev, source.runId());

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(403));
    }

    @Test
    void aProductionRunAsSourceIsForbidden() {
        var dev = sandboxFx.developer("valid-llm-platform.json");
        var production = fx.runningAgent("valid-llm-platform.json");   // PRODUCTION, via LlmFixtures/ExecutionFixtures
        SandboxProfile profile = replayProfile(dev, production.runId());

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(403));
    }

    @Test
    void aSourceWithNoRecordedCallsIs422() {
        var dev = sandboxFx.developer("valid-llm-platform.json");
        var bareSource = sandboxFx.sandboxRun(dev, sandboxFx.profile(dev, "bare"), RunStatus.COMPLETED);
        SandboxProfile profile = replayProfile(dev, bareSource.getId());

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(422));
    }

    @Test
    void crossVersionReplayIsAllowed() {
        var dev = sandboxFx.developer("valid-llm-platform.json");
        var versionB = sandboxFx.versionOf(dev.agent(), "2.0.0", "valid-llm-platform.json",
                com.agentstore.platform.catalog.VersionStatus.DRAFT);
        var source = sandboxSourceWithRecording(dev, dev.version());   // recorded on version A
        SandboxProfile profile = replayProfile(dev, source.runId());

        var run = service.start(dev.org().getId(), UUID.randomUUID(), versionB.getId(), profile.getId(), null);   // starts on version B

        assertThat(run.getReplayRunId()).isEqualTo(source.runId());
    }

    /** A minimal recorded OK call, so a run with no real LLM traffic can still be a valid replay source. */
    void recordOneOkCall(UUID runId) {
        jdbc.update("INSERT INTO llm_model (id, provider, display_name, provider_retains_data, input_price_per_mtok, "
                + "output_price_per_mtok, currency, enabled) VALUES ('test-claude','ANTHROPIC','test-claude',FALSE,3,15,'USD',TRUE) "
                + "ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO llm_call (id, run_id, model_id, source, status, request_hash, recording_expires_at, created_at) "
                + "VALUES (?, ?, 'test-claude', 'PLATFORM', 'OK', ?, now() + interval '14 days', now())",
                UUID.randomUUID(), runId, "h-" + UUID.randomUUID());
    }

    @Test
    void rerunStartsTheSameVersionWithReplayOfTheOriginalAndIsQuotaAndOwnershipChecked() {
        var dev = sandboxFx.developer("valid-tier-a.json");
        SandboxProfile profile = sandboxFx.profile(dev, "p");
        Run original = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);

        // unverified concurrency limit 1: the original run is still open, so a rerun trying to start a second is refused
        assertThatThrownBy(() -> service.rerun(dev.org().getId(), UUID.randomUUID(), original.getId()))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(429));

        service.cancel(dev.org().getId(), original.getId());
        recordOneOkCall(original.getId());
        Run rerun = service.rerun(dev.org().getId(), UUID.randomUUID(), original.getId());

        assertThat(rerun.getAgentVersion().getId()).isEqualTo(dev.version().getId());
        assertThat(rerun.getLlmMode()).isEqualTo(RunLlmMode.REPLAY);
        assertThat(rerun.getReplayRunId()).isEqualTo(original.getId());
        assertThat(rerun.getSandboxProfileId()).isEqualTo(profile.getId());

        var other = sandboxFx.developer("valid-tier-a.json");
        assertThatThrownBy(() -> service.rerun(other.org().getId(), UUID.randomUUID(), original.getId()))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(404));
    }
}
