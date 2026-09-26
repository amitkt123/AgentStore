package com.agentstore.runner.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeBackendBuildTest {

    final FakeBackend backend = new FakeBackend("runner-test");

    @Test
    void buildsAFakeImage(@TempDir Path app) {
        List<String> log = new ArrayList<>();
        String image = backend.buildImage(new ImageBuild("b1", app, AgentRuntime.PYTHON_3_12, "main.py"), log::add);
        assertThat(image).isEqualTo("fake-image-b1");
        assertThat(log).anyMatch(l -> l.contains("main.py"));
    }

    @Test
    void failsWithTheCodeInTheMarkerFile(@TempDir Path app) throws Exception {
        Files.writeString(app.resolve(FakeBackend.FAIL_MARKER), "DEP_VULN\n");
        assertThatThrownBy(() -> backend.buildImage(new ImageBuild("b2", app, AgentRuntime.NODE_22, "index.js"), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("DEP_VULN"));
    }
}
