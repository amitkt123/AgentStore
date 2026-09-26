package com.agentstore.platform.build;

import com.agentstore.platform.build.BuildRunnerClient.RunnerBuild;
import com.agentstore.platform.build.BuildRunnerClient.RunnerBuildRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpBuildRunnerClientTest {

    MockRestServiceServer server;
    HttpBuildRunnerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpBuildRunnerClient(builder, "http://runner.test", "shh");
    }

    @Test
    void submitsWithTheSecretAndParsesTheAnswer() {
        server.expect(requestTo("http://runner.test/builds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Runner-Secret", "shh"))
                .andExpect(jsonPath("$.buildId").value("b1"))
                .andExpect(jsonPath("$.bundleUrl").value("http://store/bundle?sig=1"))
                .andExpect(jsonPath("$.logUploadUrl").value("http://store/log?sig=2"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"buildId\":\"b1\",\"status\":\"QUEUED\",\"backend\":\"e2b\"}"));

        RunnerBuild build = client.submit(new RunnerBuildRequest("b1", "python-3.12", "http://store/bundle?sig=1",
                "a".repeat(64), "main.py", "http://store/log?sig=2"));
        assertThat(build.status()).isEqualTo("QUEUED");
        assertThat(build.backend()).isEqualTo("e2b");
    }

    @Test
    void getsAFinishedBuild() {
        server.expect(requestTo("http://runner.test/builds/b1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"buildId\":\"b1\",\"status\":\"FAILED\",\"backend\":\"e2b\","
                        + "\"failureCode\":\"DEP_VULN\",\"failureReason\":\"2 high\"}", MediaType.APPLICATION_JSON));
        RunnerBuild build = client.get("b1");
        assertThat(build.failureCode()).isEqualTo("DEP_VULN");
        assertThat(build.failureReason()).isEqualTo("2 high");
    }

    @Test
    void anUnreachableRunnerIsUnavailableWithoutLeakingTheUrls() {
        server.expect(requestTo("http://runner.test/builds")).andRespond(withServerError());
        assertThatThrownBy(() -> client.submit(new RunnerBuildRequest("b1", "python-3.12", "http://store/bundle?sig=SECRET",
                "a".repeat(64), "main.py", "http://store/log?sig=SECRET")))
                .isInstanceOf(BuildRunnerUnavailableException.class)
                .hasMessageNotContaining("SECRET");
    }
}
