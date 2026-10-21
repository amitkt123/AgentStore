package com.agentstore.platform.payments;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.BillingModeService;
import com.agentstore.platform.ledger.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookProcessorTest extends AbstractIntegrationTest {

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
    BillingModeService billingModes;
    @Autowired
    JdbcTemplate jdbc;

    FakePaymentProvider fake() {
        return (FakePaymentProvider) provider;
    }

    Org buyer() {
        return orgRepository.save(new Org(null, "HookCo-" + UUID.randomUUID(), OrgType.BUYER));
    }

    CheckoutResponse order(Org org, String amount) {
        return topUps.createTopUp(org.getId(), UUID.randomUUID(), new BigDecimal(amount), PaymentMethod.UPI);
    }

    PaymentWebhookEvent captured(String eventId, String providerOrderId, String paymentId, String amount) {
        return new PaymentWebhookEvent(eventId, PaymentWebhookEvent.Kind.PAYMENT_CAPTURED, providerOrderId, paymentId,
                null, null, new BigDecimal(amount), PaymentMethod.UPI, null);
    }

    PaymentWebhookEvent of(PaymentWebhookEvent.Kind kind, String providerOrderId, String paymentId) {
        return new PaymentWebhookEvent("evt_" + UUID.randomUUID(), kind, providerOrderId, paymentId, null, null,
                new BigDecimal("500.00"), PaymentMethod.UPI, kind == PaymentWebhookEvent.Kind.PAYMENT_FAILED ? "declined" : null);
    }

    WebhookProcessor.Outcome send(PaymentWebhookEvent e) {
        byte[] body = fake().webhookBody(e);
        return processor.process("fake", body, fake().signedHeaders(body));
    }

    BigDecimal wallet(UUID orgId) {
        return walletRepository.balanceOfOrg(orgId).orElse(BigDecimal.ZERO);
    }

    BigDecimal walletAccount(UUID orgId) {
        List<BigDecimal> r = jdbc.queryForList("select b.balance from ledger_account_balance b join ledger_account a "
                + "on a.id = b.account_id where a.org_id = ? and a.kind = 'BUYER_WALLET'", BigDecimal.class, orgId);
        return r.isEmpty() ? BigDecimal.ZERO : r.get(0);
    }

    BigDecimal clearing() {
        return jdbc.queryForObject("select b.balance from ledger_account_balance b join ledger_account a "
                + "on a.id = b.account_id where a.org_id is null and a.kind = 'PAYMENT_CLEARING'", BigDecimal.class);
    }

    long topUpPosts(UUID orgId) {
        return jdbc.queryForObject("select count(*) from ledger_transaction where type = 'TOPUP' and org_id = ?",
                Long.class, orgId);
    }

    PaymentOrder stored(CheckoutResponse c) {
        return orders.findById(c.order().id()).orElseThrow();
    }

    long eventCount() {
        return jdbc.queryForObject("select count(*) from payment_webhook_event", Long.class);
    }

    @Test
    void capturedCreditsOnce() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        BigDecimal clearingBefore = clearing();
        PaymentWebhookEvent e = captured("evt_" + UUID.randomUUID(), c.providerOrderId(), "pay_" + UUID.randomUUID(), "500.00");

        assertThat(send(e)).isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("500.00");
        assertThat(clearing()).isEqualByComparingTo(clearingBefore.subtract(new BigDecimal("500.00")));
        PaymentOrder o = stored(c);
        assertThat(o.getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(o.getProviderPaymentId()).isEqualTo(e.paymentId());
        assertThat(o.getPaidAt()).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.topup_paid' "
                + "and aggregate_id = ?", Long.class, o.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select processed_at is not null from payment_webhook_event "
                + "where provider = 'fake' and event_id = ?", Boolean.class, e.eventId())).isTrue();

        assertThat(send(e)).isEqualTo(WebhookProcessor.Outcome.DUPLICATE);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("500.00");
        assertThat(topUpPosts(org.getId())).isEqualTo(1L);
    }

    @Test
    void samePaymentDifferentEventIdCreditsOnce() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        String paymentId = "pay_" + UUID.randomUUID();
        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), paymentId, "500.00"));
        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), paymentId, "500.00"));
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("500.00");
        assertThat(topUpPosts(org.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.topup_paid' "
                + "and org_id = ?", Long.class, org.getId())).isEqualTo(1L);
    }

    @Test
    void concurrentDuplicateDeliveryCreditsOnce() throws Exception {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        PaymentWebhookEvent e = captured("evt_" + UUID.randomUUID(), c.providerOrderId(), "pay_" + UUID.randomUUID(), "500.00");
        byte[] body = fake().webhookBody(e);
        Map<String, String> headers = fake().signedHeaders(body);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WebhookProcessor.Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return processor.process("fake", body, headers);
                }));
            }
            start.countDown();
            List<WebhookProcessor.Outcome> outcomes = new ArrayList<>();
            for (Future<WebhookProcessor.Outcome> f : futures) outcomes.add(f.get(60, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(o -> o == WebhookProcessor.Outcome.PROCESSED).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == WebhookProcessor.Outcome.DUPLICATE).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("500.00");
        assertThat(topUpPosts(org.getId())).isEqualTo(1L);
    }

    @Test
    void concurrentSamePaymentUnderDistinctEventIdsCreditsOnce() throws Exception {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        String paymentId = "pay_" + UUID.randomUUID();
        int threads = 8;
        List<byte[]> bodies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            bodies.add(fake().webhookBody(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), paymentId, "500.00")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WebhookProcessor.Outcome>> futures = new ArrayList<>();
        try {
            for (byte[] body : bodies) {
                Map<String, String> headers = fake().signedHeaders(body);
                futures.add(pool.submit(() -> {
                    start.await();
                    return processor.process("fake", body, headers);
                }));
            }
            start.countDown();
            for (Future<WebhookProcessor.Outcome> f : futures) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(WebhookProcessor.Outcome.PROCESSED);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("500.00");
        assertThat(topUpPosts(org.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event where event_id like 'evt_%' "
                + "and payload ->> 'paymentId' = ?", Long.class, paymentId)).isEqualTo((long) threads);
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.topup_paid' "
                + "and org_id = ?", Long.class, org.getId())).isEqualTo(1L);
    }

    @Test
    void secondPaymentOnPaidTopUpCreditsAsItsOwnOrder() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        String firstPay = "pay_" + UUID.randomUUID();
        String secondPay = "pay_" + UUID.randomUUID();
        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), firstPay, "500.00"));

        assertThat(send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), secondPay, "500.00")))
                .isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        assertThat(wallet(org.getId())).isEqualByComparingTo("1000.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("1000.00");
        assertThat(topUpPosts(org.getId())).isEqualTo(2L);
        assertThat(stored(c).getProviderPaymentId()).isEqualTo(firstPay);

        PaymentOrder dup = orders.findAll().stream().filter(o -> ("dup:" + secondPay).equals(o.getProviderOrderId()))
                .findFirst().orElseThrow();
        assertThat(dup.getOrgId()).isEqualTo(org.getId());
        assertThat(dup.getPurpose()).isEqualTo(PaymentOrder.Purpose.TOPUP);
        assertThat(dup.getMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(dup.getAmount()).isEqualByComparingTo("500.00");
        assertThat(dup.getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(dup.getProvider()).isEqualTo("fake");
        assertThat(dup.getProviderPaymentId()).isEqualTo(secondPay);
        assertThat(dup.getPaidAt()).isNotNull();
        assertThat(jdbc.queryForObject("select reference ->> 'paymentOrderId' from ledger_transaction "
                + "where idempotency_key = ?", String.class, "payment:" + secondPay)).isEqualTo(dup.getId().toString());
        assertThat(jdbc.queryForObject("select count(*) from domain_event where type = 'payment.topup_paid' "
                + "and aggregate_id = ?", Long.class, dup.getId())).isEqualTo(1L);

        // the same second payment redelivered under a new event id is not credited again
        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), secondPay, "500.00"));
        assertThat(wallet(org.getId())).isEqualByComparingTo("1000.00");
        assertThat(orders.findAll().stream().filter(o -> ("dup:" + secondPay).equals(o.getProviderOrderId()))).hasSize(1);
    }

    @Test
    void secondPaymentOnPaidBillIsAnIssueNotACredit() {
        Org org = buyer();
        PaymentOrder bill = new PaymentOrder();
        bill.setOrgId(org.getId());
        bill.setPurpose(PaymentOrder.Purpose.BILL);
        bill.setMethod(PaymentMethod.UPI);
        bill.setAmount(new BigDecimal("700.00"));
        bill.setStatus(PaymentOrder.Status.PAID);
        bill.setProvider("fake");
        bill.setProviderOrderId("fake_order_bill_" + UUID.randomUUID());
        bill.setProviderPaymentId("pay_" + UUID.randomUUID());
        bill.setPaidAt(java.time.Instant.now());
        bill = orders.save(bill);
        String secondPay = "pay_" + UUID.randomUUID();
        long postsBefore = jdbc.queryForObject("select count(*) from ledger_transaction", Long.class);

        assertThat(send(captured("evt_" + UUID.randomUUID(), bill.getProviderOrderId(), secondPay, "700.00")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where kind = 'DUPLICATE_PAYMENT' "
                + "and provider_ref = ?", Long.class, secondPay)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction", Long.class)).isEqualTo(postsBefore);
        assertThat(wallet(org.getId())).isEqualByComparingTo("0");
    }

    @Test
    void failedThenCapturedCredits() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        assertThat(send(of(PaymentWebhookEvent.Kind.PAYMENT_FAILED, c.providerOrderId(), "pay_" + UUID.randomUUID())))
                .isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        assertThat(stored(c).getStatus()).isEqualTo(PaymentOrder.Status.FAILED);

        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), "pay_" + UUID.randomUUID(), "500.00"));
        assertThat(stored(c).getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    void failedAfterPaidLeavesOrderPaid() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), "pay_" + UUID.randomUUID(), "500.00"));
        send(of(PaymentWebhookEvent.Kind.PAYMENT_FAILED, c.providerOrderId(), "pay_" + UUID.randomUUID()));
        assertThat(stored(c).getStatus()).isEqualTo(PaymentOrder.Status.PAID);
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    void amountMismatchDoesNotCredit() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        String paymentId = "pay_" + UUID.randomUUID();
        assertThat(send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), paymentId, "499.00")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(wallet(org.getId())).isEqualByComparingTo("0");
        assertThat(topUpPosts(org.getId())).isZero();
        assertThat(stored(c).getStatus()).isEqualTo(PaymentOrder.Status.CREATED);
        assertThat(jdbc.queryForObject("select count(*) from reconciliation_issue where kind = 'AMOUNT_MISMATCH' "
                + "and provider_ref = ?", Long.class, paymentId)).isEqualTo(1L);
    }

    @Test
    void unknownOrderIgnored() {
        PaymentWebhookEvent e = captured("evt_" + UUID.randomUUID(), "order_nobody_" + UUID.randomUUID(),
                "pay_" + UUID.randomUUID(), "500.00");
        long postsBefore = jdbc.queryForObject("select count(*) from ledger_transaction", Long.class);
        assertThat(send(e)).isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event where provider = 'fake' "
                + "and event_id = ?", Long.class, e.eventId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction", Long.class)).isEqualTo(postsBefore);
    }

    @Test
    void orderPaidIsStoredOnly() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        PaymentWebhookEvent e = of(PaymentWebhookEvent.Kind.ORDER_PAID, c.providerOrderId(), "pay_" + UUID.randomUUID());
        send(e);
        assertThat(jdbc.queryForObject("select type from payment_webhook_event where provider = 'fake' "
                + "and event_id = ?", String.class, e.eventId())).isEqualTo("ORDER_PAID");
        assertThat(stored(c).getStatus()).isEqualTo(PaymentOrder.Status.CREATED);
        assertThat(wallet(org.getId())).isEqualByComparingTo("0");
        assertThat(topUpPosts(org.getId())).isZero();
    }

    @Test
    void postpaidSwitchStillCredits() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        billingModes.setMode(org.getId(), BillingMode.POSTPAID);
        send(captured("evt_" + UUID.randomUUID(), c.providerOrderId(), "pay_" + UUID.randomUUID(), "500.00"));
        assertThat(wallet(org.getId())).isEqualByComparingTo("500.00");
        assertThat(walletAccount(org.getId())).isEqualByComparingTo("500.00");
        assertThat(stored(c).getStatus()).isEqualTo(PaymentOrder.Status.PAID);
    }

    @Test
    void badSignatureStoresNothing() {
        Org org = buyer();
        CheckoutResponse c = order(org, "500.00");
        byte[] body = fake().webhookBody(captured("evt_" + UUID.randomUUID(), c.providerOrderId(),
                "pay_" + UUID.randomUUID(), "500.00"));
        long before = eventCount();
        assertThatThrownBy(() -> processor.process("fake", body,
                Map.of(FakePaymentProvider.SIGNATURE_HEADER, "deadbeef")))
                .isInstanceOf(WebhookProcessor.BadSignatureException.class);
        assertThat(eventCount()).isEqualTo(before);
        assertThat(wallet(org.getId())).isEqualByComparingTo("0");
    }
}
