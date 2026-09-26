package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContractTest extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("../contracts/runtime-v1.yaml");

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    @Test
    void theCommittedContractMatchesWhatTheControllersGenerate() throws Exception {
        String base = "http://localhost:" + port;
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject(base + "/v3/api-docs", String.class));

        if (System.getProperty("contract.update") != null) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, rest.getForObject(base + "/v3/api-docs.yaml", String.class));
        }

        JsonNode committed = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        assertThat(generated.equals(committed))
                .as("contracts/runtime-v1.yaml is out of date. If the change is intended, regenerate it with: "
                        + "cd backend && mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true, then commit the file.")
                .isTrue();
    }

    @Test
    void theContractDescribesOnlyTheRuntimeApi() throws Exception {
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject("http://localhost:" + port + "/v3/api-docs", String.class));

        assertThat(generated.get("openapi").asText()).startsWith("3.1");
        generated.get("paths").fieldNames().forEachRemaining(path -> assertThat(path).startsWith("/runtime/v1/"));
        assertThat(generated.get("paths").fieldNames()).toIterable().contains(
                "/runtime/v1/context", "/runtime/v1/gates", "/runtime/v1/gates/{gateId}", "/runtime/v1/usage",
                "/runtime/v1/logs", "/runtime/v1/heartbeat", "/runtime/v1/complete", "/runtime/v1/fail",
                "/runtime/v1/llm/chat", "/runtime/v1/resources/{resource}/records",
                "/runtime/v1/resources/{resource}/file/content");
    }
}
