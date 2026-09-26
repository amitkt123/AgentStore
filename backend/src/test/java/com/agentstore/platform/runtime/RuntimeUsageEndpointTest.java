package com.agentstore.platform.runtime;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeUsageEndpointTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    RuntimeFixtures fx;
    @Autowired
    RunRepository runRepository;

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> usage(String token, Object body) {
        return rest.exchange("http://localhost:" + port + "/runtime/v1/usage", HttpMethod.POST,
                new HttpEntity<>(body, RuntimeFixtures.bearer(token)), Map.class);
    }

    @Test
    void usageInTheManifestUnitIsChargedAtThePlatformPrice() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = usage(agent.token(), Map.of("quantity", 3, "unit", "reconciled_transaction", "outcomeRef", "batch-7"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(String.valueOf(response.getBody().get("charged")))).isEqualByComparingTo("6.00");
        assertThat(new BigDecimal(String.valueOf(response.getBody().get("costCapRemaining")))).isEqualByComparingTo("494.00");
    }

    @Test
    void aWrongUnitIsForbiddenAndNothingIsCharged() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        var response = usage(agent.token(), Map.of("quantity", 3, "unit", "gigabytes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("SCOPE_NOT_GRANTED");
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
    }

    @Test
    void usageBeyondTheCapChargesTheRemainderReturns402AndStopsTheRun() {
        var agent = fx.runningAgent(new BigDecimal("10.00"));

        var response = usage(agent.token(), Map.of("quantity", 6, "unit", "reconciled_transaction"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().get("code")).isEqualTo("BUDGET_EXCEEDED");
        var run = runRepository.findById(agent.run().getId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getTotalCharged()).isEqualByComparingTo("10.00");
    }

    @Test
    void zeroNegativeMissingAndNonNumericQuantitiesAreValidationErrors() {
        var agent = fx.runningAgent(new BigDecimal("500.00"));

        for (Object quantity : new Object[]{0, -3, "abc"}) {
            var response = usage(agent.token(), Map.of("quantity", quantity, "unit", "reconciled_transaction"));
            assertThat(response.getStatusCode()).as("quantity %s", quantity).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(usage(agent.token(), Map.of("unit", "reconciled_transaction")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(runRepository.findById(agent.run().getId()).orElseThrow().getTotalCharged()).isEqualByComparingTo("0");
    }
}
