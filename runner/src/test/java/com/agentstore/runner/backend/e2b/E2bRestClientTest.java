package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.SandboxLaunch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class E2bRestClientTest {

    MockRestServiceServer server;
    E2bRestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new E2bRestClient(builder, "https://e2b.test", "api-key");
    }

    @Test
    void createSendsTemplateTimeoutMetadataAndDenyAllNetworkButNoEnv() {
        server.expect(requestTo("https://e2b.test/sandboxes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "api-key"))
                .andExpect(jsonPath("$.templateID").value("base"))
                .andExpect(jsonPath("$.timeout").value(60))
                .andExpect(jsonPath("$.metadata['agentstore.requestId']").value("run-1"))
                .andExpect(jsonPath("$.network.allowOut[0]").value("example.com"))
                .andExpect(jsonPath("$.network.denyOut[0]").value("0.0.0.0/0"))
                .andExpect(jsonPath("$.envVars").doesNotExist())
                .andRespond(withSuccess("{\"sandboxID\":\"sbx-1\"}", MediaType.APPLICATION_JSON));

        String id = client.create(new SandboxLaunch("base", List.of("true"), Map.of("TOKEN", "s3cret"),
                List.of("example.com"), 1, 512, 60, Map.of(Labels.REQUEST_ID, "run-1")));

        assertThat(id).isEqualTo("sbx-1");
        server.verify();
    }

    @Test
    void existsIsFalseOn404() {
        server.expect(requestTo("https://e2b.test/sandboxes/sbx-9")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.exists("sbx-9")).isFalse();
    }

    @Test
    void deleteIgnores404() {
        server.expect(requestTo("https://e2b.test/sandboxes/sbx-9")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        client.delete("sbx-9");
        server.verify();
    }

    @Test
    void listReturnsOnlyThisRunnersSandboxes() {
        server.expect(requestTo("https://e2b.test/sandboxes")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"sandboxID":"a","metadata":{"agentstore.runnerId":"runner-1","agentstore.requestId":"r1"}},
                         {"sandboxID":"b","metadata":{"agentstore.runnerId":"other"}},
                         {"sandboxID":"c"}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.list("runner-1")).singleElement()
                .satisfies(o -> assertThat(o.requestId()).isEqualTo("r1"));
    }
}
