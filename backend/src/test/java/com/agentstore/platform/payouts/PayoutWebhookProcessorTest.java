package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerChecks;
import com.agentstore.platform.payments.WebhookProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutWebhookProcessorTest extends WithdrawalTestSupport {

    @Autowired
    PayoutWebhookProcessor processor;
    @Autowired
    LedgerChecks ledgerChecks;

    private record Sent(Org org, UUID withdrawalId, String payoutId) {}

    /** A developer with 1000.00 earnings and a PROCESSING withdrawal of {@code amount}. */
    private Sent processing(String amount) {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), amount));
        assertThat(status(id)).isEqualTo("PROCESSING");
        return new Sent(org, id, payoutId(id));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from withdrawal where id = ?", String.class, id);
    }

    private String payoutId(UUID id) {
        return jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id);
    }

    private FakePayoutProvider.SignedEvent event(PayoutWebhookEvent.Kind kind, String id, Object... kv) {
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) fields.put((String) kv[i], kv[i + 1]);
        return fake().signedEvent(kind, id, fields);
    }

    private WebhookProcessor.Outcome deliver(FakePayoutProvider.SignedEvent e) {
        return processor.process("fake", e.body(), e.headers());
    }

    private FakePayoutProvider.SignedEvent processedEvent(Sent s, String amount, String fees, String utr) {
        return event(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, s.payoutId(), "amount", amount, "fees", fees,
                "utr", utr);
    }

    private long ledgerTx(String key) {
        return jdbc.queryForObject("select count(*) from ledger_transaction where idempotency_key = ?", Long.class, key);
    }

    private long events(String type, UUID aggregateId) {
        return jdbc.queryForObject("select count(*) from domain_event where type = ? and aggregate_id = ?", Long.class,
                type, aggregateId);
    }

    private long issues(String kind, String ref) {
        return jdbc.queryForObject("select count(*) from reconciliation_issue where kind = ? and provider_ref = ?",
                Long.class, kind, ref);
    }

    private void assertLedgerChecks() {
        assertThat(ledgerChecks.run()).isEqualTo(new LedgerChecks.Result(true, true, true));
    }

    @Test
    void processedSetsUtrAndMovesClearingToRazorpayX() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Sent s = processing("400.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING).subtract(clearing0))
                .isEqualByComparingTo("400.00");

        assertThat(deliver(processedEvent(s, "400.00", "0.00", "UTR0001"))).isEqualTo(WebhookProcessor.Outcome.PROCESSED);

        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select utr from withdrawal where id = ?", String.class, s.withdrawalId()))
                .isEqualTo("UTR0001");
        assertThat(jdbc.queryForObject("select processed_at from withdrawal where id = ?", Instant.class,
                s.withdrawalId())).isNotNull();
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("400.00");
        assertThat(payableBalance(s.org())).isEqualByComparingTo("600.00");
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(ledgerTx("payout-fee:" + s.withdrawalId())).isZero();
        assertThat(events("payout.withdrawal_processed", s.withdrawalId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select payload->>'utr' from domain_event where type = "
                + "'payout.withdrawal_processed' and aggregate_id = ?", String.class, s.withdrawalId()))
                .isEqualTo("UTR0001");
        assertThat(jdbc.queryForObject("select processed_at is not null from payment_webhook_event "
                + "where provider = 'payouts:fake' and payload->>'payoutId' = ?", Boolean.class, s.payoutId())).isTrue();
    }

    @Test
    void processedWithFeePostsPayoutFee() {
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        BigDecimal fees0 = platformBalance(LedgerAccountKind.PAYOUT_FEES);
        Sent s = processing("400.00");

        deliver(processedEvent(s, "400.00", "5.90", "UTR0002"));

        assertThat(ledgerTx("payout-fee:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select type from ledger_transaction where idempotency_key = ?", String.class,
                "payout-fee:" + s.withdrawalId())).isEqualTo("ADJUSTMENT");
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("405.90");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_FEES).subtract(fees0)).isEqualByComparingTo("-5.90");
        assertThat(jdbc.queryForObject("select fees from withdrawal where id = ?", BigDecimal.class, s.withdrawalId()))
                .isEqualByComparingTo("5.90");
        assertLedgerChecks();
    }

    @Test
    void replayedEventIsDuplicateAndPostsOnce() {
        Sent s = processing("400.00");
        var e = processedEvent(s, "400.00", "0.00", "UTR0003");

        assertThat(deliver(e)).isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        assertThat(deliver(e)).isEqualTo(WebhookProcessor.Outcome.DUPLICATE);

        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(events("payout.withdrawal_processed", s.withdrawalId())).isEqualTo(1L);
    }

    @Test
    void sameOutcomeUnderNewEventIdPostsOnce() {
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Sent s = processing("400.00");

        deliver(processedEvent(s, "400.00", "2.00", "UTR0004"));
        deliver(processedEvent(s, "400.00", "2.00", "UTR0004"));

        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(ledgerTx("payout-fee:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("402.00");
        assertThat(events("payout.withdrawal_processed", s.withdrawalId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event where provider = 'payouts:fake' "
                + "and payload->>'payoutId' = ?", Long.class, s.payoutId())).isEqualTo(2L);
    }

    @Test
    void failedRestoresPayableOnce() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        Sent s = processing("400.00");

        assertThat(deliver(event(PayoutWebhookEvent.Kind.PAYOUT_FAILED, s.payoutId(), "failureReason", "IFSC closed")))
                .isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        deliver(event(PayoutWebhookEvent.Kind.PAYOUT_FAILED, s.payoutId(), "failureReason", "IFSC closed"));

        assertThat(status(s.withdrawalId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_reason from withdrawal where id = ?", String.class,
                s.withdrawalId())).isEqualTo("IFSC closed");
        assertThat(payableBalance(s.org())).isEqualByComparingTo("1000.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        assertThat(ledgerTx("withdrawal-reversed:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(events("payout.withdrawal_reversed", s.withdrawalId())).isEqualTo(1L);
    }

    @Test
    void reversedAfterProcessedRestoresPayableFromRazorpayX() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Sent s = processing("400.00");
        deliver(processedEvent(s, "400.00", "0.00", "UTR0005"));
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("400.00");

        assertThat(deliver(event(PayoutWebhookEvent.Kind.PAYOUT_REVERSED, s.payoutId(),
                "failureReason", "Beneficiary bank returned it"))).isEqualTo(WebhookProcessor.Outcome.PROCESSED);
        deliver(event(PayoutWebhookEvent.Kind.PAYOUT_REVERSED, s.payoutId()));

        assertThat(status(s.withdrawalId())).isEqualTo("REVERSED");
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE)).isEqualByComparingTo(rzpx0);
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        assertThat(payableBalance(s.org())).isEqualByComparingTo("1000.00");
        assertThat(ledgerTx("withdrawal-reversed:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(events("payout.withdrawal_reversed", s.withdrawalId())).isEqualTo(1L);
        assertLedgerChecks();
    }

    @Test
    void processedAfterReversalChangesNothingAndRecordsIssue() {
        Sent s = processing("400.00");
        deliver(event(PayoutWebhookEvent.Kind.PAYOUT_FAILED, s.payoutId()));
        BigDecimal clearing = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        BigDecimal rzpx = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);

        assertThat(deliver(processedEvent(s, "400.00", "0.00", "UTR0006"))).isEqualTo(WebhookProcessor.Outcome.IGNORED);

        assertThat(status(s.withdrawalId())).isEqualTo("FAILED");
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isZero();
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE)).isEqualByComparingTo(rzpx);
        assertThat(payableBalance(s.org())).isEqualByComparingTo("1000.00");
        assertThat(events("payout.withdrawal_processed", s.withdrawalId())).isZero();
        assertThat(issues("PAYOUT_AFTER_REVERSAL", s.payoutId())).isEqualTo(1L);
    }

    @Test
    void amountMismatchIsNotSettledAndRecordsIssue() {
        Sent s = processing("400.00");
        BigDecimal clearing = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);

        assertThat(deliver(processedEvent(s, "401.00", "0.00", "UTR0007"))).isEqualTo(WebhookProcessor.Outcome.IGNORED);

        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSING");
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isZero();
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing);
        assertThat(issues("PAYOUT_AMOUNT_MISMATCH", s.payoutId())).isEqualTo(1L);
    }

    @Test
    void unknownPayoutIsStoredAndIgnored() {
        String unknown = "fake_pout_unknown_" + UUID.randomUUID();
        BigDecimal rzpx = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);

        assertThat(deliver(event(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, unknown, "amount", "100.00")))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(deliver(event(PayoutWebhookEvent.Kind.PAYOUT_REVERSED, unknown)))
                .isEqualTo(WebhookProcessor.Outcome.IGNORED);

        assertThat(jdbc.queryForObject("select count(*) from payment_webhook_event where provider = 'payouts:fake' "
                + "and payload->>'payoutId' = ?", Long.class, unknown)).isEqualTo(2L);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE)).isEqualByComparingTo(rzpx);
    }

    @Test
    void otherIsStoredOnly() {
        String payoutId = "fake_pout_x_" + UUID.randomUUID();
        assertThat(deliver(event(PayoutWebhookEvent.Kind.OTHER, payoutId))).isEqualTo(WebhookProcessor.Outcome.IGNORED);
        assertThat(jdbc.queryForObject("select type from payment_webhook_event where provider = 'payouts:fake' "
                + "and payload->>'payoutId' = ?", String.class, payoutId)).isEqualTo("OTHER");
    }

    @Test
    void validationCompletedVerifiesPendingAccount() {
        Org org = developer(true);
        assertCreated(addBank(org, "123456781111"));   // stays PENDING at the fake
        String validationId = jdbc.queryForObject("select provider_validation_id from payout_account where org_id = ?",
                String.class, org.getId());
        assertThat(jdbc.queryForObject("select status from payout_account where org_id = ?", String.class, org.getId()))
                .isEqualTo("PENDING");

        assertThat(deliver(event(PayoutWebhookEvent.Kind.VALIDATION_COMPLETED, validationId,
                "registeredName", "AMIT TIWARI"))).isEqualTo(WebhookProcessor.Outcome.PROCESSED);

        assertThat(jdbc.queryForObject("select status from payout_account where org_id = ?", String.class, org.getId()))
                .isEqualTo("VERIFIED");
    }

    @Test
    void validationFailedFailsPendingAccount() {
        Org org = developer(true);
        assertCreated(addBank(org, "123456781111"));
        String validationId = jdbc.queryForObject("select provider_validation_id from payout_account where org_id = ?",
                String.class, org.getId());

        deliver(event(PayoutWebhookEvent.Kind.VALIDATION_FAILED, validationId, "failureReason", "Account closed"));

        assertThat(jdbc.queryForObject("select status from payout_account where org_id = ?", String.class, org.getId()))
                .isEqualTo("FAILED");
        assertThat(deliver(event(PayoutWebhookEvent.Kind.VALIDATION_COMPLETED, "fake_fav_unknown_" + UUID.randomUUID(),
                "registeredName", "X"))).isEqualTo(WebhookProcessor.Outcome.IGNORED);
    }

    @Test
    void settleAppliesPolledPayoutWithSameRules() {
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Sent s = processing("400.00");
        var polled = new PayoutProvider.ProviderPayout(s.payoutId(), s.withdrawalId().toString(),
                new BigDecimal("400.00"), new BigDecimal("0.00"), "PROCESSING", null, null, Instant.now());

        processor.settle(s.withdrawalId(), polled);
        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSING");

        processor.settle(s.withdrawalId(), new PayoutProvider.ProviderPayout(s.payoutId(), s.withdrawalId().toString(),
                new BigDecimal("400.00"), new BigDecimal("0.00"), "PROCESSED", "UTR0008", null, Instant.now()));
        processor.settle(s.withdrawalId(), new PayoutProvider.ProviderPayout(s.payoutId(), s.withdrawalId().toString(),
                new BigDecimal("400.00"), new BigDecimal("0.00"), "PROCESSED", "UTR0008", null, Instant.now()));

        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSED");
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isEqualTo(1L);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("400.00");

        processor.settle(s.withdrawalId(), new PayoutProvider.ProviderPayout(s.payoutId(), s.withdrawalId().toString(),
                new BigDecimal("400.00"), new BigDecimal("0.00"), "REVERSED", null, "returned", Instant.now()));
        assertThat(status(s.withdrawalId())).isEqualTo("REVERSED");
        assertThat(payableBalance(s.org())).isEqualByComparingTo("1000.00");
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE)).isEqualByComparingTo(rzpx0);
    }

    @Test
    void ledgerChecksPassAfterEachScenario() {
        Sent paid = processing("300.00");
        deliver(processedEvent(paid, "300.00", "3.54", "UTR0009"));
        assertLedgerChecks();

        Sent failed = processing("200.00");
        deliver(event(PayoutWebhookEvent.Kind.PAYOUT_FAILED, failed.payoutId()));
        assertLedgerChecks();

        Sent returned = processing("250.00");
        deliver(processedEvent(returned, "250.00", "1.00", "UTR0010"));
        deliver(event(PayoutWebhookEvent.Kind.PAYOUT_REVERSED, returned.payoutId()));
        assertLedgerChecks();

        Sent mismatch = processing("150.00");
        deliver(processedEvent(mismatch, "149.00", "0.00", "UTR0011"));
        assertLedgerChecks();
    }
    // ---- a payout created behind a timeout (final review item 3b) and stored payloads (item 2) ----

    /** A withdrawal whose createPayout timed out after the provider made the payout: REQUESTED, no payout id. */
    private Sent requestedBehindTimeout(String amount) {
        Org org = readyDeveloper("1000.00");
        fake().timeoutAfterNextCreate();
        UUID id = id(withdraw(org, UUID.randomUUID(), amount));
        assertThat(status(id)).isEqualTo("REQUESTED");
        assertThat(payoutId(id)).isNull();
        return new Sent(org, id, fake().payoutForKey(id.toString()).orElseThrow().payoutId());
    }

    @Test
    void processedWebhookBeforeRetryFindsWithdrawalByReference() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Sent s = requestedBehindTimeout("400.00");

        assertThat(deliver(processedEvent(s, "400.00", "0.00", "UTR0100")))
                .isEqualTo(WebhookProcessor.Outcome.PROCESSED);

        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSED");
        assertThat(payoutId(s.withdrawalId())).isEqualTo(s.payoutId());
        assertThat(jdbc.queryForObject("select utr from withdrawal where id = ?", String.class, s.withdrawalId()))
                .isEqualTo("UTR0100");
        assertThat(payableBalance(s.org())).isEqualByComparingTo("600.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("400.00");
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isEqualTo(1L);
        assertLedgerChecks();

        // the retry job's resubmit now finds nothing to do
        withdrawals.submit(s.withdrawalId());
        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSED");
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isEqualTo(1L);
        assertLedgerChecks();
    }

    @Test
    void failedWebhookBeforeRetryFindsWithdrawalByReferenceAndRestoresPayable() {
        BigDecimal clearing0 = platformBalance(LedgerAccountKind.PAYOUT_CLEARING);
        Sent s = requestedBehindTimeout("400.00");

        assertThat(deliver(event(PayoutWebhookEvent.Kind.PAYOUT_FAILED, s.payoutId(), "failureReason", "IFSC closed")))
                .isEqualTo(WebhookProcessor.Outcome.PROCESSED);

        assertThat(status(s.withdrawalId())).isEqualTo("FAILED");
        assertThat(payoutId(s.withdrawalId())).isEqualTo(s.payoutId());
        assertThat(payableBalance(s.org())).isEqualByComparingTo("1000.00");
        assertThat(platformBalance(LedgerAccountKind.PAYOUT_CLEARING)).isEqualByComparingTo(clearing0);
        withdrawals.submit(s.withdrawalId());
        assertThat(status(s.withdrawalId())).isEqualTo("FAILED");
        assertLedgerChecks();
    }

    @Test
    void referenceToWithdrawalWithAnotherPayoutIsIgnored() {
        Sent s = processing("400.00");
        String other = "fake_pout_other_" + UUID.randomUUID();

        assertThat(deliver(event(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, other, "amount", "400.00",
                "referenceId", s.withdrawalId().toString()))).isEqualTo(WebhookProcessor.Outcome.IGNORED);

        assertThat(status(s.withdrawalId())).isEqualTo("PROCESSING");
        assertThat(payoutId(s.withdrawalId())).isEqualTo(s.payoutId());
        assertThat(ledgerTx("withdrawal-paid:" + s.withdrawalId())).isZero();
    }

    @Test
    void storedPayloadHasNoFullAccountNumberOrVpa() {
        String payoutId = "fake_pout_x_" + UUID.randomUUID();
        Map<String, Object> fundAccount = Map.of("id", "fa_1",
                "bank_account", Map.of("account_number", "765432123456789", "ifsc", "HDFC0000053", "name", "Asha"),
                "vpa", Map.of("address", "asha.sharma@exampleupi"));

        deliver(event(PayoutWebhookEvent.Kind.OTHER, payoutId, "fund_account", fundAccount));

        String stored = jdbc.queryForObject("select payload::text from payment_webhook_event "
                + "where provider = 'payouts:fake' and payload->>'payoutId' = ?", String.class, payoutId);
        assertThat(stored).doesNotContain("765432123456789").doesNotContain("asha.sharma@exampleupi")
                .contains("6789").contains("HDFC0000053");
    }
}
