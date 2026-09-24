package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class E2bSandboxProviderTest {

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    E2bSandboxProvider provider = new E2bSandboxProvider(builder, "https://api.e2b.app", "test-api-key");

    @Test
    void createPostsTemplateAndReturnsSandboxId() {
        server.expect(requestTo("https://api.e2b.app/sandboxes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andExpect(jsonPath("$.templateID").value("bank-recon-template-v1"))
                .andExpect(jsonPath("$.network.allowOut[0]").value("api.agentstore.example"))
                .andExpect(jsonPath("$.network.denyOut[0]").value("0.0.0.0/0"))
                .andExpect(jsonPath("$.network.denyOut.length()").value(1))
                .andRespond(withSuccess(
                        "{\"sandboxID\":\"sbx-123\",\"templateID\":\"bank-recon-template-v1\"}",
                        MediaType.APPLICATION_JSON));

        SandboxHandle handle = provider.create(new SandboxSpec("bank-recon-template-v1", 900, Map.of(),
                List.of("api.agentstore.example")));

        assertThat(handle.sandboxId()).isEqualTo("sbx-123");
        server.verify();
    }

    @Test
    void killSendsDelete() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-123"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andRespond(withSuccess());

        provider.kill("sbx-123");

        server.verify();
    }

    @Test
    void statusReportsRunningForALiveSandbox() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"sandboxID\":\"sbx-1\"}", MediaType.APPLICATION_JSON));
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.RUNNING);
    }

    @Test
    void statusReportsExitedWhenE2bNoLongerKnowsTheSandbox() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-2")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(provider.status("sbx-2")).isEqualTo(SandboxStatus.EXITED);
    }

    @Test
    void statusReportsUnknownOnProviderErrors() {
        server.expect(requestTo("https://api.e2b.app/sandboxes/sbx-3")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(provider.status("sbx-3")).isEqualTo(SandboxStatus.UNKNOWN);
    }

    @Test
    void readTimeoutFailsFastInsteadOfHangingForeverWhenE2bStalls() throws IOException {
        // A raw socket that accepts the connection but never writes a response, simulating a hung/degraded E2B.
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread stallingServer = new Thread(() -> {
                try (var socket = serverSocket.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignored) {
                    // test teardown or the client giving up first; nothing to do.
                }
            });
            stallingServer.setDaemon(true);
            stallingServer.start();

            E2bSandboxProvider hangingProvider = new E2bSandboxProvider(RestClient.builder(),
                    "http://localhost:" + serverSocket.getLocalPort(), "test-api-key",
                    Duration.ofMillis(300), Duration.ofMillis(300));

            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> hangingProvider.create(new SandboxSpec("t", 900, Map.of(), List.of())))
                    .isInstanceOf(RestClientException.class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            assertThat(elapsedMs).isLessThan(4_000);
        }
    }
}
