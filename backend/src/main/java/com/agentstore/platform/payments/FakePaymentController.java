package com.agentstore.platform.payments;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Dev-only stand-in for paying at the provider: builds a signed fake webhook for an order and runs it through the
 * real {@link WebhookProcessor}. Exists only while the configured provider is {@code fake}.
 */
@RestController
@ConditionalOnProperty(prefix = "agentstore.money.payments", name = "provider", havingValue = "fake")
public class FakePaymentController {

    public record SimulateRequest(UUID orderId, String outcome) {}

    private final PaymentProvider provider;
    private final WebhookProcessor processor;
    private final PaymentOrderRepository orders;
    private final PaymentAccess access;

    public FakePaymentController(PaymentProvider provider, WebhookProcessor processor, PaymentOrderRepository orders,
                                 PaymentAccess access) {
        this.provider = provider;
        this.processor = processor;
        this.orders = orders;
        this.access = access;
    }

    @PostMapping("/api/v1/dev/payments/simulate")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public Map<String, Object> simulate(@RequestBody SimulateRequest request) {
        if (!(provider instanceof FakePaymentProvider fake)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request == null || request.orderId() == null || request.outcome() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId and outcome are required");
        }
        PaymentOrder order = orders.findById(request.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        access.requireOwnOrgOrAdmin(order.getOrgId());
        if (order.getStatus() == PaymentOrder.Status.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is already paid");
        }

        String eventId = "fake_evt_" + random();
        String paymentId = "fake_pay_" + random();
        PaymentWebhookEvent event = switch (request.outcome()) {
            case "PAID" -> {
                recordCapture(fake, order, paymentId);
                yield new PaymentWebhookEvent(eventId, PaymentWebhookEvent.Kind.PAYMENT_CAPTURED,
                        order.getProviderOrderId(), paymentId, null, null, order.getAmount(), order.getMethod(), null);
            }
            case "FAILED" -> new PaymentWebhookEvent(eventId, PaymentWebhookEvent.Kind.PAYMENT_FAILED,
                    order.getProviderOrderId(), paymentId, null, null, order.getAmount(), order.getMethod(),
                    "simulated failure");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome must be PAID or FAILED");
        };
        byte[] body = fake.webhookBody(event);
        WebhookProcessor.Outcome outcome = processor.process(fake.name(), body, fake.signedHeaders(body));
        PaymentOrder after = orders.findById(order.getId()).orElseThrow();
        return Map.of("outcome", outcome.name(), "order", PaymentOrderView.of(after));
    }

    /** Keeps the fake's own records in step, so reconciliation sees the payment; survives a restarted fake. */
    private static void recordCapture(FakePaymentProvider fake, PaymentOrder order, String paymentId) {
        try {
            fake.capture(order.getProviderOrderId(), paymentId);
        } catch (IllegalArgumentException unknownToFake) {
            fake.seedPayment(paymentId, order.getProviderOrderId(), order.getAmount(), "captured", null,
                    java.time.Instant.now());
        }
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
