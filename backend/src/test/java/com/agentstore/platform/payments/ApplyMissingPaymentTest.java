package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code POST /api/v1/admin/payments/payments/{id}/apply}: the safe way to resolve a MISSING_LOCALLY issue. */
class ApplyMissingPaymentTest extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 3, 11);
    static final Instant AT = Instant.parse("2026-03-11T08:00:00Z");

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    PaymentProvider provider;
    @Autowired
    PaymentOrderRepository orders;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    JdbcTemplate jdbc;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    @BeforeEach
    void clean() {
        fake().reset();
        jdbc.update("delete from reconciliation_issue where day = ?", Date.valueOf(DAY));
    }

    HttpHeaders auth(UserRole role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), role));
        return h;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> apply(String paymentId, UserRole role) {
        return rest.exchange("http://localhost:" + port + "/api/v1/admin/payments/payments/" + paymentId + "/apply",
                HttpMethod.POST, new HttpEntity<>(null, auth(role)), Map.class);
    }

    String uniq(String p) {
        return p + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    UUID org() {
        return fixtures.liveTierA(new BigDecimal("1000.00"), new BigDecimal("0.00")).buyer().getId();
    }

    BigDecimal balance(UUID org) {
        return jdbc.queryForObject("select balance from wallet where org_id = ?", BigDecimal.class, org);
    }

    PaymentOrder createdTopUp(UUID org, String providerOrderId, String amount) {
        PaymentOrder o = new PaymentOrder();
        o.setOrgId(org);
        o.setPurpose(PaymentOrder.Purpose.TOPUP);
        o.setMethod(PaymentMethod.UPI);
        o.setAmount(new BigDecimal(amount));
        o.setStatus(PaymentOrder.Status.CREATED);
        o.setProvider("fake");
        o.setProviderOrderId(providerOrderId);
        return orders.saveAndFlush(o);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void creditsAMissingTopUpOnceAndResolvesTheIssue() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_");
        PaymentOrder order = createdTopUp(org, ord, "500.00");
        fake().seedPayment(pay, ord, new BigDecimal("500.00"), "captured", null, AT);
        reconciliation.reconcile(DAY);
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where provider_ref = ? "
                + "and kind = 'MISSING_LOCALLY' and resolved_at is null", Integer.class, pay)).isEqualTo(1);
        BigDecimal before = balance(org);

        assertThat(apply(pay, UserRole.BUYER_ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balance(org)).isEqualByComparingTo(before);

        ResponseEntity<Map> first = apply(pay, UserRole.ADMIN);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("result", "CREDITED").containsEntry("paymentOrderId",
                order.getId().toString()).containsEntry("issuesResolved", 1);
        assertThat(balance(org).subtract(before)).isEqualByComparingTo("500.00");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where provider_ref = ? "
                + "and resolved_at is null", Integer.class, pay)).isZero();

        ResponseEntity<Map> again = apply(pay, UserRole.ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("result", "ALREADY_CREDITED");
        assertThat(balance(org).subtract(before)).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where idempotency_key = ?",
                Integer.class, "payment:" + pay)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void creditsABankTransferToAKnownVirtualAccount() {
        UUID org = org();
        String va = uniq("fake_va_"), pay = uniq("pay_");
        jdbc.update("insert into virtual_account (org_id, provider, provider_va_id, account_number, ifsc, beneficiary_name) "
                + "values (?, 'fake', ?, ?, 'FAKE0000001', 'Acme')", org, va, uniq("ACC"));
        fake().seedPayment(pay, null, new BigDecimal("2500.00"), "captured", va, AT);
        BigDecimal before = balance(org);

        ResponseEntity<Map> res = apply(pay, UserRole.ADMIN);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("result", "CREDITED");
        assertThat(balance(org).subtract(before)).isEqualByComparingTo("2500.00");
        PaymentOrder order = orders.findByProviderAndProviderOrderId("fake", "va:" + pay).orElseThrow();
        assertThat(order.getMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(order.getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(apply(pay, UserRole.ADMIN).getBody()).containsEntry("result", "ALREADY_CREDITED");
        assertThat(balance(org).subtract(before)).isEqualByComparingTo("2500.00");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void refusesWhatItCannotSafelyCredit() {
        UUID org = org();
        BigDecimal before = balance(org);

        ResponseEntity<Map> unknown = apply(uniq("pay_"), UserRole.ADMIN);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).containsEntry("code", "PAYMENT_NOT_FOUND");

        String ord = uniq("ord_");
        createdTopUp(org, ord, "300.00");
        String failed = uniq("pay_");
        fake().seedPayment(failed, ord, new BigDecimal("300.00"), "failed", null, AT);
        ResponseEntity<Map> notCaptured = apply(failed, UserRole.ADMIN);
        assertThat(notCaptured.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notCaptured.getBody()).containsEntry("code", "PAYMENT_NOT_CAPTURED");

        String stranger = uniq("pay_");
        fake().seedPayment(stranger, "ord_nobody_" + stranger, new BigDecimal("300.00"), "captured", null, AT);
        ResponseEntity<Map> noOrder = apply(stranger, UserRole.ADMIN);
        assertThat(noOrder.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noOrder.getBody()).containsEntry("code", "ORDER_UNKNOWN");

        String wrong = uniq("pay_");
        fake().seedPayment(wrong, ord, new BigDecimal("299.00"), "captured", null, AT);
        ResponseEntity<Map> mismatch = apply(wrong, UserRole.ADMIN);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mismatch.getBody()).containsEntry("code", "AMOUNT_MISMATCH");

        assertThat(balance(org)).isEqualByComparingTo(before);
    }
}
