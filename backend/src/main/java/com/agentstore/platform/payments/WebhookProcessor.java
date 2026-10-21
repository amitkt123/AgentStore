package com.agentstore.platform.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles one provider webhook delivery in a single transaction: verify the signature on the raw bytes, store the
 * event once per (provider, event_id), act on it, mark it processed. A replayed delivery is a no-op; a second
 * delivery of the same payment under another event id is absorbed by the ledger key {@code payment:{paymentId}}.
 */
@Service
public class WebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);

    public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

    /** The signature did not verify; nothing was stored. */
    public static class BadSignatureException extends RuntimeException {
        public BadSignatureException(String message) {
            super(message);
        }
    }

    private final PaymentProvider provider;
    private final PaymentOrderRepository orders;
    private final PaymentCrediting crediting;
    private final ReconciliationIssues issues;
    private final JdbcTemplate jdbc;
    private final VirtualAccountRepository virtualAccounts;
    private final RefundService refunds;

    public WebhookProcessor(PaymentProvider provider, PaymentOrderRepository orders, PaymentCrediting crediting,
                            ReconciliationIssues issues, JdbcTemplate jdbc, VirtualAccountRepository virtualAccounts,
                            RefundService refunds) {
        this.refunds = refunds;
        this.virtualAccounts = virtualAccounts;
        this.provider = provider;
        this.orders = orders;
        this.crediting = crediting;
        this.issues = issues;
        this.jdbc = jdbc;
    }

    /** The configured provider's name; the controller answers 404 for any other. */
    public String providerName() {
        return provider.name();
    }

    /**
     * @throws BadSignatureException when the signature fails (nothing stored)
     * @throws PaymentProviderRejectedException when a correctly signed body cannot be parsed (nothing stored)
     */
    @Transactional
    public Outcome process(String providerName, byte[] body, Map<String, String> headers) {
        if (!provider.name().equals(providerName)) {
            throw new IllegalArgumentException("Not the configured payment provider: " + providerName);
        }
        if (!provider.verifyWebhook(body, headers)) {
            throw new BadSignatureException("Webhook signature did not verify");
        }
        PaymentWebhookEvent event = provider.parseWebhook(body, headers);
        if (event.eventId() == null || event.eventId().isBlank() || event.kind() == null) {
            throw new PaymentProviderRejectedException("Webhook has no event id or kind");
        }

        // A concurrent delivery of the same event blocks here on the unique index until the first one commits.
        int inserted = jdbc.update("insert into payment_webhook_event (id, provider, event_id, type, payload) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?::jsonb) on conflict (provider, event_id) do nothing",
                providerName, event.eventId(), event.kind().name(), new String(body, StandardCharsets.UTF_8));
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }

        Outcome outcome = switch (event.kind()) {
            case PAYMENT_CAPTURED -> captured(event);
            case PAYMENT_FAILED -> failed(event);
            case ORDER_PAID -> Outcome.PROCESSED;   // stored only: the capture event carries the money
            case VIRTUAL_ACCOUNT_CREDITED -> virtualAccountCredited(event);
            case REFUND_PROCESSED -> refundOutcome(event, true);
            case REFUND_FAILED -> refundOutcome(event, false);
            // OTHER is stored only.
            default -> Outcome.IGNORED;
        };

        jdbc.update("update payment_webhook_event set processed_at = now() where provider = ? and event_id = ?",
                providerName, event.eventId());
        return outcome;
    }

    private Outcome captured(PaymentWebhookEvent event) {
        Optional<PaymentOrder> found = findOrder(event);
        if (found.isEmpty()) {
            log.warn("Captured payment {} for unknown order {}; stored, not credited", event.paymentId(), event.orderId());
            return Outcome.IGNORED;
        }
        PaymentOrder order = found.get();
        if (event.paymentId() == null || event.paymentId().isBlank()) {
            log.error("Captured event {} for order {} has no payment id; not credited", event.eventId(), order.getId());
            return Outcome.IGNORED;
        }
        BigDecimal amount = event.amount();
        if (amount == null || amount.compareTo(order.getAmount()) != 0) {
            log.error("Captured payment {} of {} does not match order {} amount {}; not credited", event.paymentId(),
                    amount == null ? "null" : amount.toPlainString(), order.getId(), order.getAmount().toPlainString());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("paymentOrderId", order.getId().toString());
            detail.put("orderAmount", order.getAmount().toPlainString());
            detail.put("paidAmount", amount == null ? null : amount.toPlainString());
            detail.put("eventId", event.eventId());
            issues.record("AMOUNT_MISMATCH", event.paymentId(), detail);
            return Outcome.IGNORED;
        }
        // Credit the order's own amount (equal by the check above) so the ledger carries its scale.
        PaymentCrediting.Result result = switch (order.getPurpose()) {
            case TOPUP -> crediting.creditTopUp(order, event.paymentId(), order.getAmount());
            case BILL -> crediting.creditBill(order, event.paymentId(), order.getAmount());
        };
        return result == PaymentCrediting.Result.REFUSED ? Outcome.IGNORED : Outcome.PROCESSED;
    }

    private Outcome virtualAccountCredited(PaymentWebhookEvent event) {
        Optional<VirtualAccount> va = event.virtualAccountId() == null ? Optional.empty()
                : virtualAccounts.findByProviderAndProviderVaId(provider.name(), event.virtualAccountId());
        if (va.isEmpty()) {
            log.warn("Credit {} for unknown virtual account {}; stored, not credited", event.paymentId(),
                    event.virtualAccountId());
            return Outcome.IGNORED;
        }
        String paymentId = event.paymentId();
        BigDecimal amount = event.amount();
        if (paymentId == null || paymentId.isBlank() || amount == null || amount.signum() <= 0) {
            log.error("Virtual account credit event {} has no payment id or a non-positive amount; not credited",
                    event.eventId());
            return Outcome.IGNORED;
        }
        amount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        String providerOrderId = "va:" + paymentId;
        // The unique provider_order_id makes a concurrent delivery of the same transfer reuse one order.
        jdbc.update("insert into payment_order (id, org_id, purpose, method, amount, status, provider, "
                        + "provider_order_id, refunded_amount, created_at) values (gen_random_uuid(), ?, 'TOPUP', "
                        + "'BANK_TRANSFER', ?, 'CREATED', ?, ?, 0, now()) on conflict (provider_order_id) do nothing",
                va.get().getOrgId(), amount, provider.name(), providerOrderId);
        PaymentOrder order = orders.findByProviderAndProviderOrderId(provider.name(), providerOrderId).orElseThrow();
        PaymentCrediting.Result result = crediting.creditTopUp(order, paymentId, order.getAmount());
        return result == PaymentCrediting.Result.REFUSED ? Outcome.IGNORED : Outcome.PROCESSED;
    }

    /**
     * Settles the refund part the provider refund id belongs to. An id we have not stored yet (the provider call
     * timed out before we saw it) is ignored: the part is still PENDING and the retry job looks it up by receipt.
     */
    private Outcome refundOutcome(PaymentWebhookEvent event, boolean processed) {
        Optional<java.util.UUID> part = refunds.partForProviderRefund(event.refundId());
        if (part.isEmpty()) {
            log.warn("Refund event {} for unknown refund {}; stored only", event.eventId(), event.refundId());
            return Outcome.IGNORED;
        }
        if (processed) {
            refunds.markDone(part.get(), event.refundId());
        } else {
            refunds.failPart(part.get(), event.failureReason() == null || event.failureReason().isBlank()
                    ? "Provider failed the refund" : event.failureReason());
        }
        return Outcome.PROCESSED;
    }

    private Outcome failed(PaymentWebhookEvent event) {
        Optional<PaymentOrder> found = findOrder(event);
        if (found.isEmpty()) {
            log.warn("Failed payment {} for unknown order {}; stored only", event.paymentId(), event.orderId());
            return Outcome.IGNORED;
        }
        // Conditional update: blocks behind a crediting transaction's row lock and then leaves a PAID order alone.
        orders.markFailedIfCreated(found.get().getId());
        return Outcome.PROCESSED;
    }

    private Optional<PaymentOrder> findOrder(PaymentWebhookEvent event) {
        if (event.orderId() == null) return Optional.empty();
        return orders.findByProviderAndProviderOrderId(provider.name(), event.orderId());
    }
}
