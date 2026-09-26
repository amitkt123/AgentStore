package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.AgentRuntime;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.ImageBuild;
import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Builds real E2B templates (a few minutes of credit each). Run:
 * E2B_API_KEY=... mvn test -Dsurefire.excludedGroups= -Dgroups=e2b -Dtest=E2bImageBuildLiveTest
 * Templates are left behind in the E2B dashboard; deleting them is WS5d's cleanup.
 */
@Tag("e2b")
class E2bImageBuildLiveTest {

    static final String RUNNER_ID = "runner-e2b-build-test";
    static E2bBackend backend;

    @BeforeAll
    static void connect() {
        String apiKey = System.getenv("E2B_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "E2B_API_KEY not set");
        var rest = new E2bRestClient(RestClient.builder(), "https://api.e2b.app", apiKey);
        var shim = new ShimClient(List.of("node", "e2b-shim/shim.mjs"), Duration.ofSeconds(60), apiKey);
        backend = new E2bBackend(rest, shim, RUNNER_ID, Duration.ofMinutes(14));
    }

    @Test
    void buildsAPythonAppThatRunsAndExits(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve("main.py"), "if __name__ == '__main__':\n    print('hello from build')\n");
        Files.writeString(app.resolve("requirements.txt"), "");
        List<String> log = new CopyOnWriteArrayList<>();

        String image = backend.buildImage(
                new ImageBuild(UUID.randomUUID().toString(), app, AgentRuntime.PYTHON_3_12, "main.py"), log::add);
        assertThat(log).isNotEmpty();

        String id = backend.create(new SandboxLaunch(image, List.of("sh", "-c", "cd /workspace/app && exec python 'main.py'"),
                Map.of(), List.of(), 1, 1024, 120,
                Map.of(Labels.RUNNER_ID, RUNNER_ID, Labels.REQUEST_ID, "build-live-" + UUID.randomUUID())));
        try {
            SandboxState state = SandboxState.running();
            long until = System.currentTimeMillis() + 90_000;
            while (!state.isTerminal() && System.currentTimeMillis() < until) {
                Thread.sleep(2_000);
                state = backend.status(id);
            }
            assertThat(state).isEqualTo(SandboxState.exited(0));
            assertThat(backend.readLogs(id, 0, 4096).data()).contains("hello from build");
        } finally {
            backend.kill(id);
        }
    }

    @Test
    void aMissingDependencyFailsWithInstallFailed(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve("main.py"), "print('x')\n");
        Files.writeString(app.resolve("requirements.txt"), "agentstore-no-such-package-3f9c==1.0.0\n");
        assertThatThrownBy(() -> backend.buildImage(
                new ImageBuild(UUID.randomUUID().toString(), app, AgentRuntime.PYTHON_3_12, "main.py"), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INSTALL_FAILED));
    }
}
