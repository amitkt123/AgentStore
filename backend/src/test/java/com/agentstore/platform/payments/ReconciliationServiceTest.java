package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationServiceTest extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 1, 15);
    static final Instant AT = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    PaymentProvider provider;
    @Autowired
    PaymentOrderRepository orders;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    MeterRegistry meters;
    @Autowired
    JdbcTemplate jdbc;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    @BeforeEach
    void clean() {
        fake().reset();
        jdbc.update("delete from reconciliation_issue where day = ?", Date.valueOf(DAY));
        // Orders and refunds this class paid on DAY in earlier tests would otherwise count as "missing at provider".
        jdbc.update("delete from buyer_refund_part where payment_order_id in "
                + "(select id from payment_order where paid_at = ?)", java.sql.Timestamp.from(AT));
        jdbc.update("delete from payment_order where paid_at = ?", java.sql.Timestamp.from(AT));
    }

    UUID org() {
        return fixtures.liveTierA(new BigDecimal("1000.00"), new BigDecimal("0.00")).buyer().getId();
    }

    PaymentOrder order(UUID orgId, PaymentOrder.Status status, String orderId, String paymentId, String amount, Instant paidAt) {
        PaymentOrder o = new PaymentOrder();
        o.setOrgId(orgId);
        o.setPurpose(PaymentOrder.Purpose.TOPUP);
        o.setMethod(PaymentMethod.UPI);
        o.setAmount(new BigDecimal(amount));
        o.setStatus(status);
        o.setProvider("fake");
        o.setProviderOrderId(orderId);
        o.setProviderPaymentId(paymentId);
        o.setPaidAt(paidAt);
        return orders.saveAndFlush(o);
    }

    String uniq(String p) {
        return p + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Issues for the fixed day, ignoring ledger-wide checks that other test classes' data could affect. */
    List<Map<String, Object>> issues() {
        return jdbc.queryForList("select kind, provider_ref, resolved_at from reconciliation_issue "
                + "where day = ? and kind <> 'LEDGER_CHECK_FAILED' order by kind, provider_ref", Date.valueOf(DAY));
    }

    @Test
    void cleanDayHasNoIssues() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_");
        order(org, PaymentOrder.Status.PAID, ord, pay, "500.00", AT);
        fake().seedPayment(pay, ord, new BigDecimal("500.00"), "captured", null, AT);

        reconciliation.reconcile(DAY);

        assertThat(issues()).isEmpty();
    }

    @Test
    void capturedButNotCredited() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_");
        order(org, PaymentOrder.Status.CREATED, ord, null, "500.00", null);
        fake().seedPayment(pay, ord, new BigDecimal("500.00"), "captured", null, AT);
        // a failed payment and an unknown order are not reported
        fake().seedPayment(uniq("pay_"), ord, new BigDecimal("500.00"), "failed", null, AT);
        fake().seedPayment(uniq("pay_"), "ord_unknown", new BigDecimal("5.00"), "captured", null, AT);

        int found = reconciliation.reconcile(DAY);

        assertThat(issues()).hasSize(1);
        assertThat(issues().get(0)).containsEntry("kind", "MISSING_LOCALLY").containsEntry("provider_ref", pay);
        assertThat(found).isGreaterThanOrEqualTo(1);
    }

    @Test
    void paidButUnknownToProvider() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_");
        order(org, PaymentOrder.Status.PAID, ord, pay, "500.00", AT);

        reconciliation.reconcile(DAY);

        assertThat(issues()).hasSize(1);
        assertThat(issues().get(0)).containsEntry("kind", "MISSING_AT_PROVIDER").containsEntry("provider_ref", pay);
    }

    @Test
    void amountDifferenceIsFlagged() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_");
        order(org, PaymentOrder.Status.PAID, ord, pay, "500.00", AT);
        fake().seedPayment(pay, ord, new BigDecimal("450.00"), "captured", null, AT);

        reconciliation.reconcile(DAY);

        assertThat(issues()).hasSize(1);
        assertThat(issues().get(0)).containsEntry("kind", "AMOUNT_MISMATCH").containsEntry("provider_ref", pay);
    }

    @Test
    void refundStatusMismatch() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_"), rfnd = uniq("rfnd_");
        PaymentOrder o = order(org, PaymentOrder.Status.PAID, ord, pay, "500.00", AT);
        fake().seedPayment(pay, ord, new BigDecimal("500.00"), "captured", null, AT);
        UUID refundId = UUID.randomUUID();
        jdbc.update("insert into buyer_refund (id, org_id, request_id, amount, status) values (?, ?, ?, 100, 'PROCESSING')",
                refundId, org, uniq("req-"));
        jdbc.update("insert into buyer_refund_part (id, refund_id, payment_order_id, amount, provider_refund_id, status) "
                + "values (gen_random_uuid(), ?, ?, 100, ?, 'PROCESSING')", refundId, o.getId(), rfnd);
        fake().seedRefund(rfnd, pay, new BigDecimal("100.00"), "PROCESSED", "r-1", AT);

        reconciliation.reconcile(DAY);

        assertThat(issues()).hasSize(1);
        assertThat(issues().get(0)).containsEntry("kind", "REFUND_MISMATCH").containsEntry("provider_ref", rfnd);
    }

    @Test
    void providerRefundWithNoLocalPartIsFlagged() {
        UUID org = org();
        String ord = uniq("ord_"), pay = uniq("pay_");
        PaymentOrder o = order(org, PaymentOrder.Status.PAID, ord, pay, "500.00", AT);
        fake().seedPayment(pay, ord, new BigDecimal("500.00"), "captured", null, AT);
        // Made outside Karya (e.g. in the dashboard): no part, no matching receipt.
        String outside = uniq("rfnd_");
        fake().seedRefund(outside, pay, new BigDecimal("50.00"), "PROCESSED", null, AT);
        // Made by us but its provider id not stored yet (timeout): the receipt is a local part id.
        UUID refundId = UUID.randomUUID(), partId = UUID.randomUUID();
        jdbc.update("insert into buyer_refund (id, org_id, request_id, amount, status) values (?, ?, ?, 100, 'PROCESSING')",
                refundId, org, uniq("req-"));
        jdbc.update("insert into buyer_refund_part (id, refund_id, payment_order_id, amount, status) "
                + "values (?, ?, ?, 100, 'PENDING')", partId, refundId, o.getId());
        fake().seedRefund(uniq("rfnd_"), pay, new BigDecimal("100.00"), "PENDING", partId.toString(), AT);

        reconciliation.reconcile(DAY);

        assertThat(issues()).hasSize(1);
        assertThat(issues().get(0)).containsEntry("kind", "REFUND_UNMATCHED").containsEntry("provider_ref", outside);
    }

    @Test
    void providerRefundUnderAnHourOldIsNotFlaggedYet() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        Instant started = Instant.now();
        Instant recent = started.minusSeconds(600);
        if (!recent.atZone(java.time.ZoneOffset.UTC).toLocalDate().equals(today)) recent = started;
        String young = uniq("rfnd_");
        String old = uniq("rfnd_");
        fake().seedRefund(young, uniq("pay_"), new BigDecimal("10.00"), "PROCESSED", null, recent);
        Instant hourAgo = started.minusSeconds(7200);
        boolean oldIsToday = hourAgo.atZone(java.time.ZoneOffset.UTC).toLocalDate().equals(today);
        if (oldIsToday) fake().seedRefund(old, uniq("pay_"), new BigDecimal("10.00"), "PROCESSED", null, hourAgo);
        try {
            reconciliation.reconcile(today);

            assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where provider_ref = ?",
                    Integer.class, young)).isZero();
            if (oldIsToday) {
                assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where provider_ref = ? "
                        + "and kind = 'REFUND_UNMATCHED'", Integer.class, old)).isEqualTo(1);
            }
        } finally {
            // Today's run also flagged other classes' data; drop only what it recorded.
            jdbc.update("delete from reconciliation_issue where day = ? and created_at >= ?", Date.valueOf(today),
                    java.sql.Timestamp.from(started));
        }
    }

    @Test
    void feesPostedOnce() {
        String settlement = uniq("setl_");
        fake().seedSettlement(settlement, new BigDecimal("23.60"), AT);
        UUID fees = ledgerAccounts.platformAccount(LedgerAccountKind.GATEWAY_FEES);
        BigDecimal before = ledgerService.balanceOf(fees);

        reconciliation.reconcile(DAY);
        reconciliation.reconcile(DAY);

        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where idempotency_key = ?",
                Integer.class, "gateway-fee:" + settlement)).isEqualTo(1);
        assertThat(ledgerService.balanceOf(fees).subtract(before)).isEqualByComparingTo("-23.60");
        assertThat(jdbc.queryForObject("select type from ledger_transaction where idempotency_key = ?",
                String.class, "gateway-fee:" + settlement)).isEqualTo("ADJUSTMENT");
    }

    @Test
    void rerunDoesNotDuplicateIssues() {
        UUID org = org();
        order(org, PaymentOrder.Status.PAID, uniq("ord_"), uniq("pay_"), "500.00", AT);

        reconciliation.reconcile(DAY);
        reconciliation.reconcile(DAY);

        assertThat(issues()).hasSize(1);
    }

    @Test
    void gaugeCountsUnresolved() {
        UUID org = org();
        order(org, PaymentOrder.Status.PAID, uniq("ord_"), uniq("pay_"), "500.00", AT);
        order(org, PaymentOrder.Status.PAID, uniq("ord_"), uniq("pay_"), "600.00", AT);
        reconciliation.reconcile(DAY);
        double before = meters.get("agentstore.money.reconciliation_issues").gauge().value();

        UUID one = jdbc.queryForObject("select id from reconciliation_issue where day = ? and kind = 'MISSING_AT_PROVIDER' "
                + "limit 1", UUID.class, Date.valueOf(DAY));
        assertThat(reconciliation.resolve(one, "checked with the bank")).isTrue();

        assertThat(meters.get("agentstore.money.reconciliation_issues").gauge().value()).isEqualTo(before - 1);
    }
}
