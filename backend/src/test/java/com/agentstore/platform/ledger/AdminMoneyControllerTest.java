package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMoneyControllerTest extends AbstractIntegrationTest {

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
    LedgerService ledgerService;
    @Autowired
    LedgerAccountRepository accounts;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LedgerAccountBalanceRepository balances;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    HttpHeaders admin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.ADMIN));
        return headers;
    }

    <T> ResponseEntity<T> call(HttpMethod method, String path, HttpHeaders headers, Object body, Class<T> type) {
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), type);
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "Buyer-" + UUID.randomUUID(), OrgType.BUYER));
    }

    Map<String, Object> adjustment(String requestId, String amount) {
        return Map.of("requestId", requestId, "amount", new BigDecimal(amount), "note", "test");
    }

    String adjustPath(UUID orgId) {
        return "/api/v1/admin/orgs/" + orgId + "/wallet/adjustments";
    }

    BigDecimal walletBalance(UUID orgId) {
        return walletRepository.findByOrgId(orgId).orElseThrow().getBalance();
    }

    @Test
    void adminSetsFeeAndItApplies() {
        ExecutionFixtures.Scenario s = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        var run = fixtures.run(s, com.agentstore.platform.execution.RunStatus.RUNNING, "sbx-" + UUID.randomUUID(),
                "tok-" + UUID.randomUUID());
        UUID devOrg = s.version().getAgent().getDeveloperOrg().getId();

        var patched = call(HttpMethod.PATCH, "/api/v1/admin/orgs/" + devOrg + "/money", admin(),
                Map.of("feePercent", new BigDecimal("10")), OrgMoneySettingsResponse.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().feePercent()).isEqualByComparingTo("10");
        assertThat(patched.getBody().effectiveFeePercent()).isEqualByComparingTo("10");

        UUID eventId = usageEvents.save(new UsageEvent(null, run.getId(), s.buyer().getId(), BigDecimal.ONE,
                new BigDecimal("100.00"), new BigDecimal("100.00"))).getId();
        ledgerService.chargeUsageEvent(eventId);

        var after = call(HttpMethod.GET, "/api/v1/admin/orgs/" + devOrg + "/money", admin(), null,
                OrgMoneySettingsResponse.class);
        assertThat(after.getBody().payableBalance()).isEqualByComparingTo("90.00");
    }

    @Test
    void feeOutOfRangeIs400() {
        Org org = buyer();
        for (String bad : List.of("100.01", "-1")) {
            var response = call(HttpMethod.PATCH, "/api/v1/admin/orgs/" + org.getId() + "/money", admin(),
                    Map.of("feePercent", new BigDecimal(bad)), String.class);
            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void adjustmentCreditsWalletOnceAndBalancesLedger() {
        Org org = buyer();
        String requestId = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            var response = call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(requestId, "500.00"),
                    WalletResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().balance()).isEqualByComparingTo("500.00");
        }
        assertThat(walletBalance(org.getId())).isEqualByComparingTo("500.00");
        UUID walletAccount = accounts.findByOrgIdAndKind(org.getId(), LedgerAccountKind.BUYER_WALLET).orElseThrow().getId();
        assertThat(ledgerService.balanceOf(walletAccount)).isEqualByComparingTo("500.00");

        var ledger = call(HttpMethod.GET, "/api/v1/admin/ledger/accounts", admin(), null, Map.class);
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ledger.getBody().get("totalsCheck")).isEqualTo(true);
        assertThat(ledger.getBody()).containsKeys("balancesMatchPostings", "walletsMatchLedger");
        assertThat(balances.accountsNotMatchingPostings()).doesNotContain(walletAccount);
        assertThat(balances.walletsNotMatchingLedger()).doesNotContain(org.getId());
    }

    @Test
    void ledgerChecksReportABalanceThatDriftedFromItsPostings() {
        Org org = buyer();
        call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(UUID.randomUUID().toString(), "40.00"),
                WalletResponse.class);
        UUID walletAccount = accounts.findByOrgIdAndKind(org.getId(), LedgerAccountKind.BUYER_WALLET).orElseThrow().getId();
        jdbc.update("update ledger_account_balance set balance = balance + 1 where account_id = ?", walletAccount);
        try {
            assertThat(balances.accountsNotMatchingPostings()).contains(walletAccount);
            assertThat(balances.walletsNotMatchingLedger()).contains(org.getId());
            var ledger = call(HttpMethod.GET, "/api/v1/admin/ledger/accounts", admin(), null, Map.class);
            assertThat(ledger.getBody().get("balancesMatchPostings")).isEqualTo(false);
            assertThat(ledger.getBody().get("walletsMatchLedger")).isEqualTo(false);
        } finally {
            jdbc.update("update ledger_account_balance set balance = balance - 1 where account_id = ?", walletAccount);
        }
        assertThat(balances.accountsNotMatchingPostings()).doesNotContain(walletAccount);
    }

    @Test
    void ledgerChecksReportAWalletWithoutItsLedgerAccount() {
        Org org = buyer();
        walletRepository.insertEmptyIfMissing(org.getId());
        assertThat(balances.walletsNotMatchingLedger()).doesNotContain(org.getId());   // empty wallet, no account
        jdbc.update("update wallet set balance = 5 where org_id = ?", org.getId());
        try {
            assertThat(balances.walletsNotMatchingLedger()).contains(org.getId());
        } finally {
            jdbc.update("update wallet set balance = 0 where org_id = ?", org.getId());
        }
    }

    @Test
    void concurrentIdenticalAdjustmentsApplyOnce() throws Exception {
        Org org = buyer();
        String requestId = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<WalletResponse>>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(requestId, "300.00"),
                                WalletResponse.class);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(requestId, "300.00"),
                                WalletResponse.class);
                    }));
            start.countDown();
            for (var f : futures) {
                var response = f.get();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody().balance()).isEqualByComparingTo("300.00");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(walletBalance(org.getId())).isEqualByComparingTo("300.00");
        UUID walletAccount = accounts.findByOrgIdAndKind(org.getId(), LedgerAccountKind.BUYER_WALLET).orElseThrow().getId();
        assertThat(ledgerService.balanceOf(walletAccount)).isEqualByComparingTo("300.00");
    }

    @Test
    void negativeAdjustmentBelowAvailableIs409() {
        Org org = buyer();
        call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(UUID.randomUUID().toString(), "100.00"),
                WalletResponse.class);

        var response = call(HttpMethod.POST, adjustPath(org.getId()), admin(),
                adjustment(UUID.randomUUID().toString(), "-100.01"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var ok = call(HttpMethod.POST, adjustPath(org.getId()), admin(),
                adjustment(UUID.randomUUID().toString(), "-100.00"), WalletResponse.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().balance()).isEqualByComparingTo("0.00");
    }

    @Test
    void adjustingAPostpaidOrgIs409() {
        Org org = buyer();
        var patched = call(HttpMethod.PATCH, "/api/v1/admin/orgs/" + org.getId() + "/money", admin(),
                Map.of("billingMode", "POSTPAID"), OrgMoneySettingsResponse.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().effectiveBillingMode()).isEqualTo(BillingMode.POSTPAID);

        var response = call(HttpMethod.POST, adjustPath(org.getId()), admin(),
                adjustment(UUID.randomUUID().toString(), "10.00"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void nonAdminIs403() {
        Org org = buyer();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), org.getId(), UserRole.BUYER_ADMIN));
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orgs/" + org.getId() + "/money", headers, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, adjustPath(org.getId()), headers, adjustment("x", "1.00"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/ledger/accounts", headers, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sameRequestIdOnAnotherOrgOrAmountIs409AndLeavesWalletAlone() {
        Org first = buyer();
        Org second = buyer();
        String requestId = UUID.randomUUID().toString();
        call(HttpMethod.POST, adjustPath(first.getId()), admin(), adjustment(requestId, "200.00"), WalletResponse.class);

        var otherOrg = call(HttpMethod.POST, adjustPath(second.getId()), admin(), adjustment(requestId, "200.00"),
                String.class);
        assertThat(otherOrg.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(walletRepository.balanceOfOrg(second.getId()).orElse(BigDecimal.ZERO)).isEqualByComparingTo("0");

        var otherAmount = call(HttpMethod.POST, adjustPath(first.getId()), admin(), adjustment(requestId, "300.00"),
                String.class);
        assertThat(otherAmount.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(walletBalance(first.getId())).isEqualByComparingTo("200.00");
    }

    @Test
    void retryAfterSwitchingToPostpaidReplays() {
        Org org = buyer();
        String requestId = UUID.randomUUID().toString();
        call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(requestId, "50.00"), WalletResponse.class);
        call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(UUID.randomUUID().toString(), "-50.00"),
                WalletResponse.class);
        call(HttpMethod.PATCH, "/api/v1/admin/orgs/" + org.getId() + "/money", admin(),
                Map.of("billingMode", "POSTPAID"), OrgMoneySettingsResponse.class);

        var retry = call(HttpMethod.POST, adjustPath(org.getId()), admin(), adjustment(requestId, "50.00"),
                String.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void patchingFeeKeepsBillingModeAndViceVersa() {
        Org org = buyer();
        call(HttpMethod.PATCH, "/api/v1/admin/orgs/" + org.getId() + "/money", admin(),
                Map.of("billingMode", "POSTPAID", "creditLimit", new BigDecimal("1000.00")),
                OrgMoneySettingsResponse.class);
        var after = call(HttpMethod.PATCH, "/api/v1/admin/orgs/" + org.getId() + "/money", admin(),
                Map.of("feePercent", new BigDecimal("5")), OrgMoneySettingsResponse.class).getBody();
        assertThat(after.billingMode()).isEqualTo(BillingMode.POSTPAID);
        assertThat(after.creditLimit()).isEqualByComparingTo("1000.00");
        assertThat(after.feePercent()).isEqualByComparingTo("5");
    }
}
