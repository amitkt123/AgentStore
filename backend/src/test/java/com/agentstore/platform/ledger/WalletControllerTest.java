package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    BillingModeService billingModeService;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    com.agentstore.platform.execution.ExecutionFixtures fixtures;
    @Autowired
    WalletReservationService walletReservationService;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ownerCanReadTheirWalletBalance() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("41220.00"), new BigDecimal("50000.00"));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("41220.00"));
    }

    @Test
    void cannotReadAnotherOrgsWallet() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo2", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "IntruderCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void prepaidOrgWithoutWalletReadsAsEmptyAndWritesNothing() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo3", OrgType.BUYER));

        WalletResponse wallet = getWallet(buyer.getId());

        assertThat(wallet.mode()).isEqualTo(BillingMode.PREPAID);
        assertThat(wallet.balance()).isEqualByComparingTo("0");
        assertThat(wallet.held()).isEqualByComparingTo("0");
        assertThat(wallet.reserved()).isEqualByComparingTo("0");
        assertThat(wallet.available()).isEqualByComparingTo("0");
        assertThat(wallet.spentThisMonth()).isEqualByComparingTo("0");
        assertThat(wallet.cap()).isNull();
        assertThat(walletRepository.findReadOnlyByOrgId(buyer.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ledger_account where org_id = ?", Long.class,
                buyer.getId())).isZero();
    }

    @Test
    void walletReadDoesNotWaitForTheWalletLock() throws Exception {
        Org buyer = orgRepository.save(new Org(null, "BuyerCoLocked", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("75.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var holder = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var held = holder.submit(() -> tx.executeWithoutResult(s -> {
                walletRepository.findByOrgId(buyer.getId()).orElseThrow();
                locked.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            var read = java.util.concurrent.CompletableFuture.supplyAsync(() -> getWallet(buyer.getId()));
            assertThat(read.get(5, java.util.concurrent.TimeUnit.SECONDS).balance()).isEqualByComparingTo("75.00");
            release.countDown();
            held.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    void adminCanReadAnyOrgWallet() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo4", OrgType.BUYER));
        Org admin = orgRepository.save(new Org(null, "AdminCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("25000.00"), new BigDecimal("100000.00"));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), admin.getId(), UserRole.ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(response.getBody().cap()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    private WalletResponse getWallet(UUID orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, UserRole.BUYER_ADMIN));
        var response = rest.exchange(url("/api/v1/orgs/" + orgId + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), WalletResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private UUID charge(com.agentstore.platform.execution.ExecutionFixtures.Scenario scenario, String amount) {
        var run = fixtures.run(scenario, com.agentstore.platform.execution.RunStatus.RUNNING,
                "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        UUID eventId = usageEvents.save(new UsageEvent(null, run.getId(), scenario.buyer().getId(), BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount))).getId();
        ledgerService.chargeUsageEvent(eventId);
        return eventId;
    }

    @Test
    void walletShowsHeldAndAvailable() {
        var scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        UUID orgId = scenario.buyer().getId();
        walletReservationService.reserve(orgId, new BigDecimal("200.00"));

        WalletResponse wallet = getWallet(orgId);

        assertThat(wallet.mode()).isEqualTo(BillingMode.PREPAID);
        assertThat(wallet.balance()).isEqualByComparingTo("1000.00");
        assertThat(wallet.held()).isEqualByComparingTo("200.00");
        assertThat(wallet.reserved()).isEqualByComparingTo("200.00");
        assertThat(wallet.available()).isEqualByComparingTo("800.00");
        assertThat(wallet.spentThisMonth()).isEqualByComparingTo("0");
        assertThat(wallet.monthlyLimit()).isNull();
    }

    @Test
    void walletSpentThisMonthCountsOnlyThisMonth() {
        var scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        UUID orgId = scenario.buyer().getId();
        UUID lastMonthEvent = charge(scenario, "30.00");
        jdbc.update("update ledger_transaction set created_at = date_trunc('month', now() at time zone 'UTC') "
                + "at time zone 'UTC' - interval '1 day' where idempotency_key = ?", "usage:" + lastMonthEvent);
        charge(scenario, "100.00");

        assertThat(getWallet(orgId).spentThisMonth()).isEqualByComparingTo("100.00");
    }

    @Test
    void postpaidWalletShowsCreditAndOwed() {
        var scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("0.00"));
        UUID orgId = scenario.buyer().getId();
        billingModeService.setMode(orgId, BillingMode.POSTPAID);
        jdbc.update("update org set credit_limit = 1000.00 where id = ?", orgId);
        charge(scenario, "100.00");
        walletReservationService.reserve(orgId, new BigDecimal("50.00"));

        WalletResponse wallet = getWallet(orgId);

        assertThat(wallet.mode()).isEqualTo(BillingMode.POSTPAID);
        assertThat(wallet.balance()).isEqualByComparingTo("0");
        assertThat(wallet.creditLimit()).isEqualByComparingTo("1000.00");
        assertThat(wallet.owed()).isEqualByComparingTo("100.00");
        assertThat(wallet.held()).isEqualByComparingTo("50.00");
        assertThat(wallet.available()).isEqualByComparingTo("850.00");
        assertThat(wallet.spentThisMonth()).isEqualByComparingTo("100.00");
    }

    @Test
    void postpaidOrgWithoutWalletRowGetsOkAndWritesNothing() {
        Org org = new Org(null, "postpaid-" + UUID.randomUUID(), OrgType.BUYER);
        org.setBillingMode(BillingMode.POSTPAID);
        UUID orgId = orgRepository.save(org).getId();

        WalletResponse wallet = getWallet(orgId);

        assertThat(wallet.owed()).isEqualByComparingTo("0");
        assertThat(wallet.available()).isEqualByComparingTo("50000.00");
        assertThat(walletRepository.findReadOnlyByOrgId(orgId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ledger_account where org_id = ?", Long.class, orgId))
                .isZero();
    }
}
