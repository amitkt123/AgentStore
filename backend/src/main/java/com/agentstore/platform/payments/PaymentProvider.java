package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam between AgentStore and a payment gateway. Money is {@link BigDecimal} rupees here; adapters convert.
 * Implementations throw {@link PaymentProviderRejectedException} for a definite rejection and
 * {@link PaymentProviderUnavailableException} for a 5xx or timeout.
 */
public interface PaymentProvider {

    String name();

    ProviderOrder createOrder(String receipt, BigDecimal amount, PaymentMethod method, Map<String, String> notes);

    ProviderVirtualAccount createVirtualAccount(UUID orgId, String orgName);

    ProviderRefund refund(String providerPaymentId, BigDecimal amount, String receipt);

    Optional<ProviderRefund> findRefund(String providerPaymentId, String receipt);

    boolean verifyWebhook(byte[] body, Map<String, String> headers);

    PaymentWebhookEvent parseWebhook(byte[] body, Map<String, String> headers);

    /** One payment by its provider id; empty when the provider does not know it. */
    Optional<ProviderPayment> fetchPayment(String paymentId);

    List<ProviderPayment> fetchPayments(LocalDate dayUtc);

    List<ProviderRefund> fetchRefunds(LocalDate dayUtc);

    List<ProviderSettlement> fetchSettlements(LocalDate dayUtc);

    record ProviderOrder(String orderId, Map<String, Object> checkout) {}

    record ProviderVirtualAccount(String id, String accountNumber, String ifsc, String beneficiaryName) {}

    /** {@code status} is PROCESSED, PENDING or FAILED; {@code createdAt} is when the provider made it (null if unknown). */
    record ProviderRefund(String refundId, String paymentId, BigDecimal amount, String status, String receipt,
                          Instant createdAt) {}

    record ProviderPayment(String paymentId, String orderId, BigDecimal amount, String status, String virtualAccountId) {}

    record ProviderSettlement(String settlementId, BigDecimal fees) {}
}
