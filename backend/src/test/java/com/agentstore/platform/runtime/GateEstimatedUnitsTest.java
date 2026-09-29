package com.agentstore.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.GateRequestRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

class GateEstimatedUnitsTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    GateRequestRepository gates;

    private org.springframework.http.ResponseEntity<Map> post(String token, Object estimatedUnits) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("action", "post_journal_entries");
        body.put("summary", "Post entries");
        body.put("payload", Map.of("rows", 2));
        body.put("affectsResources", List.of("journal_entries"));
        if (estimatedUnits != null) {
            body.put("estimatedUnits", estimatedUnits);
        }
        return rest.exchange("http://localhost:" + port + "/runtime/v1/gates", HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void aValidEstimateIsStoredOnTheGate() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        var response = post(agent.token(), 142);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var gate = gates.findById(UUID.fromString((String) response.getBody().get("gateId"))).orElseThrow();
        assertThat(gate.getEstimatedUnits()).isEqualByComparingTo("142");
    }

    @Test
    void zeroNegativeAndOverlongEstimatesAreBadRequests() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        assertThat(post(agent.token(), 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(agent.token(), -1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(agent.token(), "12345678901.5").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anEstimateIsOptional() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));
        assertThat(post(agent.token(), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
