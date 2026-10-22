package com.agentstore.platform.payouts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The seam to the payout provider. Rupees ({@link BigDecimal}) cross it; provider units stay inside the adapter.
 * Implementations throw {@link PayoutProviderRejectedException} for a definite no,
 * {@link PayoutProviderUnavailableException} when the outcome is unknown, and
 * {@link PayoutInsufficientBalanceException} when the platform's own provider balance is too low.
 */
public interface PayoutProvider {

    /** "razorpayx" or "fake". */
    String name();

    /** Returns the provider contact id. */
    String createContact(UUID orgId, String name);

    /** Returns the provider fund account id. */
    String createFundAccount(String contactId, FundAccountDetails details);

    ProviderValidation validateFundAccount(String fundAccountId, PayoutAccountType type);

    Optional<ProviderValidation> fetchValidation(String validationId);

    /** {@code idempotencyKey} is our withdrawal id; repeating a key returns the first payout. */
    ProviderPayout createPayout(String fundAccountId, BigDecimal amount, PayoutMode mode, String idempotencyKey);

    Optional<ProviderPayout> fetchPayout(String payoutId);

    List<ProviderPayout> fetchPayouts(LocalDate dayUtc);

    boolean verifyWebhook(byte[] body, Map<String, String> headers);

    PayoutWebhookEvent parseWebhook(byte[] body, Map<String, String> headers);

    record FundAccountDetails(PayoutAccountType type, String accountNumber, String ifsc, String vpa, String holderName) {}

    /** status: COMPLETED | PENDING | FAILED; registeredName null unless COMPLETED. */
    record ProviderValidation(String validationId, String status, String registeredName, String failureReason) {}

    /** status: QUEUED | PROCESSING | PROCESSED | REVERSED | FAILED; idempotencyKey is our withdrawal id. */
    record ProviderPayout(String payoutId, String idempotencyKey, BigDecimal amount, BigDecimal fees,
                          String status, String utr, String failureReason, Instant createdAt) {}
}
