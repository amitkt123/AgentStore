package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "agentstore.runner.secret=runner-test-secret")
class InternalRunsControllerTest extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ExecutionFixtures fixtures;

    String url() { return "http://localhost:" + port + "/internal/runs/active-ids"; }

    HttpEntity<Void> withSecret(String secret) {
        HttpHeaders h = new HttpHeaders();
        if (secret != null) h.set("X-Runner-Secret", secret);
        return new HttpEntity<>(h);
    }

    @Test
    void listsActiveRunsOnly() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        Run running = fixtures.run(s, RunStatus.RUNNING, "sbx-a", "tok-" + UUID.randomUUID());
        Run done = fixtures.run(s, RunStatus.COMPLETED, "sbx-b", "tok-" + UUID.randomUUID());

        var response = rest.exchange(url(), HttpMethod.GET, withSecret("runner-test-secret"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) response.getBody().get("runIds");
        assertThat(ids).contains(running.getId().toString()).doesNotContain(done.getId().toString());
    }

    @Test
    void wrongOrMissingSecretIsUnauthorized() {
        assertThat(rest.exchange(url(), HttpMethod.GET, withSecret("nope"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(url(), HttpMethod.GET, withSecret(null), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
