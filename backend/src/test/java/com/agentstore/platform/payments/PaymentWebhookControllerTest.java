package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentWebhookControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    PaymentProvider provider;
    @Autowired
    TopUpService topUps;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    PaymentOrderRepository orders;
    @Autowired
    JdbcTemplate jdbc;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "HookHttpCo-" + UUID.randomUUID(), OrgType.BUYER));
    }

    HttpHeaders auth(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    CheckoutResponse order(Org org) {
        return topUps.createTopUp(org.getId(), UUID.randomUUID(), new BigDecimal("500.00"), PaymentMethod.UPI);
    }

    byte[] capturedBody(CheckoutResponse c) {
        return fake().webhookBody(new PaymentWebhookEvent("evt_" + UUID.randomUUID(),
                PaymentWebhookEvent.Kind.PAYMENT_CAPTURED, c.providerOrderId(), "pay_" + UUID.randomUUID(), null, null,
                new BigDecimal("500.00"), PaymentMethod.UPI, null));
    }

    ResponseEntity<String> postHook(String provider, byte[] body, Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(h::set);
        return rest.exchange(url("/webhooks/payments/" + provider), HttpMethod.POST, new HttpEntity<>(body, h),
                String.class);
    }

    BigDecimal wallet(UUID orgId) {
        return walletRepository.balanceOfOrg(orgId).orElse(BigDecimal.ZERO);
    }

    @Test
    void validSignatureIs200AndWrongSignatureIs400() {
        Org org = buyer();
        CheckoutResponse c = order(org);
        byte[] body = capturedBody(c);

        long before = jdbc.queryForObject("select count(*) from payment_webhook_event", Long.class);
        var bad = postHook("fake", body, Map.of(FakePaymentProvider.SIGNATURE_HEADER, "00ff"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event", Long.class)).isEqualTo(before);

        var ok = postHook("fake", body, fake().signedHeaders(body));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");

        var again = postHook("fake", body, fake().signedHeaders(body));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    void otherProviderIs404() {
        byte[] body = "{}".getBytes();
        var r = postHook("razorpay", body, fake().signedHeaders(body));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void simulatePaidCreditsWallet() {
        Org org = buyer();
        CheckoutResponse c = order(org);
        var r = rest.exchange(url("/api/v1/dev/payments/simulate"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", c.order().id(), "outcome", "PAID"),
                        auth(org.getId(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        PaymentOrder o = orders.findById(c.order().id()).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(o.getProviderPaymentId()).startsWith("fake_pay_");
        assertThat(fake().payments()).anyMatch(p -> p.getPaymentId().equals(o.getProviderPaymentId())
                && p.getOrderId().equals(c.providerOrderId()));
    }

    @Test
    void simulateFailedMarksOrderFailed() {
        Org org = buyer();
        CheckoutResponse c = order(org);
        var r = rest.exchange(url("/api/v1/dev/payments/simulate"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", c.order().id(), "outcome", "FAILED"),
                        auth(org.getId(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(orders.findById(c.order().id()).orElseThrow().getStatus()).isEqualTo(PaymentOrder.Status.FAILED);
        assertThat(wallet(org.getId())).isEqualByComparingTo("0");
    }

    @Test
    void simulateForAnotherOrgIs403() {
        Org org = buyer();
        CheckoutResponse c = order(org);
        Org other = buyer();
        var r = rest.exchange(url("/api/v1/dev/payments/simulate"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", c.order().id(), "outcome", "PAID"),
                        auth(other.getId(), UserRole.BUYER_ADMIN)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wallet(org.getId())).isEqualByComparingTo("0");
        assertThat(orders.findById(c.order().id()).orElseThrow().getStatus()).isEqualTo(PaymentOrder.Status.CREATED);
    }
}
