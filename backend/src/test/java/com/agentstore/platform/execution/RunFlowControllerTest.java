package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.runtime.RuntimeFixtures;
import com.agentstore.platform.sandbox.SandboxFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunFlowControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures runtimeFixtures;
    @Autowired
    SandboxFixtures sandboxFixtures;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(UUID orgId, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return headers;
    }

    @Test
    @SuppressWarnings("rawtypes")
    void theBuyerCanSeeFlowForAProductionRun() {
        var agent = runtimeFixtures.runningAgent(new BigDecimal("500.00"));

        var response = rest.exchange(url("/api/v1/runs/" + agent.run().getId() + "/flow"), HttpMethod.GET,
                new HttpEntity<>(bearer(agent.scenario().buyer().getId(), UserRole.BUYER_MEMBER)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void theDeveloperCanSeeFlowForTheirOwnSandboxRun() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);

        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/flow"), HttpMethod.GET,
                new HttpEntity<>(sandboxFixtures.bearer(dev.org(), UserRole.DEVELOPER)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNonOwningDeveloperCannotSeeAnotherOrgsSandboxRunFlow() {
        var dev = sandboxFixtures.developer("valid-runtime-agent.json");
        Run run = sandboxFixtures.sandboxRun(dev, null, RunStatus.RUNNING);
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.DEVELOPER));

        var response = rest.exchange(url("/api/v1/runs/" + run.getId() + "/flow"), HttpMethod.GET,
                new HttpEntity<>(bearer(intruder.getId(), UserRole.DEVELOPER)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
