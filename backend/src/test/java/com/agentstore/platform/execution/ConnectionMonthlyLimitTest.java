package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionMonthlyLimitTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    WalletRepository walletRepository;

    ExecutionFixtures.Scenario scenario(String monthlyLimit) {
        var s = fixtures.liveTierA(new BigDecimal("100.00"), new BigDecimal("1000.00"));
        s.connection().setMonthlyLimit(new BigDecimal(monthlyLimit));
        connectionRepository.save(s.connection());
        return s;
    }

    void priorRun(ExecutionFixtures.Scenario s, RunStatus status, String charged, String reserved, Instant startedAt) {
        Run run = new Run(null, s.connection(), s.version(), status, new BigDecimal("100.00"), new BigDecimal(charged),
                null, RunTokenService.hash("tok-" + UUID.randomUUID()));
        run.setReservedAmount(new BigDecimal(reserved));
        run.setStartedAt(startedAt);
        runRepository.save(run);
    }

    ResponseEntity<String> trigger(ExecutionFixtures.Scenario s) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), s.buyer().getId(), UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("http://localhost:" + port + "/api/v1/runs",
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), headers), String.class);
    }

    BigDecimal reserved(ExecutionFixtures.Scenario s) {
        return walletRepository.findAll().stream().filter(w -> w.getOrgId().equals(s.buyer().getId()))
                .findFirst().orElseThrow().getReserved();
    }

    @Test
    void refusesARunThatWouldPassTheMonthlyLimitAndReservesNothing() {
        var s = scenario("150.00");
        priorRun(s, RunStatus.COMPLETED, "60.00", "0.00", Instant.now());

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).contains("AGENT_MONTHLY_LIMIT");
        assertThat(reserved(s)).isEqualByComparingTo("0");
    }

    @Test
    void allowsARunThatFitsUnderTheLimit() {
        var s = scenario("200.00");
        priorRun(s, RunStatus.COMPLETED, "60.00", "0.00", Instant.now());

        assertThat(trigger(s).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anInFlightRunCountsForItsReservation() {
        var s = scenario("150.00");
        priorRun(s, RunStatus.RUNNING, "10.00", "100.00", Instant.now());

        var response = trigger(s);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).contains("AGENT_MONTHLY_LIMIT");
    }

    @Test
    void lastMonthsRunsDoNotCount() {
        var s = scenario("150.00");
        priorRun(s, RunStatus.COMPLETED, "500.00", "0.00", Instant.now().minus(Duration.ofDays(40)));

        assertThat(trigger(s).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
