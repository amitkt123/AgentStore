package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Razorpay adapter. Task 8 fills in the calls; for now only the start-up validation exists. */
public class RazorpayPaymentProvider implements PaymentProvider {

    private final PaymentProperties.Razorpay config;

    public RazorpayPaymentProvider(PaymentProperties.Razorpay config) {
        List<String> missing = new ArrayList<>();
        if (config == null || isBlank(config.keyId())) missing.add("RAZORPAY_KEY_ID");
        if (config == null || isBlank(config.keySecret())) missing.add("RAZORPAY_KEY_SECRET");
        if (config == null || isBlank(config.webhookSecret())) missing.add("RAZORPAY_WEBHOOK_SECRET");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Payment provider razorpay requires these environment variables to be set: " + String.join(", ", missing));
        }
        this.config = config;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String name() {
        return "razorpay";
    }

    private static UnsupportedOperationException todo() {
        return new UnsupportedOperationException("Razorpay adapter is not implemented yet");
    }

    @Override
    public ProviderOrder createOrder(String receipt, BigDecimal amount, PaymentMethod method, Map<String, String> notes) {
        throw todo();
    }

    @Override
    public ProviderVirtualAccount createVirtualAccount(UUID orgId, String orgName) {
        throw todo();
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String receipt) {
        throw todo();
    }

    @Override
    public Optional<ProviderRefund> findRefund(String providerPaymentId, String receipt) {
        throw todo();
    }

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers) {
        throw todo();
    }

    @Override
    public PaymentWebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        throw todo();
    }

    @Override
    public List<ProviderPayment> fetchPayments(LocalDate dayUtc) {
        throw todo();
    }

    @Override
    public List<ProviderRefund> fetchRefunds(LocalDate dayUtc) {
        throw todo();
    }

    @Override
    public List<ProviderSettlement> fetchSettlements(LocalDate dayUtc) {
        throw todo();
    }
}
