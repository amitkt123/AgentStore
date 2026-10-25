package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunRerunTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    ExecutionFixtures fixtures;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpEntity<Void> as(UUID orgId, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return new HttpEntity<>(headers);
    }

    Run finishedRun(ExecutionFixtures.Scenario s) {
        Run run = new Run(null, s.connection(), s.version(), RunStatus.COMPLETED, new BigDecimal("500.00"),
                new BigDecimal("10.00"), null, RunTokenService.hash("tok-" + UUID.randomUUID()));
        return runRepository.save(run);
    }

    @Test
    void buyerRerunStartsANewRunOnTheSameConnection() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run original = finishedRun(s);

        var response = rest.exchange(url("/api/v1/runs/" + original.getId() + "/rerun"), HttpMethod.POST,
                as(s.buyer().getId(), UserRole.BUYER_MEMBER), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isNotEqualTo(original.getId());
        Run created = runRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(created.getConnection().getId()).isEqualTo(s.connection().getId());
        assertThat(created.getMode()).isEqualTo(RunMode.PRODUCTION);
    }

    @Test
    void anotherOrgCannotRerun() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run original = finishedRun(s);
        Org intruder = orgRepository.save(new Org(null, "Intruder", OrgType.BUYER));

        var response = rest.exchange(url("/api/v1/runs/" + original.getId() + "/rerun"), HttpMethod.POST,
                as(intruder.getId(), UserRole.BUYER_ADMIN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aSandboxRunIsNotABuyersRun() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run sandbox = new Run(null, null, s.version(), RunStatus.COMPLETED, new BigDecimal("5.00"),
                BigDecimal.ZERO, null, RunTokenService.hash("tok-sb"));
        sandbox.setMode(RunMode.SANDBOX);
        sandbox.setInitiatedByOrgId(s.buyer().getId());
        sandbox = runRepository.save(sandbox);

        var response = rest.exchange(url("/api/v1/runs/" + sandbox.getId() + "/rerun"), HttpMethod.POST,
                as(s.buyer().getId(), UserRole.BUYER_ADMIN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anInactiveConnectionBlocksTheRerun() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run original = finishedRun(s);
        Connection connection = s.connection();
        connection.setStatus(ConnectionStatus.REVOKED);
        connectionRepository.save(connection);

        var response = rest.exchange(url("/api/v1/runs/" + original.getId() + "/rerun"), HttpMethod.POST,
                as(s.buyer().getId(), UserRole.BUYER_ADMIN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lowBalanceBlocksTheRerun() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1.00"));
        Run original = finishedRun(s);

        var response = rest.exchange(url("/api/v1/runs/" + original.getId() + "/rerun"), HttpMethod.POST,
                as(s.buyer().getId(), UserRole.BUYER_ADMIN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void aRerunRecordsWhoStartedIt() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        Run original = finishedRun(s);
        UUID starter = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(starter, s.buyer().getId(), UserRole.BUYER_MEMBER));

        var response = rest.exchange(url("/api/v1/runs/" + original.getId() + "/rerun"), HttpMethod.POST,
                new HttpEntity<>(headers), RunResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runRepository.findById(response.getBody().id()).orElseThrow().getStartedByUserId())
                .isEqualTo(starter);
    }
}
