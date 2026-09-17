package com.agentstore.platform.execution;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
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
}
