package com.agentstore.platform.build;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import com.agentstore.platform.catalog.AgentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildPipelineTest extends AbstractStorageIntegrationTest {

    @Autowired BuildFixtures fixtures;
    @Autowired FakeBuildRunnerClient runner;
    @Autowired BuildDispatcher dispatcher;
    @Autowired BuildPoller poller;
    @Autowired AgentBundleRepository bundles;
    @Autowired AgentVersionRepository versions;

    @BeforeEach
    void reset() {
        fixtures.clearActiveBuilds();
        runner.reset();
    }

    @Test
    void dispatchSendsTheOldestQueuedBuildWithWorkingPresignedUrls() throws Exception {
        var built = fixtures.queued("e2b");

        assertThat(dispatcher.dispatchOnce()).isTrue();

        var request = runner.submitted().get(0);
        assertThat(request.buildId()).isEqualTo(built.build().getId().toString());
        assertThat(request.runtime()).isEqualTo("python-3.12");
        assertThat(request.entrypoint()).isEqualTo("agent/main.py");
        assertThat(request.bundleSha256()).isEqualTo(built.bundle().getSha256());
        HttpResponse<byte[]> bundle = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(request.bundleUrl())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(bundle.statusCode()).isEqualTo(200);
        assertThat(TestBundles.sha256(bundle.body())).isEqualTo(built.bundle().getSha256());
        HttpResponse<Void> log = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(request.logUploadUrl()))
                .PUT(HttpRequest.BodyPublishers.ofString("line\n")).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(log.statusCode()).isEqualTo(200);

        var build = fixtures.reload(built.build());
        assertThat(build.getStatus()).isEqualTo(BuildStatus.BUILDING);
        assertThat(build.getAttempts()).isEqualTo(1);
        assertThat(build.getStartedAt()).isNotNull();
        assertThat(fixtures.reload(built.version()).getBuildStatus()).isEqualTo(BuildStatus.BUILDING);
    }

    @Test
    void onlyOneBuildRunsAtATime() {
        fixtures.building("e2b", Instant.now(), 1);
        fixtures.queued("e2b");
        assertThat(dispatcher.dispatchOnce()).isFalse();
        assertThat(runner.submitted()).isEmpty();
    }

    @Test
    void anUnreachableRunnerLeavesTheBuildQueued() {
        var built = fixtures.queued("e2b");
        runner.setUnavailable(true);
        assertThat(dispatcher.dispatchOnce()).isFalse();
        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.QUEUED);
    }

    @Test
    void successIsCopiedToTheBuildAndTheVersion() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "SUCCEEDED", "e2b", "tpl-42", null, null));

        poller.pollOnce();

        var build = fixtures.reload(built.build());
        assertThat(build.getStatus()).isEqualTo(BuildStatus.SUCCEEDED);
        assertThat(build.getImageRef()).isEqualTo("tpl-42");
        assertThat(build.getFinishedAt()).isNotNull();
        var version = fixtures.reload(built.version());
        assertThat(version.getImageRef()).isEqualTo("tpl-42");
        assertThat(version.getBuildStatus()).isEqualTo(BuildStatus.SUCCEEDED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SECRET_FOUND", "DEP_VULN", "INSTALL_FAILED", "SMOKE_FAILED", "TIMEOUT", "INVALID_BUNDLE",
            "BUILD_FAILED"})
    void eachFailureCodeIsCopied(String code) {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "FAILED", "e2b", null, code, "because " + code));

        poller.pollOnce();

        var build = fixtures.reload(built.build());
        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getFailureCode()).isEqualTo(code);
        assertThat(build.getFailureReason()).isEqualTo("because " + code);
        assertThat(fixtures.reload(built.version()).getBuildStatus()).isEqualTo(BuildStatus.FAILED);
    }

    @Test
    void aRunnerRestartRequeuesUntilTheThirdAttemptThenFails() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "FAILED", "e2b", null, "RUNNER_RESTARTED", "restarted"));

        poller.pollOnce();
        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.QUEUED);

        var third = fixtures.building("e2b", Instant.now(), 3);
        String thirdId = third.build().getId().toString();
        runner.answer(thirdId, new RunnerBuild(thirdId, "FAILED", "e2b", null, "RUNNER_RESTARTED", "restarted"));
        poller.pollOnce();
        var failed = fixtures.reload(third.build());
        assertThat(failed.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("RUNNER_RESTARTED");
    }

    @Test
    void anUnreachableRunnerDuringPollChangesNothingUntilTheSafetyTimeout() {
        var recent = fixtures.building("e2b", Instant.now(), 1);
        var stale = fixtures.building("e2b", Instant.now().minus(Duration.ofMinutes(21)), 1);
        runner.setUnavailable(true);

        poller.pollOnce();

        assertThat(fixtures.reload(recent.build()).getStatus()).isEqualTo(BuildStatus.BUILDING);
        var timedOut = fixtures.reload(stale.build());
        assertThat(timedOut.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(timedOut.getFailureCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void aResultForABundleThatIsNoLongerCurrentNeverTouchesTheVersion() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        AgentBundle newer = bundles.save(new AgentBundle(UUID.randomUUID(), built.agent(),
                built.bundle().getUploadedByUserId(), "bundles/x/newer.zip", "b".repeat(64), 10, BundleStatus.UPLOADED));
        var version = fixtures.reload(built.version());
        version.setBundle(newer);
        version.setBuildStatus(BuildStatus.QUEUED);
        versions.save(version);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "SUCCEEDED", "e2b", "tpl-stale", null, null));

        poller.pollOnce();

        assertThat(fixtures.reload(built.build()).getStatus()).isEqualTo(BuildStatus.SUCCEEDED);
        var after = fixtures.reload(built.version());
        assertThat(after.getImageRef()).isNull();
        assertThat(after.getBuildStatus()).isEqualTo(BuildStatus.QUEUED);
    }

    @Test
    void aSucceededAnswerWithoutAnImageIsBuildFailed() {
        var built = fixtures.building("e2b", Instant.now(), 1);
        String id = built.build().getId().toString();
        runner.answer(id, new RunnerBuild(id, "SUCCEEDED", "e2b", " ", null, null));
        poller.pollOnce();
        assertThat(fixtures.reload(built.build()).getFailureCode()).isEqualTo("BUILD_FAILED");
    }
}
