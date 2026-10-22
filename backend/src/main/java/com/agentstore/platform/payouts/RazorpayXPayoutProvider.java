package com.agentstore.platform.payouts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RazorpayX adapter. Rupees ({@link BigDecimal}) cross the seam; paise ({@code long}) will exist only in here.
 * This is a stub: it validates its configuration and refuses to start without every secret, and its calls are
 * filled in by a later task.
 */
public class RazorpayXPayoutProvider implements PayoutProvider {

    static final String DEFAULT_BASE_URL = "https://api.razorpay.com";

    private final String accountNumber;

    public RazorpayXPayoutProvider(PayoutProperties props) {
        PayoutProperties.RazorpayX config = props == null ? null : props.razorpayx();
        List<String> missing = new ArrayList<>();
        if (config == null || isBlank(config.keyId())) missing.add("RAZORPAY_KEY_ID");
        if (config == null || isBlank(config.keySecret())) missing.add("RAZORPAY_KEY_SECRET");
        if (config == null || isBlank(config.accountNumber())) missing.add("RAZORPAYX_ACCOUNT_NUMBER");
        if (config == null || isBlank(config.webhookSecret())) missing.add("RAZORPAYX_WEBHOOK_SECRET");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Payout provider razorpayx requires these environment variables to be set: " + String.join(", ", missing));
        }
        this.accountNumber = config.accountNumber();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static UnsupportedOperationException notYet() {
        return new UnsupportedOperationException("RazorpayX payouts are not implemented yet");
    }

    @Override
    public String name() {
        return "razorpayx";
    }

    @Override
    public String createContact(UUID orgId, String name) {
        throw notYet();
    }

    @Override
    public String createFundAccount(String contactId, FundAccountDetails details) {
        throw notYet();
    }

    @Override
    public ProviderValidation validateFundAccount(String fundAccountId, PayoutAccountType type) {
        throw notYet();
    }

    @Override
    public Optional<ProviderValidation> fetchValidation(String validationId) {
        throw notYet();
    }

    @Override
    public ProviderPayout createPayout(String fundAccountId, BigDecimal amount, PayoutMode mode, String idempotencyKey) {
        throw notYet();
    }

    @Override
    public Optional<ProviderPayout> fetchPayout(String payoutId) {
        throw notYet();
    }

    @Override
    public List<ProviderPayout> fetchPayouts(LocalDate dayUtc) {
        throw notYet();
    }

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers) {
        throw notYet();
    }

    @Override
    public PayoutWebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        throw notYet();
    }
}
