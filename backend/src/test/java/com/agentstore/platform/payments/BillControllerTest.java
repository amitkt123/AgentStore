package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.ledger.BillingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    BuyerBillRepository bills;
    @Autowired
    PaymentProvider provider;

    @BeforeEach
    void reset() {
        ((FakePaymentProvider) provider).reset();
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    Org buyer(BillingMode mode) {
        Org org = new Org(null, "BillCo-" + UUID.randomUUID(), OrgType.BUYER);
        org.setBillingMode(mode);
        return orgRepository.save(org);
    }

    BuyerBill bill(UUID orgId, String period, String amount, BuyerBill.Status status) {
        BuyerBill b = new BuyerBill();
        b.setOrgId(orgId);
        b.setPeriod(period);
        b.setAmount(new BigDecimal(amount));
        b.setStatus(status);
        b.setDueAt(Instant.now().plus(Duration.ofDays(10)));
        if (status == BuyerBill.Status.PAID) b.setPaidAt(Instant.now());
        return bills.save(b);
    }

    HttpHeaders auth(UUID orgId, UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), orgId, role));
        return h;
    }

    ResponseEntity<List> list(UUID orgId, HttpHeaders h) {
        return rest.exchange(url("/api/v1/orgs/" + orgId + "/bills"), HttpMethod.GET, new HttpEntity<>(h), List.class);
    }

    HttpStatusCode listStatus(UUID orgId, HttpHeaders h) {
        return rest.exchange(url("/api/v1/orgs/" + orgId + "/bills"), HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode();
    }

    ResponseEntity<Map> pay(UUID orgId, UUID billId, HttpHeaders h, String method) {
        return rest.exchange(url("/api/v1/orgs/" + orgId + "/bills/" + billId + "/pay"), HttpMethod.POST,
                new HttpEntity<>(Map.of("method", method), h), Map.class);
    }

    @Test
    void listsNewestFirst() {
        Org org = buyer(BillingMode.POSTPAID);
        bill(org.getId(), "2026-08", "80.00", BuyerBill.Status.PAID);
        BuyerBill sept = bill(org.getId(), "2026-09", "120.50", BuyerBill.Status.OPEN);
        bill(buyer(BillingMode.POSTPAID).getId(), "2026-09", "5.00", BuyerBill.Status.OPEN);   // another org's

        var r = list(org.getId(), auth(org.getId(), UserRole.BUYER_ADMIN));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> body = r.getBody();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("id")).isEqualTo(sept.getId().toString());
        assertThat(body.get(0).get("period")).isEqualTo("2026-09");
        assertThat(new BigDecimal(body.get(0).get("amount").toString())).isEqualByComparingTo("120.50");
        assertThat(body.get(0).get("status")).isEqualTo("OPEN");
        assertThat(body.get(0).get("dueAt")).isNotNull();
        assertThat(body.get(0).get("paidAt")).isNull();
        assertThat(body.get(1).get("period")).isEqualTo("2026-08");
        assertThat(body.get(1).get("status")).isEqualTo("PAID");
        assertThat(body.get(1).get("paidAt")).isNotNull();
    }

    @Test
    void payCreatesAnOrderForTheBillAmountAndTheCapturePaysIt() {
        Org org = buyer(BillingMode.POSTPAID);
        BuyerBill b = bill(org.getId(), "2026-09", "120.50", BuyerBill.Status.OPEN);
        HttpHeaders admin = auth(org.getId(), UserRole.BUYER_ADMIN);

        var r = pay(org.getId(), b.getId(), admin, "UPI");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map order = (Map) r.getBody().get("order");
        assertThat(order.get("purpose")).isEqualTo("BILL");
        assertThat(new BigDecimal(order.get("amount").toString())).isEqualByComparingTo("120.50");
        assertThat(order.get("status")).isEqualTo("CREATED");
        assertThat(r.getBody().get("providerOrderId")).isNotNull();

        var sim = rest.exchange(url("/api/v1/dev/payments/simulate"), HttpMethod.POST,
                new HttpEntity<>(Map.of("orderId", order.get("id"), "outcome", "PAID"), admin), Map.class);
        assertThat(sim.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map listed = (Map) list(org.getId(), admin).getBody().get(0);
        assertThat(listed.get("status")).isEqualTo("PAID");
        assertThat(listed.get("paidAt")).isNotNull();
        // A paid bill cannot be paid again.
        var again = pay(org.getId(), b.getId(), admin, "UPI");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("BILL_NOT_PAYABLE");
    }

    @Test
    void overdueBillCanBePaid() {
        Org org = buyer(BillingMode.POSTPAID);
        BuyerBill b = bill(org.getId(), "2026-07", "10.00", BuyerBill.Status.OVERDUE);

        assertThat(pay(org.getId(), b.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), "CARD").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void paidOrOtherOrgsOrUnknownBillIsNotPayable() {
        Org org = buyer(BillingMode.POSTPAID);
        HttpHeaders admin = auth(org.getId(), UserRole.BUYER_ADMIN);
        BuyerBill paid = bill(org.getId(), "2026-08", "80.00", BuyerBill.Status.PAID);
        BuyerBill others = bill(buyer(BillingMode.POSTPAID).getId(), "2026-09", "5.00", BuyerBill.Status.OPEN);

        for (UUID billId : List.of(paid.getId(), others.getId(), UUID.randomUUID())) {
            var r = pay(org.getId(), billId, admin, "UPI");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code")).isEqualTo("BILL_NOT_PAYABLE");
        }
    }

    @Test
    void prepaidOrgHasNoBillsToPay() {
        Org org = buyer(BillingMode.PREPAID);
        BuyerBill b = bill(org.getId(), "2026-09", "50.00", BuyerBill.Status.OPEN);

        var r = pay(org.getId(), b.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), "UPI");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("PREPAID_NO_BILLS");
    }

    @Test
    void bankTransferCannotPayABill() {
        Org org = buyer(BillingMode.POSTPAID);
        BuyerBill b = bill(org.getId(), "2026-09", "50.00", BuyerBill.Status.OPEN);

        var r = pay(org.getId(), b.getId(), auth(org.getId(), UserRole.BUYER_ADMIN), "BANK_TRANSFER");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("PAYMENT_METHOD_DISABLED");
    }

    @Test
    void roles() {
        Org org = buyer(BillingMode.POSTPAID);
        BuyerBill b = bill(org.getId(), "2026-09", "50.00", BuyerBill.Status.OPEN);
        UUID orgId = org.getId();

        assertThat(listStatus(orgId, auth(UUID.randomUUID(), UserRole.ADMIN))).isEqualTo(HttpStatus.OK);
        assertThat(listStatus(orgId, auth(orgId, UserRole.BUYER_MEMBER))).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(listStatus(orgId, auth(UUID.randomUUID(), UserRole.BUYER_ADMIN)))
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(pay(orgId, b.getId(), auth(UUID.randomUUID(), UserRole.ADMIN), "UPI").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pay(orgId, b.getId(), auth(orgId, UserRole.BUYER_MEMBER), "UPI").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pay(orgId, b.getId(), auth(UUID.randomUUID(), UserRole.BUYER_ADMIN), "UPI").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bills.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BuyerBill.Status.OPEN);
    }
}
