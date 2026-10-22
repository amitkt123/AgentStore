package com.agentstore.platform.payments;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FakePaymentProviderTest {

    final FakePaymentProvider provider =
            new FakePaymentProvider(new PaymentProperties.Fake("fake-webhook-secret"));

    PaymentWebhookEvent event() {
        return new PaymentWebhookEvent("evt_1", PaymentWebhookEvent.Kind.PAYMENT_CAPTURED, "order_1", "pay_1",
                null, null, new BigDecimal("250.00"), PaymentMethod.UPI, null);
    }

    @Test
    void signedWebhookVerifies() {
        byte[] body = provider.webhookBody(event());
        assertThat(provider.verifyWebhook(body, provider.signedHeaders(body))).isTrue();
    }

    @Test
    void tamperedBodyOrMissingSignatureFails() {
        byte[] body = provider.webhookBody(event());
        Map<String, String> headers = provider.signedHeaders(body);
        byte[] tampered = body.clone();
        tampered[tampered.length / 2] ^= 1;
        assertThat(provider.verifyWebhook(tampered, headers)).isFalse();
        assertThat(provider.verifyWebhook(body, Map.of())).isFalse();
    }

    @Test
    void parseRoundTrips() {
        PaymentWebhookEvent e = event();
        byte[] body = provider.webhookBody(e);
        assertThat(provider.parseWebhook(body, provider.signedHeaders(body))).isEqualTo(e);
    }

    @Test
    void createOrderReturnsCheckoutDescriptor() {
        PaymentProvider.ProviderOrder order =
                provider.createOrder("rcpt_" + UUID.randomUUID(), new BigDecimal("500.00"), PaymentMethod.CARD, Map.of());
        assertThat(order.orderId()).startsWith("fake_order_");
        assertThat(order.checkout()).containsEntry("provider", "fake").containsEntry("orderId", order.orderId())
                .containsKey("method");
    }

    @Test
    void recordsAreReturnedByFetchAndResetClearsThem() {
        PaymentProvider.ProviderOrder order =
                provider.createOrder("r1", new BigDecimal("500.00"), PaymentMethod.UPI, Map.of());
        assertThat(provider.orders()).hasSize(1);
        provider.reset();
        assertThat(provider.orders()).isEmpty();
        assertThat(order).isNotNull();
    }
}
