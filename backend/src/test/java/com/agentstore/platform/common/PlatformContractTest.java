package com.agentstore.platform.common;

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

class PlatformContractTest extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("../contracts/platform-v1.yaml");

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    @Test
    void theCommittedContractMatchesWhatTheControllersGenerate() throws Exception {
        String base = "http://localhost:" + port;
        JsonNode generated = new ObjectMapper().readTree(rest.getForObject(base + "/v3/api-docs/platform", String.class));

        if (System.getProperty("contract.update") != null) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, rest.getForObject(base + "/v3/api-docs.yaml/platform", String.class));
        }

        JsonNode committed = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(CONTRACT));
        assertThat(generated.equals(committed))
                .as("contracts/platform-v1.yaml is out of date. If the change is intended, regenerate it with: "
                        + "cd backend && mvn -q test -Dtest=PlatformContractTest -Dcontract.update=true, then commit the file.")
                .isTrue();
    }

    @Test
    void theContractDescribesExactlyTheFourPlatformEndpoints() throws Exception {
        JsonNode generated = new ObjectMapper().readTree(
                rest.getForObject("http://localhost:" + port + "/v3/api-docs/platform", String.class));

        assertThat(generated.get("paths").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/api/v1/runs/{runId}/flow", "/api/v1/agents/{agentId}/versions/{versionId}/readiness",
                "/api/v1/agents/{agentId}/versions/{versionId}/manifest/patches",
                "/api/v1/agents/{agentId}/sandbox-profiles/sync");
    }
}
