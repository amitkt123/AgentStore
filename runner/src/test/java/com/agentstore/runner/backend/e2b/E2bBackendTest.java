package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.BackendException;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
}
