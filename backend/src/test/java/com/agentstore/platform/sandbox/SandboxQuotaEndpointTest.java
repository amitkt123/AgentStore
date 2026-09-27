package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxQuotaEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired SandboxFixtures fx;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> quota(com.agentstore.platform.identity.Org org, UserRole role) {
        return rest.exchange("http://localhost:" + port + "/api/v1/developer/quota", HttpMethod.GET,
                new HttpEntity<>(null, fx.bearer(org, role)), Map.class);
    }

    @Test
    void returnsAllFiveEntriesWithUsedLimitAndVerifiedLimit() {
        var dev = fx.developer("valid-tier-a.json");
        fx.sandboxRun(dev, fx.profile(dev, "p"), RunStatus.RUNNING);

        var response = quota(dev.org(), UserRole.DEVELOPER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.keySet()).containsExactlyInAnyOrder("runSeconds", "concurrentRuns", "llmSpend", "fixtureBytes", "buildsToday");
        var concurrent = (Map<?, ?>) body.get("concurrentRuns");
        assertThat(String.valueOf(concurrent.get("used"))).isEqualTo("1");
        assertThat(String.valueOf(concurrent.get("limit"))).isEqualTo("1");
        assertThat(String.valueOf(concurrent.get("verifiedLimit"))).isEqualTo("5");
    }

    @Test
    void aBuyerIsForbidden() {
        var dev = fx.developer("valid-tier-a.json");

        assertThat(quota(dev.org(), UserRole.BUYER_ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
