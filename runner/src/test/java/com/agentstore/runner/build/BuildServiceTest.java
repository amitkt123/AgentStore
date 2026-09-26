package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.FakeBackend;
import com.agentstore.runner.build.BuildService.BuildCommand;
import com.agentstore.runner.build.BuildService.BuildView;
import com.agentstore.runner.build.BuildService.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildServiceTest {

    @TempDir Path work;
    BundleServer server;
    FakeBackend backend;
    BuildService service;

    @BeforeEach
    void setUp() {
        server = new BundleServer();
        backend = new FakeBackend("runner-test");
        service = newService(Duration.ofSeconds(30), "clean.sh");
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        server.close();
    }

    BuildService newService(Duration timeout, String gitleaksScript) {
        HttpClient http = HttpClient.newHttpClient();
        return new BuildService(backend,
                new BundleFetcher(http, BundleFetcher.MAX_BUNDLE_BYTES, Duration.ofSeconds(10)),
                new BundleUnpacker(),
                new SecretScanner(List.of("sh", "src/test/resources/gitleaks/" + gitleaksScript), Duration.ofSeconds(10)),
                new LogUploader(http, Duration.ofSeconds(5)),
                work, timeout, Duration.ofMillis(200), Clock.systemUTC());
    }

    static Map<String, String> app(String... extra) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("manifest.json", "{}");
        files.put("main.py", "print('hi')");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            files.put(extra[i], extra[i + 1]);
        }
        return files;
    }

    BuildView submit(String buildId, Map<String, String> files) {
        byte[] zip = TestZips.zip(files);
        server.serve(zip);
        return service.submit(new BuildCommand(buildId, "python-3.12", server.bundleUrl(), TestZips.sha256(zip),
                "main.py", server.logUrl()));
    }

    BuildView awaitFinished(String buildId) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        BuildView view = service.get(buildId);
        while ((view.status() == Status.QUEUED || view.status() == Status.BUILDING) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            view = service.get(buildId);
        }
        return view;
    }

    @Test
    void aGoodBundleSucceedsAndItsLogIsUploadedBeforeTheStatusFlips() throws Exception {
        submit("b1", app());
        BuildView view = awaitFinished("b1");

        assertThat(view.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(view.imageRef()).isEqualTo("fake-image-b1");
        assertThat(view.backend()).isEqualTo("fake");
        assertThat(server.lastLog()).contains("Secret scan: no findings").contains("fake build of main.py")
                .contains("Build succeeded: fake-image-b1");
    }

    @Test
    void theSameBuildIdIsBuiltOnce() throws Exception {
        submit("b2", app());
        submit("b2", app());
        awaitFinished("b2");
        assertThat(backend.buildCalls.get()).isEqualTo(1);
    }

    @Test
    void aShaMismatchIsAnInvalidBundle() throws Exception {
        byte[] zip = TestZips.zip(app());
        server.serve(zip);
        service.submit(new BuildCommand("b3", "python-3.12", server.bundleUrl(), "0".repeat(64), "main.py", server.logUrl()));
        BuildView view = awaitFinished("b3");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.INVALID_BUNDLE);
        assertThat(view.failureReason()).contains("sha256").doesNotContain("secret-signature");
        assertThat(backend.buildCalls.get()).isZero();
    }

    @Test
    void aMissingEntrypointIsAnInvalidBundle() throws Exception {
        byte[] zip = TestZips.zip(Map.of("manifest.json", "{}"));
        server.serve(zip);
        service.submit(new BuildCommand("b4", "python-3.12", server.bundleUrl(), TestZips.sha256(zip), "main.py", server.logUrl()));
        BuildView view = awaitFinished("b4");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.INVALID_BUNDLE);
        assertThat(view.failureReason()).contains("Entrypoint main.py");
        // The unpack itself succeeded (only the entrypoint check failed after), leaving a partial app dir —
        // the build directory must still be removed on a FAILED outcome, not only on success.
        try (var entries = Files.list(work)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void anUnknownRuntimeIsAnInvalidBundle() throws Exception {
        byte[] zip = TestZips.zip(app());
        server.serve(zip);
        service.submit(new BuildCommand("b5", "ruby-3", server.bundleUrl(), TestZips.sha256(zip), "main.py", server.logUrl()));
        assertThat(awaitFinished("b5").failureCode()).isEqualTo(BuildFailedException.INVALID_BUNDLE);
    }

    @Test
    void aSecretFailsTheBuildBeforeTheBackendRuns() throws Exception {
        service.shutdown();
        service = newService(Duration.ofSeconds(30), "found.sh");
        submit("b6", app());
        BuildView view = awaitFinished("b6");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.SECRET_FOUND);
        assertThat(view.failureReason()).contains("config.py:3");
        assertThat(backend.buildCalls.get()).isZero();
    }

    @Test
    void aBackendStepFailureKeepsItsCode() throws Exception {
        submit("b7", app(FakeBackend.FAIL_MARKER, "DEP_VULN"));
        BuildView view = awaitFinished("b7");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo("DEP_VULN");
        assertThat(server.lastLog()).contains("Build failed: DEP_VULN");
    }

    @Test
    void aBuildPastItsTimeoutFailsWithTimeout() throws Exception {
        service.shutdown();
        service = newService(Duration.ofMillis(500), "clean.sh");
        backend.buildDelay = Duration.ofSeconds(10);
        submit("b8", app());
        BuildView view = awaitFinished("b8");
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.TIMEOUT);
        assertThat(server.lastLog()).contains("Build failed: " + BuildFailedException.TIMEOUT);
    }

    @Test
    void secretsPrintedDuringTheBuildAreMaskedInTheUploadedLog() throws Exception {
        submit("b9", app(FakeBackend.LOG_MARKER, "Downloading from https://bot:hunter2@pypi.example.com\nAKIAXWNVK4PQ2JT6ZM3C"));
        awaitFinished("b9");
        assertThat(server.lastLog()).doesNotContain("hunter2").doesNotContain("AKIAXWNVK4PQ2JT6ZM3C").contains("[REDACTED]");
    }

    @Test
    void anUnknownBuildIdMeansTheRunnerRestarted() {
        BuildView view = service.get("never-submitted");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.RUNNER_RESTARTED);
    }

    @Test
    void theBuildDirectoryIsRemovedAfterwards() throws Exception {
        submit("b10", app());
        awaitFinished("b10");
        try (var entries = Files.list(work)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void aBackendFailureMessageContainingACredentialIsRedactedInFailureReason() throws Exception {
        submit("b11", app(FakeBackend.FAIL_MARKER,
                FakeBackend.RUNTIME_PREFIX + "shim build failed: https://bot:hunter2@pypi.example.com/repo.git"));
        BuildView view = awaitFinished("b11");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.BUILD_FAILED);
        assertThat(view.failureReason()).doesNotContain("hunter2").contains("[REDACTED]");
    }

    @Test
    void anUnexpectedRuntimeExceptionFromTheBackendEndsAsBuildFailed() throws Exception {
        submit("b12", app(FakeBackend.FAIL_MARKER, FakeBackend.RUNTIME_PREFIX + "boom"));
        BuildView view = awaitFinished("b12");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.BUILD_FAILED);
    }

    @Test
    void aBackendExceptionFromTheBackendEndsAsBuildFailed() throws Exception {
        submit("b13", app(FakeBackend.FAIL_MARKER, FakeBackend.BACKEND_PREFIX + "backend unavailable"));
        BuildView view = awaitFinished("b13");
        assertThat(view.status()).isEqualTo(Status.FAILED);
        assertThat(view.failureCode()).isEqualTo(BuildFailedException.BUILD_FAILED);
    }

    @Test
    void aSecondBuildSubmittedWhileTheFirstIsBuildingStaysQueued() throws Exception {
        backend.buildDelay = Duration.ofSeconds(5);
        BuildView first = submit("b14", app());
        long until = System.currentTimeMillis() + 5_000;
        while (first.status() == Status.QUEUED && System.currentTimeMillis() < until) {
            Thread.sleep(20);
            first = service.get("b14");
        }
        assertThat(first.status()).isEqualTo(Status.BUILDING);

        BuildView second = submit("b15", app());
        assertThat(second.status()).isEqualTo(Status.QUEUED);

        awaitFinished("b14");
        awaitFinished("b15");
    }
}
