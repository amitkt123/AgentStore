package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.AgentRuntime;
import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.BuildFailedException;
import com.agentstore.runner.backend.ImageBuild;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class E2bBackendTest {

    final E2bRestClient rest = mock(E2bRestClient.class);
    final ShimClient shim = mock(ShimClient.class);
    final E2bBackend backend = new E2bBackend(rest, shim, "runner-1");
    final ObjectMapper om = new ObjectMapper();

    SandboxLaunch launch() {
        return new SandboxLaunch("base", List.of("sh", "-c", "true"), Map.of("TOKEN", "s3cret"), List.of(),
                1, 512, 60, Map.of());
    }

    ObjectNode file(String content) {
        return om.createObjectNode().put("ok", true).put("exists", true)
                .put("data", Base64.getEncoder().encodeToString(content.getBytes())).put("size", content.length());
    }

    ObjectNode missing() {
        return om.createObjectNode().put("ok", true).put("exists", false);
    }

    @Test
    void createStartsTheCommandThroughTheShimWithEnv() {
        when(rest.create(any())).thenReturn("sbx-1");
        when(shim.call(any())).thenReturn(om.createObjectNode().put("ok", true));

        assertThat(backend.create(launch())).isEqualTo("sbx-1");
        verify(shim).call(argThat(req -> "start".equals(req.get("op")) && "sbx-1".equals(req.get("sandboxId"))
                && Map.of("TOKEN", "s3cret").equals(req.get("env"))));
    }

    @Test
    void startFailureKillsTheSandboxAndThrowsWithoutSecrets() {
        when(rest.create(any())).thenReturn("sbx-1");
        when(shim.call(any())).thenThrow(new ShimException("start failed: boom"));

        assertThatThrownBy(() -> backend.create(launch()))
                .isInstanceOf(BackendException.class)
                .hasMessageNotContaining("s3cret");
        verify(rest).delete("sbx-1");
    }

    @Test
    void goneWhenE2bReturns404() {
        when(rest.exists("sbx-1")).thenReturn(false);
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.gone());
    }

    @Test
    void exitedWithCodeWhenTheExitFileExists() {
        when(rest.exists("sbx-1")).thenReturn(true);
        when(shim.call(argThat(r -> "/workspace/.run/exit".equals(r.get("path"))))).thenReturn(file("3"));
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.exited(3));
    }

    @Test
    void runningWhenNoExitFile() {
        when(rest.exists("sbx-1")).thenReturn(true);
        when(shim.call(any())).thenReturn(missing());
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.running());
    }

    @Test
    void unknownWhenE2bOrTheShimCannotBeReached() {
        when(rest.exists("sbx-1")).thenThrow(new ResourceAccessException("timeout"));
        assertThat(backend.status("sbx-1")).isEqualTo(SandboxState.unknown());

        when(rest.exists("sbx-2")).thenReturn(true);
        when(shim.call(any())).thenThrow(new ShimException("timed out"));
        assertThat(backend.status("sbx-2")).isEqualTo(SandboxState.unknown());
    }

    @Test
    void readLogsAdvancesByBytesAndReplacesInvalidUtf8() {
        byte[] bytes = {'o', 'k', (byte) 0xC3};   // truncated multi-byte sequence
        when(rest.exists("sbx-1")).thenReturn(true);
        when(shim.call(any())).thenReturn(om.createObjectNode().put("ok", true).put("exists", true)
                .put("data", Base64.getEncoder().encodeToString(bytes)).put("size", 3));

        var chunk = backend.readLogs("sbx-1", 10, 1024);
        assertThat(chunk.nextOffset()).isEqualTo(13);
        assertThat(chunk.data()).startsWith("ok").hasSize(3);
    }

    @Test
    void readLogsOfAGoneSandboxIsEmpty() {
        when(rest.exists("sbx-1")).thenReturn(false);
        assertThat(backend.readLogs("sbx-1", 5, 1024).data()).isEmpty();
        verifyNoInteractions(shim);
    }

    ImageBuild imageBuild(Path app) {
        return new ImageBuild("B1", app, AgentRuntime.PYTHON_3_12, "agent/main.py");
    }

    @Test
    void buildSendsTheRecipeInputsAndReturnsTheImageRef(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenReturn(om.createObjectNode().put("ok", true).put("imageRef", "tpl-1"));

        assertThat(backend.buildImage(imageBuild(app), l -> { })).isEqualTo("tpl-1");
        verify(shim).call(argThat(req -> "build".equals(req.get("op")) && "agentstore-b1".equals(req.get("name"))
                && app.toAbsolutePath().toString().equals(req.get("appDir")) && "python-3.12".equals(req.get("runtime"))
                && "agent/main.py".equals(req.get("entrypoint")) && req.get("logPath") != null), eq(Duration.ofMinutes(14)));
    }

    @Test
    void linesTheShimWritesReachTheBuildLog(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            Files.writeString(Path.of((String) req.get("logPath")), "Collecting requests\nInstalled\n");
            return om.createObjectNode().put("ok", true).put("imageRef", "tpl-1");
        });
        List<String> lines = new ArrayList<>();
        backend.buildImage(imageBuild(app), lines::add);
        assertThat(lines).containsExactly("Collecting requests", "Installed");
    }

    @Test
    void aRecipeStepFailureKeepsItsCode(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimException("shim build failed: audit", "DEP_VULN"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("DEP_VULN"));
    }

    @Test
    void anyOtherShimFailureIsBuildFailed(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimException("shim build failed: x", "E2B_ERROR"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("BUILD_FAILED"));
    }

    @Test
    void aShimTimeoutIsTimeout(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimTimeoutException("shim build timed out"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("TIMEOUT"));
    }

    @Test
    void aBlankImageRefIsBuildFailed(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenReturn(om.createObjectNode().put("ok", true));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("BUILD_FAILED"));
    }

    @Test
    void aShimFailureWithNoCodeIsBuildFailed(@TempDir Path app) {
        when(shim.call(any(), any(Duration.class))).thenThrow(new ShimException("shim build could not start: boom"));
        assertThatThrownBy(() -> backend.buildImage(imageBuild(app), l -> { }))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo("BUILD_FAILED"));
    }
}
