package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.BillingModeService;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BankTransferTest extends AbstractIntegrationTest {

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
    @Autowired
    PaymentProvider provider;
    @Autowired
    WebhookProcessor processor;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    BillingModeService billingModes;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "BankCo-" + UUID.randomUUID(), OrgType.BUYER));
    }

    HttpHeaders auth(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    ResponseEntity<Map> details(UUID orgId, HttpHeaders h) {
        return rest.exchange("http://localhost:" + port + "/api/v1/orgs/" + orgId + "/wallet/bank-transfer-details",
                HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    WebhookProcessor.Outcome credited(String vaId, String paymentId, String amount) {
        var e = new PaymentWebhookEvent("evt_" + UUID.randomUUID(), PaymentWebhookEvent.Kind.VIRTUAL_ACCOUNT_CREDITED,
                null, paymentId, null, vaId, new BigDecimal(amount), PaymentMethod.BANK_TRANSFER, null);
        byte[] body = fake().webhookBody(e);
        return processor.process("fake", body, fake().signedHeaders(body));
    }

    @Test
    void detailsCreatedOnce() {
        Org org = buyer();
        HttpHeaders h = auth(org.getId(), UserRole.BUYER_ADMIN);
        int before = fake().virtualAccountCalls();
        var a = details(org.getId(), h);
        var b = details(org.getId(), h);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody().get("accountNumber")).isNotNull().isEqualTo(b.getBody().get("accountNumber"));
        assertThat(a.getBody().get("ifsc")).isNotNull();
        assertThat(a.getBody().get("beneficiaryName")).isNotNull();
        assertThat(fake().virtualAccountCalls() - before).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from virtual_account where org_id = ?", Long.class,
                org.getId())).isEqualTo(1L);
    }

    @Test
    void otherRolesAndOrgsAreForbidden() {
        Org org = buyer();
        assertThat(details(org.getId(), auth(org.getId(), UserRole.BUYER_APPROVER)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(details(org.getId(), auth(UUID.randomUUID(), UserRole.BUYER_ADMIN)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void postpaidIs409() {
        Org org = buyer();
        billingModes.setMode(org.getId(), BillingMode.POSTPAID);
        var r = details(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("POSTPAID_NO_WALLET");
    }

    @Nested
    @TestPropertySource(properties = "agentstore.money.payments.methods=UPI,CARD")
    class Disabled {
        @LocalServerPort
        int nestedPort;
        @Autowired
        TestRestTemplate nestedRest;

        @Test
        void disabledIs404() {
            Org org = buyer();
            var r = nestedRest.exchange("http://localhost:" + nestedPort + "/api/v1/orgs/" + org.getId()
                            + "/wallet/bank-transfer-details", HttpMethod.GET,
                    new HttpEntity<>(auth(org.getId(), UserRole.BUYER_ADMIN)), Map.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody().get("code")).isEqualTo("BANK_TRANSFER_DISABLED");
        }
    }

    @Test
    void creditByTransfer() {
        Org org = buyer();
        String vaId = fake().createVirtualAccount(org.getId(), "x").id();
        details(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN));
        String pay = "pay_" + UUID.randomUUID();

        assertThat(credited(vaId, pay, "25000.00")).isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        assertThat(walletRepository.balanceOfOrg(org.getId()).orElseThrow()).isEqualByComparingTo("25000.00");
        Map<String, Object> order = jdbc.queryForMap("select * from payment_order where org_id = ?", org.getId());
        assertThat(order.get("purpose")).isEqualTo("TOPUP");
        assertThat(order.get("method")).isEqualTo("BANK_TRANSFER");
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat(order.get("provider_order_id")).isEqualTo("va:" + pay);
        assertThat(order.get("provider_payment_id")).isEqualTo(pay);

        // replay under a new event id credits nothing more
        credited(vaId, pay, "25000.00");
        assertThat(walletRepository.balanceOfOrg(org.getId()).orElseThrow()).isEqualByComparingTo("25000.00");
        assertThat(jdbc.queryForObject("select count(*) from payment_order where org_id = ?", Long.class,
                org.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'TOPUP' and org_id = ?",
                Long.class, org.getId())).isEqualTo(1L);
    }

    @Test
    void unknownVirtualAccountIgnored() {
        long posts = jdbc.queryForObject("select count(*) from ledger_transaction", Long.class);
        assertThat(credited("fake_va_nobody_" + UUID.randomUUID(), "pay_" + UUID.randomUUID(), "100.00"))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction", Long.class)).isEqualTo(posts);
    }
}
