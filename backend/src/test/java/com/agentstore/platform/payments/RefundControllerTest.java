package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefundControllerTest extends AbstractIntegrationTest {

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
    TopUpService topUps;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    @BeforeEach
    void reset() {
        fake().reset();
    }

    String url(UUID orgId, String path) {
        return "http://localhost:" + port + "/api/v1/orgs/" + orgId + "/wallet" + path;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "RefundApiCo-" + UUID.randomUUID(), OrgType.BUYER));
    }

    HttpHeaders auth(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    void paidTopUp(Org org, String amount, int daysAgo) {
        CheckoutResponse c = topUps.createTopUp(org.getId(), UUID.randomUUID(), new BigDecimal(amount), PaymentMethod.UPI);
        String paymentId = "pay_" + UUID.randomUUID();
        fake().capture(c.providerOrderId(), paymentId);
        byte[] body = fake().webhookBody(new PaymentWebhookEvent("evt_" + UUID.randomUUID(),
                PaymentWebhookEvent.Kind.PAYMENT_CAPTURED, c.providerOrderId(), paymentId, null, null,
                new BigDecimal(amount), PaymentMethod.UPI, null));
        processor.process("fake", body, fake().signedHeaders(body));
        jdbc.update("update payment_order set paid_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo))), c.order().id());
    }

    ResponseEntity<Map> refundable(UUID orgId, HttpHeaders h, String amount) {
        return rest.exchange(url(orgId, "/refundable" + (amount == null ? "" : "?amount=" + amount)), HttpMethod.GET,
                new HttpEntity<>(h), Map.class);
    }

    ResponseEntity<Map> request(UUID orgId, HttpHeaders h, String requestId, Object amount) {
        return rest.exchange(url(orgId, "/refunds"), HttpMethod.POST,
                new HttpEntity<>(Map.of("requestId", requestId, "amount", amount), h), Map.class);
    }

    HttpStatusCode listStatus(UUID orgId, HttpHeaders h) {
        return rest.exchange(url(orgId, "/refunds"), HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode();
    }

    ResponseEntity<List> list(UUID orgId, HttpHeaders h) {
        return rest.exchange(url(orgId, "/refunds"), HttpMethod.GET, new HttpEntity<>(h), List.class);
    }

    @Test
    void refundableShowsThePlan() {
        Org org = buyer();
        paidTopUp(org, "1000.00", 2);
        paidTopUp(org, "500.00", 1);
        HttpHeaders admin = auth(org.getId(), UserRole.BUYER_ADMIN);

        var all = refundable(org.getId(), admin, null);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(all.getBody().get("refundable").toString())).isEqualByComparingTo("1500.00");
        assertThat((List<Map>) all.getBody().get("parts")).hasSize(2);

        var some = refundable(org.getId(), admin, "700.00");
        List<Map> parts = (List<Map>) some.getBody().get("parts");
        assertThat(parts).hasSize(2);
        assertThat(new BigDecimal(parts.get(0).get("amount").toString())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(parts.get(1).get("amount").toString())).isEqualByComparingTo("200.00");
        assertThat(parts.get(0).get("method")).isEqualTo("UPI");
        assertThat(parts.get(0).get("paidAt")).isNotNull();

        assertThat(refundable(org.getId(), auth(UUID.randomUUID(), UserRole.ADMIN), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void requestIsIdempotentOnRequestId() {
        Org org = buyer();
        paidTopUp(org, "1000.00", 1);
        HttpHeaders admin = auth(org.getId(), UserRole.BUYER_ADMIN);
        String requestId = "req-" + UUID.randomUUID();

        var first = request(org.getId(), admin, requestId, "400.00");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("status")).isEqualTo("DONE");
        assertThat(new BigDecimal(first.getBody().get("amount").toString())).isEqualByComparingTo("400.00");
        assertThat((List<Map>) first.getBody().get("parts")).singleElement()
                .satisfies(p -> assertThat(p.get("status")).isEqualTo("DONE"));
        assertThat(first.getBody().get("createdAt")).isNotNull();

        var again = request(org.getId(), admin, requestId, "400.00");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(jdbc.queryForObject("select balance from wallet where org_id = ?", BigDecimal.class, org.getId()))
                .isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'REFUND' and org_id = ?",
                Long.class, org.getId())).isEqualTo(1L);
        assertThat(fake().refunds()).hasSize(1);

        var listed = list(org.getId(), admin);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).hasSize(1);
        assertThat(((Map) listed.getBody().get(0)).get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(list(org.getId(), auth(UUID.randomUUID(), UserRole.ADMIN)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void errorsCarryCodes() {
        Org org = buyer();
        HttpHeaders admin = auth(org.getId(), UserRole.BUYER_ADMIN);
        var nothing = request(org.getId(), admin, "req-" + UUID.randomUUID(), "100.00");
        assertThat(nothing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(nothing.getBody().get("code")).isEqualTo("NOTHING_REFUNDABLE");

        paidTopUp(org, "300.00", 1);
        var tooMuch = request(org.getId(), admin, "req-" + UUID.randomUUID(), "300.01");
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.getBody().get("code")).isEqualTo("REFUND_EXCEEDS_REFUNDABLE");

        var negative = request(org.getId(), admin, "req-" + UUID.randomUUID(), "-5");
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(negative.getBody().get("code")).isEqualTo("AMOUNT_INVALID");
    }

    @Test
    void rolesAndOrgsAreChecked() {
        Org org = buyer();
        paidTopUp(org, "500.00", 1);
        UUID id = org.getId();
        HttpHeaders approver = auth(id, UserRole.BUYER_APPROVER);
        HttpHeaders otherAdmin = auth(UUID.randomUUID(), UserRole.BUYER_ADMIN);
        HttpHeaders platformAdmin = auth(UUID.randomUUID(), UserRole.ADMIN);

        assertThat(refundable(id, approver, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refundable(id, otherAdmin, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(listStatus(id, approver)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(listStatus(id, otherAdmin)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(id, approver, "req-" + UUID.randomUUID(), "100.00").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(id, otherAdmin, "req-" + UUID.randomUUID(), "100.00").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // a platform admin may read but not refund
        assertThat(request(id, platformAdmin, "req-" + UUID.randomUUID(), "100.00").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(*) from buyer_refund where org_id = ?", Long.class, id))
                .isEqualTo(0L);
    }
}
