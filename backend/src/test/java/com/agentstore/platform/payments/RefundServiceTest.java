package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefundServiceTest extends AbstractIntegrationTest {

    /** Orgs touched by this class, checked together by {@link #ledgerBalances()}. */
    static final Set<UUID> ORGS = ConcurrentHashMap.newKeySet();

    @Autowired
    RefundService refunds;
    @Autowired
    WebhookProcessor processor;
    @Autowired
    PaymentProvider provider;
    @Autowired
    TopUpService topUps;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    PaymentOrderRepository orders;
    @Autowired
    BuyerRefundPartRepository parts;
    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;
    @PersistenceContext
    EntityManager entityManager;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    @BeforeEach
    @AfterEach
    void clearHooks() {
        fake().reset();
    }

    Org buyer() {
        Org org = orgRepository.save(new Org(null, "RefundCo-" + UUID.randomUUID(), OrgType.BUYER));
        ORGS.add(org.getId());
        return org;
    }

    /** A PAID top-up through the real path (order, fake capture, signed webhook), with paid_at set to {@code paidAt}. */
    PaymentOrder paidTopUp(Org org, String amount, Instant paidAt) {
        CheckoutResponse c = topUps.createTopUp(org.getId(), UUID.randomUUID(), new BigDecimal(amount), PaymentMethod.UPI);
        String paymentId = "pay_" + UUID.randomUUID();
        fake().capture(c.providerOrderId(), paymentId);
        send(new PaymentWebhookEvent("evt_" + UUID.randomUUID(), PaymentWebhookEvent.Kind.PAYMENT_CAPTURED,
                c.providerOrderId(), paymentId, null, null, new BigDecimal(amount), PaymentMethod.UPI, null));
        jdbc.update("update payment_order set paid_at = ? where id = ?", Timestamp.from(paidAt), c.order().id());
        PaymentOrder o = orders.findById(c.order().id()).orElseThrow();
        assertThat(o.getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        return o;
    }

    WebhookProcessor.Outcome send(PaymentWebhookEvent e) {
        byte[] body = fake().webhookBody(e);
        return processor.process("fake", body, fake().signedHeaders(body));
    }

    WebhookProcessor.Outcome refundEvent(PaymentWebhookEvent.Kind kind, String providerRefundId) {
        return send(new PaymentWebhookEvent("evt_" + UUID.randomUUID(), kind, null, null, providerRefundId, null,
                null, null, kind == PaymentWebhookEvent.Kind.REFUND_FAILED ? "bank rejected" : null));
    }

    /** An admin adjustment credit, posted the way AdminMoneyController does it (wallet lock, ledger, dual-write). */
    void adjust(Org org, String amount) {
        BigDecimal a = new BigDecimal(amount);
        tx.executeWithoutResult(s -> {
            UUID external = ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL);
            walletRepository.insertEmptyIfMissing(org.getId());
            Wallet w = walletRepository.findByOrgId(org.getId()).orElseThrow();
            entityManager.refresh(w, LockModeType.PESSIMISTIC_WRITE);
            UUID account = ledgerAccounts.buyerWallet(w);
            ledgerService.post(LedgerTransactionType.ADJUSTMENT, "adjustment:" + UUID.randomUUID(), null, org.getId(),
                    Map.of("reason", "test"), List.of(new Posting(account, a), new Posting(external, a.negate())));
            w.setBalance(w.getBalance().add(a));
            walletRepository.save(w);
        });
    }

    BigDecimal wallet(UUID orgId) {
        return walletRepository.balanceOfOrg(orgId).orElse(BigDecimal.ZERO);
    }

    Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    BigDecimal refunded(PaymentOrder o) {
        return orders.findById(o.getId()).orElseThrow().getRefundedAmount();
    }

    String paymentId(PaymentOrder o) {
        return orders.findById(o.getId()).orElseThrow().getProviderPaymentId();
    }

    @Test
    @Order(1)
    void splitsNewestFirst() {
        Org org = buyer();
        PaymentOrder day1 = paidTopUp(org, "1000.00", daysAgo(2));
        PaymentOrder day2 = paidTopUp(org, "500.00", daysAgo(1));

        List<RefundService.Part> plan = refunds.plan(org.getId(), new BigDecimal("700.00"));
        assertThat(plan).extracting(RefundService.Part::paymentOrderId).containsExactly(day2.getId(), day1.getId());
        assertThat(plan.get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(plan.get(1).amount()).isEqualByComparingTo("200.00");
        assertThat(plan.get(0).method()).isEqualTo(PaymentMethod.UPI);

        RefundView v = refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(), new BigDecimal("700.00"));
        assertThat(v.status()).isEqualTo("DONE");
        assertThat(v.parts()).extracting(RefundView.PartView::paymentOrderId).containsExactly(day2.getId(), day1.getId());
        assertThat(v.parts()).extracting(RefundView.PartView::status).containsExactly("DONE", "DONE");
        assertThat(wallet(org.getId())).isEqualByComparingTo("800.00");
        assertThat(refunded(day2)).isEqualByComparingTo("500.00");
        assertThat(refunded(day1)).isEqualByComparingTo("200.00");
        assertThat(fake().refunds()).filteredOn(r -> r.getPaymentId().equals(paymentId(day1)))
                .singleElement().satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("200.00"));
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.refund_done' "
                + "and aggregate_id = ?", Long.class, v.id())).isEqualTo(1L);
        // what is left is the rest of the day-1 order
        assertThat(refunds.refundable(org.getId())).isEqualByComparingTo("800.00");
    }

    @Test
    @Order(2)
    void adjustmentMoneyIsNotRefundable() {
        Org org = buyer();
        adjust(org, "1000.00");
        paidTopUp(org, "300.00", daysAgo(1));
        assertThat(refunds.refundable(org.getId())).isEqualByComparingTo("300.00");
        assertThatThrownBy(() -> refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(),
                new BigDecimal("300.01")))
                .isInstanceOf(PaymentApiException.class)
                .extracting("code").isEqualTo("REFUND_EXCEEDS_REFUNDABLE");
    }

    @Test
    @Order(3)
    void heldMoneyIsNotRefundable() {
        Org org = buyer();
        paidTopUp(org, "1000.00", daysAgo(1));
        jdbc.update("update wallet set reserved = 800.00 where org_id = ?", org.getId());
        assertThat(refunds.refundable(org.getId())).isEqualByComparingTo("200.00");
        long refundPosts = jdbc.queryForObject("select count(*) from ledger_transaction where type = 'REFUND' "
                + "and org_id = ?", Long.class, org.getId());
        assertThatThrownBy(() -> refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(),
                new BigDecimal("201.00")))
                .isInstanceOf(PaymentApiException.class)
                .extracting("code").isEqualTo("REFUND_EXCEEDS_REFUNDABLE");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'REFUND' "
                + "and org_id = ?", Long.class, org.getId())).isEqualTo(refundPosts);
        assertThat(wallet(org.getId())).isEqualByComparingTo("1000.00");
        jdbc.update("update wallet set reserved = 0 where org_id = ?", org.getId());
    }

    @Test
    @Order(4)
    void outsideWindowSkipped() {
        Org org = buyer();
        paidTopUp(org, "400.00", daysAgo(181));
        assertThat(refunds.refundable(org.getId())).isEqualByComparingTo("0.00");
        assertThat(refunds.plan(org.getId(), new BigDecimal("100.00"))).isEmpty();
        assertThatThrownBy(() -> refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(),
                new BigDecimal("100.00")))
                .isInstanceOf(PaymentApiException.class)
                .extracting("code").isEqualTo("NOTHING_REFUNDABLE");
    }

    @Test
    @Order(5)
    void partialProviderFailureReversesOnlyThatPart() {
        Org org = buyer();
        PaymentOrder day1 = paidTopUp(org, "1000.00", daysAgo(2));
        PaymentOrder day2 = paidTopUp(org, "500.00", daysAgo(1));
        fake().failRefundsFor(paymentId(day1));
        BigDecimal before = wallet(org.getId());

        RefundView v = refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(), new BigDecimal("700.00"));

        assertThat(v.status()).isEqualTo("PARTLY_FAILED");
        assertThat(v.parts()).extracting(RefundView.PartView::status).containsExactly("DONE", "FAILED");
        assertThat(v.parts().get(1).failureReason()).isNotBlank();
        assertThat(wallet(org.getId())).isEqualByComparingTo(before.subtract(new BigDecimal("500.00")));
        assertThat(refunded(day2)).isEqualByComparingTo("500.00");
        assertThat(refunded(day1)).isEqualByComparingTo("0.00");
        UUID failedPart = v.parts().get(1).id();
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where idempotency_key = ?",
                Long.class, "refund-failed:" + failedPart)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.refund_done' "
                + "and aggregate_id = ?", Long.class, v.id())).isEqualTo(1L);
    }

    @Test
    @Order(6)
    void refundFailedWebhookReversesOnce() {
        Org org = buyer();
        PaymentOrder o = paidTopUp(org, "600.00", daysAgo(1));
        fake().refundsStayPending(true);

        RefundView v = refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(), new BigDecimal("250.00"));
        assertThat(v.status()).isEqualTo("PROCESSING");
        assertThat(v.parts()).singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("PROCESSING"));
        assertThat(wallet(org.getId())).isEqualByComparingTo("350.00");
        String providerRefundId = parts.findById(v.parts().get(0).id()).orElseThrow().getProviderRefundId();
        assertThat(providerRefundId).isNotNull();

        assertThat(refundEvent(PaymentWebhookEvent.Kind.REFUND_FAILED, providerRefundId))
                .isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        refundEvent(PaymentWebhookEvent.Kind.REFUND_FAILED, providerRefundId);
        // a late "processed" for a part already failed and reversed must not move money again
        refundEvent(PaymentWebhookEvent.Kind.REFUND_PROCESSED, providerRefundId);

        assertThat(wallet(org.getId())).isEqualByComparingTo("600.00");
        assertThat(refunded(o)).isEqualByComparingTo("0.00");
        BuyerRefundPart part = parts.findById(v.parts().get(0).id()).orElseThrow();
        assertThat(part.getStatus()).isEqualTo(BuyerRefundPart.Status.FAILED);
        assertThat(part.getFailureReason()).isEqualTo("bank rejected");
        assertThat(jdbc.queryForObject("select status from buyer_refund where id = ?", String.class, v.id()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'REVERSAL' "
                + "and org_id = ?", Long.class, org.getId())).isEqualTo(1L);
        // nothing was returned, so no refund_done
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.refund_done' "
                + "and aggregate_id = ?", Long.class, v.id())).isEqualTo(0L);
    }

    @Test
    @Order(7)
    void refundProcessedWebhookCompletes() {
        Org org = buyer();
        paidTopUp(org, "600.00", daysAgo(1));
        fake().refundsStayPending(true);
        RefundView v = refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(), new BigDecimal("100.00"));
        String providerRefundId = parts.findById(v.parts().get(0).id()).orElseThrow().getProviderRefundId();

        refundEvent(PaymentWebhookEvent.Kind.REFUND_PROCESSED, providerRefundId);
        refundEvent(PaymentWebhookEvent.Kind.REFUND_PROCESSED, providerRefundId);
        // a late "failed" for a part already done must not reverse it
        refundEvent(PaymentWebhookEvent.Kind.REFUND_FAILED, providerRefundId);

        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("select status from buyer_refund where id = ?", String.class, v.id()))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.refund_done' "
                + "and aggregate_id = ?", Long.class, v.id())).isEqualTo(1L);
    }

    @Test
    @Order(8)
    void retryDoesNotDoubleRefund() {
        Org org = buyer();
        PaymentOrder o = paidTopUp(org, "800.00", daysAgo(1));
        fake().timeoutNextRefund();

        RefundView v = refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(), new BigDecimal("300.00"));
        assertThat(v.status()).isEqualTo("PROCESSING");
        UUID partId = v.parts().get(0).id();
        assertThat(parts.findById(partId).orElseThrow().getStatus()).isEqualTo(BuyerRefundPart.Status.PENDING);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");

        // the retry job leaves freshly created parts to the request that is still submitting them
        refunds.submitPending();
        assertThat(parts.findById(partId).orElseThrow().getStatus()).isEqualTo(BuyerRefundPart.Status.PENDING);

        jdbc.update("update buyer_refund_part set updated_at = now() - interval '10 minutes' where id = ?", partId);
        refunds.submitPending();
        refunds.submitPending();

        assertThat(fake().refunds()).filteredOn(r -> r.getPaymentId().equals(paymentId(o))).hasSize(1);
        BuyerRefundPart part = parts.findById(partId).orElseThrow();
        assertThat(part.getStatus()).isEqualTo(BuyerRefundPart.Status.DONE);
        assertThat(part.getProviderRefundId()).isNotNull();
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(refunded(o)).isEqualByComparingTo("300.00");
        assertThat(jdbc.queryForObject("select status from buyer_refund where id = ?", String.class, v.id()))
                .isEqualTo("DONE");
    }

    @Test
    @Order(9)
    void staleProcessingPartIsPolled() {
        // A REFUND_FAILED webhook that arrived before the provider refund id was stored (or never arrived) must
        // not leave the money out of the wallet for good: the retry job asks the provider about old PROCESSING parts.
        Org org = buyer();
        PaymentOrder o = paidTopUp(org, "700.00", daysAgo(1));
        fake().refundsStayPending(true);
        RefundView v = refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(), new BigDecimal("200.00"));
        UUID partId = v.parts().get(0).id();
        assertThat(parts.findById(partId).orElseThrow().getStatus()).isEqualTo(BuyerRefundPart.Status.PROCESSING);

        fake().refunds().stream().filter(r -> r.getPaymentId().equals(paymentId(o))).forEach(r -> r.setStatus("FAILED"));
        refunds.submitPending();   // recently touched: not polled yet
        assertThat(parts.findById(partId).orElseThrow().getStatus()).isEqualTo(BuyerRefundPart.Status.PROCESSING);

        jdbc.update("update buyer_refund_part set updated_at = now() - interval '2 hours' where id = ?", partId);
        refunds.submitPending();
        refunds.submitPending();

        assertThat(parts.findById(partId).orElseThrow().getStatus()).isEqualTo(BuyerRefundPart.Status.FAILED);
        assertThat(wallet(org.getId())).isEqualByComparingTo("700.00");
        assertThat(refunded(o)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where type = 'REVERSAL' "
                + "and org_id = ?", Long.class, org.getId())).isEqualTo(1L);
    }

    @Test
    @Order(10)
    void concurrentRequestsCannotOverRefund() throws Exception {
        Org org = buyer();
        PaymentOrder o = paidTopUp(org, "500.00", daysAgo(1));
        int n = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    return refunds.request(org.getId(), UUID.randomUUID(), "req-" + UUID.randomUUID(),
                            new BigDecimal("300.00")).status();
                } catch (PaymentApiException e) {
                    return e.code();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new java.util.ArrayList<>();
        for (var f : results) outcomes.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(outcomes).filteredOn("DONE"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("REFUND_EXCEEDS_REFUNDABLE"::equals).hasSize(n - 1);
        assertThat(wallet(org.getId())).isEqualByComparingTo("200.00");
        assertThat(refunded(o)).isEqualByComparingTo("300.00");
    }

    @Test
    @Order(11)
    void sameRequestIdReturnsTheExistingRefund() {
        Org org = buyer();
        paidTopUp(org, "500.00", daysAgo(1));
        String requestId = "req-" + UUID.randomUUID();
        RefundView a = refunds.request(org.getId(), UUID.randomUUID(), requestId, new BigDecimal("100.00"));
        RefundView b = refunds.request(org.getId(), UUID.randomUUID(), requestId, new BigDecimal("100.00"));
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(wallet(org.getId())).isEqualByComparingTo("400.00");
        assertThat(fake().refunds()).hasSize(1);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void ledgerBalances() {
        // Every transaction balances, and every account's cached balance is the sum of its postings.
        assertThat(jdbc.queryForList("select transaction_id from ledger_posting group by transaction_id "
                + "having sum(amount) <> 0")).isEmpty();
        assertThat(jdbc.queryForList("select b.account_id from ledger_account_balance b left join "
                + "(select account_id, sum(amount) s from ledger_posting group by account_id) p "
                + "on p.account_id = b.account_id where b.balance <> coalesce(p.s, 0)")).isEmpty();
        assertThat(ORGS).isNotEmpty();
        for (UUID orgId : ORGS) {
            BigDecimal account = jdbc.queryForList("select b.balance from ledger_account_balance b join ledger_account a "
                    + "on a.id = b.account_id where a.org_id = ? and a.kind = 'BUYER_WALLET'", BigDecimal.class, orgId)
                    .stream().findFirst().orElse(BigDecimal.ZERO);
            assertThat(wallet(orgId)).as("wallet.balance of %s", orgId).isEqualByComparingTo(account);
            // each order's refunded_amount is the sum of its parts that were not reversed
            assertThat(jdbc.queryForList("select o.id from payment_order o left join (select payment_order_id, "
                    + "sum(amount) s from buyer_refund_part where status <> 'FAILED' group by payment_order_id) p "
                    + "on p.payment_order_id = o.id where o.org_id = ? and o.refunded_amount <> coalesce(p.s, 0)",
                    UUID.class, orgId)).isEmpty();
        }
    }
}
