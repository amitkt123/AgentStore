package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.WalletResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TopUpControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    JdbcTemplate jdbc;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "TopUpCo-" + UUID.randomUUID(), OrgType.BUYER));
    }

    HttpHeaders auth(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    ResponseEntity<Map> options(UUID orgId, HttpHeaders h) {
        return rest.exchange(url("/api/v1/orgs/" + orgId + "/wallet/payment-options"), HttpMethod.GET,
                new HttpEntity<>(h), Map.class);
    }

    ResponseEntity<Map> topUp(UUID orgId, HttpHeaders h, Object amount, String method) {
        return rest.exchange(url("/api/v1/orgs/" + orgId + "/wallet/topups"), HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", amount, "method", method), h), Map.class);
    }

    @Test
    void optionsListEnabledMethods() {
        Org org = buyer();
        var r = options(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("provider")).isEqualTo("fake");
        assertThat((List<Object>) r.getBody().get("methods")).containsExactly("UPI", "CARD", "NETBANKING", "BANK_TRANSFER");
        assertThat(new BigDecimal(r.getBody().get("topUpMin").toString())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(r.getBody().get("topUpRemainingToday").toString())).isEqualByComparingTo("100000.00");
        assertThat(options(org.getId(), auth(UUID.randomUUID(), UserRole.ADMIN)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Nested
    @TestPropertySource(properties = "agentstore.money.payments.methods=UPI")
    class OnlyUpi {
        // a nested class gets its own Spring context, so it needs its own injected beans (not the outer instance's)
        @LocalServerPort
        int nestedPort;
        @Autowired
        TestRestTemplate nestedRest;

        @Test
        void disabledMethodIsRefused() {
            Org org = buyer();
            HttpHeaders h = auth(org.getId(), UserRole.BUYER_ADMIN);
            String base = "http://localhost:" + nestedPort + "/api/v1/orgs/" + org.getId() + "/wallet";
            var r = nestedRest.exchange(base + "/topups", HttpMethod.POST,
                    new HttpEntity<>(Map.of("amount", 500, "method", "CARD"), h), Map.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(r.getBody().get("code")).isEqualTo("PAYMENT_METHOD_DISABLED");
            var opts = nestedRest.exchange(base + "/payment-options", HttpMethod.GET, new HttpEntity<>(h), Map.class);
            assertThat((List<Object>) opts.getBody().get("methods")).containsExactly("UPI");
        }
    }

    @Test
    void bankTransferIsNotAnOrder() {
        Org org = buyer();
        var r = topUp(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), 500, "BANK_TRANSFER");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("PAYMENT_METHOD_DISABLED");
        assertThat(r.getBody().get("message")).isEqualTo("Bank transfer top-ups use the account details, not an order");
    }

    @Test
    void createsOrderWithoutCreditingWallet() {
        Org org = buyer();
        HttpHeaders h = auth(org.getId(), UserRole.BUYER_ADMIN);
        var r = topUp(org.getId(), h, 500, "UPI");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> order = (Map<?, ?>) r.getBody().get("order");
        assertThat(order.get("status")).isEqualTo("CREATED");
        assertThat(((Map<?, ?>) r.getBody().get("checkout")).get("method")).isEqualTo("UPI");
        assertThat(r.getBody().get("providerOrderId").toString()).doesNotStartWith("pending:");

        var wallet = rest.exchange(url("/api/v1/orgs/" + org.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(h), WalletResponse.class);
        assertThat(wallet.getBody().balance()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_transaction where type = 'TOPUP' and org_id = ?", Long.class,
                org.getId())).isZero();

        var list = rest.exchange(url("/api/v1/orgs/" + org.getId() + "/wallet/topups"), HttpMethod.GET,
                new HttpEntity<>(h), List.class);
        assertThat(list.getBody()).hasSize(1);
    }

    @Test
    void belowMinimum() {
        Org org = buyer();
        var r = topUp(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), "99.99", "UPI");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("BELOW_MINIMUM");
    }

    @Test
    void badAmount() {
        Org org = buyer();
        HttpHeaders h = auth(org.getId(), UserRole.BUYER_ADMIN);
        for (Object amount : new Object[]{0, -5, "10.001"}) {
            var r = topUp(org.getId(), h, amount, "UPI");
            assertThat(r.getStatusCode()).as("amount %s", amount).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code")).isEqualTo("AMOUNT_INVALID");
        }
    }

    @Test
    void dailyLimit() {
        Org org = buyer();
        HttpHeaders h = auth(org.getId(), UserRole.BUYER_ADMIN);
        assertThat(topUp(org.getId(), h, 60000, "UPI").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var second = topUp(org.getId(), h, 60000, "UPI");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody().get("code")).isEqualTo("DAILY_LIMIT_REACHED");

        jdbc.update("update payment_order set status = 'FAILED' where org_id = ?", org.getId());
        assertThat(topUp(org.getId(), h, 60000, "UPI").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        jdbc.update("update payment_order set status = 'EXPIRED' where org_id = ? and status = 'CREATED'",
                org.getId());
        jdbc.update("update payment_order set status = 'PAID' where org_id = ? and status = 'FAILED'", org.getId());
        // A is now PAID (60k) and B EXPIRED (not counted): exactly 40k remains
        assertThat(topUp(org.getId(), h, 40000, "UPI").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(topUp(org.getId(), h, 100, "UPI").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void abandonedCheckoutStopsCountingAfterThirtyMinutes() {
        Org org = buyer();
        HttpHeaders h = auth(org.getId(), UserRole.BUYER_ADMIN);
        assertThat(topUp(org.getId(), h, 100000, "UPI").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(topUp(org.getId(), h, 100, "UPI").getBody().get("code")).isEqualTo("DAILY_LIMIT_REACHED");

        // The buyer closed Checkout without paying; once the order is 31 minutes old it no longer holds the limit.
        jdbc.update("update payment_order set created_at = now() - interval '31 minutes' where org_id = ?", org.getId());
        assertThat(topUp(org.getId(), h, 100000, "UPI").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Autowired
    FakePaymentProvider fake;

    @Test
    void providerFailureMarksOrderFailed() {
        Org org = buyer();
        fake.failNextCreateOrder();
        try {
            var r = topUp(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), 500, "UPI");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(r.getBody().get("code")).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(jdbc.queryForList("select status from payment_order where org_id = ?", String.class,
                    org.getId())).containsExactly("FAILED");
        } finally {
            fake.reset();
        }
    }

    @Test
    void postpaidOrgHasNoWallet() {
        Org org = new Org(null, "PostpaidCo-" + UUID.randomUUID(), OrgType.BUYER);
        org.setBillingMode(BillingMode.POSTPAID);
        org = orgRepository.save(org);
        var r = topUp(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), 500, "UPI");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("POSTPAID_NO_WALLET");
    }

    @Test
    void onlyOwnBuyerAdmin() {
        Org org = buyer();
        Org other = buyer();
        assertThat(topUp(org.getId(), auth(other.getId(), UserRole.BUYER_ADMIN), 500, "UPI").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(topUp(org.getId(), auth(org.getId(), UserRole.BUYER_MEMBER), 500, "UPI").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(topUp(org.getId(), auth(UUID.randomUUID(), UserRole.ADMIN), 500, "UPI").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
