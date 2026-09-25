package com.agentstore.platform.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class RunnerSandboxProviderTest {

    MockRestServiceServer server;
    RunnerSandboxProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RunnerSandboxProvider(builder, "http://runner.test", "shh");
    }

    SandboxSpec spec() {
        return new SandboxSpec("run-1", "img", List.of("sh", "-c", "go"), Map.of("K", "V"), List.of("example.com"),
                1, 1024, 900);
    }

    @Test
    void createPostsTheSpecWithTheSecret() {
        server.expect(requestTo("http://runner.test/sandboxes")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Runner-Secret", "shh"))
                .andExpect(jsonPath("$.requestId").value("run-1"))
                .andExpect(jsonPath("$.imageRef").value("img"))
                .andExpect(jsonPath("$.command[2]").value("go"))
                .andExpect(jsonPath("$.env.K").value("V"))
                .andExpect(jsonPath("$.allowOut[0]").value("example.com"))
                .andExpect(jsonPath("$.timeoutSeconds").value(900))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sandboxId\":\"sbx-1\"}"));

        assertThat(provider.create(spec()).sandboxId()).isEqualTo("sbx-1");
        server.verify();
    }

    @Test
    void create429BecomesSandboxCapacityExceptionWithTheCode() {
        server.expect(requestTo("http://runner.test/sandboxes"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"BUDGET_EXHAUSTED\",\"message\":\"m\"}"));

        assertThatThrownBy(() -> provider.create(spec()))
                .isInstanceOfSatisfying(SandboxCapacityException.class,
                        e -> assertThat(e.code()).isEqualTo("BUDGET_EXHAUSTED"));
    }

    @Test
    void statusMapsStateAndExitCode() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1"))
                .andRespond(withSuccess("{\"state\":\"EXITED\",\"exitCode\":3}", MediaType.APPLICATION_JSON));
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.exited(3));
    }

    @Test
    void unreachableRunnerIsUnknown() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1")).andRespond(withServerError());
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.UNKNOWN);
    }

    @Test
    void nonNumericExitCodeIsUnknown() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1"))
                .andRespond(withSuccess("{\"state\":\"EXITED\",\"exitCode\":\"three\"}", MediaType.APPLICATION_JSON));
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.UNKNOWN);
    }

    @Test
    void unknownStatusIsLoggedAsWarnWithoutTheResponseBody(CapturedOutput output) {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1"))
                .andRespond(withServerError().body("leaked-body-token"));
        assertThat(provider.status("sbx-1")).isEqualTo(SandboxStatus.UNKNOWN);
        assertThat(output.getOut()).contains("WARN").contains("sbx-1").contains("UNKNOWN")
                .doesNotContain("leaked-body-token");
    }

    @Test
    void killDeletes() {
        server.expect(requestTo("http://runner.test/sandboxes/sbx-1")).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("X-Runner-Secret", "shh"))
                .andRespond(withNoContent());
        provider.kill("sbx-1");
        server.verify();
    }
}
