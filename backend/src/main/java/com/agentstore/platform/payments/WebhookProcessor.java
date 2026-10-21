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

    public WebhookProcessor(PaymentProvider provider, PaymentOrderRepository orders, PaymentCrediting crediting,
                            ReconciliationIssues issues, JdbcTemplate jdbc) {
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
            // VIRTUAL_ACCOUNT_CREDITED and REFUND_* are handled in later tasks; OTHER is stored only.
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
