package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class RunControllerWalletTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    RunRepository runRepository;
    @Autowired
    FakeSandboxProvider fakeSandboxProvider;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders buyerAdmin(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    <T> ResponseEntity<T> trigger(ExecutionFixtures.Scenario s, Class<T> type) {
        return rest.postForEntity(url("/api/v1/runs"),
                new HttpEntity<>(new TriggerRunRequest(s.connection().getId()), buyerAdmin(s.buyer().getId())), type);
    }

    Wallet wallet(ExecutionFixtures.Scenario s) {
        return walletRepository.findByOrgId(s.buyer().getId()).orElseThrow();
    }

    void agentEvent(UUID runId, String kind, Map<String, Object> detail) {
        String token = fakeSandboxProvider.specForRun(runId).envVars().get("AGENT_CALLBACK_TOKEN");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.postForEntity(url("/internal/agent-callback/runs/" + runId + "/events"),
                new HttpEntity<>(Map.of("kind", kind, "detail", detail), headers), Void.class);
    }

    @Test
    void triggeringWithInsufficientWalletBalanceIsRejected() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("10.00"));

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void triggerReservesTheSpendCap() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));

        var run = trigger(s, RunResponse.class).getBody();

        assertThat(wallet(s).getReserved()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(runRepository.findById(run.id()).orElseThrow().getReservedAmount())
                .isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void concurrentTriggersCannotReserveMoreThanTheWalletHolds() throws Exception {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("300.00"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<HttpStatusCode> call = () -> {
                start.await();
                return trigger(s, String.class).getStatusCode();
            };
            Future<HttpStatusCode> a = pool.submit(call);
            Future<HttpStatusCode> b = pool.submit(call);
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.PAYMENT_REQUIRED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void chargesConsumeTheReservationAndCompletionReleasesTheRest() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        UUID runId = trigger(s, RunResponse.class).getBody().id();

        agentEvent(runId, "COST", Map.of("quantity", 5));   // 5 × 2.00 = 10.00
        assertThat(wallet(s).getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(new BigDecimal("190.00"));

        agentEvent(runId, "COMPLETE", Map.of());
        assertThat(wallet(s).getBalance()).isEqualByComparingTo(new BigDecimal("990.00"));
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(runRepository.findById(runId).orElseThrow().getReservedAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sandboxCreationFailureReleasesTheReservation() {
        var s = fixtures.liveTierA(new BigDecimal("200.00"), new BigDecimal("1000.00"));
        fakeSandboxProvider.failNextCreate();

        assertThat(trigger(s, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(wallet(s).getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
