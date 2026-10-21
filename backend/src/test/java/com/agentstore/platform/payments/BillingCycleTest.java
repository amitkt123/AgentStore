package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.BillingModeService;
import com.agentstore.platform.ledger.InsufficientFundsException;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.UsageEvent;
import com.agentstore.platform.ledger.UsageEventRepository;
import com.agentstore.platform.ledger.WalletRepository;
import com.agentstore.platform.ledger.WalletReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingCycleTest extends AbstractIntegrationTest {

    @Autowired
    BillingCycleService billing;
    @Autowired
    BuyerBillRepository bills;
    @Autowired
    PaymentOrderRepository orders;
    @Autowired
    TopUpService topUps;
    @Autowired
    WebhookProcessor processor;
    @Autowired
    PaymentProvider provider;
    @Autowired
    ExecutionFixtures fixtures;
    @Autowired
    BillingModeService billingModes;
    @Autowired
    WalletReservationService reservations;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    UsageEventRepository usageEvents;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    @BeforeEach
    void reset() {
        fake().reset();
    }

    record Setup(ExecutionFixtures.Scenario scenario, Run run) {
        UUID orgId() {
            return scenario.buyer().getId();
        }
    }

    Setup postpaid() {
        ExecutionFixtures.Scenario scenario = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("0.00"));
        billingModes.setMode(scenario.buyer().getId(), BillingMode.POSTPAID);
        Org org = orgRepository.findById(scenario.buyer().getId()).orElseThrow();
        org.setCreditLimit(new BigDecimal("10000.00"));
        orgRepository.save(org);
        Run run = fixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID());
        return new Setup(scenario, run);
    }

    /** Charges {@code amount} to the run and backdates the charge's transaction to {@code at}. */
    void chargeAt(Setup s, String amount, Instant at) {
        UUID eventId = usageEvents.save(new UsageEvent(null, s.run().getId(), s.orgId(), BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount))).getId();
        ledgerService.chargeUsageEvent(eventId);
        // Past the immutability trigger, which replica mode skips for this transaction only.
        tx.executeWithoutResult(st -> {
            jdbc.execute("set local session_replication_role = replica");
            jdbc.update("update ledger_transaction set created_at = ? where idempotency_key = ?",
                    Timestamp.from(at), "usage:" + eventId);
        });
    }

    List<BuyerBill> billsOf(UUID orgId) {
        return bills.findByOrgIdOrderByPeriodDesc(orgId);
    }

    BuyerBill bill(UUID orgId, String period, String amount, BuyerBill.Status status, Instant dueAt) {
        BuyerBill b = new BuyerBill();
        b.setOrgId(orgId);
        b.setPeriod(period);
        b.setAmount(new BigDecimal(amount));
        b.setStatus(status);
        b.setDueAt(dueAt);
        return bills.save(b);
    }

    /** Creates a bill order for the bill and captures it through a signed fake webhook. */
    String payBill(BuyerBill b) {
        CheckoutResponse c = topUps.createOrder(b.getOrgId(), UUID.randomUUID(), "BILL", b.getId(), b.getAmount(),
                PaymentMethod.UPI);
        return capture(c.providerOrderId(), b.getAmount());
    }

    String capture(String providerOrderId, BigDecimal amount) {
        String paymentId = "pay_" + UUID.randomUUID();
        fake().capture(providerOrderId, paymentId);
        byte[] body = fake().webhookBody(new PaymentWebhookEvent("evt_" + UUID.randomUUID(),
                PaymentWebhookEvent.Kind.PAYMENT_CAPTURED, providerOrderId, paymentId, null, null, amount,
                PaymentMethod.UPI, null));
        processor.process("fake", body, fake().signedHeaders(body));
        return paymentId;
    }

    @Test
    void billsExactlyPreviousMonth() {
        Setup s = postpaid();
        chargeAt(s, "40.00", Instant.parse("2026-09-01T00:00:00Z"));
        chargeAt(s, "60.00", Instant.parse("2026-09-30T23:59:59Z"));
        chargeAt(s, "25.00", Instant.parse("2026-10-01T00:00:00Z"));

        billing.issueBills(YearMonth.of(2026, 9));
        billing.issueBills(YearMonth.of(2026, 9));

        assertThat(billsOf(s.orgId())).singleElement().satisfies(b -> {
            assertThat(b.getPeriod()).isEqualTo("2026-09");
            assertThat(b.getAmount()).isEqualByComparingTo("100.00");
            assertThat(b.getStatus()).isEqualTo(BuyerBill.Status.OPEN);
            assertThat(b.getDueAt()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
            assertThat(b.getPaidAt()).isNull();
        });
    }

    @Test
    void prepaidAndZeroOrgsGetNoBill() {
        ExecutionFixtures.Scenario prepaid = fixtures.liveTierA(new BigDecimal("100000.00"), new BigDecimal("1000.00"));
        Setup prepaidSetup = new Setup(prepaid,
                fixtures.run(prepaid, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), "tok-" + UUID.randomUUID()));
        chargeAt(prepaidSetup, "50.00", Instant.parse("2026-09-10T00:00:00Z"));
        Setup idle = postpaid();   // postpaid, but nothing charged in September
        chargeAt(idle, "30.00", Instant.parse("2026-10-02T00:00:00Z"));

        billing.issueBills(YearMonth.of(2026, 9));

        assertThat(billsOf(prepaid.buyer().getId())).isEmpty();
        assertThat(billsOf(idle.orgId())).isEmpty();
    }

    @Test
    void overdueBlocksRuns() {
        Setup s = postpaid();
        chargeAt(s, "100.00", Instant.parse("2026-08-15T00:00:00Z"));
        BuyerBill b = bill(s.orgId(), "2026-08", "100.00", BuyerBill.Status.OPEN,
                Instant.now().minus(Duration.ofDays(1)));
        assertThat(billing.hasOverdueBill(s.orgId())).isTrue();

        assertThatThrownBy(() -> reservations.reserve(s.orgId(), new BigDecimal("10.00")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("A bill is overdue; pay it to start new runs");
        assertThat(walletRepository.findByOrgId(s.orgId()).orElseThrow().getReserved()).isEqualByComparingTo("0");

        payBill(b);

        assertThat(billing.hasOverdueBill(s.orgId())).isFalse();
        reservations.reserve(s.orgId(), new BigDecimal("10.00"));
        assertThat(walletRepository.findByOrgId(s.orgId()).orElseThrow().getReserved()).isEqualByComparingTo("10.00");
    }

    @Test
    void openBillNotYetDueDoesNotBlock() {
        Setup s = postpaid();
        bill(s.orgId(), "2026-08", "100.00", BuyerBill.Status.OPEN, Instant.now().plus(Duration.ofDays(1)));

        assertThat(billing.hasOverdueBill(s.orgId())).isFalse();
        reservations.reserve(s.orgId(), new BigDecimal("10.00"));
    }

    @Test
    void payingClearsReceivable() {
        Setup s = postpaid();
        chargeAt(s, "100.00", Instant.parse("2026-09-15T00:00:00Z"));
        billing.issueBills(YearMonth.of(2026, 9));
        BuyerBill b = billsOf(s.orgId()).get(0);
        assertThat(billingModes.owed(s.orgId())).isEqualByComparingTo("100.00");
        BigDecimal walletBefore = walletRepository.findByOrgId(s.orgId()).orElseThrow().getBalance();

        String paymentId = payBill(b);

        assertThat(billingModes.owed(s.orgId())).isEqualByComparingTo("0.00");
        BuyerBill paid = bills.findById(b.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(BuyerBill.Status.PAID);
        assertThat(paid.getPaidAt()).isNotNull();
        PaymentOrder order = orders.findAll().stream().filter(o -> b.getId().equals(o.getBillId())).findFirst()
                .orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(order.getProviderPaymentId()).isEqualTo(paymentId);
        // Bill payments never touch the wallet balance.
        assertThat(walletRepository.findByOrgId(s.orgId()).orElseThrow().getBalance()).isEqualByComparingTo(walletBefore);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'BILL_PAYMENT' "
                + "and idempotency_key = ? and org_id = ?", Long.class, "payment:" + paymentId, s.orgId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.bill_paid' "
                + "and org_id = ?", Long.class, s.orgId())).isEqualTo(1L);
        assertThat(jdbc.queryForList(
                "select transaction_id from ledger_posting group by transaction_id having sum(amount) <> 0"))
                .as("unbalanced transactions").isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ledger_account_balance b where b.balance <> "
                + "(select coalesce(sum(p.amount), 0) from ledger_posting p where p.account_id = b.account_id)",
                Long.class)).as("cached balances off their postings").isZero();

        // Redelivering the capture under a new event id credits nothing more.
        byte[] body = fake().webhookBody(new PaymentWebhookEvent("evt_" + UUID.randomUUID(),
                PaymentWebhookEvent.Kind.PAYMENT_CAPTURED, order.getProviderOrderId(), paymentId, null, null,
                b.getAmount(), PaymentMethod.UPI, null));
        processor.process("fake", body, fake().signedHeaders(body));
        assertThat(billingModes.owed(s.orgId())).isEqualByComparingTo("0.00");
    }

    @Test
    void secondOrderOnPaidBillIsAnIssueNotACredit() {
        Setup s = postpaid();
        chargeAt(s, "100.00", Instant.parse("2026-09-15T00:00:00Z"));
        billing.issueBills(YearMonth.of(2026, 9));
        BuyerBill b = billsOf(s.orgId()).get(0);
        CheckoutResponse first = topUps.createOrder(s.orgId(), UUID.randomUUID(), "BILL", b.getId(), b.getAmount(),
                PaymentMethod.UPI);
        CheckoutResponse second = topUps.createOrder(s.orgId(), UUID.randomUUID(), "BILL", b.getId(), b.getAmount(),
                PaymentMethod.CARD);
        capture(first.providerOrderId(), b.getAmount());

        String secondPay = capture(second.providerOrderId(), b.getAmount());

        assertThat(billingModes.owed(s.orgId())).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'BILL_PAYMENT' "
                + "and org_id = ?", Long.class, s.orgId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where kind = 'DUPLICATE_PAYMENT' "
                + "and provider_ref = ?", Long.class, secondPay)).isEqualTo(1L);
        assertThat(orders.findById(second.order().id()).orElseThrow().getStatus())
                .isEqualTo(PaymentOrder.Status.CREATED);
    }

    @Test
    void maintenanceExpiresOldOrdersAndMarksOverdue() {
        Setup s = postpaid();
        BuyerBill late = bill(s.orgId(), "2026-07", "10.00", BuyerBill.Status.OPEN,
                Instant.now().minus(Duration.ofHours(1)));
        BuyerBill notDue = bill(s.orgId(), "2026-08", "20.00", BuyerBill.Status.OPEN,
                Instant.now().plus(Duration.ofDays(3)));
        BuyerBill paid = bill(s.orgId(), "2026-06", "30.00", BuyerBill.Status.PAID,
                Instant.now().minus(Duration.ofDays(30)));
        CheckoutResponse old = topUps.createOrder(s.orgId(), UUID.randomUUID(), "BILL", notDue.getId(),
                notDue.getAmount(), PaymentMethod.UPI);
        CheckoutResponse fresh = topUps.createOrder(s.orgId(), UUID.randomUUID(), "BILL", notDue.getId(),
                notDue.getAmount(), PaymentMethod.UPI);
        CheckoutResponse paidOld = topUps.createOrder(s.orgId(), UUID.randomUUID(), "BILL", notDue.getId(),
                notDue.getAmount(), PaymentMethod.UPI);
        jdbc.update("update payment_order set created_at = ? where id in (?, ?)",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))), old.order().id(), paidOld.order().id());
        jdbc.update("update payment_order set status = 'PAID' where id = ?", paidOld.order().id());

        billing.markOverdueBills();
        topUps.expireStaleOrders();

        assertThat(bills.findById(late.getId()).orElseThrow().getStatus()).isEqualTo(BuyerBill.Status.OVERDUE);
        assertThat(bills.findById(notDue.getId()).orElseThrow().getStatus()).isEqualTo(BuyerBill.Status.OPEN);
        assertThat(bills.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(BuyerBill.Status.PAID);
        assertThat(orders.findById(old.order().id()).orElseThrow().getStatus()).isEqualTo(PaymentOrder.Status.EXPIRED);
        assertThat(orders.findById(fresh.order().id()).orElseThrow().getStatus()).isEqualTo(PaymentOrder.Status.CREATED);
        assertThat(orders.findById(paidOld.order().id()).orElseThrow().getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        // An OVERDUE bill still blocks runs, and still counts as overdue.
        assertThat(billing.hasOverdueBill(s.orgId())).isTrue();
    }
}
